package com.se2le;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Converts static Skyrim SE meshes (NIF stream 100, BSTriShape) to Skyrim LE (stream 83, NiTriShape + NiTriShapeData).
 * Only block types verified against real LE/SE files are handled; anything else is refused so the caller can
 * leave the file for a full converter. Existing block indices never move: new data blocks are appended.
 */
public final class NifConverter {
    private NifConverter() {}

    public static final class Result {
        public final byte[] data; public final String note;
        Result(byte[] d, String n) { data = d; note = n; }
    }

    private static final Set<String> COPY_OK = new HashSet<>(Arrays.asList(
        "BSFadeNode", "NiNode", "BSInvMarker", "BSXFlags", "NiStringExtraData",
        "bhkBoxShape", "bhkConvexTransformShape", "bhkListShape", "bhkRigidBody", "bhkRigidBodyT", "bhkCollisionObject",
        "BSLightingShaderProperty", "BSShaderTextureSet", "BSEffectShaderProperty", "NiAlphaProperty"));

    private static UnsupportedOperationException no(String m) { return new UnsupportedOperationException(m); }
    private static int le16(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8); }
    private static int le32(byte[] b, int o) { return le16(b, o) | (le16(b, o + 2) << 16); }
    private static long le64(byte[] b, int o) { return (le32(b, o) & 0xFFFFFFFFL) | ((long) le32(b, o + 4) << 32); }

    private static final class Hdr {
        int off0, offStream, offTypes, offRest, blockStart, nb, stream;
        String[] types; int[] typeIdx, sizes; int[] blockOff;
    }

    /** Returns null when the buffer is not a Skyrim 20.2.0.7 NIF. */
    private static Hdr parse(byte[] d) {
        String sig = "Gamebryo File Format, Version 20.2.0.7";
        if (d.length < 64 || !new String(d, 0, sig.length(), StandardCharsets.ISO_8859_1).equals(sig)) return null;
        int p = 0; while (p < d.length && d[p] != '\n') p++;
        p++;
        if (le32(d, p) != 0x14020007) return null;
        p += 5;
        if (le32(d, p) != 12) return null;                      // user version 12 = Skyrim
        p += 4;
        Hdr h = new Hdr();
        h.off0 = p; h.nb = le32(d, p); p += 4;
        h.offStream = p; h.stream = le32(d, p); p += 4;
        for (int i = 0; i < 3; i++) p += 1 + (d[p] & 255);      // author, process script, export script
        h.offTypes = p;
        int nt = le16(d, p); p += 2;
        h.types = new String[nt];
        for (int i = 0; i < nt; i++) { int n = le32(d, p); p += 4; h.types[i] = new String(d, p, n, StandardCharsets.ISO_8859_1); p += n; }
        h.typeIdx = new int[h.nb]; h.sizes = new int[h.nb];
        for (int i = 0; i < h.nb; i++) { h.typeIdx[i] = le16(d, p); p += 2; }
        for (int i = 0; i < h.nb; i++) { h.sizes[i] = le32(d, p); p += 4; }
        h.offRest = p;
        int ns = le32(d, p); p += 8;
        for (int i = 0; i < ns; i++) { int n = le32(d, p); p += 4 + n; }
        int ng = le32(d, p); p += 4 + 4 * ng;
        h.blockStart = p;
        h.blockOff = new int[h.nb + 1];
        int o = p;
        for (int i = 0; i < h.nb; i++) { h.blockOff[i] = o; o += h.sizes[i]; }
        h.blockOff[h.nb] = o;
        if (o > d.length) throw no("truncated NIF");
        return h;
    }

    private static float half(int h) {
        int s = (h >> 15) & 1, e = (h >> 10) & 0x1F, m = h & 0x3FF;
        double v = e == 0 ? m * Math.pow(2, -24) : e == 31 ? (m == 0 ? Double.POSITIVE_INFINITY : Double.NaN) : (1 + m / 1024.0) * Math.pow(2, e - 15);
        return (float) (s == 1 ? -v : v);
    }
    private static float unorm(int b) { return (b & 255) / 255f * 2f - 1f; }
    private static void put3(ByteBuffer o, float x, float y, float z, boolean norm) {
        if (norm) { double l = Math.sqrt((double) x * x + (double) y * y + (double) z * z); if (l > 1e-9) { x /= l; y /= l; z /= l; } }
        o.putFloat(x); o.putFloat(y); o.putFloat(z);
    }

    private static byte[][] convertShape(byte[] b, int dataIndex) {
        int nex = le32(b, 4);
        int pre = 8 + 4 * nex + 8 + 12 + 36 + 4 + 4;           // NiAVObject part is identical in both formats
        int p = pre;
        int sphere = p; p += 16;
        int skin = le32(b, p), shader = le32(b, p + 4), alpha = le32(b, p + 8); p += 12;
        long vd = le64(b, p); p += 8;
        int nt = le16(b, p), nv = le16(b, p + 2); p += 4;
        long dsz = le32(b, p) & 0xFFFFFFFFL; p += 4;
        if (skin != -1) throw no("skinned mesh (needs bone weights rebuilt)");
        int flags = (int) ((vd >>> 44) & 0x7FF);
        if ((flags & ~0x3B) != 0 || (flags & 1) == 0) throw no(String.format("unsupported vertex attributes (flags 0x%X)", flags));
        boolean hasUV = (flags & 2) != 0, hasN = (flags & 8) != 0, hasT = (flags & 0x10) != 0, hasC = (flags & 0x20) != 0;
        if (hasT && !hasN) throw no("tangents without normals");
        int vs = (int) (vd & 0xF) * 4;
        int oV = (int) ((vd >>> 4) & 0xF) * 4, oUV = (int) ((vd >>> 8) & 0xF) * 4, oN = (int) ((vd >>> 16) & 0xF) * 4,
            oT = (int) ((vd >>> 20) & 0xF) * 4, oC = (int) ((vd >>> 24) & 0xF) * 4;
        if ((long) nv * vs + (long) nt * 6 != dsz) throw no("unexpected vertex data size");
        if (oV + (hasT ? 16 : 12) > vs || (hasUV && oUV + 4 > vs) || (hasN && oN + 4 > vs) || (hasT && oT + 4 > vs) || (hasC && oC + 4 > vs))
            throw no("vertex layout does not fit the vertex size");
        int vbase = p, tbase = p + nv * vs;
        if ((long) p + dsz + 4 != b.length) throw no("unexpected shape block size");
        if (le32(b, p + (int) dsz) != 0) throw no("particle data");

        ByteBuffer s = ByteBuffer.allocate(pre + 25).order(ByteOrder.LITTLE_ENDIAN);
        s.put(b, 0, pre); s.putInt(dataIndex); s.putInt(-1); s.putInt(0); s.putInt(0); s.put((byte) 0); s.putInt(shader); s.putInt(alpha);

        int size = 4 + 2 + 3 + 12 * nv + 2 + 4 + 1 + (hasN ? 12 * nv * (hasT ? 3 : 1) : 0) + 16 + 1 + (hasC ? 16 * nv : 0)
                 + (hasUV ? 8 * nv : 0) + 2 + 4 + 2 + 4 + 1 + 6 * nt + 2;
        ByteBuffer o = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        o.putInt(0); o.putShort((short) nv); o.put((byte) 0); o.put((byte) 0); o.put((byte) 1);
        for (int i = 0; i < nv; i++) { int q = vbase + i * vs + oV; o.putFloat(Float.intBitsToFloat(le32(b, q))); o.putFloat(Float.intBitsToFloat(le32(b, q + 4))); o.putFloat(Float.intBitsToFloat(le32(b, q + 8))); }
        o.putShort((short) ((hasUV ? 1 : 0) | (hasT ? 0x1000 : 0))); o.putInt(0);
        o.put((byte) (hasN ? 1 : 0));
        if (hasN) {
            for (int i = 0; i < nv; i++) { int q = vbase + i * vs + oN; put3(o, unorm(b[q]), unorm(b[q + 1]), unorm(b[q + 2]), true); }
            if (hasT) {
                // LE's first array is SE's tangent, the second is SE's bitangent (checked against UV derivatives in both formats)
                for (int i = 0; i < nv; i++) { int q = vbase + i * vs + oT; put3(o, unorm(b[q]), unorm(b[q + 1]), unorm(b[q + 2]), true); }
                for (int i = 0; i < nv; i++) {
                    int base = vbase + i * vs;
                    put3(o, Float.intBitsToFloat(le32(b, base + oV + 12)), unorm(b[base + oN + 3]), unorm(b[base + oT + 3]), true);
                }
            }
        }
        o.put(b, sphere, 16);
        o.put((byte) (hasC ? 1 : 0));
        if (hasC) for (int i = 0; i < nv; i++) { int q = vbase + i * vs + oC; for (int c = 0; c < 4; c++) o.putFloat((b[q + c] & 255) / 255f); }
        if (hasUV) for (int i = 0; i < nv; i++) { int q = vbase + i * vs + oUV; o.putFloat(half(le16(b, q))); o.putFloat(half(le16(b, q + 2))); }
        o.putShort((short) 0); o.putInt(-1);
        o.putShort((short) nt); o.putInt(3 * nt); o.put((byte) 1);
        o.put(b, tbase, 6 * nt);
        o.putShort((short) 0);
        return new byte[][] { s.array(), o.array() };
    }

    /** null = nothing to do (not an SE Skyrim mesh). Throws UnsupportedOperationException when it can't be converted safely. */
    public static Result convert(byte[] d) {
        Hdr h = parse(d);
        if (h == null || h.stream != 100) return null;
        List<String> names = new ArrayList<>(); List<byte[]> blocks = new ArrayList<>();
        List<String> xNames = new ArrayList<>(); List<byte[]> xBlocks = new ArrayList<>();
        int convex = 0;
        for (int i = 0; i < h.nb; i++) {
            String t = h.types[h.typeIdx[i]];
            byte[] blk = Arrays.copyOfRange(d, h.blockOff[i], h.blockOff[i + 1]);
            if (t.equals("BSTriShape")) {
                byte[][] r = convertShape(blk, h.nb + xBlocks.size());
                names.add("NiTriShape"); blocks.add(r[0]); xNames.add("NiTriShapeData"); xBlocks.add(r[1]);
            } else if (COPY_OK.contains(t)) { names.add(t); blocks.add(blk); }
            else if (t.equals("bhkConvexVerticesShape")) {
                // Plain collision geometry with no references to other blocks. It is copied as it is, but only when its size fits the known layout exactly.
                String e = checkConvex(blk);
                if (e != null) throw no("contains a bhkConvexVerticesShape block with an unexpected layout (" + e + ")");
                names.add(t); blocks.add(blk); convex++;
            }
            else throw no("contains a " + t + " block, which this converter does not handle");
        }
        int shapes = xBlocks.size();
        names.addAll(xNames); blocks.addAll(xBlocks);
        int nb = blocks.size();
        LinkedHashMap<String, Integer> types = new LinkedHashMap<>();
        for (String n : names) if (!types.containsKey(n)) types.put(n, types.size());

        long total = (long) h.blockStart + 128 + (long) nb * 8 + (long) types.size() * 64 + (d.length - h.blockOff[h.nb]);
        for (byte[] b : blocks) total += b.length;
        if (total > Integer.MAX_VALUE - 1024) throw no("converted mesh would be too large");
        ByteBuffer hd = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        hd.put(d, 0, h.off0); hd.putInt(nb); hd.putInt(83);
        hd.put(d, h.offStream + 4, h.offTypes - (h.offStream + 4));
        hd.putShort((short) types.size());
        for (String n : types.keySet()) { hd.putInt(n.length()); hd.put(n.getBytes(StandardCharsets.ISO_8859_1)); }
        for (String n : names) hd.putShort((short) (int) types.get(n));
        for (byte[] b : blocks) hd.putInt(b.length);
        hd.put(d, h.offRest, h.blockStart - h.offRest);
        for (byte[] b : blocks) hd.put(b);
        hd.put(d, h.blockOff[h.nb], d.length - h.blockOff[h.nb]);     // footer (root list) unchanged
        byte[] out = Arrays.copyOf(hd.array(), hd.position());
        String err = validate(out);
        if (err != null) throw no("self-check failed: " + err);
        return new Result(out, shapes + " BSTriShape -> NiTriShape + NiTriShapeData, NIF stream 100 -> 83"
            + (convex > 0 ? "; " + convex + " convex collision shape(s) copied unchanged" : ""));
    }

    /**
     * bhkConvexVerticesShape: material, radius, (optionally 6 unknown floats), two 12-byte property records, then the vertex list and the normal list
     * (16 bytes per entry, each with a count in front). Returns null only when one of the two layouts accounts for every byte of the block.
     */
    private static String checkConvex(byte[] b) {
        for (int pre : new int[] { 56, 32 }) {
            if (b.length < pre + 8) continue;
            long nv = le32(b, pre) & 0xFFFFFFFFL;
            long q = pre + 4 + 16 * nv;
            if (q + 4 > b.length) continue;
            long nn = le32(b, (int) q) & 0xFFFFFFFFL;
            if (q + 4 + 16 * nn == b.length) return null;
        }
        return "size does not match the expected layout";
    }

    /** Structural self-check of an LE mesh. Returns null when it looks valid. */
    public static String validate(byte[] d) {
        Hdr h;
        try { h = parse(d); } catch (RuntimeException e) { return "unreadable header"; }
        if (h == null) return "not a Skyrim NIF";
        if (h.stream != 83) return "not stream 83";
        for (int i = 0; i < h.nb; i++) {
            String t = h.types[h.typeIdx[i]];
            if (t.equals("BSTriShape")) return "BSTriShape block " + i + " remains";
            byte[] b = Arrays.copyOfRange(d, h.blockOff[i], h.blockOff[i + 1]);
            if (t.equals("NiTriShape")) {
                int nex = le32(b, 4), p = 8 + 4 * nex + 8 + 12 + 36 + 4 + 4;
                if (b.length != p + 25) return "NiTriShape " + i + " has the wrong size";
                int data = le32(b, p), skin = le32(b, p + 4), shader = le32(b, p + 17), alpha = le32(b, p + 21);
                if (data < 0 || data >= h.nb || !h.types[h.typeIdx[data]].equals("NiTriShapeData")) return "NiTriShape " + i + " points at a bad data block";
                for (int r : new int[] { skin, shader, alpha }) if (r < -1 || r >= h.nb) return "NiTriShape " + i + " has an out-of-range reference";
            } else if (t.equals("NiTriShapeData")) {
                String e = checkData(b);
                if (e != null) return "NiTriShapeData " + i + ": " + e;
            }
        }
        return null;
    }

    private static String checkData(byte[] b) {
        try {
            int p = 4; int nv = le16(b, p); p += 2 + 3 + 12 * nv;
            int bs = le16(b, p); p += 2 + 4;
            boolean hasN = b[p++] != 0;
            if (hasN) p += 12 * nv * ((bs & 0x1000) != 0 ? 3 : 1);
            p += 16;
            boolean hasC = b[p++] != 0;
            if (hasC) p += 16 * nv;
            if ((bs & 1) != 0) p += 8 * nv;
            p += 2 + 4;
            int nt = le16(b, p); p += 2;
            if (le32(b, p) != 3 * nt) return "triangle point count mismatch";
            p += 4;
            boolean hasT = b[p++] != 0;
            if (hasT) {
                for (int i = 0; i < 3 * nt; i++) if (le16(b, p + 2 * i) >= nv) return "triangle index out of range";
                p += 6 * nt;
            }
            p += 2;
            return p == b.length ? null : "size mismatch (" + p + " vs " + b.length + ")";
        } catch (RuntimeException e) { return "unreadable"; }
    }
}
