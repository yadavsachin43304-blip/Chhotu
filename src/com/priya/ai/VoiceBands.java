package com.priya.ai;

/**
 * Tiny spectral feature for lip-sync (no FFT): four band-pass biquads run over Priya's 24 kHz output PCM and give
 * the energy share of each band per slice. Pure Java (no Android), cheap (~16 multiply-adds per sample).
 *
 * Bands: 0 = 80-400 Hz (voicing, nasal hum, rounded o/u), 1 = 400-1100 Hz (F1: open vowels a/aa),
 * 2 = 1100-2800 Hz (F2: front vowels e/i), 3 = 2800-7000 Hz (s, sh, f, t - spread lips / teeth).
 * {@link #process} returns the slice RMS and leaves the per-band shares (sum 1) in {@link #share}, averaged over
 * the last ~80 ms so one 20 ms slice never flips the mouth.
 */
public final class VoiceBands {
    public static final int N = 4;
    private static final float[] LO = {80f, 400f, 1100f, 2800f}, HI = {400f, 1100f, 2800f, 7000f};
    private static final int HIST = 4;   // 4 x 20 ms slices = 80 ms window

    private final float[] b0 = new float[N], b2 = new float[N], a1 = new float[N], a2 = new float[N];
    private final float[] x1 = new float[N], x2 = new float[N], y1 = new float[N], y2 = new float[N];
    private final float[][] hist = new float[HIST][N];
    private int hpos = 0;
    /** Energy share per band (0..1, sums to ~1) over the last ~80 ms. */
    public final float[] share = new float[N];

    public VoiceBands(float sampleRate) {
        for (int k = 0; k < N; k++) {
            // RBJ band-pass (constant 0 dB peak gain), centre = geometric mean, Q from the band edges
            double f0 = Math.sqrt(LO[k] * HI[k]);
            double q = f0 / (HI[k] - LO[k]);
            double w = 2 * Math.PI * f0 / sampleRate, alpha = Math.sin(w) / (2 * q), a0 = 1 + alpha;
            b0[k] = (float) (alpha / a0);
            b2[k] = (float) (-alpha / a0);
            a1[k] = (float) (-2 * Math.cos(w) / a0);
            a2[k] = (float) ((1 - alpha) / a0);
        }
    }

    public void reset() {
        for (int k = 0; k < N; k++) { x1[k] = x2[k] = y1[k] = y2[k] = 0f; share[k] = 0f; }
        for (float[] h : hist) java.util.Arrays.fill(h, 0f);
    }

    /** 16-bit little-endian mono PCM slice. Returns its RMS (16-bit scale); updates {@link #share}. */
    public float process(byte[] b, int off, int len) {
        float[] e = hist[hpos];
        java.util.Arrays.fill(e, 0f);
        double sum = 0;
        int n = 0;
        for (int i = off; i + 1 < off + len; i += 2) {
            float s = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            sum += s * s;
            n++;
            for (int k = 0; k < N; k++) {
                float y = b0[k] * s + b2[k] * x2[k] - a1[k] * y1[k] - a2[k] * y2[k];
                x2[k] = x1[k]; x1[k] = s; y2[k] = y1[k]; y1[k] = y;
                e[k] += y * y;
            }
        }
        // per-band level compensation: speech has far more energy low; weight so a neutral vowel reads ~even
        e[1] *= 1.6f; e[2] *= 4.0f; e[3] *= 9.0f;
        hpos = (hpos + 1) % HIST;
        float tot = 0f;
        for (int k = 0; k < N; k++) {
            float a = 0f;
            for (float[] h : hist) a += h[k];
            share[k] = a;
            tot += a;
        }
        for (int k = 0; k < N; k++) share[k] = tot > 1e-3f ? share[k] / tot : 0f;
        return n == 0 ? 0f : (float) Math.sqrt(sum / n);
    }
}
