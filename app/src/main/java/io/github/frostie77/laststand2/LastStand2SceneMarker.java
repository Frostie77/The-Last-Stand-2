package io.github.frostie77.laststand2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Host scene notifications, applied in memory to the tested v1.31 SWF only. */
final class LastStand2SceneMarker {
    private static final String TESTED_SHA256 =
        "db326f3ce2f331d3320cd442c88f17152198a5da4cca3d0fb9c1509afb430ea6";

    private LastStand2SceneMarker() {}

    static byte[] patch(InputStream source) throws IOException {
        byte[] original = read(source);
        try {
            StringBuilder digest = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(original))
                digest.append(String.format("%02x", b & 255));
            if (!TESTED_SHA256.equals(digest.toString())) return original;
            byte[] body = read(new InflaterInputStream(
                new ByteArrayInputStream(original, 8, original.length - 8)));
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            plain.write(original, 0, 8);
            plain.write(body);
            byte[] movie = plain.toByteArray();
            int pos = 8 + (5 + 4 * ((movie[8] & 255) >>> 3) + 7) / 8 + 4;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(movie, 0, pos);
            int hooks = 0;
            while (pos + 2 <= movie.length) {
                int start = pos, header = ushort(movie, pos);
                pos += 2;
                int tag = header >>> 6, size = header & 63;
                if (size == 63) { size = uint(movie, pos); pos += 4; }
                if (size < 0 || pos + size > movie.length) return original;
                byte[] replacement = null;
                if (tag == 12) {
                    ByteArrayOutputStream actions = new ByteArrayOutputStream();
                    int at = pos, end = pos + size;
                    while (at < end) {
                        int actionStart = at, op = movie[at++] & 255, length = 0;
                        if (op >= 128) { length = ushort(movie, at); at += 2; }
                        int recordEnd = at + length;
                        if (recordEnd > end) return original;
                        if (op == 0x9b || op == 0x8e) {
                            int nameEnd = at;
                            while (nameEnd < recordEnd && movie[nameEnd] != 0) nameEnd++;
                            String name = new String(movie, at, nameEnd - at, StandardCharsets.UTF_8);
                            int codeSizeAt = recordEnd - 2;
                            int codeSize = ushort(movie, codeSizeAt);
                            int functionEnd = recordEnd + codeSize;
                            if (functionEnd > end) return original;
                            if ("showCrosshairs".equals(name) || "hideCrosshairs".equals(name)) {
                                byte[] notify = notification("showCrosshairs".equals(name) ? "combat" : "menu");
                                if (codeSize + notify.length > 65535) return original;
                                actions.write(movie, actionStart, codeSizeAt - actionStart);
                                word(actions, codeSize + notify.length);
                                actions.write(notify);
                                actions.write(movie, recordEnd, codeSize);
                                hooks++;
                            } else actions.write(movie, actionStart, functionEnd - actionStart);
                            at = functionEnd;
                        } else {
                            actions.write(movie, actionStart, recordEnd - actionStart);
                            at = recordEnd;
                        }
                    }
                    replacement = actions.toByteArray();
                }
                if (replacement != null) {
                    word(out, (tag << 6) | 63);
                    dword(out, replacement.length);
                    out.write(replacement);
                } else out.write(movie, start, pos + size - start);
                pos += size;
                if (tag == 0) break;
            }
            if (hooks != 2) return original;
            byte[] patched = out.toByteArray();
            for (int i = 0; i < 4; i++) patched[4 + i] = (byte)(patched.length >>> (8 * i));
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            compressed.write(patched, 0, 8);
            try (DeflaterOutputStream zip = new DeflaterOutputStream(compressed)) {
                zip.write(patched, 8, patched.length - 8);
            }
            return compressed.toByteArray();
        } catch (Exception error) {
            return original; // Unknown/unsupported copies retain working Direct touch input.
        }
    }

    private static byte[] notification(String phase) throws IOException {
        byte[] args = ("FSCommand:tls2Phase\0" + phase + "\0").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x83); word(out, args.length); out.write(args);
        return out.toByteArray();
    }
    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int n;
        while ((n = in.read(block)) != -1) {
            if (out.size() + n > 40_000_000) throw new IOException("SWF too large");
            out.write(block, 0, n);
        }
        return out.toByteArray();
    }
    private static int ushort(byte[] b, int at) { return (b[at] & 255) | (b[at + 1] & 255) << 8; }
    private static int uint(byte[] b, int at) { return ushort(b, at) | ushort(b, at + 2) << 16; }
    private static void word(ByteArrayOutputStream out, int value) { out.write(value); out.write(value >>> 8); }
    private static void dword(ByteArrayOutputStream out, int value) { word(out, value); word(out, value >>> 16); }
}
