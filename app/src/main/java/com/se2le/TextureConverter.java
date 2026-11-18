package com.se2le;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Converts Skyrim SE textures (BC7 / BC5 / BC4 and DX10-header BC1-BC3) to the legacy DDS formats
 * Skyrim LE can read (DXT1 / DXT5). Works block-to-block, so memory use stays at one input and one output buffer.
 */
public final class TextureConverter {
    private TextureConverter() {}

    public static final class Result {
        public final byte[] data;
        public final String note;
        Result(byte[] data, String note) { this.data = data; this.note = note; }
    }

    private static final int BC1 = 1, BC2 = 2, BC3 = 3, BC4 = 4, BC5 = 5, BC7 = 7;

    /** Per-thread scratch space so the per-block code never allocates. */
    static final class Ctx {
        final int[] px = new int[64], t0 = new int[16], t1 = new int[16], pal8 = new int[8], apal = new int[8];
        final int[][] ep = new int[6][4];
        final int[] pbit = new int[6], subset = new int[16], idx = new int[16], idx2 = new int[16];
        final double[] mean = new double[3], cov = new double[9], ax = new double[3], nv = new double[3], a = new double[3], b = new double[3],
            na = new double[3], nb = new double[3], ra = new double[3], rb = new double[3];
        final int[] eidx = new int[16], bidx = new int[16], cc = new int[2], pal = new int[12];
        final int[] a16 = new int[16], ia = new int[16], ib = new int[16];
    }

    private interface Range { void run(int from, int to, Ctx c); }

