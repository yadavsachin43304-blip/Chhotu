package com.priya.ai;

/**
 * Geometry + per-picture data for {@link Maya3DHeadView} (pure Java, no Android, so the offline preview tool builds
 * exactly the same mesh and uniforms).
 *
 * The head is a real 3D height-field mesh: every vertex of a fine grid over the picture is pushed out to the depth
 * sculpted for that picture (assets/av_N_geo.webp, made from a parametric face template fitted to hand-measured
 * landmarks: skull, brow ridge, eye sockets + eyeballs, nose bridge/tip/wings, cheekbones, lips, chin, jaw, neck,
 * hair / ears / headphone layers), un-projected through a perspective camera so the rest pose matches the picture
 * pixel for pixel, then turned/tilted in 3D (depth test = real occlusion, normals = light changes as she turns).
 * The mouth is cut along the lip line (upper and lower lip vertices are separate), the jaw rotates about a hinge, and
 * a mouth-cavity mesh (dark interior, upper + lower teeth, tongue) sits behind the lips.
 */
public final class Maya3DModel {
    private Maya3DModel() {}

    public static final float ZMAX = 0.40f;   // depth range of the 16-bit geo map (head units: picture height = 1)
    public static final float CAM_D = 3.2f;   // camera distance (head units)
    public static final int NX = 132;         // grid columns over the picture

    // ------------------------------------------------------------------ landmarks (picture units: px / H, y down)
    // index: 0 asp, 1 texel | eyeL 2 cx 3 cy 4 rx 5 tilt 6 up 7 dn 8 irisR 9 lash 10 ix 11 iy 12 band | eyeR 13..23 |
    // mouth 24 clx 25 cly 26 crx 27 cry 28 seam 29 top 30 bot 31 chin | jaw 32 hingeY 33 hingeZ 34 maxAngle 35 jawHW |
    // browL 36..39 | browR 40..43 | 44 pivotY 45 pivotZ | fx 46 cx 47 cy 48 rx 49 ry | face circle 50 cx 51 cy 52 r |
    // 53 teeth length 54 lower teeth 55 cavity depth 56 brow lift 57 breath
    // BEGIN GENERATED LM (preview tools gen.py from the hand landmarks in lm.py)
    static final float[][] LM = {
            {0.91954f, 0.00115f, 0.34598f, 0.38046f, 0.04598f, 0.00000f, 0.01609f, 0.01149f, 0.01149f, 0.00460f, 0.34598f, 0.37931f, 0.02299f, 0.54483f, 0.38046f, 0.04598f, 0.00000f, 0.01609f, 0.01149f, 0.01149f, 0.00460f, 0.54368f, 0.37816f, 0.02299f, 0.37011f, 0.58506f, 0.52529f, 0.58506f, 0.58506f, 0.56207f, 0.60920f, 0.70690f, 0.37931f, -0.04310f, 0.19526f, 0.16092f, 0.34253f, 0.31264f, 0.05977f, 0.01379f, 0.55402f, 0.31034f, 0.05977f, 0.01379f, 0.54310f, 0.02845f, 0.44828f, 0.37931f, 0.21839f, 0.31782f, 0.44828f, 0.48276f, 0.27011f, 0.20000f, 0.80000f, 0.04267f, 0.01200f, 0.00250f},
            {1.00000f, 0.00139f, 0.42500f, 0.36944f, 0.03333f, 0.06000f, 0.01667f, 0.01111f, 0.01389f, 0.00556f, 0.42639f, 0.36667f, 0.00972f, 0.57639f, 0.36806f, 0.03889f, -0.15000f, 0.01806f, 0.00972f, 0.01389f, 0.00556f, 0.57639f, 0.36667f, 0.00972f, 0.44167f, 0.50833f, 0.55833f, 0.50417f, 0.51111f, 0.49167f, 0.53889f, 0.59444f, 0.38194f, -0.03833f, 0.19986f, 0.11389f, 0.43472f, 0.32500f, 0.04167f, 0.00833f, 0.58056f, 0.31944f, 0.04722f, 0.00972f, 0.48194f, 0.02083f, 0.50000f, 0.35417f, 0.20833f, 0.25903f, 0.50000f, 0.46528f, 0.24306f, 0.22000f, 0.80000f, 0.03208f, 0.01200f, 0.00250f},
            {0.78109f, 0.00103f, 0.27338f, 0.46454f, 0.05961f, 0.30000f, 0.02980f, 0.03083f, 0.03083f, 0.01233f, 0.28571f, 0.46454f, 0.01028f, 0.50874f, 0.46557f, 0.06475f, -0.20000f, 0.03186f, 0.03289f, 0.03083f, 0.01233f, 0.48818f, 0.46454f, 0.01028f, 0.32169f, 0.59712f, 0.45940f, 0.59507f, 0.60534f, 0.59609f, 0.62693f, 0.70915f, 0.48304f, -0.06296f, 0.17691f, 0.16444f, 0.29291f, 0.39054f, 0.05653f, 0.01028f, 0.50051f, 0.38232f, 0.05653f, 0.01028f, 0.58582f, 0.02852f, 0.39054f, 0.39054f, 0.23638f, 0.31860f, 0.39363f, 0.49332f, 0.24152f, 0.18000f, 0.80000f, 0.03787f, 0.01000f, 0.00250f},
    };
    // END GENERATED LM

