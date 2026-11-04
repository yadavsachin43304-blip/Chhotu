package com.priya.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural 3D female characters for Priya (no libraries, no asset files).
 *
 *   style 0 = Hologram : sleek hair + bun, rendered as a glowing dotted hologram with orbit rings
 *   style 1 = Cyber    : semi-realistic woman, long dark hair with teal streaks, cyber suit + face lines
 *   style 2 = Anime    : big-eyed anime girl, lavender bob with bangs, headphones, toon shading + outline
 *
 * Pure Java (no android.* imports) so the geometry can be built and previewed off-device.
 * Every vertex carries a small "rig" so the vertex shader can animate it:
 *   rig.x = jaw weight (0..1)      rig.y = part id (see P_*)
 *   rig.z = part extra (smile weight for lips, inner weight for brows, sway for hair, speed for rings)
 *   rig.w = side / u (eye side -1/+1, lip u -1..1)
 *   hw    = how much the vertex follows the head (1) vs the body (0)
 * Units: head ~2.2 tall, head centre at origin, face looks down +z, y up.
 */
public final class Avatar3D {

    public static final int P_SKIN = 0, P_LID = 1, P_EYE = 2, P_BROW = 3, P_LIP_UP = 4, P_LIP_LO = 5,
            P_MOUTH = 6, P_TEETH = 7, P_HAIR = 8, P_BODY = 9, P_ACC = 10, P_RING = 11;

    /** Floats per vertex: pos3 nor3 col4(rgb+emissive) rig4 hw1. */
    public static final int STRIDE = 15;

    /** One draw batch (indices fit in unsigned short). */
    public static final class Chunk {
        public float[] v;
        public short[] idx;
        public int vertexCount() { return v.length / STRIDE; }
    }

    /** A built character: geometry + the rig pivots the shader needs. */
    public static final class Model {
        public final List<Chunk> chunks = new ArrayList<>();
        public int style;
        public float mouthY, mouthZ, eyeX, eyeY, eyeZ, jawY, jawZ, eyeR;
        public int triangles;
    }

    // ------------------------------------------------------------------ style parameters

    static final class Style {
        int id;
        float headW, headD, jawTaper, faceLen, cranium;
        float nose, noseTip, socket, cheek;
        float ex, ey, er, irisA, pupilA;
        float my, mw, lipFull;
        float[] skin, lip, brow, iris, iris2, hair, hair2, cloth, accent, accent2;
        float blush;
        int hairType; // 0 bun, 1 long side-swept, 2 bob with bangs
    }

    static Style style(int s) {
        Style st = new Style();
        st.id = s;
        switch (s) {
            case 2: // anime
                st.headW = 0.80f; st.headD = 0.86f; st.jawTaper = 0.62f; st.faceLen = 1.02f; st.cranium = 0.10f;
                st.nose = 0.035f; st.noseTip = 0.03f; st.socket = 0.035f; st.cheek = 0.04f;
                st.ex = 0.31f; st.ey = -0.10f; st.er = 0.165f; st.irisA = 0.66f; st.pupilA = 0.30f;
                st.my = -0.60f; st.mw = 0.10f; st.lipFull = 0.65f;
                st.skin = c(1.00f, 0.87f, 0.81f); st.lip = c(0.96f, 0.52f, 0.58f); st.brow = c(0.46f, 0.32f, 0.66f);
                st.iris = c(0.42f, 0.30f, 0.82f); st.iris2 = c(0.72f, 0.70f, 1.00f);
                st.hair = c(0.62f, 0.52f, 0.92f); st.hair2 = c(0.86f, 0.78f, 1.00f);
                st.cloth = c(0.16f, 0.17f, 0.32f); st.accent = c(0.55f, 0.75f, 1.00f); st.accent2 = c(0.92f, 0.90f, 1.0f);
                st.blush = 0.35f; st.hairType = 2;
                break;
            case 1: // cyber
                st.headW = 0.76f; st.headD = 0.90f; st.jawTaper = 0.50f; st.faceLen = 1.10f; st.cranium = 0.02f;
                st.nose = 0.11f; st.noseTip = 0.05f; st.socket = 0.07f; st.cheek = 0.035f;
                st.ex = 0.29f; st.ey = 0.02f; st.er = 0.118f; st.irisA = 0.46f; st.pupilA = 0.19f;
                st.my = -0.53f; st.mw = 0.155f; st.lipFull = 1.0f;
                st.skin = c(0.93f, 0.78f, 0.69f); st.lip = c(0.74f, 0.38f, 0.40f); st.brow = c(0.13f, 0.12f, 0.14f);
                st.iris = c(0.36f, 0.48f, 0.56f); st.iris2 = c(0.62f, 0.74f, 0.80f);
                st.hair = c(0.09f, 0.09f, 0.11f); st.hair2 = c(0.25f, 0.85f, 0.85f);
                st.cloth = c(0.20f, 0.21f, 0.25f); st.accent = c(0.30f, 0.95f, 0.92f); st.accent2 = c(0.45f, 0.48f, 0.55f);
                st.blush = 0.08f; st.hairType = 1;
                break;
            default: // hologram
                st.headW = 0.77f; st.headD = 0.90f; st.jawTaper = 0.52f; st.faceLen = 1.08f; st.cranium = 0.03f;
                st.nose = 0.10f; st.noseTip = 0.045f; st.socket = 0.065f; st.cheek = 0.035f;
                st.ex = 0.29f; st.ey = 0.02f; st.er = 0.12f; st.irisA = 0.48f; st.pupilA = 0.20f;
                st.my = -0.53f; st.mw = 0.150f; st.lipFull = 1.0f;
                st.skin = c(0.36f, 0.85f, 1.00f); st.lip = c(0.95f, 0.78f, 0.35f); st.brow = c(0.55f, 0.92f, 1.00f);
                st.iris = c(0.96f, 0.80f, 0.30f); st.iris2 = c(1.0f, 0.95f, 0.70f);
                st.hair = c(0.30f, 0.75f, 1.00f); st.hair2 = c(0.96f, 0.78f, 0.30f);
                st.cloth = c(0.25f, 0.70f, 1.00f); st.accent = c(0.96f, 0.77f, 0.26f); st.accent2 = c(0.36f, 0.88f, 1.0f);
                st.blush = 0f; st.hairType = 0;
                break;
        }
        return st;
    }

