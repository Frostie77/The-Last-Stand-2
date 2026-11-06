package io.github.frostie77.laststand2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** AVM1 lookup reduction for three verified, synchronous combat routines. */
final class LastStand2LoopOptimizer {
    private LastStand2LoopOptimizer() {}

    static boolean supports(String name) {
        return "collisionTest".equals(name) || "sortZombieDepths".equals(name) || "sortDeadCats".equals(name);
    }

    static byte[] rewrite(String name, byte[] movie, int start, int bodyAt, int end,
            Map<String, Integer> pool) throws IOException {
        if (!supports(name) || (movie[start] & 255) != 0x8e) throw new IOException("Unexpected combat function");
        int nameEnd = start + 3;
        while (nameEnd < bodyAt && movie[nameEnd] != 0) nameEnd++;
        if (u16(movie,nameEnd+1) != 0) throw new IOException("Combat function has parameters");
        int registerAt = nameEnd + 3, count = movie[registerAt] & 255;
        int flags = u16(movie,registerAt+1), rootRegister;
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        if ((flags & 0x40) != 0) {
            rootRegister = 1 + Integer.bitCount(flags & 0x15);
            if (rootRegister >= count) throw new IOException("Invalid preloaded root");
        } else {
            rootRegister = count++;
            pushLiteral(prefix,"_root"); prefix.write(0x1c); store(prefix,rootRegister);
        }
        // These arrays/dictionaries keep their identity during each routine.
        // Splice, deletion, pushes and sorting still operate on the live objects.
        String[] cachedNames = "collisionTest".equals(name)
            ? new String[]{"aliveZombies","roundsOnScreen","activeTraps"}
            : new String[]{"sortZombieDepths".equals(name) ? "enemyOnScreen" : "deadZombies"};
        Map<String,Integer> cached = new LinkedHashMap<>();
        for (String field : cachedNames) {
            int slot = count++;
            cached.put(field,slot);
            pushRootField(prefix,rootRegister,constant(pool,field)); prefix.write(0x4e); store(prefix,slot);
        }
        if (count > 255) throw new IOException("Combat register limit");
        Set<String> rootFields = new HashSet<>(Arrays.asList("i","j","v","k","t",
            "enemyOnScreen","deadZombies","stageBG","wall","hud","hero","mainChar","currentWeapon"));
        List<Record> records = new ArrayList<>();
        Set<Integer> boundaries = new HashSet<>(), targets = new HashSet<>();
        int at = bodyAt;
        while (at < end) {
            int begin = at, op = movie[at++] & 255, size = 0;
            if (op >= 128) { size = u16(movie,at); at += 2; }
            int finish = at + size;
            if (finish > end) throw new IOException("Invalid combat action");
            // The selected routines have no nested/local scope or target changes.
            // Refuse a different layout rather than bypassing a local binding.
            if (op == 0x3c || op == 0x41 || op == 0x94 || op == 0x9b || op == 0x8e ||
                    op == 0x8f || op == 0x8b || op == 0x20) throw new IOException("Unexpected combat scope");
            Record record = new Record(begin-bodyAt,finish-bodyAt,op,Arrays.copyOfRange(movie,begin,finish));
            if (op == 0x99 || op == 0x9d) {
                if (size != 2) throw new IOException("Invalid combat branch");
                record.target = finish-bodyAt + (short)u16(movie,at);
                targets.add(record.target);
            }
            records.add(record); boundaries.add(record.start); at = finish;
        }
        boundaries.add(end-bodyAt);
        if (!boundaries.containsAll(targets)) throw new IOException("Combat branch is not an action boundary");
        int changes = 0;
        for (int i=0;i+1<records.size();i++) {
            Record push = records.get(i), get = records.get(i+1);
            if (push.op != 0x96 || get.op != 0x1c || targets.contains(get.start)) continue;
            Tail tail = lastPush(push.output,pool);
            if (tail == null) continue;
            Integer slot = cached.get(tail.name);
            if (slot == null && !rootFields.contains(tail.name)) continue;
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(push.output,3,tail.offset-3);
            payload.write(4); payload.write(slot != null ? slot : rootRegister);
            if (slot == null) payload.write(push.output,tail.offset,push.output.length-tail.offset);
            push.output = longAction(0x96,payload.toByteArray());
            get.output = slot != null ? new byte[0] : new byte[]{0x4e};
            changes++;
        }
        if (changes == 0) throw new IOException("No combat lookups found");
        Map<Integer,Integer> relocated = new HashMap<>();
        int offset = prefix.size();
        for (Record record : records) { relocated.put(record.start,offset); offset += record.output.length; }
        relocated.put(end-bodyAt,offset);
        if (offset > 65535) throw new IOException("Combat function size limit");
        ByteArrayOutputStream body = new ByteArrayOutputStream(); prefix.writeTo(body);
        for (Record record : records) {
            if (record.target != null) {
                int delta = relocated.get(record.target) - (relocated.get(record.start)+5);
                if (delta < Short.MIN_VALUE || delta > Short.MAX_VALUE) throw new IOException("Combat branch size limit");
                record.output[3]=(byte)delta; record.output[4]=(byte)(delta >>> 8);
            }
            body.write(record.output);
        }
        byte[] header = Arrays.copyOfRange(movie,start,bodyAt);
        header[registerAt-start]=(byte)count;
        header[header.length-2]=(byte)body.size(); header[header.length-1]=(byte)(body.size() >>> 8);
        ByteArrayOutputStream result = new ByteArrayOutputStream(); result.write(header); body.writeTo(result);
        return result.toByteArray();
    }