    public static int clampStyle(int s) { return Math.max(0, Math.min(2, s)); }

    // ------------------------------------------------------------------ mesh

    public static final class Mesh {
        /** 8 floats per vertex: u, v, z, headWeight, side(-1 upper lip / +1 lower lip / 0), nx, ny, nz. */
        public float[] verts;
        public short[] idx;
        public float zMouth, zCorner;
        public float[] cav;        // 2 floats per vertex: s, t
        public short[] cavIdx;
    }

    private static float sampleBil(float[] m, int w, int h, float u, float v) {
        float x = u * w - 0.5f, y = v * h - 0.5f;
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float fx = x - x0, fy = y - y0;
        int xa = Math.max(0, Math.min(w - 1, x0)), xb = Math.max(0, Math.min(w - 1, x0 + 1));
        int ya = Math.max(0, Math.min(h - 1, y0)), yb = Math.max(0, Math.min(h - 1, y0 + 1));
        float a = m[ya * w + xa], b = m[ya * w + xb], c = m[yb * w + xa], d = m[yb * w + xb];
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    /** Lip line y (picture units) at x: quadratic through both corners and the centre seam point. */
    public static float seamY(float[] lm, float x) {
        float t = (x - lm[24]) / (lm[26] - lm[24]);
        t = Math.max(0f, Math.min(1f, t));
        return lm[25] + (lm[27] - lm[25]) * t + 4f * (lm[28] - 0.5f * (lm[25] + lm[27])) * t * (1f - t);
    }

    /**
     * @param geo ARGB pixels of av_N_geo.webp (R = head weight, G/B = 16-bit depth)
     */
    public static Mesh build(int style, int[] geo, int gw, int gh) {
        float[] lm = LM[clampStyle(style)];
        float asp = lm[0];
        int nx = NX, ny = Math.round(NX / asp);
        float[] zm = new float[gw * gh], wm = new float[gw * gh];
        for (int i = 0; i < gw * gh; i++) {
            int c = geo[i];
            wm[i] = ((c >> 16) & 255) / 255f;
            zm[i] = (((c >> 8) & 255) * 256 + (c & 255)) / 65535f * ZMAX;
        }
        int cols = nx + 1, rows = ny + 1;
        float[] U = new float[cols * rows], V = new float[cols * rows];
        for (int j = 0; j < rows; j++)
            for (int i = 0; i < cols; i++) { U[j * cols + i] = i / (float) nx; V[j * cols + i] = j / (float) ny; }

        // snap one row per column onto the lip line (between the corners) so the mouth can split there
        int[] seamRow = new int[cols];
        java.util.Arrays.fill(seamRow, -1);
        // the lips part only between the inner corners (~88% of the corner-to-corner half width)
        float mc = 0.5f * (lm[24] + lm[26]) / asp, mh = 0.5f * (lm[26] - lm[24]) / asp;
        float cl = mc - 0.88f * mh, cr = mc + 0.88f * mh, cell = 1f / nx;
        for (int i = 0; i < cols; i++) {
            float u = i / (float) nx;
            if (u <= cl + 0.35f * cell || u >= cr - 0.35f * cell) continue;
            float sv = seamY(lm, u * asp);
            int j = Math.round(sv * ny);
            if (j < 1 || j >= ny) continue;
            seamRow[i] = j;
            V[j * cols + i] = sv;
        }
        int nSeam = 0;
        int[] dup = new int[cols];
        for (int i = 0; i < cols; i++) dup[i] = seamRow[i] >= 0 ? cols * rows + nSeam++ : -1;
        int nv = cols * rows + nSeam;
        float[] vt = new float[nv * 8];
        // depth + weights
        float[] Z = new float[cols * rows];
        for (int k = 0; k < cols * rows; k++) Z[k] = sampleBil(zm, gw, gh, U[k], V[k]);
        for (int j = 0; j < rows; j++)
            for (int i = 0; i < cols; i++) {
                int k = j * cols + i;
                // normal from the depth gradient (head units: x = u*asp, y up = -v)
                int il = Math.max(0, i - 1), ir = Math.min(nx, i + 1), jt = Math.max(0, j - 1), jb = Math.min(ny, j + 1);
                float dzdx = (Z[j * cols + ir] - Z[j * cols + il]) / ((U[j * cols + ir] - U[j * cols + il]) * asp + 1e-6f);
                float dzdv = (Z[jb * cols + i] - Z[jt * cols + i]) / (V[jb * cols + i] - V[jt * cols + i] + 1e-6f);
                float nxv = -dzdx, nyv = dzdv, nzv = 1f;   // dz/dy(up) = -dz/dv
                float nl = (float) Math.sqrt(nxv * nxv + nyv * nyv + nzv * nzv);
                int o = k * 8;
                vt[o] = U[k]; vt[o + 1] = V[k]; vt[o + 2] = Z[k];
                vt[o + 3] = sampleBil(wm, gw, gh, U[k], V[k]);
                vt[o + 4] = seamRow[i] == j ? -1f : 0f;
                vt[o + 5] = nxv / nl; vt[o + 6] = nyv / nl; vt[o + 7] = nzv / nl;
                if (seamRow[i] == j) {
                    int d = dup[i] * 8;
                    System.arraycopy(vt, o, vt, d, 8);
                    vt[d + 4] = 1f;
                }
            }
        // triangles; cells the lip line crosses are split along it, lower triangles use the lower-lip copies
        short[] idx = new short[nx * ny * 6];
        int n = 0;
        for (int j = 0; j < ny; j++)
            for (int i = 0; i < nx; i++) {
                int a = j * cols + i, b = a + 1, c = a + cols, d = c + 1;
                boolean diagAD = seamRow[i] >= 0 && seamRow[i + 1] == seamRow[i] + 1 && seamRow[i] == j;
                int[][] tris = diagAD ? new int[][]{{a, c, d}, {a, d, b}} : new int[][]{{a, c, b}, {b, c, d}};
                for (int[] tr : tris) {
                    boolean lower = false;
                    for (int q : tr) {
                        int qi = q % cols, qj = q / cols;
                        if (seamRow[qi] == qj) continue;
                        float sv = seamY(lm, U[q] * asp);
                        if (V[q] > sv) lower = true;
                    }
                    for (int q : tr) {
                        int qi = q % cols, qj = q / cols;
                        int vtx = (lower && seamRow[qi] == qj) ? dup[qi] : q;
                        idx[n++] = (short) vtx;
                    }
                }
            }
        Mesh m = new Mesh();
        m.verts = vt;
        m.idx = idx;
        float mcx = 0.5f * (lm[24] + lm[26]);
        m.zMouth = sampleBil(zm, gw, gh, mcx / asp, lm[28]);
        m.zCorner = 0.5f * (sampleBil(zm, gw, gh, lm[24] / asp, lm[25]) + sampleBil(zm, gw, gh, lm[26] / asp, lm[27]));
        // cavity: s across the mouth (-1.1..1.1), t from above the upper lip (-0.35) to below the lower lip (1.35)
        int cs = 28, ct = 14;
        m.cav = new float[(cs + 1) * (ct + 1) * 2];
        int k = 0;
        for (int j = 0; j <= ct; j++)
            for (int i = 0; i <= cs; i++) {
                m.cav[k++] = -1.1f + 2.2f * i / cs;
                m.cav[k++] = -0.35f + 1.7f * j / ct;
            }
        m.cavIdx = new short[cs * ct * 6];
        k = 0;
        for (int j = 0; j < ct; j++)
            for (int i = 0; i < cs; i++) {
                int a = j * (cs + 1) + i, b = a + 1, c = a + cs + 1, d = c + 1;
                m.cavIdx[k++] = (short) a; m.cavIdx[k++] = (short) c; m.cavIdx[k++] = (short) b;
                m.cavIdx[k++] = (short) b; m.cavIdx[k++] = (short) c; m.cavIdx[k++] = (short) d;
            }
        return m;
    }

    // ------------------------------------------------------------------ uniforms

    /** Every uniform the programs use, filled each frame (the view uploads them; the preview tool dumps them). */
    public static final class Uniforms {
        public final float[] pic = new float[4], picF = new float[4], rect = new float[4], rot = new float[9],
                pivot = new float[4], mouth = new float[4], mouth2 = new float[4], mouthZ = new float[4], jaw = new float[4],
                browL = new float[4], browR = new float[4], anim = new float[4], anim2 = new float[4], anim3 = new float[4],
                eyeL = new float[4], eyeL2 = new float[4], eyeR = new float[4], eyeR2 = new float[4], iris = new float[4],
                lid = new float[4], eyeA = new float[4], teeth = new float[4], styleF = new float[4], fxP = new float[4],
                fxC = new float[4];
        public float alpha = 1f;
    }

    private static void set(float[] d, float a, float b, float c, float e) { d[0] = a; d[1] = b; d[2] = c; d[3] = e; }

    /**
     * @param rect picture rect in NDC (left, top, right, bottom)
     */
    public static void fill(Uniforms u, int style, Mesh m, Maya3DAnim a, float time, float[] rect) {
        int st = clampStyle(style);
        float[] lm = LM[st];
        set(u.pic, lm[0], lm[1], CAM_D, 0f);
        set(u.picF, lm[0], lm[1], 0f, 0f);
        System.arraycopy(rect, 0, u.rect, 0, 4);
        // R = Rz(roll) * Rx(pitch) * Ry(yaw), column-major
        float cy = (float) Math.cos(a.yaw), sy = (float) Math.sin(a.yaw);
        float cp = (float) Math.cos(a.pitch), sp = (float) Math.sin(a.pitch);
        float cr = (float) Math.cos(a.roll), sr = (float) Math.sin(a.roll);
        float[] ry = {cy, 0, -sy, 0, 1, 0, sy, 0, cy};          // columns
        float[] rx = {1, 0, 0, 0, cp, sp, 0, -sp, cp};          // y' = y cos - z sin (positive pitch = nod down)
        float[] rz = {cr, sr, 0, -sr, cr, 0, 0, 0, 1};
        float[] t = mul(rz, mul(rx, ry));
        System.arraycopy(t, 0, u.rot, 0, 9);
        set(u.pivot, lm[44], lm[45], lm[57], lm[56]);
        set(u.mouth, lm[24], lm[25], lm[26], lm[27]);
        set(u.mouth2, lm[28], lm[29], lm[30], lm[31]);
        set(u.mouthZ, m == null ? 0.2f : m.zMouth, m == null ? 0.18f : m.zCorner, lm[55], 0f);
        set(u.jaw, lm[32], lm[33], lm[34], lm[35]);
        set(u.browL, lm[36], lm[37], lm[38], lm[39]);
        set(u.browR, lm[40], lm[41], lm[42], lm[43]);
        set(u.anim, a.open, a.round, a.wide, a.press);
        set(u.anim2, a.blink, a.gazeX, a.gazeY, a.brow);
        set(u.anim3, a.breath, time, a.fx, st);
        set(u.eyeL, lm[2], lm[3], lm[4], lm[5]);
        set(u.eyeL2, lm[6], lm[7], lm[8], lm[9]);
        set(u.eyeR, lm[13], lm[14], lm[15], lm[16]);
        set(u.eyeR2, lm[17], lm[18], lm[19], lm[20]);
        set(u.iris, lm[10], lm[11], lm[21], lm[22]);
        set(u.lid, lm[12], lm[23], 0f, 0f);
        set(u.eyeA, a.blink, a.gazeX, a.gazeY, a.brow);
        set(u.teeth, lm[53], lm[54], 0f, 0f);
        set(u.styleF, a.round, time, a.fx, st);
        set(u.fxP, lm[0], time, a.fx, st);
        set(u.fxC, lm[46], lm[47], lm[48], lm[49]);
    }

    private static float[] mul(float[] A, float[] B) {   // column-major 3x3
        float[] C = new float[9];
        for (int c = 0; c < 3; c++)
            for (int r = 0; r < 3; r++) {
                float s = 0;
                for (int k = 0; k < 3; k++) s += A[k * 3 + r] * B[c * 3 + k];
                C[c * 3 + r] = s;
            }
        return C;
    }
}
