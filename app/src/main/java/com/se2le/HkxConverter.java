package com.se2le;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Converts Skyrim Special Edition animation files (Havok packfiles with 64-bit pointers) to the Legendary Edition layout
 * (the same Havok 2010.2 packfile with 32-bit pointers).
 * Only plain animation clips are supported: hkRootLevelContainer, hkaAnimationContainer, hkaSplineCompressedAnimation and
 * hkaAnimationBinding. Anything else (behaviors, skeletons, other classes or versions) is refused with {@link Unsupported}
 * and must be left unchanged by the caller.
 */
public final class HkxConverter {
    private HkxConverter() {}

    /** The file cannot be converted safely; the message says why. */
    public static final class Unsupported extends Exception {
        public Unsupported(String m) { super(m); }
    }

    private static final int MAGIC1 = 0x57E0E057, MAGIC2 = 0x10C0C010;

    /** 0 when the bytes are not a Havok packfile, otherwise the pointer size in bytes (4 = Legendary Edition, 8 = Special Edition). */
    public static int pointerSize(byte[] b) {
        if (b.length < 64) return 0;
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.getInt(0) != MAGIC1 || bb.getInt(4) != MAGIC2) return 0;
        return b[16];
    }


    /**
     * Structural check of a converted (32-bit) file: header, section tables and every fixup must be inside the data, and every
     * pointer slot must be empty. Returns null when the file is fine, otherwise what is wrong.
     */
    public static String verify(byte[] b) {
        try {
            if (pointerSize(b) != 4) return "not a 32-bit packfile";
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            if (bb.getInt(12) != 8 || bb.getInt(20) != 3) return "unexpected header";
            int cn = bb.getInt(64 + 20), cl = bb.getInt(64 + 24);
            int ads = bb.getInt(64 + 96 + 20), lf = bb.getInt(64 + 96 + 24), gf = bb.getInt(64 + 96 + 28), vf = bb.getInt(64 + 96 + 32);
            int ex = bb.getInt(64 + 96 + 36), im = bb.getInt(64 + 96 + 40), en = bb.getInt(64 + 96 + 44);
            if (ex != im || im != en || ads + en != b.length) return "section sizes do not add up";
            if (lf % 16 != 0 || !(lf <= gf && gf <= vf && vf <= ex)) return "section tables out of order";
            for (int p = ads + lf; p < ads + gf; p += 8) {
                int a = bb.getInt(p), d = bb.getInt(p + 4);
                if (a == -1) break;
                if (a < 0 || a + 4 > lf || d < 0 || d >= lf || bb.getInt(ads + a) != 0) return "bad local fixup";
            }
            for (int p = ads + gf; p < ads + vf; p += 12) {
                int a = bb.getInt(p), s = bb.getInt(p + 4), d = bb.getInt(p + 8);
                if (a == -1) break;
                if (a < 0 || a + 4 > lf || s != 2 || d < 0 || d >= lf || bb.getInt(ads + a) != 0) return "bad global fixup";
            }
            for (int p = ads + vf; p < ads + ex; p += 12) {
                int a = bb.getInt(p), s = bb.getInt(p + 4), d = bb.getInt(p + 8);
                if (a == -1) break;
                if (a < 0 || a >= lf || s != 0 || d < 0 || d >= cl) return "bad virtual fixup";
            }
            return null;
        } catch (RuntimeException e) { return "unreadable: " + e.getClass().getSimpleName(); }
    }

    // ------------------------------------------------------------------ model

    private static final class Fix { final int at; final Chunk target; final boolean global; Fix(int at, Chunk t, boolean g) { this.at = at; target = t; global = g; } }

    private static final class Chunk {
        final int src; int srcSize; byte[] out; final List<Fix> fixes = new ArrayList<>();
        int classNameOffset = -1, classNameSection = 0; int dst;
        Chunk(int src) { this.src = src; }
        ByteBuffer buf() { return ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN); }
    }

    private static final class Ctx {
        final ByteBuffer d; // the source data section, little endian
        final Map<Integer, Integer> local = new HashMap<>();
        final Map<Integer, Integer> global = new HashMap<>();
        final Map<Integer, int[]> virtual = new HashMap<>();
        final Map<Integer, Chunk> chunks = new TreeMap<>();
        final Set<Integer> used = new HashSet<>();
        final byte[] names; final int namesStart, namesLen;
        Ctx(ByteBuffer d, byte[] names, int ns, int nl) { this.d = d; this.names = names; namesStart = ns; namesLen = nl; }

        int i32(int o) { return d.getInt(o); }
        short i16(int o) { return d.getShort(o); }

        /** Where the pointer stored at `o` points to, or -1 when it is null. Marks the pointer as handled. */
        int target(int o) {
            Integer l = local.get(o);
            if (l != null) { used.add(o); return l; }
            Integer gg = global.get(o);
            if (gg != null) { used.add(o); return gg; }
            return -1;
        }
        boolean isGlobal(int o) { return global.containsKey(o); }

        String className(int off) {
            int p = namesStart + off + 0; int e = p;
            while (e < namesStart + namesLen && names[e] != 0) e++;
            return new String(names, p, e - p, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    private static int align(int v, int a) { return (v + a - 1) / a * a; }

    // ------------------------------------------------------------------ conversion

    /**
     * Returns the Legendary Edition version of a Special Edition animation file. A file that is already 32-bit is returned as it is.
     * @throws Unsupported when the file is not a simple animation clip this converter understands
     */
    public static byte[] toLegendary(byte[] in) throws Unsupported {
        int ps = pointerSize(in);
        if (ps == 0) throw new Unsupported("not a Havok packfile (it may be a tagfile or another format)");
        if (ps == 4) return in;
        if (ps != 8) throw new Unsupported("unexpected pointer size " + ps);
        ByteBuffer bb = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
        int version = bb.getInt(12), nsec = bb.getInt(20);
        if (version != 8) throw new Unsupported("packfile version " + version + " (only version 8 is supported)");
        String contents = new String(in, 40, 16, java.nio.charset.StandardCharsets.ISO_8859_1).split("\0")[0];
        if (!contents.equals("hk_2010.2.0-r1")) throw new Unsupported("Havok version " + contents + " (only hk_2010.2.0-r1 is supported)");
        if (nsec != 3 || in[17] != 1) throw new Unsupported("unexpected packfile layout");
        // section headers start right after the 64-byte header
        int[][] sec = new int[3][];
        for (int i = 0; i < 3; i++) {
            int o = 64 + 48 * i;
            String nm = new String(in, o, 19, java.nio.charset.StandardCharsets.ISO_8859_1).split("\0")[0];
            String want = i == 0 ? "__classnames__" : i == 1 ? "__types__" : "__data__";
            if (!nm.equals(want)) throw new Unsupported("unexpected section " + nm);
            sec[i] = new int[7];
            for (int k = 0; k < 7; k++) sec[i][k] = bb.getInt(o + 20 + 4 * k);
        }
        int[] dsec = sec[2];
        int ads = dsec[0], lfo = dsec[1], gfo = dsec[2], vfo = dsec[3], exo = dsec[4];
        if (dsec[4] != dsec[5] || dsec[5] != dsec[6] || ads + dsec[6] != in.length)
            throw new Unsupported("unexpected exports/imports in the data section");
        ByteBuffer d = ByteBuffer.wrap(in, ads, lfo).slice().order(ByteOrder.LITTLE_ENDIAN);
        Ctx c = new Ctx(d, in, sec[0][0], sec[0][1]);
        for (int p = ads + lfo; p < ads + gfo; p += 8) { int a = bb.getInt(p), b = bb.getInt(p + 4); if (a == -1) break; c.local.put(a, b); }
        for (int p = ads + gfo; p < ads + vfo; p += 12) {
            int a = bb.getInt(p), s = bb.getInt(p + 4), b = bb.getInt(p + 8); if (a == -1) break;
            if (s != 2) throw new Unsupported("pointer into another section");
            c.global.put(a, b);
        }
        for (int p = ads + vfo; p < ads + exo; p += 12) {
            int a = bb.getInt(p), s = bb.getInt(p + 4), b = bb.getInt(p + 8); if (a == -1) break;
            c.virtual.put(a, new int[] { s, b });
        }
        if (c.virtual.isEmpty() || !c.virtual.containsKey(0)) throw new Unsupported("no root object");

        Chunk root = object(c, 0);
        // everything in the file must have been understood
        if (c.used.size() != c.local.size() + c.global.size()) throw new Unsupported("the file has data this converter does not understand");
        for (int v : c.virtual.keySet()) if (!c.chunks.containsKey(v)) throw new Unsupported("unused object at " + v);

        // lay the chunks out again in the same order with 32-bit sizes
        List<Chunk> list = new ArrayList<>(c.chunks.values());
        int cur = 0; Chunk prev = null;
        for (Chunk k : list) {
            if (prev == null) k.dst = 0;
            else {
                int prevSrcEnd = prev.src + prev.srcSize;
                if (k.src < prevSrcEnd) throw new Unsupported("overlapping data at " + k.src);
                boolean adjacent = k.src == prevSrcEnd && k.src % 16 != 0;
                k.dst = adjacent ? cur : align(cur, 16);
            }
            cur = k.dst + k.out.length; prev = k;
        }
        int dataLen = align(cur, 16);
        ByteBuffer outData = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN);
        List<int[]> loc = new ArrayList<>(), glo = new ArrayList<>(), vir = new ArrayList<>();
        for (Chunk k : list) {
            System.arraycopy(k.out, 0, outData.array(), k.dst, k.out.length);
            for (Fix f : k.fixes) (f.global ? glo : loc).add(new int[] { k.dst + f.at, f.global ? 2 : 0, f.target.dst });
            if (k.classNameOffset >= 0) vir.add(new int[] { k.dst, k.classNameSection, k.classNameOffset });
        }
        Comparator<int[]> byAt = (x, y) -> Integer.compare(x[0], y[0]);
        // Havok lists local fixups in the order of the data they point to; the other two lists go by where the pointer is
        loc.sort((x, y) -> x[2] != y[2] ? Integer.compare(x[2], y[2]) : Integer.compare(x[0], y[0]));
        glo.sort(byAt); vir.sort(byAt);
        int lfSize = align(8 * loc.size(), 16), gfSize = align(12 * glo.size(), 16), vfSize = align(12 * vir.size(), 16);
        int total = ads + dataLen + lfSize + gfSize + vfSize;
        byte[] out = new byte[total];
        System.arraycopy(in, 0, out, 0, ads);
        out[16] = 4; // 32-bit pointers
        ByteBuffer ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        int hdr = 64 + 48 * 2 + 20;
        ob.putInt(hdr, ads);                                  // absoluteDataStart (unchanged)
        ob.putInt(hdr + 4, dataLen);                          // local fixups
        ob.putInt(hdr + 8, dataLen + lfSize);                 // global fixups
        ob.putInt(hdr + 12, dataLen + lfSize + gfSize);       // virtual fixups
        int end = dataLen + lfSize + gfSize + vfSize;
        ob.putInt(hdr + 16, end); ob.putInt(hdr + 20, end); ob.putInt(hdr + 24, end);
        System.arraycopy(outData.array(), 0, out, ads, dataLen);
        Arrays.fill(out, ads + dataLen, total, (byte) 0xFF);
        int p = ads + dataLen;
        for (int[] e : loc) { ob.putInt(p, e[0]); ob.putInt(p + 4, e[2]); p += 8; }
        p = ads + dataLen + lfSize;
        for (int[] e : glo) { ob.putInt(p, e[0]); ob.putInt(p + 4, e[1]); ob.putInt(p + 8, e[2]); p += 12; }
        p = ads + dataLen + lfSize + gfSize;
        for (int[] e : vir) { ob.putInt(p, e[0]); ob.putInt(p + 4, e[1]); ob.putInt(p + 8, e[2]); p += 12; }
        return out;
    }

    // ------------------------------------------------------------------ chunks

    private static Chunk string(Ctx c, int off) throws Unsupported {
        Chunk k = c.chunks.get(off);
        if (k != null) return k;
        int e = off;
        while (e < c.d.limit() && c.d.get(e) != 0) e++;
        if (e >= c.d.limit()) throw new Unsupported("unterminated string");
        k = new Chunk(off);
        k.srcSize = e - off + 1; k.out = new byte[k.srcSize];
        for (int i = 0; i < k.srcSize; i++) k.out[i] = c.d.get(off + i);
        c.chunks.put(off, k);
        return k;
    }

    private static void link(Ctx c, int so, Chunk into, int dof, Chunk target) {
        into.fixes.add(new Fix(dof, target, c.isGlobal(so)));
    }

    private static void stringField(Ctx c, int so, Chunk into, int dof) throws Unsupported {
        int t = c.target(so);
        if (t < 0) return;
        link(c, so, into, dof, string(c, t));
    }

    /** hkArray (16 bytes) at `so` -> hkArray (12 bytes) at `dof`; the elements, if any, become a chunk made by `maker`. */
    private interface Maker { Chunk make(Ctx c, int at, int n) throws Unsupported; }

    private static void array(Ctx c, int so, Chunk into, int dof, Maker maker) throws Unsupported {
        int size = c.i32(so + 8), cap = c.i32(so + 12);
        ByteBuffer b = into.buf();
        b.putInt(dof + 4, size); b.putInt(dof + 8, cap);
        int t = c.target(so);
        if (t < 0) { if (size != 0) throw new Unsupported("array without data"); return; }
        if (size <= 0) throw new Unsupported("empty array with data");
        link(c, so, into, dof, maker.make(c, t, size));
    }

    private static Maker raw(final int elem) {
        return (c, at, n) -> {
            Chunk k = c.chunks.get(at);
            if (k != null) return k;
            k = new Chunk(at); k.srcSize = n * elem; k.out = new byte[k.srcSize];
            if (at + k.srcSize > c.d.limit()) throw new Unsupported("array past the end of the data");
            for (int i = 0; i < k.srcSize; i++) k.out[i] = c.d.get(at + i);
            c.chunks.put(at, k);
            return k;
        };
    }

    private static Maker pointers() {
        return (c, at, n) -> {
            Chunk k = c.chunks.get(at);
            if (k != null) return k;
            k = new Chunk(at); k.srcSize = n * 8; k.out = new byte[n * 4];
            c.chunks.put(at, k);
            for (int i = 0; i < n; i++) {
                int t = c.target(at + 8 * i);
                if (t < 0) continue;
                link(c, at + 8 * i, k, 4 * i, object(c, t));
            }
            return k;
        };
    }

    private static void header(Ctx c, int so, Chunk k) {
        // hkReferencedObject: vptr, memSizeAndFlags, referenceCount
        ByteBuffer b = k.buf();
        b.putShort(4, c.i16(so + 8)); b.putShort(6, c.i16(so + 10));
    }

    private static Chunk object(Ctx c, int off) throws Unsupported {
        Chunk have = c.chunks.get(off);
        if (have != null) return have;
        int[] v = c.virtual.get(off);
        if (v == null) throw new Unsupported("object without a class at " + off);
        String cls = c.className(v[1]);
        Chunk k = new Chunk(off);
        k.classNameSection = v[0]; k.classNameOffset = v[1];
        c.chunks.put(off, k);
        switch (cls) {
            case "hkRootLevelContainer": {
                k.srcSize = 16; k.out = new byte[12];
                array(c, 0 + off, k, 0, (cc, at, n) -> namedVariants(cc, at, n));
                break;
            }
            case "hkaAnimationContainer": {
                k.srcSize = 96; k.out = new byte[68];
                header(c, off, k);
                for (int i = 0; i < 5; i++) array(c, off + 16 + 16 * i, k, 8 + 12 * i, pointers());
                break;
            }
            case "hkaSplineCompressedAnimation": {
                k.srcSize = 176; k.out = new byte[132];
                header(c, off, k);
                ByteBuffer b = k.buf();
                b.putInt(8, c.i32(off + 16)); b.putInt(12, c.i32(off + 20)); b.putInt(16, c.i32(off + 24)); b.putInt(20, c.i32(off + 28));
                if (c.target(off + 32) >= 0) throw new Unsupported("animation has extracted motion");
                array(c, off + 40, k, 28, (cc, at, n) -> annotationTracks(cc, at, n));
                for (int i = 0; i < 7; i++) b.putInt(40 + 4 * i, c.i32(off + 56 + 4 * i));
                array(c, off + 88, k, 68, raw(4));
                array(c, off + 104, k, 80, raw(4));
                array(c, off + 120, k, 92, raw(4));
                array(c, off + 136, k, 104, raw(4));
                array(c, off + 152, k, 116, raw(1));
                b.putInt(128, c.i32(off + 168));
                break;
            }
            case "hkaAnimationBinding": {
                k.srcSize = 72; k.out = new byte[44];
                header(c, off, k);
                stringField(c, off + 16, k, 8);
                int t = c.target(off + 24);
                if (t >= 0) link(c, off + 24, k, 12, object(c, t));
                array(c, off + 32, k, 16, raw(2));
                array(c, off + 48, k, 28, raw(2));
                k.out[40] = c.d.get(off + 64);
                break;
            }
            default:
                throw new Unsupported("contains " + cls + ", which is not a plain animation clip");
        }
        return k;
    }

    private static Chunk namedVariants(Ctx c, int at, int n) throws Unsupported {
        Chunk k = c.chunks.get(at);
        if (k != null) return k;
        k = new Chunk(at); k.srcSize = n * 24; k.out = new byte[n * 12];
        c.chunks.put(at, k);
        for (int i = 0; i < n; i++) {
            int s = at + 24 * i, d = 12 * i;
            stringField(c, s, k, d);
            stringField(c, s + 8, k, d + 4);
            int t = c.target(s + 16);
            if (t >= 0) link(c, s + 16, k, d + 8, object(c, t));
        }
        return k;
    }

    private static Chunk annotationTracks(Ctx c, int at, int n) throws Unsupported {
        Chunk k = c.chunks.get(at);
        if (k != null) return k;
        k = new Chunk(at); k.srcSize = n * 24; k.out = new byte[n * 16];
        c.chunks.put(at, k);
        for (int i = 0; i < n; i++) {
            int s = at + 24 * i, d = 16 * i;
            stringField(c, s, k, d);
            array(c, s + 8, k, d + 4, (cc, a2, m) -> annotations(cc, a2, m));
        }
        return k;
    }

    private static Chunk annotations(Ctx c, int at, int n) throws Unsupported {
        Chunk k = c.chunks.get(at);
        if (k != null) return k;
        k = new Chunk(at); k.srcSize = n * 16; k.out = new byte[n * 8];
        c.chunks.put(at, k);
        for (int i = 0; i < n; i++) {
            int s = at + 16 * i, d = 8 * i;
            k.buf().putInt(d, c.i32(s));
            stringField(c, s + 8, k, d + 4);
        }
        return k;
    }
}