    private static Tail lastPush(byte[] data, Map<String,Integer> pool) throws IOException {
        int at=3, last=at; String name=null;
        while (at < data.length) {
            last=at; int type=data[at++] & 255; name=null;
            switch (type) {
                case 0: {
                    int stop=at;
                    while(stop<data.length && data[stop]!=0) stop++;
                    if(stop==data.length) throw new IOException("Unterminated combat string");
                    name=new String(data,at,stop-at,StandardCharsets.UTF_8); at=stop+1; break;
                }
                case 1: case 7: at+=4; break;
                case 2: case 3: break;
                case 4: case 5: at++; break;
                case 6: at+=8; break;
                case 8: case 9: {
                    int index=type==8 ? data[at++] & 255 : u16(data,at);
                    if(type==9) at+=2;
                    for(Map.Entry<String,Integer> entry:pool.entrySet())
                        if(entry.getValue()==index) { name=entry.getKey(); break; }
                    break;
                }
                default: throw new IOException("Invalid combat push type");
            }
            if(at>data.length) throw new IOException("Truncated combat push");
        }
        return name==null ? null : new Tail(last,name);
    }
    private static int constant(Map<String,Integer> pool,String key) throws IOException {
        Integer index=pool.get(key);
        if(index==null) throw new IOException("Missing combat constant: "+key);
        return index;
    }
    private static void pushLiteral(ByteArrayOutputStream out,String value) throws IOException {
        ByteArrayOutputStream payload=new ByteArrayOutputStream(); payload.write(0);
        payload.write(value.getBytes(StandardCharsets.US_ASCII)); payload.write(0);
        out.write(longAction(0x96,payload.toByteArray()));
    }
    private static void pushRootField(ByteArrayOutputStream out,int root,int field) throws IOException {
        out.write(longAction(0x96,new byte[]{4,(byte)root,9,(byte)field,(byte)(field >>> 8)}));
    }
    private static void store(ByteArrayOutputStream out,int register) throws IOException {
        out.write(new byte[]{(byte)0x87,1,0,(byte)register,0x17});
    }
    private static byte[] longAction(int op,byte[] payload) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(op);out.write(payload.length);out.write(payload.length >>> 8);out.write(payload);return out.toByteArray();
    }
    private static int u16(byte[] data,int at) { return (data[at] & 255) | (data[at+1] & 255) << 8; }
    private static final class Record {
        final int start,end,op; Integer target; byte[] output;
        Record(int start,int end,int op,byte[] output) { this.start=start;this.end=end;this.op=op;this.output=output; }
    }
    private static final class Tail {
        final int offset; final String name;
        Tail(int offset,String name) { this.offset=offset;this.name=name; }
    }
}