    private static final int THREADS = Math.max(1, Integer.getInteger("se2le.threads", Runtime.getRuntime().availableProcessors()));
    private static final ExecutorService POOL = THREADS > 1 ? Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "se2le-tex"); t.setDaemon(true); return t;
    }) : null;

    /** Runs task over [0,n) split into chunks on all cores; each chunk gets its own scratch space. */
    private static void parallelFor(int n, int minChunk, Range task) {
        if (POOL == null || n < 2 * minChunk) { task.run(0, n, new Ctx()); return; }
        int chunks = Math.min(THREADS * 4, n / minChunk);
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            final int a = (int) ((long) n * i / chunks), b = (int) ((long) n * (i + 1) / chunks);
            jobs.add(() -> { task.run(a, b, new Ctx()); return null; });
        }
        try { for (Future<Void> f : POOL.invokeAll(jobs)) f.get(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        catch (ExecutionException e) { Throwable c = e.getCause(); throw c instanceof RuntimeException ? (RuntimeException) c : new RuntimeException(c); }
    }

    private static final int[] P2 = {
        52428, 34952, 61166, 60616, 51328, 65260, 65224, 60544,
        51200, 65516, 65152, 59392, 65512, 65280, 65520, 61440,
        63248, 142, 28928, 2254, 140, 29456, 12544, 36046,
        2188, 12560, 26214, 13932, 6120, 4080, 29070, 14748,
        43690, 61680, 23130, 13260, 15420, 21930, 38550, 42330,
        29646, 5064, 12876, 15324, 27030, 49980, 39270, 1632,
        626, 1252, 20032, 10016, 51510, 37740, 14790, 25500,
        37686, 40134, 33150, 59160, 52464, 4044, 30532, 60962
    };
    private static final int[] P3 = {
        0xAA685050, 0x6A5A5040, 0x5A5A4200, 0x5450A0A8, 0xA5A50000, 0xA0A05050, 0x5555A0A0, 0x5A5A5050,
        0xAA550000, 0xAA555500, 0xAAAA5500, 0x90909090, 0x94949494, 0xA4A4A4A4, 0xA9A59450, 0x2A0A4250,
        0xA5945040, 0x0A425054, 0xA5A5A500, 0x55A0A0A0, 0xA8A85454, 0x6A6A4040, 0xA4A45000, 0x1A1A0500,
        0x0050A4A4, 0xAAA59090, 0x14696914, 0x69691400, 0xA08585A0, 0xAA821414, 0x50A4A450, 0x6A5A0200,
        0xA9A58000, 0x5090A0A8, 0xA8A09050, 0x24242424, 0x00AA5500, 0x24924924, 0x24499224, 0x50A50A50,
        0x500AA550, 0xAAAA4444, 0x66660000, 0xA5A0A5A0, 0x50A050A0, 0x69286928, 0x44AAAA44, 0x66666600,
        0xAA444444, 0x54A854A8, 0x95809580, 0x96969600, 0xA85454A8, 0x80959580, 0xAA141414, 0x96960000,
        0xAAAA1414, 0xA05050A0, 0xA0A5A5A0, 0x96000000, 0x40804080, 0xA9A8A9A8, 0xAAAAAA44, 0x2A4A5254
    };
    private static final int[] A2 = {
        15, 15, 15, 15, 15, 15, 15, 15,
        15, 15, 15, 15, 15, 15, 15, 15,
        15, 2, 8, 2, 2, 8, 8, 15,
        2, 8, 2, 2, 8, 8, 2, 2,
        15, 15, 6, 8, 2, 8, 15, 15,
        2, 8, 2, 2, 2, 15, 15, 6,
        6, 2, 6, 8, 15, 15, 2, 2,
        15, 15, 15, 15, 15, 2, 2, 15
    };
    private static final int[] A3B = {
        3, 3, 15, 15, 8, 3, 15, 15,
        8, 8, 6, 6, 6, 5, 3, 3,
        3, 3, 8, 15, 3, 3, 6, 10,
        5, 8, 8, 6, 8, 5, 15, 15,
        8, 15, 3, 5, 6, 10, 8, 15,
        15, 3, 15, 5, 15, 15, 15, 15,
        3, 15, 5, 5, 5, 8, 5, 10,
        5, 10, 8, 13, 15, 12, 3, 3
    };
    private static final int[] A3C = {
        15, 8, 8, 3, 15, 15, 3, 8,
        15, 15, 15, 15, 15, 15, 15, 8,
        15, 8, 15, 3, 15, 8, 15, 8,
        3, 15, 6, 10, 15, 15, 10, 8,
        15, 3, 15, 10, 10, 8, 9, 10,
        6, 15, 8, 15, 3, 6, 6, 8,
        15, 3, 15, 15, 15, 15, 15, 15,
        15, 15, 15, 15, 3, 15, 15, 8
    };

    private static final int[] W2 = {0, 21, 43, 64};
    private static final int[] W3 = {0, 9, 18, 27, 37, 46, 55, 64};
    private static final int[] W4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};
    // NS, PB, RB, ISB, CB, AB, EPB, SPB, IB, IB2
    private static final int[][] MODES = {
        {3, 4, 0, 0, 4, 0, 1, 0, 3, 0}, {2, 6, 0, 0, 6, 0, 0, 1, 3, 0},
        {3, 6, 0, 0, 5, 0, 0, 0, 2, 0}, {2, 6, 0, 0, 7, 0, 1, 0, 2, 0},
        {1, 0, 2, 1, 5, 6, 0, 0, 2, 3}, {1, 0, 2, 0, 7, 8, 0, 0, 2, 2},
        {1, 0, 0, 0, 7, 7, 1, 0, 4, 0}, {2, 6, 0, 0, 5, 5, 1, 0, 2, 0}};

    private static UnsupportedOperationException no(String m) { return new UnsupportedOperationException(m); }
    private static int le32(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8) | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
    }
    private static int le16(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8); }
    private static void put32(byte[] b, int o, int v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >>> (8 * i)); }

    // ------------------------------------------------------------------ BC7 decode
    private static final class BitR {
        final long lo, hi; int pos;
        BitR(long lo, long hi, int pos) { this.lo = lo; this.hi = hi; this.pos = pos; }
        int get(int n) {
            if (n == 0) return 0;
            long mask = (1L << n) - 1;
            int p = pos, v;
            if (p + n <= 64) v = (int) ((lo >>> p) & mask);
            else if (p >= 64) v = (int) ((hi >>> (p - 64)) & mask);
            else v = (int) (((lo >>> p) | (hi << (64 - p))) & mask);
            pos += n;
            return v;
        }
    }

    private static int unq(int v, int n) { return (v << (8 - n)) | (v >> (2 * n - 8)); }
    private static int[] weights(int n) { return n == 2 ? W2 : n == 3 ? W3 : W4; }

    /** out = 16 pixels x (r,g,b,a). */
    static void decodeBC7(byte[] d, int off, int[] out) { decodeBC7(d, off, out, new Ctx()); }

    static void decodeBC7(byte[] d, int off, int[] out, Ctx cx) {
        long lo = 0, hi = 0;
        for (int i = 0; i < 8; i++) {
            lo |= (long) (d[off + i] & 255) << (8 * i);
            hi |= (long) (d[off + 8 + i] & 255) << (8 * i);
        }
        if ((lo & 0xFF) == 0) { Arrays.fill(out, 0, 64, 0); return; }
        int mode = Long.numberOfTrailingZeros(lo);
        int[] m = MODES[mode];
        int ns = m[0], pb = m[1], rb = m[2], isb = m[3], cb = m[4], ab = m[5], epb = m[6], spb = m[7], ib = m[8], ib2 = m[9];
        BitR r = new BitR(lo, hi, mode + 1);
        int part = r.get(pb), rot = r.get(rb), sel = r.get(isb);
        int ne = ns * 2;
        int[][] ep = cx.ep;
        for (int ch = 0; ch < 3; ch++) for (int e = 0; e < ne; e++) ep[e][ch] = r.get(cb);
        if (ab > 0) for (int e = 0; e < ne; e++) ep[e][3] = r.get(ab);
        int[] pbit = cx.pbit; Arrays.fill(pbit, 0);
        boolean hasP = epb == 1 || spb == 1;
        if (epb == 1) for (int e = 0; e < ne; e++) pbit[e] = r.get(1);
        else if (spb == 1) for (int s = 0; s < ns; s++) { int p = r.get(1); pbit[2 * s] = p; pbit[2 * s + 1] = p; }
        int cbits = cb + (hasP ? 1 : 0), abits = ab + (hasP && ab > 0 ? 1 : 0);
        for (int e = 0; e < ne; e++) {
            for (int ch = 0; ch < 3; ch++) { int v = ep[e][ch]; if (hasP) v = (v << 1) | pbit[e]; ep[e][ch] = unq(v, cbits); }
            if (ab > 0) { int v = ep[e][3]; if (hasP) v = (v << 1) | pbit[e]; ep[e][3] = unq(v, abits); } else ep[e][3] = 255;
        }
        int[] subset = cx.subset;
        for (int i = 0; i < 16; i++) subset[i] = ns == 1 ? 0 : ns == 2 ? (P2[part] >>> i) & 1 : (P3[part] >>> (2 * i)) & 3;
        int a1 = ns == 2 ? A2[part] : ns == 3 ? A3B[part] : -1, a2 = ns == 3 ? A3C[part] : -1;
        int[] idx = cx.idx;
        for (int i = 0; i < 16; i++) idx[i] = r.get(ib - ((i == 0 || i == a1 || i == a2) ? 1 : 0));
        int[] idx2 = null;
        if (ib2 > 0) { idx2 = cx.idx2; for (int i = 0; i < 16; i++) idx2[i] = r.get(ib2 - (i == 0 ? 1 : 0)); }
        for (int i = 0; i < 16; i++) {
            int[] e0 = ep[2 * subset[i]], e1 = ep[2 * subset[i] + 1];
            int ci, ai; int[] wc, wa;
            if (idx2 == null) { ci = ai = idx[i]; wc = wa = weights(ib); }
            else if (sel == 0) { ci = idx[i]; wc = weights(ib); ai = idx2[i]; wa = weights(ib2); }
            else { ci = idx2[i]; wc = weights(ib2); ai = idx[i]; wa = weights(ib); }
            int pr = (e0[0] * (64 - wc[ci]) + e1[0] * wc[ci] + 32) >> 6;
            int pg = (e0[1] * (64 - wc[ci]) + e1[1] * wc[ci] + 32) >> 6;
            int pbl = (e0[2] * (64 - wc[ci]) + e1[2] * wc[ci] + 32) >> 6;
            int pa = (e0[3] * (64 - wa[ai]) + e1[3] * wa[ai] + 32) >> 6;
            int t;
            if (rot == 1) { t = pr; pr = pa; pa = t; }
            else if (rot == 2) { t = pg; pg = pa; pa = t; }
            else if (rot == 3) { t = pbl; pbl = pa; pa = t; }
            out[i * 4] = pr; out[i * 4 + 1] = pg; out[i * 4 + 2] = pbl; out[i * 4 + 3] = pa;
        }
    }

    // ------------------------------------------------------------------ BC4 / BC5 decode
    static void decodeBC4(byte[] d, int off, int[] v) { decodeBC4(d, off, v, new int[8]); }

    static void decodeBC4(byte[] d, int off, int[] v, int[] p) {
        int a0 = d[off] & 255, a1 = d[off + 1] & 255;
        p[0] = a0; p[1] = a1;
        if (a0 > a1) for (int i = 1; i <= 6; i++) p[i + 1] = ((7 - i) * a0 + i * a1) / 7;
        else { for (int i = 1; i <= 4; i++) p[i + 1] = ((5 - i) * a0 + i * a1) / 5; p[6] = 0; p[7] = 255; }
        long bits = 0;
        for (int k = 0; k < 6; k++) bits |= (long) (d[off + 2 + k] & 255) << (8 * k);
        for (int i = 0; i < 16; i++) v[i] = p[(int) ((bits >>> (3 * i)) & 7)];
    }

    private static void decodeBlock(int fmt, byte[] d, int off, Ctx cx) {
        int[] px = cx.px, t0 = cx.t0, t1 = cx.t1;
        if (fmt == BC7) { decodeBC7(d, off, px, cx); return; }
        if (fmt == BC4) {
            decodeBC4(d, off, t0, cx.pal8);
            for (int i = 0; i < 16; i++) { px[i * 4] = px[i * 4 + 1] = px[i * 4 + 2] = t0[i]; px[i * 4 + 3] = 255; }
            return;
        }
        decodeBC4(d, off, t0, cx.pal8); decodeBC4(d, off + 8, t1, cx.pal8);       // BC5: rebuild the normal's Z
        for (int i = 0; i < 16; i++) {
            double x = t0[i] / 255.0 * 2 - 1, y = t1[i] / 255.0 * 2 - 1;
            double z = Math.sqrt(Math.max(0, 1 - x * x - y * y));
            px[i * 4] = t0[i]; px[i * 4 + 1] = t1[i];
            px[i * 4 + 2] = (int) Math.round((z * 0.5 + 0.5) * 255); px[i * 4 + 3] = 255;
        }
    }

    // ------------------------------------------------------------------ BC1 / BC3 encode
    private static int q565(double r, double g, double b) {
        int r5 = (int) Math.max(0, Math.min(31, Math.round(r * 31 / 255)));
        int g6 = (int) Math.max(0, Math.min(63, Math.round(g * 63 / 255)));
        int b5 = (int) Math.max(0, Math.min(31, Math.round(b * 31 / 255)));
        return (r5 << 11) | (g6 << 5) | b5;
    }
    private static void x565(int c, int[] o) {
        int r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
        o[0] = (r << 3) | (r >> 2); o[1] = (g << 2) | (g >> 4); o[2] = (b << 3) | (b >> 2);
    }

    private static void x565(int c, int[] o, int off) {
        int r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
        o[off] = (r << 3) | (r >> 2); o[off + 1] = (g << 2) | (g >> 4); o[off + 2] = (b << 3) | (b >> 2);
    }

    /** Fits a 4-colour block to endpoints a,b (float RGB). Returns the error; fills idx and c[0]=c0, c[1]=c1 (c0 > c1 unless equal). */
    private static long fit(int[] px, double[] a, double[] b, int[] idx, int[] c, int[] pal) {
        int q0 = q565(a[0], a[1], a[2]), q1 = q565(b[0], b[1], b[2]);
        if (q0 < q1) { int t = q0; q0 = q1; q1 = t; }
        c[0] = q0; c[1] = q1;
        x565(q0, pal, 0); x565(q1, pal, 3);
        for (int k = 0; k < 3; k++) { pal[6 + k] = (2 * pal[k] + pal[3 + k]) / 3; pal[9 + k] = (pal[k] + 2 * pal[3 + k]) / 3; }
        long err = 0;
        for (int i = 0; i < 16; i++) {
            int r = px[i * 4], g = px[i * 4 + 1], bl = px[i * 4 + 2];
            int best = 0; long be;
            if (q0 == q1) {                       // flat palette: only index 0 is safe in 3-colour mode
                int dr = r - pal[0], dg = g - pal[1], db = bl - pal[2];
                be = (long) dr * dr + dg * dg + db * db;
            } else {
                be = Long.MAX_VALUE;
                for (int j = 0; j < 4; j++) {
                    int dr = r - pal[j * 3], dg = g - pal[j * 3 + 1], db = bl - pal[j * 3 + 2];
                    long e = (long) dr * dr + dg * dg + db * db;
                    if (e < be) { be = e; best = j; }
                }
            }
            idx[i] = best; err += be;
        }
        return err;
    }

    static void encodeColor(int[] px, byte[] out, int op) { encodeColor(new Ctx(), px, out, op); }

    static void encodeColor(Ctx cx, int[] px, byte[] out, int op) {
        boolean flat = true;
        for (int i = 1; i < 16 && flat; i++) flat = px[i * 4] == px[0] && px[i * 4 + 1] == px[1] && px[i * 4 + 2] == px[2];
        if (flat) {                                // identical to what the full path produces, minus the maths
            int q = q565(px[0], px[1], px[2]);
            out[op] = (byte) q; out[op + 1] = (byte) (q >> 8); out[op + 2] = (byte) q; out[op + 3] = (byte) (q >> 8);
            out[op + 4] = 0; out[op + 5] = 0; out[op + 6] = 0; out[op + 7] = 0;
            return;
        }
        double[] mean = cx.mean, cov = cx.cov, ax = cx.ax, nv = cx.nv, a = cx.a, b = cx.b;
        mean[0] = mean[1] = mean[2] = 0;
        for (int i = 0; i < 16; i++) for (int k = 0; k < 3; k++) mean[k] += px[i * 4 + k] / 16.0;
        Arrays.fill(cov, 0);
        for (int i = 0; i < 16; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++)
            cov[j * 3 + k] += (px[i * 4 + j] - mean[j]) * (px[i * 4 + k] - mean[k]);
        ax[0] = 0.577; ax[1] = 0.577; ax[2] = 0.577;
        for (int it = 0; it < 8; it++) {
            for (int j = 0; j < 3; j++) { nv[j] = 0; for (int k = 0; k < 3; k++) nv[j] += cov[j * 3 + k] * ax[k]; }
            double len = Math.sqrt(nv[0] * nv[0] + nv[1] * nv[1] + nv[2] * nv[2]);
            if (len < 1e-9) break;
            for (int j = 0; j < 3; j++) ax[j] = nv[j] / len;
        }
        double tmin = 1e30, tmax = -1e30;
        for (int i = 0; i < 16; i++) {
            double t = 0;
            for (int k = 0; k < 3; k++) t += (px[i * 4 + k] - mean[k]) * ax[k];
            tmin = Math.min(tmin, t); tmax = Math.max(tmax, t);
        }
        for (int k = 0; k < 3; k++) {
            a[k] = Math.max(0, Math.min(255, mean[k] + ax[k] * tmin));
            b[k] = Math.max(0, Math.min(255, mean[k] + ax[k] * tmax));
        }
        int[] idx = cx.eidx, bestIdx = cx.bidx, c = cx.cc, pal = cx.pal;
        long bestErr = fit(px, a, b, idx, c, pal);
        System.arraycopy(idx, 0, bestIdx, 0, 16);
        int bc0 = c[0], bc1 = c[1];
        double[] ra = cx.ra, rb = cx.rb, na = cx.na, nb = cx.nb;
        for (int iter = 0; iter < 3 && bestErr > 0; iter++) {       // least-squares endpoint refinement
            double aa = 0, bb = 0, ab = 0;
            ra[0] = ra[1] = ra[2] = 0; rb[0] = rb[1] = rb[2] = 0;
            for (int i = 0; i < 16; i++) {
                double t = bestIdx[i] == 0 ? 0 : bestIdx[i] == 1 ? 1 : bestIdx[i] == 2 ? 1.0 / 3 : 2.0 / 3;
                double s = 1 - t; aa += s * s; bb += t * t; ab += s * t;
                for (int k = 0; k < 3; k++) { ra[k] += s * px[i * 4 + k]; rb[k] += t * px[i * 4 + k]; }
            }
            double det = aa * bb - ab * ab;
            if (Math.abs(det) < 1e-9) break;
            for (int k = 0; k < 3; k++) {
                na[k] = Math.max(0, Math.min(255, (ra[k] * bb - rb[k] * ab) / det));
                nb[k] = Math.max(0, Math.min(255, (rb[k] * aa - ra[k] * ab) / det));
            }
            long e = fit(px, na, nb, idx, c, pal);
            if (e < bestErr) { bestErr = e; System.arraycopy(idx, 0, bestIdx, 0, 16); bc0 = c[0]; bc1 = c[1]; } else break;
        }
        out[op] = (byte) bc0; out[op + 1] = (byte) (bc0 >> 8); out[op + 2] = (byte) bc1; out[op + 3] = (byte) (bc1 >> 8);
        for (int r = 0; r < 4; r++)
            out[op + 4 + r] = (byte) (bestIdx[r * 4] | (bestIdx[r * 4 + 1] << 2) | (bestIdx[r * 4 + 2] << 4) | (bestIdx[r * 4 + 3] << 6));
    }

    private static long fitAlpha(int[] a, int p0, int p1, int[] idx, int[] pal) {
        pal[0] = p0; pal[1] = p1;
        if (p0 > p1) for (int i = 1; i <= 6; i++) pal[i + 1] = ((7 - i) * p0 + i * p1) / 7;
        else { for (int i = 1; i <= 4; i++) pal[i + 1] = ((5 - i) * p0 + i * p1) / 5; pal[6] = 0; pal[7] = 255; }
        long err = 0;
        for (int i = 0; i < 16; i++) {
            int best = 0, be = Integer.MAX_VALUE;
            for (int j = 0; j < 8; j++) { int e = (a[i] - pal[j]) * (a[i] - pal[j]); if (e < be) { be = e; best = j; } }
            idx[i] = best; err += be;
        }
        return err;
    }

    static void encodeAlpha(int[] px, byte[] out, int op) { encodeAlpha(new Ctx(), px, out, op); }

    static void encodeAlpha(Ctx cx, int[] px, byte[] out, int op) {
        int[] a = cx.a16, ia = cx.ia, ib = cx.ib;
        int mn = 255, mx = 0, lo = 255, hi = 0; boolean mid = false;
        for (int i = 0; i < 16; i++) {
            a[i] = px[i * 4 + 3]; mn = Math.min(mn, a[i]); mx = Math.max(mx, a[i]);
            if (a[i] != 0 && a[i] != 255) { mid = true; lo = Math.min(lo, a[i]); hi = Math.max(hi, a[i]); }
        }
        long ea = fitAlpha(a, mx, mn, ia, cx.apal);
        int p0 = mx, p1 = mn; int[] best = ia;
        if (mid) {
            long eb = fitAlpha(a, lo, hi, ib, cx.apal);
            if (eb < ea) { p0 = lo; p1 = hi; best = ib; }
        }
        out[op] = (byte) p0; out[op + 1] = (byte) p1;
        long bits = 0;
        for (int i = 0; i < 16; i++) bits |= (long) best[i] << (3 * i);
        for (int k = 0; k < 6; k++) out[op + 2 + k] = (byte) (bits >>> (8 * k));
    }

    // ------------------------------------------------------------------ container handling
    private static int dxgiToFmt(int id) {
        if (id >= 70 && id <= 72) return BC1;
        if (id >= 73 && id <= 75) return BC2;
        if (id >= 76 && id <= 78) return BC3;
        if (id == 79 || id == 80) return BC4;
        if (id == 82 || id == 83) return BC5;
        if (id >= 97 && id <= 99) return BC7;
        if (id == 81 || id == 84) throw no("signed (SNORM) BC4/BC5 is not converted");
        if (id >= 94 && id <= 96) throw no("BC6H (HDR) is not converted");
        throw no("DXGI format " + id + " is not converted");
    }

    private static int blocks(int w, int h) { return Math.max(1, (w + 3) / 4) * Math.max(1, (h + 3) / 4); }

    private static long surfaceBytes(int w, int h, int mips, int faces, int blockBytes) {
        long t = 0;
        for (int m = 0; m < mips; m++) { t += (long) blocks(w, h) * blockBytes; w = Math.max(1, w >> 1); h = Math.max(1, h >> 1); }
        return t * faces;
    }

    /** True only if every pixel of the block is certainly opaque (the alpha source's endpoints are all 255). May say false for some opaque blocks. */
    static boolean bc7BlockOpaque(byte[] d, int off) {
        long lo = 0, hi = 0;
        for (int i = 0; i < 8; i++) { lo |= (long) (d[off + i] & 255) << (8 * i); hi |= (long) (d[off + 8 + i] & 255) << (8 * i); }
        if ((lo & 0xFF) == 0) return false;                 // reserved block decodes to all zeros
        int mode = Long.numberOfTrailingZeros(lo);
        if (mode < 4) return true;                          // modes 0-3 have no alpha channel
        int[] m = MODES[mode];
        int ns = m[0], pb = m[1], rb = m[2], isb = m[3], cb = m[4], ab = m[5], epb = m[6];
        BitR r = new BitR(lo, hi, mode + 1);
        r.get(pb); int rot = r.get(rb); r.get(isb);
        int ne = ns * 2;
        int ch = rot == 0 ? 3 : rot - 1;                    // which stored channel ends up as alpha
        int bits = ch == 3 ? ab : cb;
        BitR q = new BitR(lo, hi, r.pos + (ch == 3 ? 3 * ne * cb : ch * ne * cb));
        int pstart = r.pos + 3 * ne * cb + ne * ab;
        for (int e = 0; e < ne; e++) {
            int v = q.get(bits), n = bits;
            if (epb == 1) { v = (v << 1) | new BitR(lo, hi, pstart + e).get(1); n++; }
            if (unq(v, n) != 255) return false;
        }
        return true;
    }

    private static boolean allOpaque(byte[] d, int sp, long totalBytes) {
        for (long o = 0; o < totalBytes; o += 16) if (!bc7BlockOpaque(d, sp + (int) o)) return false;
        return true;
    }

    /**
     * Returns null when the texture is already LE-readable. Returns the converted file otherwise.
     * Throws UnsupportedOperationException when the texture needs conversion this class can't do.
     */
    public static Result convert(byte[] d) {
        if (d.length < 128 || d[0] != 'D' || d[1] != 'D' || d[2] != 'S' || d[3] != ' ') return null;
        if ((le32(d, 80) & 4) == 0) return null;                       // uncompressed: LE reads these
        String cc = new String(d, 84, 4, StandardCharsets.ISO_8859_1);
        boolean cube = (le32(d, 112) & 0x200) != 0, dx10 = false;
        int fmt, dataOff = 128;
        if (cc.equals("DXT1") || cc.equals("DXT3") || cc.equals("DXT5")) return null;
        else if (cc.equals("ATI1") || cc.equals("BC4U")) fmt = BC4;
        else if (cc.equals("ATI2") || cc.equals("BC5U")) fmt = BC5;
        else if (cc.equals("DX10")) {
            if (d.length < 148) throw no("truncated DX10 header");
            if (le32(d, 132) != 3) throw no("not a 2D texture");
            if (le32(d, 140) != 1) throw no("texture array");
            cube = (le32(d, 136) & 4) != 0;
            dataOff = 148; dx10 = true;
            fmt = dxgiToFmt(le32(d, 128));
        } else throw no("FourCC '" + cc + "' is not DXT1/DXT3/DXT5");

        int w = le32(d, 16), h = le32(d, 12);
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) throw no("invalid size");
        int mips = (le32(d, 8) & 0x20000) != 0 ? Math.max(1, Math.min(32, le32(d, 28))) : 1;
        int faces = cube ? 6 : 1;
        int srcBlock = (fmt == BC1 || fmt == BC4) ? 8 : 16;
        long srcTotal = surfaceBytes(w, h, mips, faces, srcBlock);
        if (dataOff + srcTotal > d.length) throw no("file is truncated");

        boolean lossless = fmt == BC1 || fmt == BC2 || fmt == BC3;
        int outFmt; String note;
        if (lossless) { outFmt = fmt; note = "DX10 header removed, " + (fmt == BC1 ? "BC1" : fmt == BC2 ? "BC2" : "BC3") + " data kept as-is"; }
        else {
            boolean opaque = fmt != BC7 || allOpaque(d, dataOff, srcTotal);
            outFmt = opaque ? BC1 : BC3;
            note = (fmt == BC7 ? "BC7" : fmt == BC5 ? "BC5" : "BC4") + " -> " + (opaque ? "BC1 (opaque)" : "BC3 (has alpha)");
        }
        int dstBlock = outFmt == BC1 ? 8 : 16;
        long dstTotal = surfaceBytes(w, h, mips, faces, dstBlock);
        byte[] out = new byte[(int) (128 + dstTotal)];
        System.arraycopy(d, 0, out, 0, 128);
        int caps = le32(d, 108) | 0x1000, caps2 = le32(d, 112);
        if (mips > 1) caps |= 0x400 | 0x8;
        if (cube) { caps |= 0x8; caps2 |= 0xFE00; }
        put32(out, 8, 0x1 | 0x2 | 0x4 | 0x1000 | 0x80000 | (mips > 1 ? 0x20000 : 0));
        put32(out, 20, blocks(w, h) * dstBlock);
        put32(out, 28, mips);
        put32(out, 76, 32); put32(out, 80, 4);
        byte[] fcc = (outFmt == BC1 ? "DXT1" : outFmt == BC2 ? "DXT3" : "DXT5").getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(fcc, 0, out, 84, 4);
        for (int o = 88; o < 108; o += 4) put32(out, o, 0);
        put32(out, 108, caps); put32(out, 112, caps2);
        if (lossless) { System.arraycopy(d, dataOff, out, 128, (int) srcTotal); return new Result(out, note); }

        int sp = dataOff, op = 128;
        final int fFmt = fmt, fOut = outFmt, fSrc = srcBlock, fDst = dstBlock;
        for (int f = 0; f < faces; f++) {
            int mw = w, mh = h;
            for (int m = 0; m < mips; m++) {
                final int n = blocks(mw, mh), s0 = sp, o0 = op;
                parallelFor(n, 1024, (a, b, c) -> {
                    for (int i = a; i < b; i++) {
                        decodeBlock(fFmt, d, s0 + i * fSrc, c);
                        if (fOut == BC1) encodeColor(c, c.px, out, o0 + i * fDst);
                        else { encodeAlpha(c, c.px, out, o0 + i * fDst); encodeColor(c, c.px, out, o0 + i * fDst + 8); }
                    }
                });
                sp += n * srcBlock; op += n * dstBlock;
                mw = Math.max(1, mw >> 1); mh = Math.max(1, mh >> 1);
            }
        }
        return new Result(out, note);
    }

    // ------------------------------------------------------------------ downscaling
    private static void decodeBC1(byte[] d, int off, int[] px, boolean force4) {
        int c0 = le16(d, off), c1 = le16(d, off + 2);
        int[][] pal = new int[4][4];
        x565(c0, pal[0]); x565(c1, pal[1]);
        pal[0][3] = 255; pal[1][3] = 255;
        if (c0 > c1 || force4) {
            for (int k = 0; k < 3; k++) { pal[2][k] = (2 * pal[0][k] + pal[1][k]) / 3; pal[3][k] = (pal[0][k] + 2 * pal[1][k]) / 3; }
            pal[2][3] = 255; pal[3][3] = 255;
        } else {
            for (int k = 0; k < 3; k++) { pal[2][k] = (pal[0][k] + pal[1][k]) / 2; pal[3][k] = 0; }
            pal[2][3] = 255; pal[3][3] = 0;
        }
        int bits = le32(d, off + 4);
        for (int i = 0; i < 16; i++) {
            int[] c = pal[(bits >>> (2 * i)) & 3];
            px[i * 4] = c[0]; px[i * 4 + 1] = c[1]; px[i * 4 + 2] = c[2]; px[i * 4 + 3] = c[3];
        }
    }

    private static void decodeDxt(int kind, byte[] d, int off, Ctx c) {
        int[] px = c.px, t0 = c.t0;
        if (kind == 1) { decodeBC1(d, off, px, false); return; }
        decodeBC1(d, off + 8, px, true);
        if (kind == 5) { decodeBC4(d, off, t0, c.pal8); for (int i = 0; i < 16; i++) px[i * 4 + 3] = t0[i]; }
        else for (int i = 0; i < 16; i++) { int v = d[off + i / 2] & 255; px[i * 4 + 3] = ((i & 1) == 0 ? v & 15 : v >> 4) * 17; }
    }

    /** kind: 1 = DXT1, 3 = DXT3, 5 = DXT5, 32 = uncompressed 32-bit. */
    private static long surf(int w, int h, int kind) { return kind == 32 ? (long) w * h * 4 : (long) blocks(w, h) * (kind == 1 ? 8 : 16); }

    private static byte[] decodeSurface(byte[] d, int off, int w, int h, int kind) {
        byte[] rgba = new byte[w * h * 4];
        if (kind == 32) { System.arraycopy(d, off, rgba, 0, w * h * 4); return rgba; }
        final int bw = Math.max(1, (w + 3) / 4), bh = Math.max(1, (h + 3) / 4), bs = kind == 1 ? 8 : 16;
        parallelFor(bw * bh, 1024, (a, b, cx) -> {
            int[] px = cx.px;
            for (int i = a; i < b; i++) {
                int bx = i % bw, by = i / bw;
                decodeDxt(kind, d, off + i * bs, cx);
                for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                    int X = bx * 4 + x, Y = by * 4 + y;
                    if (X < w && Y < h) for (int c = 0; c < 4; c++) rgba[(Y * w + X) * 4 + c] = (byte) px[(y * 4 + x) * 4 + c];
                }
            }
        });
        return rgba;
    }

    static void encodeSurface(byte[] rgba, int w, int h, int outKind, byte[] out, int op) {
        if (outKind == 32) { System.arraycopy(rgba, 0, out, op, w * h * 4); return; }
        final int bw = Math.max(1, (w + 3) / 4), bh = Math.max(1, (h + 3) / 4), bs = outKind == 1 ? 8 : 16, o0 = op;
        parallelFor(bw * bh, 1024, (a, b, cx) -> {
            int[] px = cx.px;
            for (int i = a; i < b; i++) {
                int bx = i % bw, by = i / bw;
                for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                    int X = Math.min(bx * 4 + x, w - 1), Y = Math.min(by * 4 + y, h - 1);
                    for (int c = 0; c < 4; c++) px[(y * 4 + x) * 4 + c] = rgba[(Y * w + X) * 4 + c] & 255;
                }
                if (outKind == 1) encodeColor(cx, px, out, o0 + i * bs);
                else { encodeAlpha(cx, px, out, o0 + i * bs); encodeColor(cx, px, out, o0 + i * bs + 8); }
            }
        });
    }

    private static byte[] halve(byte[] s, int w, int h) {
        int nw = Math.max(1, w >> 1), nh = Math.max(1, h >> 1);
        final byte[] o = new byte[nw * nh * 4];
        parallelFor(nh, 64, (ya, yb, cx) -> {
            for (int y = ya; y < yb; y++) for (int x = 0; x < nw; x++) {
                int x0 = Math.min(2 * x, w - 1), x1 = Math.min(2 * x + 1, w - 1), y0 = Math.min(2 * y, h - 1), y1 = Math.min(2 * y + 1, h - 1);
                for (int c = 0; c < 4; c++) {
                    int sum = (s[(y0 * w + x0) * 4 + c] & 255) + (s[(y0 * w + x1) * 4 + c] & 255) + (s[(y1 * w + x0) * 4 + c] & 255) + (s[(y1 * w + x1) * 4 + c] & 255);
                    o[(y * nw + x) * 4 + c] = (byte) ((sum + 2) >> 2);
                }
            }
        });
        return o;
    }

    private static void fixHeader(byte[] out, int w, int h, int mips, int kind, boolean cube, boolean setFourcc) {
        put32(out, 8, 0x1 | 0x2 | 0x4 | 0x1000 | (mips > 1 ? 0x20000 : 0) | (kind == 32 ? 0x8 : 0x80000));
        put32(out, 12, h); put32(out, 16, w);
        put32(out, 20, kind == 32 ? w * 4 : (int) surf(w, h, kind));
        put32(out, 28, mips);
        put32(out, 108, 0x1000 | ((mips > 1 || cube) ? 0x8 : 0) | (mips > 1 ? 0x400 : 0));
        if (kind != 32 && setFourcc) System.arraycopy((kind == 1 ? "DXT1" : kind == 3 ? "DXT3" : "DXT5").getBytes(StandardCharsets.ISO_8859_1), 0, out, 84, 4);
    }

    /**
     * Shrinks a legacy-header texture (DXT1/3/5 or 32-bit uncompressed) so its larger side is at most maxDim.
     * Uses the file's own mip levels when it has enough of them; otherwise decodes, box-filters and re-encodes.
     * Returns null when the texture already fits. Throws UnsupportedOperationException for formats it can't shrink.
     */
    /** Like downscale, but only ever drops mip levels (never decodes). Works on DX10-header files too. Returns null when that isn't possible or needed. */
    public static Result dropMips(byte[] d, int maxDim) { return downscale(d, maxDim, false); }

    public static Result downscale(byte[] d, int maxDim) { return downscale(d, maxDim, true); }

    private static Result downscale(byte[] d, int maxDim, boolean allowResample) {
        if (maxDim <= 0 || d.length < 128 || d[0] != 'D' || d[1] != 'D' || d[2] != 'S' || d[3] != ' ') return null;
        int w = le32(d, 16), h = le32(d, 12);
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) throw no("invalid size");
        if (Math.max(w, h) <= maxDim) return null;
        int kind, dataOff = 128;
        boolean dx10 = false;
        if ((le32(d, 80) & 4) != 0) {
            String cc = new String(d, 84, 4, StandardCharsets.ISO_8859_1);
            if (cc.equals("DXT1")) kind = 1; else if (cc.equals("DXT3")) kind = 3; else if (cc.equals("DXT5")) kind = 5;
            else if (cc.equals("DX10")) {
                if (d.length < 148 || le32(d, 132) != 3 || le32(d, 140) != 1) throw no("this DX10 texture layout can't be downscaled");
                int id = le32(d, 128);
                if ((id >= 70 && id <= 72) || (id >= 79 && id <= 81)) kind = 1;                                  // 8-byte blocks
                else if ((id >= 73 && id <= 78) || (id >= 82 && id <= 84) || (id >= 94 && id <= 99)) kind = 3;  // 16-byte blocks
                else throw no("DXGI format " + id + " can't be downscaled");
                dx10 = true; dataOff = 148;
            } else throw no("format '" + cc + "' can't be downscaled (convert it first)");
        } else if ((le32(d, 80) & 0x40) != 0 && le32(d, 88) == 32) kind = 32;
        else throw no("this uncompressed format can't be downscaled");
        int k = 0;
        while (Math.max(w >> k, h >> k) > maxDim) k++;
        int nw = Math.max(1, w >> k), nh = Math.max(1, h >> k);
        int mips = (le32(d, 8) & 0x20000) != 0 ? Math.max(1, Math.min(32, le32(d, 28))) : 1;
        boolean cube = dx10 ? (le32(d, 136) & 4) != 0 : (le32(d, 112) & 0x200) != 0;
        int faces = cube ? 6 : 1;
        long faceBytes = 0, skip = 0;
        for (int m = 0; m < mips; m++) { long s = surf(Math.max(1, w >> m), Math.max(1, h >> m), kind); faceBytes += s; if (m < k) skip += s; }
        if (dataOff + faceBytes * faces > d.length) throw no("file is truncated");
        String from = w + "x" + h + " -> " + nw + "x" + nh;

        if (mips > k) {                                    // drop the top levels: no resampling needed
            long nf = faceBytes - skip;
            byte[] out = new byte[(int) (dataOff + nf * faces)];
            System.arraycopy(d, 0, out, 0, dataOff);
            for (int f = 0; f < faces; f++) System.arraycopy(d, (int) (dataOff + f * faceBytes + skip), out, (int) (dataOff + f * nf), (int) nf);
            fixHeader(out, nw, nh, mips - k, kind, cube, !dx10);
            return new Result(out, from + ", used the file's own mip levels (dropped " + k + ")");
        }

        if (!allowResample) return null;
        if (dx10) throw no("this DX10 texture has too few mip levels; convert it first, then it can be resampled");
        byte[][] base = new byte[faces][];                 // not enough mips: resample from the top level
        for (int f = 0; f < faces; f++) {
            byte[] cur = decodeSurface(d, (int) (128 + f * faceBytes), w, h, kind);
            int cw = w, ch = h;
            for (int i = 0; i < k; i++) { cur = halve(cur, cw, ch); cw = Math.max(1, cw >> 1); ch = Math.max(1, ch >> 1); }
            base[f] = cur;
        }
        int outKind = kind == 32 ? 32 : 5;
        if (kind == 1) {
            boolean opaque = true;
            for (byte[] b : base) for (int i = 3; i < b.length; i += 4) if (b[i] != (byte) 255) { opaque = false; break; }
            if (opaque) outKind = 1;
        }
        int outMips = mips > 1 ? 1 + (31 - Integer.numberOfLeadingZeros(Math.max(nw, nh))) : 1;
        long nf = 0;
        for (int m = 0; m < outMips; m++) nf += surf(Math.max(1, nw >> m), Math.max(1, nh >> m), outKind);
        byte[] out = new byte[(int) (128 + nf * faces)];
        System.arraycopy(d, 0, out, 0, 128);
        int op = 128;
        for (int f = 0; f < faces; f++) {
            byte[] cur = base[f];
            int lw = nw, lh = nh;
            for (int m = 0; m < outMips; m++) {
                encodeSurface(cur, lw, lh, outKind, out, op);
                op += (int) surf(lw, lh, outKind);
                if (m + 1 < outMips) { cur = halve(cur, lw, lh); lw = Math.max(1, lw >> 1); lh = Math.max(1, lh >> 1); }
            }
        }
        fixHeader(out, nw, nh, outMips, outKind, cube, true);
        return new Result(out, from + ", resampled (the file had too few mip levels)");
    }
}
