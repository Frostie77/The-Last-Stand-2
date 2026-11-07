package io.github.frostie77.laststand2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
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
        return patch(read(source), false);
    }

    static byte[] patch(byte[] original, boolean enableReloadTest) {
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
            int hooks = 0, fireHooks = 0, particles = 0, loops = 0, proximityChecks = 0, diagnostics = 0;
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
                                boolean combat = "showCrosshairs".equals(name);
                                byte[] notify = notification(combat ? "combat" : "menu");
                                byte[] follow = combat ? new byte[0] : followReticle();
                                byte[] pointer = combatPointer(combat);
                                int updatedSize = codeSize + notify.length + follow.length + pointer.length;
                                if (updatedSize > 65535) return original;
                                actions.write(movie, actionStart, codeSizeAt - actionStart);
                                word(actions, updatedSize);
                                actions.write(notify);
                                actions.write(follow);
                                actions.write(pointer);
                                actions.write(movie, recordEnd, codeSize);
                                hooks++;
                            } else if ("fireButton".equals(name)) {
                                if (codeSize != 86) return original;
                                byte[] release = stageReleaseOutside(true);
                                actions.write(movie, actionStart, codeSizeAt - actionStart);
                                word(actions, codeSize + release.length);
                                actions.write(movie, recordEnd, codeSize);
                                actions.write(release);
                                fireHooks++;
                            } else if ("moveParticle".equals(name)) {
                                actions.write(particleFunction(pool));
                                particles++;
                            } else if ("zombieProximityCheck".equals(name)) {
                                if (op != 0x8e || codeSize != 446) return original;
                                actions.write(proximityFunction(movie, recordEnd, pool));
                                proximityChecks++;
                            } else if (LastStand2LoopOptimizer.supports(name)) {
                                actions.write(LastStand2LoopOptimizer.rewrite(name,movie,actionStart,
                                    recordEnd,functionEnd,pool));
                                loops++;
                            } else if ("reloadMagazine".equals(name)) {
                                actions.write(movie,actionStart,functionEnd-actionStart);
                                actions.write(inputStateCallback());
                                if (enableReloadTest) actions.write(prepareSawnOffCallback());
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
            if (hooks != 2 || fireHooks != 1 || particles != 1 || loops != 3 || proximityChecks != 1 || diagnostics != 1)
                return original;
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
        return externalCallback("tls2GameInputState", body);
    }

    private static byte[] prepareSawnOffCallback() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        // Call the game's weapon swap, then leave one shell missing for manual reload.
        literal(body, "sawnOff");
        integer(body, 1);
        rootPath(body, "");
        literal(body, "swapWeapon");
        body.write(0x52); // CallMethod
        body.write(0x17); // Pop
        rootPath(body, "");
        literal(body, "ammoInMag");
        integer(body, 1);
        body.write(0x4f); // SetMember
        body.write(0);
        return externalCallback("tls2PrepareSawnOffReload", body);
    }

    private static byte[] externalCallback(String name, ByteArrayOutputStream body) throws IOException {
        String internal="__"+name;
        ByteArrayOutputStream header=new ByteArrayOutputStream();
        header.write((internal+"\0").getBytes(StandardCharsets.US_ASCII));word(header,0);word(header,body.size());
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(0x9b);word(out,header.size());header.writeTo(out);body.writeTo(out);
        literal(out,internal);out.write(0x1c);literal(out,"_root");out.write(0x1c);
        literal(out,name);integer(out,3);
        literal(out,"flash");out.write(0x1c);literal(out,"external");out.write(0x4e);
        literal(out,"ExternalInterface");out.write(0x4e);literal(out,"addCallback");out.write(0x52);out.write(0x17);
        return out.toByteArray();
    }
    private static void rootPath(ByteArrayOutputStream out,String path) throws IOException {
        literal(out,"_root");out.write(0x1c);
        if (path.isEmpty()) return;
        for(String member:path.split("\\.")) { literal(out,member);out.write(0x4e); }
    }
    private static void literal(ByteArrayOutputStream out,String text) throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.US_ASCII);
        out.write(0x96);word(out,bytes.length+2);out.write(0);out.write(bytes);out.write(0);
    }
    private static void integer(ByteArrayOutputStream out,int value) {
        out.write(0x96);word(out,5);out.write(7);dword(out,value);
    }

    static byte[] followReticle() throws IOException {
        // The original setup calls hideCrosshairs immediately after
        // aimer.startDrag(true). Stop that drag before the next player tick.
        // Only install once, even when the game changes scenes.
        Map<String, Integer> constants = Collections.emptyMap();
        ActionCode follow = new ActionCode(constants);
        integer(follow.out, 0);
        rootPath(follow.out, "aimer");
        literal(follow.out, "stopDrag");
        follow.op(0x52, 0x17); // CallMethod, Pop

        ActionCode move = new ActionCode(constants);
        // Set the clip's x/y from the root's xmouse/ymouse. The reticle is
        // attached to the root, so all four values use the same coordinates.
        move.push(R1, 0, R2, 20); move.op(0x22, 0x23);
        move.push(R1, 1, R2, 21); move.op(0x22, 0x23, 0);
        rootPath(follow.out, "aimer");
        literal(follow.out, "onEnterFrame");
        follow.out.write(move.function("", 3, 0x69)); // Preload this and root.
        follow.op(0x4f); // SetMember
        integer(follow.out, 0);
        rootPath(follow.out, "aimer");
        literal(follow.out, "onEnterFrame");
        follow.op(0x52, 0x17);
        follow.out.write(reticleHitArea());

        ActionCode install = new ActionCode(constants);
        rootPath(install.out, "aimer.onEnterFrame");
        install.op(0x44); // TypeOf
        literal(install.out, "function");
        install.op(0x66); // StrictEquals
        install.branchPast(follow.bytes());

        return whenClipExists("aimer", install.bytes());
    }

    private static byte[] whenClipExists(String clip, byte[] actions) throws IOException {
        ActionCode check = new ActionCode(Collections.emptyMap());
        rootPath(check.out, clip);
        check.op(0x44);
        literal(check.out, "movieclip");
        check.op(0x66, 0x12); // StrictEquals, Not
        check.branchPast(actions);
        return check.bytes();
    }

    private static byte[] reticleHitArea() throws IOException {
        Map<String, Integer> constants = Collections.emptyMap();
        ActionCode empty = new ActionCode(constants);
        rootPath(empty.out, "aimer.__tls2EmptyHitArea");
        empty.op(0x3e); // Return

        ActionCode menu = new ActionCode(constants);
        menu.push(R1, 21); menu.op(0x22); // Root _ymouse.
        menu.push(380); menu.op(0x48); // Less
        menu.branchPast(empty.bytes());

        ActionCode getter = new ActionCode(constants);
        // The bottom-left corner contains the Flash options button and its
        // hover label. An empty hit area there lets Ruffle pick them normally.
        getter.push(R1, 20); getter.op(0x22); // Root _xmouse.
        getter.push(128); getter.op(0x48, 0x12); // Less, Not
        getter.branchPast(menu.bytes());
        rootPath(getter.out, "stageBG.onPress");
        getter.op(0x44);
        literal(getter.out, "function");
        getter.op(0x66);
        getter.branchPast(empty.bytes());
        rootPath(getter.out, "stageBG");
        getter.op(0x3e, 0);

        ActionCode install = new ActionCode(constants);
        integer(install.out, 0);
        literal(install.out, "__tls2EmptyHitArea");
        integer(install.out, 2);
        rootPath(install.out, "aimer");
        literal(install.out, "createEmptyMovieClip");
        install.op(0x52, 0x17);
        install.op(0x96); word(install.out, 1); install.op(2); // Null setter.
        install.out.write(getter.function("", 2, 0x6a)); // Preload root.
        literal(install.out, "hitArea");
        integer(install.out, 3);
        rootPath(install.out, "aimer");
        literal(install.out, "addProperty");
        install.op(0x52, 0x17);
        return install.bytes();
    }

    static byte[] combatPointer(boolean combat) throws IOException {
        Map<String, Integer> constants = Collections.emptyMap();
        ActionCode pointer = new ActionCode(constants);
        // The reticle already sits above scenery. During combat it can be
        // picked first, using the game's firing surface as its hit area.
        for (String event : new String[]{"onPress", "onRelease", "onReleaseOutside"}) {
            rootPath(pointer.out, "aimer");
            literal(pointer.out, event);
            if (combat) {
                ActionCode delegate = new ActionCode(constants);
                integer(delegate.out, 0);
                rootPath(delegate.out, "stageBG");
                literal(delegate.out, "onPress".equals(event) ? "onPress" : "onRelease");
                delegate.op(0x52, 0x17, 0);
                pointer.out.write(delegate.function("", 2, 0x2a));
                pointer.op(0x4f); // SetMember
            } else {
                pointer.op(0x3a, 0x17); // Delete, Pop
            }
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(whenClipExists("aimer", pointer.bytes()));
        if (!combat) result.write(stageReleaseOutside(false));
        return result.toByteArray();
    }

    static byte[] stageReleaseOutside(boolean combat) throws IOException {
        ByteArrayOutputStream action = new ByteArrayOutputStream();
        // A press can start on either firing surface and finish over the
        // other one. Both targets must end the hold on ReleaseOutside.
        rootPath(action, "stageBG");
        literal(action, "onReleaseOutside");
        if (combat) {
            rootPath(action, "stageBG.onRelease");
            action.write(0x4f); // SetMember
            return action.toByteArray();
        }
        action.write(0x3a); // Delete
        action.write(0x17); // Pop
        return whenClipExists("stageBG", action.toByteArray());
    }

    private static byte[] proximityFunction(byte[] movie, int bodyAt, Map<String, Integer> pool)
            throws IOException {
        ActionCode c = new ActionCode(pool);
        // Retain the exact v1.31 muzzle-coordinate setup through localToGlobal.
        // r1 is the preloaded root and r2 is the resulting point.
        c.out.write(movie, bodyAt, 104);
        c.push(R1, "aliveZombies"); c.op(0x4e); c.store(R3); c.op(0x55); // Enumerate2
        int loopStart = c.out.size();
        c.store(R0);
        c.op(0x96); word(c.out, 1); c.op(2); // Push null (end of enumeration).
        c.op(0x49);

        ActionCode step = new ActionCode(pool);
        // Retain the shared v counter, each zombie's target coordinates and
        // rounded distance, and its original debug text assignment.
        step.push("v", R0); step.op(0x1d);
        step.push(R3, R0); step.op(0x4e); step.store(R4); step.op(0x17);
        step.push(R4, "ref"); step.op(0x4e); step.store(R5); step.op(0x17);
        step.push(R4, "targetX", R5, "_x"); step.op(0x4e); step.store(R6); step.op(0x4f);
        step.push(R4, "targetY", R5, "_y"); step.op(0x4e);
        step.push(45); step.op(0x0b); step.store(R7); step.op(0x4f);
        step.push(R4, "proximity", R2, "x"); step.op(0x4e);
        step.push(R6); step.op(0x0b); step.store(R6); step.push(R6); step.op(0x0c);
        step.push(R2, "y"); step.op(0x4e);
        step.push(R7); step.op(0x0b); step.store(R7); step.push(R7); step.op(0x0c, 0x47);
        step.push(1, "Math"); step.op(0x1c); step.push("sqrt"); step.op(0x52);
        step.push(1, "Math"); step.op(0x1c); step.push("round"); step.op(0x52, 0x4f);
        step.push(R5, "zombieProximity"); step.op(0x4e);
        step.push("text", R0); step.op(0x4f);
        int loopEnd = c.out.size() + 5 + step.out.size() + 5; // Include both branches.
        step.op(0x99); word(step.out, 2); word(step.out, loopStart - loopEnd);
        c.branchPast(step.bytes());
        c.op(0);
        return c.function("zombieProximityCheck", 8, 0x6a);
    }

    // The original per-particle callback uses `with(this)` and repeatedly
    // resolves `this` and its fields through the AVM1 scope chain. All these
    // fields are assigned by particleEmitter. Preload the receiver into r1
    // and access it directly; retain the physics/fade/rotation update order.
    // GetProperty/SetProperty use numeric Flash indices for clip transforms,
    // avoiding string-map lookups for _x, _y, _alpha and _rotation.
    // Strings refer to the existing constant pool, avoiding per-frame strings.
    static byte[] particleFunction(Map<String, Integer> pool) throws IOException {
        ActionCode c = new ActionCode(pool);
        c.push(R1,6,R1,6); c.op(0x22);
        c.push(R1,"particleFade"); c.op(0x4e,0x0b,0x23);
        c.push(R1,6); c.op(0x22); c.push(8); c.op(0x48,0x12);
        ActionCode remove = new ActionCode(pool);
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
        ActionCode horizontal = new ActionCode(pool);
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
        c.store(R2);
        c.op(0x4f); c.push(R2); c.op(0x47,0x23,0);
        return c.function("moveParticle", 3, 0x29); // Preload this; suppress arguments/super.
    }

    private static final Register R0 = new Register(0), R1 = new Register(1), R2 = new Register(2),
        R3 = new Register(3), R4 = new Register(4), R5 = new Register(5),
        R6 = new Register(6), R7 = new Register(7);
    private static final class Register {
        final int number;
        Register(int number) { this.number = number; }
    }
    private static final class ActionCode {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final Map<String, Integer> pool;
        ActionCode(Map<String, Integer> pool) { this.pool = pool; }
        byte[] bytes() { return out.toByteArray(); }
        void op(int... values) { for (int value : values) out.write(value); }
        void store(Register register) {
            op(0x87); word(out, 1); op(register.number); // StoreRegister leaves the value on the stack.
        }
        void push(Object... values) throws IOException {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            for (Object value : values) {
                if (value instanceof Register) {
                    data.write(4); data.write(((Register)value).number);
                } else if (value instanceof String) {
                    Integer index = pool.get(value);
                    if (index == null) throw new IOException("Missing combat constant: " + value);
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
        byte[] function(String name, int registers, int flags) throws IOException {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            header.write((name + "\0").getBytes(StandardCharsets.US_ASCII));
            word(header, 0);
            header.write(registers);
            word(header, flags);
            word(header, out.size());
            ByteArrayOutputStream function = new ByteArrayOutputStream();
            function.write(0x8e);
            word(function, header.size());
            header.writeTo(function);
            out.writeTo(function);
            return function.toByteArray();
        }
    }

    private static byte[] notification(String phase) throws IOException {
        byte[] args = ("FSCommand:tls2Phase\0" + phase + "\0").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x83); word(out, args.length); out.write(args);
        return out.toByteArray();
    }
    static byte[] read(InputStream in) throws IOException {
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