    private static float[] c(float r, float g, float b) { return new float[]{r, g, b}; }

    // ------------------------------------------------------------------ mesh builder

    private static final class Builder {
        final Model model = new Model();
        float[] v = new float[STRIDE * 4096];
        int vn = 0;                 // vertices in current chunk
        short[] idx = new short[4096 * 6];
        int in = 0;

        int vert(float x, float y, float z, float nx, float ny, float nz, float[] col, float em,
                 float jaw, int part, float extra, float side, float hw) {
            if ((vn + 1) * STRIDE > v.length) v = java.util.Arrays.copyOf(v, v.length * 2);
            int o = vn * STRIDE;
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l < 1e-6f) { nx = 0; ny = 0; nz = 1; l = 1; }
            v[o] = x; v[o + 1] = y; v[o + 2] = z;
            v[o + 3] = nx / l; v[o + 4] = ny / l; v[o + 5] = nz / l;
            v[o + 6] = col[0]; v[o + 7] = col[1]; v[o + 8] = col[2]; v[o + 9] = em;
            v[o + 10] = jaw; v[o + 11] = part; v[o + 12] = extra; v[o + 13] = side; v[o + 14] = hw;
            return vn++;
        }

        void tri(int a, int b, int c) {
            if (in + 3 > idx.length) idx = java.util.Arrays.copyOf(idx, idx.length * 2);
            idx[in++] = (short) a; idx[in++] = (short) b; idx[in++] = (short) c;
            model.triangles++;
        }

        /** Start a new chunk if the next n vertices would not fit in 16-bit indices. */
        void reserve(int n) {
            if (vn + n > 65000) flush();
        }

        void flush() {
            if (vn == 0) return;
            Chunk ch = new Chunk();
            ch.v = java.util.Arrays.copyOf(v, vn * STRIDE);
            ch.idx = java.util.Arrays.copyOf(idx, in);
            model.chunks.add(ch);
            vn = 0; in = 0;
        }

