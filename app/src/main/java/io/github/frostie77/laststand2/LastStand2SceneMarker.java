package io.github.frostie77.laststand2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** In-memory input diagnostics, scene notifications and combat fast paths for tested v1.31. */
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
            int hooks = 0, particles = 0, loops = 0, diagnostics = 0;
            while (pos + 2 <= movie.length) {
                int start = pos, header = ushort(movie, pos);
                pos += 2;
                int tag = header >>> 6, size = header & 63;
                if (size == 63) { size = uint(movie, pos); pos += 4; }
                if (size < 0 || pos + size > movie.length) return original;
                byte[] replacement = null;
                if (tag == 12) {
                    ByteArrayOutputStream actions = new ByteArrayOutputStream();
                    Map<String, Integer> pool = new LinkedHashMap<>();
                    int at = pos, end = pos + size;
                    while (at < end) {
                        int actionStart = at, op = movie[at++] & 255, length = 0;
                        if (op >= 128) { length = ushort(movie, at); at += 2; }
                        int recordEnd = at + length;
                        if (recordEnd > end) return original;
                        if (op == 0x88) {
                            pool.clear();
                            int cursor = at + 2;
                            for (int i = 0, count = ushort(movie, at); i < count; i++) {
                                int terminator = cursor;
                                while (terminator < recordEnd && movie[terminator] != 0) terminator++;
                                if (terminator == recordEnd) return original;
                                pool.putIfAbsent(new String(movie, cursor, terminator - cursor,
                                    StandardCharsets.UTF_8), i);
                                cursor = terminator + 1;
                            }
                        }
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
                            } else if ("moveParticle".equals(name)) {
                                actions.write(particleFunction(pool));
                                particles++;
                            } else if (LastStand2LoopOptimizer.supports(name)) {
                                actions.write(LastStand2LoopOptimizer.rewrite(name,movie,actionStart,
                                    recordEnd,functionEnd,pool));
                                loops++;
                            } else if ("reloadMagazine".equals(name)) {
                                actions.write(movie,actionStart,functionEnd-actionStart);
                                actions.write(inputStateCallback());
                                diagnostics++;
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
            if (hooks != 2 || particles != 1 || loops != 3 || diagnostics != 1) return original;
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

    // Invoked only by the desktop recorder, never by the gameplay loop.
    private static byte[] inputStateCallback() throws IOException {
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        String[] fields={"currentWeapon","ammoInMag","reloading","reloadAnimPlaying","pumping",
            "firing","gamePaused","_currentframe","mainGunRef._currentframe","mainGunRef._rotation",
            "_xmouse","_ymouse"};
        for(int i=0;i<fields.length;i++) {
            if(i>0) { literal(body,"|");body.write(0x21); }
            rootPath(body,fields[i]); if(i>0) body.write(0x21);
        }
        for(String path:new String[]{"game.onEnterFrame","mainChar.onEnterFrame"}) {
            literal(body,"|");body.write(0x21);rootPath(body,path);body.write(0x44);body.write(0x21);
        }
        literal(body,"|");body.write(0x21);
        integer(body,82);integer(body,1);literal(body,"Key");body.write(0x1c);
        literal(body,"isDown");body.write(0x52);body.write(0x21);body.write(0x3e);body.write(0);
        String internal="__tls2GameInputState";
        ByteArrayOutputStream header=new ByteArrayOutputStream();
        header.write((internal+"\0").getBytes(StandardCharsets.US_ASCII));word(header,0);word(header,body.size());
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(0x9b);word(out,header.size());header.writeTo(out);body.writeTo(out);
        literal(out,internal);out.write(0x1c);literal(out,"_root");out.write(0x1c);
        literal(out,"tls2GameInputState");integer(out,3);
        literal(out,"flash");out.write(0x1c);literal(out,"external");out.write(0x4e);
        literal(out,"ExternalInterface");out.write(0x4e);literal(out,"addCallback");out.write(0x52);out.write(0x17);
        return out.toByteArray();
    }
    private static void rootPath(ByteArrayOutputStream out,String path) throws IOException {
        literal(out,"_root");out.write(0x1c);
        for(String member:path.split("\\.")) { literal(out,member);out.write(0x4e); }
    }
    private static void literal(ByteArrayOutputStream out,String text) throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.US_ASCII);
        out.write(0x96);word(out,bytes.length+2);out.write(0);out.write(bytes);out.write(0);
    }
    private static void integer(ByteArrayOutputStream out,int value) {
        out.write(0x96);word(out,5);out.write(7);dword(out,value);
    }

    // The original per-particle callback uses `with(this)` and repeatedly
    // resolves `this` and its fields through the AVM1 scope chain. All these
    // fields are assigned by particleEmitter. Preload the receiver into r1
    // and access it directly; retain the physics/fade/rotation update order.
    // GetProperty/SetProperty use numeric Flash indices for clip transforms,
    // avoiding string-map lookups for _x, _y, _alpha and _rotation.
    // Strings refer to the existing constant pool, avoiding per-frame strings.
    static byte[] particleFunction(Map<String, Integer> pool) throws IOException {
        ParticleCode c = new ParticleCode(pool);
        c.push(R1,6,R1,6); c.op(0x22);
        c.push(R1,"particleFade"); c.op(0x4e,0x0b,0x23);
        c.push(R1,6); c.op(0x22); c.push(8); c.op(0x48,0x12);
        ParticleCode remove = new ParticleCode(pool);
        remove.push(0,R1,"removeMovieClip"); remove.op(0x52,0x17);
        c.branchPast(remove.bytes());
        c.push(R1,10,R1,10); c.op(0x22);
        c.push(R1,"rotation"); c.op(0x4e,0x47,0x23);
        // The tested blood emitter fixes angle/HorzEnergy for the particle's
        // life. Other particle types retain the original calculation each time.
        c.push(R1,"x"); c.op(0x4e);
        c.op(0x96); word(c.out,1); c.op(3); // Push undefined.
        c.op(0x66,0x12);
        c.push(R1,"partType"); c.op(0x4e);
        c.push("bloodParticle"); c.op(0x66,0x10);
        ParticleCode horizontal = new ParticleCode(pool);
        horizontal.push(R1,"x",R1,"angle"); horizontal.op(0x4e); horizontal.push(Math.PI);
        horizontal.op(0x0c); horizontal.push(180); horizontal.op(0x0d); horizontal.push(1,"Math");
        horizontal.op(0x1c); horizontal.push("cos"); horizontal.op(0x52);
        horizontal.push(R1,"HorzEnergy"); horizontal.op(0x4e,0x0c,0x4f);
        c.branchPast(horizontal.bytes());
        c.push(R1,0,R1,0); c.op(0x22);
        c.push(R1,"x"); c.op(0x4e,0x0b,0x23);
        c.push(R1,1,R1,1); c.op(0x22);
        c.push(R1,"VertEnergy",R1,"VertEnergy"); c.op(0x4e);
        c.push(R1,"gravity"); c.op(0x4e,0x47);
        c.op(0x87); word(c.out,1); c.op(2); // StoreRegister r2 leaves its value on the stack.
        c.op(0x4f); c.push(R2); c.op(0x47,0x23,0);
        byte[] body = c.bytes();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write("moveParticle\0".getBytes(StandardCharsets.US_ASCII));
        word(header,0); header.write(3); word(header,0x29); // preload this; suppress arguments/super
        word(header,body.length);
        ByteArrayOutputStream function = new ByteArrayOutputStream();
        function.write(0x8e); word(function,header.size());
        header.writeTo(function); function.write(body);
        return function.toByteArray();
    }

    private static final Register R1 = new Register(1), R2 = new Register(2);
    private static final class Register {
        final int number;
        Register(int number) { this.number = number; }
    }
    private static final class ParticleCode {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final Map<String, Integer> pool;
        ParticleCode(Map<String, Integer> pool) { this.pool = pool; }
        byte[] bytes() { return out.toByteArray(); }
        void op(int... values) { for (int value : values) out.write(value); }
        void push(Object... values) throws IOException {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            for (Object value : values) {
                if (value instanceof Register) {
                    data.write(4); data.write(((Register)value).number);
                } else if (value instanceof String) {
                    Integer index = pool.get(value);
                    if (index == null) throw new IOException("Missing particle constant: " + value);
                    data.write(9); word(data,index);
                } else if (value instanceof Integer) {
                    data.write(7); dword(data,(Integer)value);
                } else {
                    data.write(6);
                    long bits = Double.doubleToLongBits((Double)value);
                    dword(data,(int)(bits >>> 32)); dword(data,(int)bits);
                }
            }
            out.write(0x96); word(out,data.size()); data.writeTo(out);
        }
        void branchPast(byte[] body) throws IOException {
            out.write(0x9d); word(out,2); word(out,body.length); out.write(body);
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