        /**
         * Adds a grid mesh (rows x cols points, already computed) and recomputes smooth normals from the
         * triangles. keep[r][c] (quad) may be null = keep all.
         */
        void grid(float[][][] P, float[][][] C, float[] em, float[][] jaw, int part, float[][] extra, float[][] side,
                  float[][] hw, boolean[][] keep, boolean wrapCols, boolean flip) {
            int R = P.length, Cn = P[0].length;
            reserve(R * Cn);
            float[][][] N = new float[R][Cn][3];
            int qc = wrapCols ? Cn : Cn - 1;
            for (int r = 0; r < R - 1; r++) {
                for (int q = 0; q < qc; q++) {
                    if (keep != null && !keep[r][q]) continue;
                    int q2 = (q + 1) % Cn;
                    float[] a = P[r][q], b = P[r][q2], cc = P[r + 1][q2], d = P[r + 1][q];
                    float[] n1 = cross(sub(b, a), sub(d, a));
                    float[] n2 = cross(sub(d, cc), sub(b, cc));
                    if (flip) { neg(n1); neg(n2); }
                    add(N[r][q], n1); add(N[r][q2], n1); add(N[r + 1][q], n1);
                    add(N[r][q2], n2); add(N[r + 1][q2], n2); add(N[r + 1][q], n2);
                }
            }
            int[][] id = new int[R][Cn];
            for (int r = 0; r < R; r++) {
                for (int q = 0; q < Cn; q++) {
                    float[] p = P[r][q], n = N[r][q];
                    id[r][q] = vert(p[0], p[1], p[2], n[0], n[1], n[2], C[r][q], em == null ? 0f : em[r * Cn + q],
                            jaw == null ? 0f : jaw[r][q], part, extra == null ? 0f : extra[r][q],
                            side == null ? 0f : side[r][q], hw == null ? 1f : hw[r][q]);
                }
            }
            for (int r = 0; r < R - 1; r++) {
                for (int q = 0; q < qc; q++) {
                    if (keep != null && !keep[r][q]) continue;
                    int q2 = (q + 1) % Cn;
                    if (flip) {
                        tri(id[r][q], id[r + 1][q], id[r][q2]);
                        tri(id[r][q2], id[r + 1][q], id[r + 1][q2]);
                    } else {
                        tri(id[r][q], id[r][q2], id[r + 1][q]);
                        tri(id[r][q2], id[r + 1][q2], id[r + 1][q]);
                    }
                }
            }
        }
    }

    static float[] sub(float[] a, float[] b) { return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    static void add(float[] a, float[] b) { a[0] += b[0]; a[1] += b[1]; a[2] += b[2]; }
    static void neg(float[] a) { a[0] = -a[0]; a[1] = -a[1]; a[2] = -a[2]; }
    static float sm(float e0, float e1, float x) {
        float t = (x - e0) / (e1 - e0);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }
    static float g2(float a, float b) { return (float) Math.exp(-(a * a + b * b)); }
    static float[] mix(float[] a, float[] b, float t) {
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }
    static float[] scale(float[] a, float s) { return new float[]{a[0] * s, a[1] * s, a[2] * s}; }
    static float hash(float x) {
        double s = Math.sin(x * 12.9898 + 78.233) * 43758.5453;
        return (float) (s - Math.floor(s));
    }

    // ------------------------------------------------------------------ head shape

    /** Head surface point for polar angle th (0 = top) and azimuth ph (0 = front, +x = +pi/2). */
    static float[] headPoint(Style s, double th, double ph) {
        float x = (float) (Math.sin(th) * Math.sin(ph));
        float y = (float) Math.cos(th);
        float z = (float) (Math.sin(th) * Math.cos(ph));
        x *= s.headW; z *= s.headD;
        if (y > 0) { x *= 1 + s.cranium * y; z *= 1 + s.cranium * 0.6f * y; }
        if (z < 0) z *= 1.06f;
        if (y < 0.12f) {
            float t = (0.12f - y) / 1.12f;
            x *= 1 - s.jawTaper * (float) Math.pow(t, 1.5);
            if (z < 0) z *= 1 - 0.45f * t * t; else z *= 1 - 0.12f * t * t;
            y = 0.12f - (0.12f - y) * s.faceLen;
        }
        if (z > 0) {
            float fr = sm(0.15f, 0.65f, z);
            float ax = Math.abs(x);
            float dz = 0;
            // nose bridge + tip + wings
            float bridge = 0.35f + 0.65f * sm(0.05f, -0.30f, y);
            dz += s.nose * g2(x / 0.065f, (y + 0.16f) / 0.20f) * bridge;
            dz += s.noseTip * g2(x / 0.085f, (y + 0.33f) / 0.065f);
            dz += 0.025f * g2((ax - 0.07f) / 0.04f, (y + 0.36f) / 0.04f) * (s.nose / 0.1f);
            // eye sockets, brow ridge, cheeks
            dz -= s.socket * g2((ax - s.ex) / 0.13f, (y - s.ey) / 0.095f);
            dz += 0.028f * g2((ax - s.ex) / 0.17f, (y - s.ey - 0.15f) / 0.05f);
            dz += s.cheek * g2((ax - 0.36f) / 0.15f, (y + 0.20f) / 0.12f);
            // lips area, under-lip crease, chin
            dz += 0.045f * g2(x / 0.15f, (y - s.my) / 0.09f);
            dz -= 0.014f * g2(x / 0.10f, (y - s.my + 0.12f) / 0.03f);
            dz += 0.035f * g2(x / 0.12f, (y - s.my + 0.36f) / 0.10f);
            z += dz * fr;
        }
        return new float[]{x, y, z};
    }

    /** Height map of the front of the face so features (eyes, lips, brows, decals) can sit on the skin. */
    static final class FrontMap {
        final int N = 220;
        final float x0 = -1.0f, x1 = 1.0f, y0 = -1.4f, y1 = 1.2f;
        final float[] zmap = new float[N * N];

        FrontMap(Style s) {
            java.util.Arrays.fill(zmap, Float.NaN);
            for (int i = 0; i <= 420; i++) {
                double th = Math.PI * i / 420.0;
                for (int j = 0; j <= 420; j++) {
                    double ph = -Math.PI / 2 + Math.PI * j / 420.0;
                    float[] p = headPoint(s, th, ph);
                    int cx = (int) ((p[0] - x0) / (x1 - x0) * (N - 1) + 0.5f);
                    int cy = (int) ((p[1] - y0) / (y1 - y0) * (N - 1) + 0.5f);
                    if (cx < 0 || cy < 0 || cx >= N || cy >= N) continue;
                    int k = cy * N + cx;
                    if (Float.isNaN(zmap[k]) || p[2] > zmap[k]) zmap[k] = p[2];
                }
            }
        }

        float z(float x, float y) {
            float fx = (x - x0) / (x1 - x0) * (N - 1), fy = (y - y0) / (y1 - y0) * (N - 1);
            int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
            float tx = fx - ix, ty = fy - iy;
            float a = at(ix, iy), b = at(ix + 1, iy), cc = at(ix, iy + 1), d = at(ix + 1, iy + 1);
            return (a * (1 - tx) + b * tx) * (1 - ty) + (cc * (1 - tx) + d * tx) * ty;
        }

        private float at(int ix, int iy) {
            for (int r = 0; r < 6; r++) {
                for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
                    int x = ix + dx, y = iy + dy;
                    if (x < 0 || y < 0 || x >= N || y >= N) continue;
                    float v = zmap[y * N + x];
                    if (!Float.isNaN(v)) return v;
                }
            }
            return 0f;
        }

        /** Surface normal from the height map. */
        float[] normal(float x, float y) {
            float e = 0.01f;
            float dzdx = (z(x + e, y) - z(x - e, y)) / (2 * e);
            float dzdy = (z(x, y + e) - z(x, y - e)) / (2 * e);
            return new float[]{-dzdx, -dzdy, 1f};
        }
    }

    // ------------------------------------------------------------------ build

    public static Model build(int styleId) {
        Style s = style(Math.max(0, Math.min(2, styleId)));
        Builder b = new Builder();
        FrontMap fm = new FrontMap(s);
        Model m = b.model;
        m.style = s.id;

        float eyeZs = fm.z(s.ex, s.ey);
        m.eyeX = s.ex; m.eyeY = s.ey; m.eyeR = s.er;
        m.eyeZ = eyeZs + s.er * 0.38f - s.er; // eyeball front pokes ~0.38 r out of the socket surface
        m.mouthY = s.my; m.mouthZ = fm.z(0, s.my);
        m.jawY = -0.05f; m.jawZ = -0.30f;

        buildHead(b, s);
        buildEyes(b, s, m);
        buildBrows(b, s, fm);
        buildMouth(b, s, fm, m);
        buildHair(b, s);
        buildNeckBody(b, s);
        if (s.id == 2) buildHeadphones(b, s);
        if (s.id == 1) buildCyberLines(b, s, fm);
        if (s.id == 0) buildHoloExtras(b, s, m);
        b.flush();
        return m;
    }

    private static void buildHead(Builder b, Style s) {
        int R = 90, Cn = 112;
        float[][][] P = new float[R][Cn][3];
        float[][][] C = new float[R][Cn][3];
        float[][] jaw = new float[R][Cn];
        float[] em = new float[R * Cn];
        for (int r = 0; r < R; r++) {
            double th = 0.0001 + (Math.PI - 0.0002) * r / (R - 1);
            for (int q = 0; q < Cn; q++) {
                double ph = -Math.PI + 2 * Math.PI * q / Cn;
                float[] p = headPoint(s, th, ph);
                P[r][q] = p;
                float ax = Math.abs(p[0]);
                // jaw: chin & lower face below the mouth line, front only
                jaw[r][q] = sm(s.my + 0.005f, s.my - 0.07f, p[1]) * sm(0.15f, 0.45f, p[2]) * (1 - sm(0.40f, 0.62f, ax));
                float[] col = s.skin.clone();
                if (s.blush > 0) {
                    float bl = s.blush * g2((ax - 0.37f) / 0.11f, (p[1] - s.ey + 0.22f) / 0.07f) * sm(0.2f, 0.6f, p[2]);
                    col = mix(col, c(1.0f, 0.55f, 0.60f), bl);
                }
                // subtle darker under the jaw / back of neck so the head reads as 3D even in flat light
                col = scale(col, 0.92f + 0.08f * sm(-1.1f, -0.5f, p[1]));
                C[r][q] = col;
            }
        }
        b.grid(P, C, em, jaw, P_SKIN, null, null, null, null, true, false);
    }

    private static void buildEyes(Builder b, Style s, Model m) {
        for (int side = -1; side <= 1; side += 2) {
            float cx = side * s.ex, cy = s.ey, cz = m.eyeZ, er = s.er;
            // eyeball
            int R = 22, Cn = 28;
            float[][][] P = new float[R][Cn][3];
            float[][][] C = new float[R][Cn][3];
            float[] em = new float[R * Cn];
            float[][] sd = new float[R][Cn];
            for (int r = 0; r < R; r++) {
                double a = Math.PI * r / (R - 1); // angle from forward axis
                for (int q = 0; q < Cn; q++) {
                    double ph = 2 * Math.PI * q / Cn;
                    float dx = (float) (Math.sin(a) * Math.cos(ph)), dy = (float) (Math.sin(a) * Math.sin(ph));
                    float dz = (float) Math.cos(a);
                    P[r][q] = new float[]{cx + dx * er, cy + dy * er, cz + dz * er};
                    float[] col;
                    float aa = (float) a;
                    if (aa < s.pupilA) col = c(0.04f, 0.03f, 0.06f);
                    else if (aa < s.irisA) {
                        float t = (aa - s.pupilA) / (s.irisA - s.pupilA);
                        col = mix(s.iris, s.iris2, (s.id == 2 ? (0.5f - 0.5f * dy) : 0.6f * (1 - t)));
                        if (t > 0.82f) col = scale(col, 0.55f); // limbal ring
                    } else col = c(0.97f, 0.97f, 1.0f);
                    C[r][q] = col;
                    // anime catch-lights painted into the iris (emissive)
                    if (s.id == 2) {
                        float hx = dx - (-0.22f), hy = dy - 0.24f;
                        if (hx * hx + hy * hy < 0.013f) { C[r][q] = c(1, 1, 1); em[r * Cn + q] = 1f; }
                        float lx = dx - 0.20f, ly = dy + 0.20f;
                        if (lx * lx + ly * ly < 0.004f) { C[r][q] = c(1, 1, 1); em[r * Cn + q] = 0.8f; }
                    }
                    if (s.id == 0 && aa < s.irisA) em[r * Cn + q] = 0.9f;
                    sd[r][q] = side;
                }
            }
            b.grid(P, C, em, null, P_EYE, null, sd, null, null, true, true);

            // upper lid (+ eyeliner and lashes) — built open; the shader rotates it down to blink
            float eOpen = s.id == 2 ? 0.42f : 0.30f;
            int LR = 12, LC = 30;
            P = new float[LR + 3][LC][3];
            C = new float[LR + 3][LC][3];
            em = new float[(LR + 3) * LC];
            sd = new float[LR + 3][LC];
            float lr = er * 1.07f;
            for (int q = 0; q < LC; q++) {
                float az = -1.85f + 3.7f * q / (LC - 1);
                float outer = sm(-0.2f, 1.5f, az * side); // 0 inner corner .. 1 outer corner
                for (int r = 0; r < LR; r++) {
                    float e = (float) (Math.PI / 2 + 0.25) - ((float) (Math.PI / 2 + 0.25) - eOpen) * r / (LR - 1);
                    float rr = lr;
                    P[r][q] = new float[]{cx + (float) (Math.sin(az) * Math.cos(e)) * rr,
                            cy + (float) Math.sin(e) * rr, cz + (float) (Math.cos(az) * Math.cos(e)) * rr};
                    float[] col = scale(s.skin, 0.93f);
                    if (r >= LR - 2) col = s.id == 0 ? s.accent2 : c(0.08f, 0.06f, 0.08f); // eyeliner
                    C[r][q] = col;
                    if (s.id == 0 && r >= LR - 2) em[r * LC + q] = 0.6f;
                    sd[r][q] = side;
                }
                // lashes: a dark fin sweeping out and up from the lid edge, longer at the outer corner
                for (int k = 1; k <= 3; k++) {
                    float t = k / 3f;
                    float len = (s.id == 2 ? 0.55f : 0.32f) * er * (0.35f + 0.9f * outer);
                    float e = eOpen + 0.05f + t * (0.25f + 0.25f * outer);
                    float rr = lr + len * t;
                    float azx = az + side * 0.18f * t * outer;
                    P[LR - 1 + k][q] = new float[]{cx + (float) (Math.sin(azx) * Math.cos(e)) * rr,
                            cy + (float) Math.sin(e) * rr - 0.01f * t, cz + (float) (Math.cos(azx) * Math.cos(e)) * rr};
                    C[LR - 1 + k][q] = s.id == 0 ? s.accent2 : c(0.05f, 0.04f, 0.06f);
                    if (s.id == 0) em[(LR - 1 + k) * LC + q] = 0.6f;
                    sd[LR - 1 + k][q] = side;
                }
            }
            boolean[][] keep = new boolean[LR + 2][LC - 1];
            for (int r = 0; r < LR + 2; r++) for (int q = 0; q < LC - 1; q++) {
                float az = -1.85f + 3.7f * (q + 0.5f) / (LC - 1);
                keep[r][q] = r < LR - 1 || Math.abs(az) < 1.25f; // lashes only across the visible front
            }
            b.grid(P, C, em, null, P_LID, null, sd, null, keep, false, false);

            // lower lid (static)
            float eLow = s.id == 2 ? -0.48f : -0.36f;
            int WR = 7;
            P = new float[WR][LC][3];
            C = new float[WR][LC][3];
            for (int q = 0; q < LC; q++) {
                float az = -1.85f + 3.7f * q / (LC - 1);
                for (int r = 0; r < WR; r++) {
                    float e = eLow - (eLow + (float) (Math.PI / 2 + 0.2)) * (WR - 1 - r) / (WR - 1);
                    float rr = er * 1.05f;
                    P[r][q] = new float[]{cx + (float) (Math.sin(az) * Math.cos(e)) * rr,
                            cy + (float) Math.sin(e) * rr, cz + (float) (Math.cos(az) * Math.cos(e)) * rr};
                    C[r][q] = r == WR - 1 ? (s.id == 0 ? s.accent2 : mix(s.skin, c(0.55f, 0.35f, 0.35f), 0.35f))
                            : scale(s.skin, 0.96f);
                }
            }
            b.grid(P, C, null, null, P_SKIN, null, null, null, null, false, false);
        }
    }

    private static void buildBrows(Builder b, Style s, FrontMap fm) {
        for (int side = -1; side <= 1; side += 2) {
            int U = 16, V = 3;
            float[][][] P = new float[V][U][3];
            float[][][] C = new float[V][U][3];
            float[][] inner = new float[V][U];
            float[] em = new float[V * U];
            float w = s.id == 2 ? 0.26f : 0.27f;
            for (int u = 0; u < U; u++) {
                float t = u / (U - 1f);
                float x = side * (s.ex - 0.13f + w * t);
                float yc = s.ey + (s.id == 2 ? 0.27f : 0.165f) + 0.04f * (float) Math.sin(Math.PI * Math.pow(t, 0.75)) - 0.025f * t;
                float th = (s.id == 2 ? 0.020f : 0.026f) * (1 - t) + 0.008f;
                for (int k = 0; k < V; k++) {
                    float y = yc + th * (k - 1);
                    float z = fm.z(x, y) + 0.010f + 0.006f * (1 - Math.abs(k - 1));
                    P[k][u] = new float[]{x, y, z};
                    C[k][u] = s.brow;
                    inner[k][u] = 1 - t;
                    if (s.id == 0) em[k * U + u] = 0.7f;
                }
            }
            b.grid(P, C, em, null, P_BROW, inner, null, null, null, false, side < 0);
        }
    }

    private static void buildMouth(Builder b, Style s, FrontMap fm, Model m) {
        // interior (dark): a flat lens behind the lips, the shader opens it with the jaw
        {
            int R = 6, Cn = 24;
            float[][][] P = new float[R][Cn][3];
            float[][][] C = new float[R][Cn][3];
            float z0 = fm.z(0, s.my) + 0.004f;
            for (int r = 0; r < R; r++) {
                float rad = r / (R - 1f);
                for (int q = 0; q < Cn; q++) {
                    double a = 2 * Math.PI * q / Cn;
                    float x = (float) Math.cos(a) * s.mw * 0.92f * rad, y = s.my + (float) Math.sin(a) * 0.025f * rad;
                    P[r][q] = new float[]{x, y, z0 - 0.01f * (1 - rad)};
                    C[r][q] = mix(c(0.10f, 0.02f, 0.04f), c(0.35f, 0.08f, 0.10f), rad);
                }
            }
            float[][] jaw = new float[R][Cn];
            b.grid(P, C, null, jaw, P_MOUTH, null, null, null, null, true, true);
        }
        // upper teeth
        {
            int U = 12;
            float[][][] P = new float[2][U][3];
            float[][][] C = new float[2][U][3];
            for (int u = 0; u < U; u++) {
                float t = -1 + 2 * u / (U - 1f);
                float x = t * s.mw * 0.62f;
                float z = fm.z(0, s.my) + 0.007f - 0.02f * t * t;
                P[0][u] = new float[]{x, s.my + 0.012f, z};
                P[1][u] = new float[]{x, s.my - 0.010f, z};
                C[0][u] = c(0.92f, 0.90f, 0.88f); C[1][u] = c(0.98f, 0.97f, 0.95f);
            }
            b.grid(P, C, null, null, P_TEETH, null, null, null, null, false, true);
        }
        // lips: two lofted tubes, half sunk into the face
        for (int lip = 0; lip < 2; lip++) {
            int U = 26, V = 14;
            float[][][] P = new float[U][V][3];
            float[][][] C = new float[U][V][3];
            float[][] jaw = new float[U][V], corner = new float[U][V], uu = new float[U][V];
            for (int i = 0; i < U; i++) {
                float u = -1 + 2 * i / (U - 1f);
                float x = u * s.mw;
                float env = (float) Math.pow(Math.max(0, 1 - u * u), 0.6);
                float yc, ry, rz;
                if (lip == 0) {
                    yc = s.my + 0.016f * env * s.lipFull + 0.003f - 0.006f * g2(u / 0.13f, 0) * s.lipFull;
                    ry = (0.014f * env + 0.002f) * s.lipFull;
                    rz = 0.020f * env + 0.003f;
                } else {
                    yc = s.my - 0.020f * env * s.lipFull - 0.002f;
                    ry = (0.018f * env + 0.002f) * s.lipFull;
                    rz = 0.024f * env + 0.003f;
                }
                float zc = fm.z(x, yc) - rz * 0.25f;
                for (int k = 0; k < V; k++) {
                    double a = 2 * Math.PI * k / V;
                    float y = yc + (float) Math.sin(a) * ry, z = zc + (float) Math.cos(a) * rz;
                    P[i][k] = new float[]{x, y, z};
                    float shade = 0.85f + 0.15f * (float) Math.cos(a);
                    float[] col = scale(s.lip, shade);
                    if (lip == 0 && Math.sin(a) < -0.5) col = scale(s.lip, 0.70f);
                    if (lip == 1 && Math.sin(a) > 0.5) col = scale(s.lip, 0.75f);
                    C[i][k] = col;
                    jaw[i][k] = lip == 1 ? 1f : 0f;
                    corner[i][k] = u * u;
                    uu[i][k] = u;
                }
            }
            float[] em = null;
            if (s.id == 0) { em = new float[U * V]; java.util.Arrays.fill(em, 0.5f); }
            b.grid(P, C, em, jaw, lip == 0 ? P_LIP_UP : P_LIP_LO, corner, uu, null, null, true, true);
        }
    }

    private static void buildHair(Builder b, Style s) {
        int Cn = 120, R = 46;
        float vEq = 0.55f;
        float hang;
        switch (s.hairType) {
            case 2: hang = 0.95f; break;  // bob to the chin
            case 1: hang = 2.05f; break;  // long, below the shoulders
            default: hang = 0.0f; break;  // bun: cap only
        }
        float[][][] P = new float[R][Cn][3];
        float[][][] C = new float[R][Cn][3];
        float[][] sway = new float[R][Cn];
        float[] em = new float[R * Cn];
        boolean[][] keep = new boolean[R - 1][Cn];
        float thEnd = (float) (Math.PI / 2 + 0.18);
        for (int q = 0; q < Cn; q++) {
            double ph = -Math.PI + 2 * Math.PI * q / Cn;
            float stripe = hash(q * 1.7f);
            float stripe2 = hash(q * 0.37f + 5f);
            for (int r = 0; r < R; r++) {
                float v = r / (R - 1f);
                float[] p;
                float swy = 0;
                if (v <= vEq || hang <= 0) {
                    double th = 0.02 + (thEnd - 0.02) * Math.min(1, v / vEq);
                    float[] hp = headPoint(s, th, ph);
                    float vol = 1.065f + 0.045f * (1 - v / vEq) + (s.hairType == 2 ? 0.02f : 0f);
                    if (s.hairType == 0) vol = 1.035f + 0.015f * (1 - v / vEq);
                    // side part for the long hair: a bit more volume on one side of the crown
                    if (s.hairType == 1) vol += 0.03f * sm(0.0f, 1.2f, (float) Math.sin(ph)) * (1 - v / vEq);
                    p = new float[]{hp[0] * vol, hp[1] * 1.035f + 0.015f, hp[2] * vol};
                } else {
                    float[] hp = headPoint(s, thEnd, ph);
                    float vol = 1.065f + (s.hairType == 2 ? 0.02f : 0f);
                    float t = (v - vEq) / (1 - vEq);
                    float flare = 1 + 0.10f * (float) Math.sin(Math.PI * Math.min(1, t * 1.2f));
                    if (s.hairType == 2) flare -= 0.16f * t * t * t; // bob ends curl inward
                    float px = hp[0] * vol * flare, pz = hp[2] * vol * flare;
                    if (s.hairType == 1) { // long hair falls behind the shoulders
                        float back = sm(0.25f, 0.9f, t);
                        pz = pz * (1 - back * 0.55f) - back * 0.18f;
                        px *= 1 + 0.10f * back;
                    }
                    p = new float[]{px, hp[1] * 1.035f + 0.015f - t * hang, pz};
                    swy = t;
                }
                P[r][q] = p;
                float[] col = mix(s.hair, scale(s.hair, 0.72f), stripe * 0.6f);
                if (s.hairType == 1 && stripe2 > 0.86f) { col = s.hair2; em[r * Cn + q] = 0.35f; }
                if (s.hairType == 2) col = mix(col, s.hair2, 0.25f * sm(0.1f, 0.0f, Math.abs(v - 0.30f) - 0.05f));
                if (s.hairType == 0) em[r * Cn + q] = 0.15f;
                C[r][q] = col;
                sway[r][q] = swy;
            }
        }
        // face opening + bangs (jagged edges for strand clumps)
        for (int q = 0; q < Cn; q++) {
            double ph = -Math.PI + 2 * Math.PI * (q + 0.5) / Cn;
            float aph = (float) Math.abs(ph);
            for (int r = 0; r < R - 1; r++) {
                float v = (r + 0.5f) / (R - 1f);
                float y = 0.5f * (P[r][q][1] + P[r + 1][q][1]);
                float zf = 0.5f * (P[r][q][2] + P[r + 1][q][2]);
                float jag = 0.025f * (float) Math.sin(q * 2.3f) + 0.02f * hash(q * 3.1f);
                boolean k = true;
                if (s.hairType == 2) {
                    float faceHalf = 0.98f;
                    if (aph < faceHalf) {
                        float bang = s.ey + 0.30f + jag * 1.6f + 0.03f * (float) Math.cos(ph * 9);
                        if (y < bang) k = false;
                    }
                    if (v > 0.985f) k = false;
                } else if (s.hairType == 1) {
                    float faceHalf = 0.92f;
                    if (aph < faceHalf) {
                        // side-swept fringe: low on the viewer's left, high on the right
                        float bang = s.ey + 0.40f - 0.20f * sm(0.6f, -0.9f, (float) ph) + jag;
                        if (y < bang) k = false;
                    }
                } else {
                    // slicked back: hairline on top of the forehead, no hair over the face or below the ears
                    float line = 0.55f + 0.08f * (float) Math.cos(ph * 2) - (aph > 1.3f ? 0.45f : 0f);
                    if (y < line && zf > -0.15f) k = false;
                    if (y < -0.25f) k = false;
                }
                keep[r][q] = k;
            }
        }
        b.grid(P, C, em, null, P_HAIR, sway, null, null, keep, true, false);

        if (s.hairType == 0) { // bun
            sphere(b, 0, 0.62f, -0.78f, 0.30f, 0.27f, 0.30f, s.hair, 0.2f, P_HAIR, 1f);
        }
    }

    private static void sphere(Builder b, float cx, float cy, float cz, float rx, float ry, float rz, float[] col,
                               float em, int part, float hw) {
        int R = 16, Cn = 24;
        float[][][] P = new float[R][Cn][3];
        float[][][] C = new float[R][Cn][3];
        float[] E = new float[R * Cn];
        float[][] H = new float[R][Cn];
        for (int r = 0; r < R; r++) {
            double th = 0.001 + (Math.PI - 0.002) * r / (R - 1);
            for (int q = 0; q < Cn; q++) {
                double ph = 2 * Math.PI * q / Cn;
                P[r][q] = new float[]{cx + rx * (float) (Math.sin(th) * Math.sin(ph)), cy + ry * (float) Math.cos(th),
                        cz + rz * (float) (Math.sin(th) * Math.cos(ph))};
                C[r][q] = col;
                E[r * Cn + q] = em;
                H[r][q] = hw;
            }
        }
        b.grid(P, C, E, null, part, null, null, H, null, true, false);
    }

    private static void buildNeckBody(Builder b, Style s) {
        // neck
        {
            int R = 12, Cn = 32;
            float[][][] P = new float[R][Cn][3];
            float[][][] C = new float[R][Cn][3];
            float[][] hw = new float[R][Cn];
            float[] em = new float[R * Cn];
            for (int r = 0; r < R; r++) {
                float t = r / (R - 1f);
                float y = -0.55f - t * 1.05f;
                float rad = 0.235f + 0.06f * t * t;
                for (int q = 0; q < Cn; q++) {
                    double a = 2 * Math.PI * q / Cn;
                    P[r][q] = new float[]{rad * (float) Math.sin(a), y, -0.10f + rad * 0.95f * (float) Math.cos(a)};
                    C[r][q] = scale(s.skin, 0.88f);
                    hw[r][q] = 1 - sm(-0.85f, -1.3f, y);
                    if (s.id == 0) em[r * Cn + q] = 0.1f;
                }
            }
            b.grid(P, C, em, null, P_SKIN, null, null, hw, null, true, false);
        }
        // shoulders / bust (lathe)
        {
            int R = 26, Cn = 64;
            float[][][] P = new float[R][Cn][3];
            float[][][] C = new float[R][Cn][3];
            float[][] hw = new float[R][Cn];
            float[] em = new float[R * Cn];
            for (int r = 0; r < R; r++) {
                float t = r / (R - 1f);
                float wv = sm(0f, 0.32f, t);
                float rx = 0.30f + 0.98f * (float) Math.pow(wv, 0.55);
                float rz = 0.27f + 0.22f * wv;
                float y0 = -1.32f - t * 1.35f;
                for (int q = 0; q < Cn; q++) {
                    double a = 2 * Math.PI * q / Cn;
                    float sx = (float) Math.sin(a), cz = (float) Math.cos(a);
                    float y = y0 - 0.20f * sx * sx * wv; // sloping shoulders
                    float x = rx * sx, z = -0.10f + rz * cz;
                    P[r][q] = new float[]{x, y, z};
                    float[] col = s.cloth;
                    float e = 0;
                    if (s.id == 1) { // cyber suit: neck ring + chest panel lines glow teal
                        if (r == 2 || r == 3) { col = s.accent; e = 0.9f; }
                        if (cz > 0.3f && Math.abs(Math.abs(x) - 0.55f) < 0.03f && t > 0.3f) { col = s.accent; e = 0.8f; }
                        if (cz > 0.2f && Math.abs(x) > 0.7f && r == 9) { col = s.accent; e = 0.8f; }
                    } else if (s.id == 2) {
                        if (r == 2) { col = s.accent; e = 0.8f; }
                        if (cz > 0.4f && Math.abs(x) < 0.02f + 0.25f * (t - 0.2f) && Math.abs(x) > 0.25f * (t - 0.2f) - 0.03f
                                && t > 0.2f && t < 0.55f) { col = s.accent; e = 0.7f; }
                    } else {
                        e = 0.15f;
                    }
                    C[r][q] = col;
                    em[r * Cn + q] = e;
                    hw[r][q] = 0f;
                }
            }
            b.grid(P, C, em, null, P_BODY, null, null, hw, null, true, false);
        }
    }

    private static void buildHeadphones(Builder b, Style s) {
        for (int side = -1; side <= 1; side += 2) {
            // ear cup: short cylinder along x with a glowing ring on the outer face
            int R = 8, Cn = 32;
            float[][][] P = new float[R][Cn][3];
            float[][][] C = new float[R][Cn][3];
            float[] em = new float[R * Cn];
            float x0 = side * 0.80f, x1 = side * 0.99f, cy = -0.06f, cz = -0.04f;
            for (int r = 0; r < R; r++) {
                float t = r / (R - 1f);
                float x, rad;
                if (t < 0.7f) { x = x0 + (x1 - x0) * (t / 0.7f); rad = 0.27f; }
                else { x = x1; rad = 0.27f * (1 - (t - 0.7f) / 0.3f); }
                for (int q = 0; q < Cn; q++) {
                    double a = 2 * Math.PI * q / Cn;
                    P[r][q] = new float[]{x, cy + rad * (float) Math.sin(a), cz + rad * (float) Math.cos(a)};
                    float[] col = t < 0.15f ? c(0.55f, 0.50f, 0.75f) : s.accent2;
                    float e = 0;
                    if (r == 5 || r == 6) { col = s.accent; e = 1f; }
                    C[r][q] = col;
                    em[r * Cn + q] = e;
                }
            }
            b.grid(P, C, em, null, P_ACC, null, null, null, null, true, side < 0);
        }
        // head band arc
        int U = 40, V = 12;
        float[][][] P = new float[U][V][3];
        float[][][] C = new float[U][V][3];
        float R0 = 1.12f;
        for (int i = 0; i < U; i++) {
            float t = i / (U - 1f);
            float ang = (float) (Math.toRadians(20) + Math.toRadians(140) * t);
            float px = -(float) Math.cos(ang) * 0.95f, py = (float) Math.sin(ang) * R0 - 0.02f;
            float tx = (float) Math.sin(ang), ty = (float) Math.cos(ang);
            for (int k = 0; k < V; k++) {
                double a = 2 * Math.PI * k / V;
                float nx = -ty, ny = tx; // in-plane normal
                float ox = (float) Math.cos(a) * 0.05f, oz = (float) Math.sin(a) * 0.08f;
                P[i][k] = new float[]{px + nx * ox, py + ny * ox, -0.04f + oz};
                C[i][k] = s.accent2;
            }
        }
        b.grid(P, C, null, null, P_ACC, null, null, null, null, true, false);
    }

    /** Thin glowing ribbon that hugs the face surface through the given (x,y) points. */
    private static void decal(Builder b, FrontMap fm, float[][] pts, float w, float[] col, float em) {
        int U = 0;
        for (int i = 0; i + 1 < pts.length; i++) U += 6;
        U += 1;
        float[][][] P = new float[2][U][3];
        float[][][] C = new float[2][U][3];
        float[] E = new float[2 * U];
        int k = 0;
        for (int i = 0; i + 1 < pts.length; i++) {
            for (int j = 0; j < 6 || (i + 2 == pts.length && j == 6); j++) {
                float t = j / 6f;
                float x = pts[i][0] + (pts[i + 1][0] - pts[i][0]) * t;
                float y = pts[i][1] + (pts[i + 1][1] - pts[i][1]) * t;
                float dx = pts[i + 1][0] - pts[i][0], dy = pts[i + 1][1] - pts[i][1];
                float l = (float) Math.sqrt(dx * dx + dy * dy);
                float nx = -dy / l * w, ny = dx / l * w;
                P[0][k] = new float[]{x - nx, y - ny, fm.z(x - nx, y - ny) + 0.006f};
                P[1][k] = new float[]{x + nx, y + ny, fm.z(x + nx, y + ny) + 0.006f};
                C[0][k] = col; C[1][k] = col;
                E[k] = em; E[U + k] = em;
                k++;
                if (k >= U) break;
            }
        }
        for (; k < U; k++) { P[0][k] = P[0][k - 1]; P[1][k] = P[1][k - 1]; C[0][k] = col; C[1][k] = col; }
        b.grid(P, C, E, null, P_SKIN, null, null, null, null, false, false);
    }

    private static void buildCyberLines(Builder b, Style s, FrontMap fm) {
        decal(b, fm, new float[][]{{-0.40f, -0.06f}, {-0.50f, -0.14f}, {-0.50f, -0.32f}}, 0.008f, s.accent, 1f);
        decal(b, fm, new float[][]{{-0.36f, -0.12f}, {-0.44f, -0.20f}, {-0.44f, -0.30f}}, 0.006f, s.accent, 1f);
        decal(b, fm, new float[][]{{0.42f, 0.40f}, {0.52f, 0.30f}, {0.55f, 0.18f}}, 0.006f, s.accent, 1f);
        decal(b, fm, new float[][]{{0.40f, -0.18f}, {0.47f, -0.26f}}, 0.006f, s.accent, 1f);
    }

    private static void ring(Builder b, float cx, float cy, float cz, float R, float tube, float tiltX, float tiltZ,
                             float[] col, float em, int part, float speed, float hw) {
        int U = 96, V = 6;
        float[][][] P = new float[U][V][3];
        float[][][] C = new float[U][V][3];
        float[] E = new float[U * V];
        float[][] SP = new float[U][V], H = new float[U][V];
        double cxr = Math.cos(tiltX), sxr = Math.sin(tiltX), czr = Math.cos(tiltZ), szr = Math.sin(tiltZ);
        for (int i = 0; i < U; i++) {
            double a = 2 * Math.PI * i / U;
            for (int k = 0; k < V; k++) {
                double bb = 2 * Math.PI * k / V;
                double rr = R + tube * Math.cos(bb);
                double x = rr * Math.cos(a), y = tube * Math.sin(bb), z = rr * Math.sin(a);
                double y2 = y * cxr - z * sxr, z2 = y * sxr + z * cxr;
                double x3 = x * czr - y2 * szr, y3 = x * szr + y2 * czr;
                P[i][k] = new float[]{cx + (float) x3, cy + (float) y3, cz + (float) z2};
                C[i][k] = col;
                E[i * V + k] = (i % 8 == 0) ? em * 1.5f : em;
                SP[i][k] = speed;
                H[i][k] = hw;
            }
        }
        b.grid(P, C, E, null, part, SP, null, H, null, true, false);
    }

    private static void buildHoloExtras(Builder b, Style s, Model m) {
        // gold ring around one eye + forehead circle (like the hologram artwork)
        ring(b, -s.ex, s.ey, m.eyeZ + s.er * 0.9f, s.er * 1.55f, 0.010f, (float) (Math.PI / 2), 0f, s.accent, 1f, P_SKIN, 0f, 1f);
        ring(b, 0, 0.58f, 0.80f, 0.07f, 0.008f, (float) (Math.PI / 2) - 0.5f, 0f, s.accent2, 1f, P_SKIN, 0f, 1f);
        // orbit rings around the head (rotate slowly) and base rings under the bust
        ring(b, 0, 0.05f, 0, 1.55f, 0.012f, 0.30f, 0.35f, s.accent2, 0.9f, P_RING, 0.25f, 0f);
        ring(b, 0, -0.05f, 0, 1.75f, 0.010f, -0.22f, -0.28f, s.accent, 0.9f, P_RING, -0.18f, 0f);
        ring(b, 0, -2.55f, -0.1f, 1.25f, 0.012f, 0f, 0f, s.accent2, 0.8f, P_RING, 0.10f, 0f);
        ring(b, 0, -2.60f, -0.1f, 1.55f, 0.010f, 0f, 0f, s.accent, 0.8f, P_RING, -0.07f, 0f);
    }
}
