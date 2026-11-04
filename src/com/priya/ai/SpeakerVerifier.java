package com.priya.ai;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Owner voice lock, plain Java (no libraries).
 *
 * Features: 13 MFCC (c1..c13, 26 mel bands, 32 ms Hamming window every 20 ms at 16 kHz) + 13 deltas = 26 dims.
 * Voiceprint: diagonal Gaussian (mean + variance per dim) + mean pitch (Hz).
 * Score: average per-frame log-likelihood (z-scored against the owner's own enrollment segments, leave-one-phrase-out),
 *        cosine of the utterance's mean static MFCC to the owner's mean (z-scored the same way), and pitch closeness.
 *        Combined into a confidence 0..1.
 * Adaptation: utterances with confidence >= ADAPT_CONF nudge the voiceprint (weight 0.02), max 50 per day.
 *        The original enrollment is kept as a backup that Settings can restore.
 */
public class SpeakerVerifier {

    public static final int SR = 16000;
    public static final int NCEP = 13;
    public static final int DIM = NCEP * 2;
    private static final int NFFT = 512;
    private static final int NMEL = 26;
    /** Frames per scoring segment during enrollment calibration (~0.5 s of speech). */
    private static final int SEG = 25;

    public static final float ADAPT_CONF = 0.80f;
    private static final float ADAPT_W = 0.02f;
    private static final int ADAPT_MAX_PER_DAY = 50;
    /** |ln(utterance pitch / owner pitch)| above this (~1.6x or ~0.62x) caps confidence at PITCH_VETO_CAP. */
    private static final double PITCH_VETO_LR = Math.log(1.6);
    private static final float PITCH_VETO_CAP = 0.49f;

    private static final String PREFS = "priya_voiceprint";

    // ---- voiceprint
    float[] mean = new float[DIM];
    float[] var = new float[DIM];
    float pitchHz = 0f;
    // calibration (from enrollment, never adapted)
    float llMean, llStd = 1f, cosMean, cosStd = 0.05f;

    private Context ctx;

    // ================================================================== persistence

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean hasVoiceprint(Context c) { return !sp(c).getString("vp", "").isEmpty(); }
    public static boolean hasBackup(Context c) { return !sp(c).getString("vp_backup", "").isEmpty(); }
    public static int adaptCountToday(Context c) {
        SharedPreferences p = sp(c);
        return today().equals(p.getString("adapt_day", "")) ? p.getInt("adapt_n", 0) : 0;
    }
    public static int adaptTotal(Context c) { return sp(c).getInt("adapt_total", 0); }

    /** @return the owner's voiceprint, or null when not enrolled. */
    public static SpeakerVerifier load(Context c) {
        SpeakerVerifier v = decode(sp(c).getString("vp", ""));
        if (v != null) v.ctx = c.getApplicationContext();
        return v;
    }

    /** Puts the original enrollment back (drops all daily adaptation). */
    public static boolean restoreBackup(Context c) {
        String b = sp(c).getString("vp_backup", "");
        if (b.isEmpty()) return false;
        sp(c).edit().putString("vp", b).putInt("adapt_total", 0).apply();
        return true;
    }

    private void save(boolean alsoBackup) {
        if (ctx == null) return;
        String s = encode();
        SharedPreferences.Editor e = sp(ctx).edit().putString("vp", s);
        if (alsoBackup) e.putString("vp_backup", s).putInt("adapt_total", 0);
        e.apply();
    }

    private String encode() {
        StringBuilder sb = new StringBuilder("v1");
        for (float f : mean) sb.append(',').append(f);
        for (float f : var) sb.append(',').append(f);
        sb.append(',').append(pitchHz).append(',').append(llMean).append(',').append(llStd)
                .append(',').append(cosMean).append(',').append(cosStd);
        return sb.toString();
    }

    private static SpeakerVerifier decode(String s) {
        if (s == null || !s.startsWith("v1,")) return null;
        try {
            String[] p = s.split(",");
            if (p.length != 1 + DIM * 2 + 5) return null;
            SpeakerVerifier v = new SpeakerVerifier();
            int k = 1;
            for (int i = 0; i < DIM; i++) v.mean[i] = Float.parseFloat(p[k++]);
            for (int i = 0; i < DIM; i++) v.var[i] = Float.parseFloat(p[k++]);
            v.pitchHz = Float.parseFloat(p[k++]);
            v.llMean = Float.parseFloat(p[k++]);
            v.llStd = Float.parseFloat(p[k++]);
            v.cosMean = Float.parseFloat(p[k++]);
            v.cosStd = Float.parseFloat(p[k]);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private static String today() { return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()); }

    // ================================================================== MFCC

    private static float[][] melBank;
    private static float[] window;
    private static float[][] dct;

    private static synchronized void init() {
        if (melBank != null) return;
        window = new float[NFFT];
        for (int i = 0; i < NFFT; i++) window[i] = (float) (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (NFFT - 1)));
        int bins = NFFT / 2 + 1;
        double lo = mel(60), hi = mel(7600);
        double[] pts = new double[NMEL + 2];
        for (int i = 0; i < pts.length; i++) pts[i] = imel(lo + (hi - lo) * i / (NMEL + 1)) * NFFT / SR;
        float[][] fb = new float[NMEL][bins];
        for (int m = 0; m < NMEL; m++) {
            double l = pts[m], c = pts[m + 1], r = pts[m + 2];
            for (int k = 0; k < bins; k++) {
                double w = 0;
                if (k > l && k <= c) w = (k - l) / (c - l);
                else if (k > c && k < r) w = (r - k) / (r - c);
                fb[m][k] = (float) w;
            }
        }
        float[][] d = new float[NCEP][NMEL];
        for (int n = 0; n < NCEP; n++) {
            int q = n + 1; // c1..c13 (c0 = loudness, skipped)
            for (int m = 0; m < NMEL; m++) d[n][m] = (float) (Math.sqrt(2.0 / NMEL) * Math.cos(Math.PI * q * (m + 0.5) / NMEL));
        }
        dct = d;
        melBank = fb;
    }

    private static double mel(double hz) { return 2595 * Math.log10(1 + hz / 700); }
    private static double imel(double m) { return 700 * (Math.pow(10, m / 2595) - 1); }

    /**
     * 13 static MFCCs from the last 512 samples of prev+cur (a 32 ms window ending at the current 20 ms frame).
     * Thread-safe (allocates its own scratch).
     */
    public static float[] mfcc(short[] prev, short[] cur) {
        init();
        float[] re = new float[NFFT], im = new float[NFFT];
        int total = prev.length + cur.length;
        int start = Math.max(0, total - NFFT);
        float last = 0f;
        int j = 0;
        for (int i = start; i < total && j < NFFT; i++, j++) {
            float s = i < prev.length ? prev[i] : cur[i - prev.length];
            float pe = s - 0.97f * last; // pre-emphasis
            last = s;
            re[j] = pe * window[j] / 32768f;
        }
        fft(re, im);
        int bins = NFFT / 2 + 1;
        float[] pow = new float[bins];
        for (int k = 0; k < bins; k++) pow[k] = re[k] * re[k] + im[k] * im[k];
        float[] logMel = new float[NMEL];
        for (int m = 0; m < NMEL; m++) {
            double e = 0;
            float[] w = melBank[m];
            for (int k = 0; k < bins; k++) if (w[k] != 0f) e += w[k] * pow[k];
            logMel[m] = (float) Math.log(e + 1e-10);
        }
        float[] c = new float[NCEP];
        for (int n = 0; n < NCEP; n++) {
            double s = 0;
            for (int m = 0; m < NMEL; m++) s += dct[n][m] * logMel[m];
            c[n] = (float) s;
        }
        return c;
    }

    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    float t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }

    /** Static MFCC sequence -> 26-dim frames (statics + deltas, delta = (c[t+1]-c[t-1])/2, edges clamped). */
    public static List<float[]> withDeltas(List<float[]> st) {
        List<float[]> out = new ArrayList<>(st.size());
        int n = st.size();
        for (int t = 0; t < n; t++) {
            float[] a = st.get(Math.max(0, t - 1)), b = st.get(Math.min(n - 1, t + 1)), c = st.get(t);
            float[] f = new float[DIM];
            for (int i = 0; i < NCEP; i++) { f[i] = c[i]; f[NCEP + i] = (b[i] - a[i]) * 0.5f; }
            out.add(f);
        }
        return out;
    }

    // ================================================================== scoring

    private static double avgLL(List<float[]> fr, float[] mu, float[] vr) {
        if (fr.isEmpty()) return -1e9;
        double c = 0;
        for (int i = 0; i < DIM; i++) c += Math.log(2 * Math.PI * vr[i]);
        double sum = 0;
        for (float[] f : fr) {
            double q = 0;
            for (int i = 0; i < DIM; i++) { double d = f[i] - mu[i]; q += d * d / vr[i]; }
            sum += -0.5 * (c + q);
        }
        return sum / fr.size() / DIM; // per frame, per dim
    }

    private static double cosine(List<float[]> fr, float[] mu) {
        double[] m = new double[NCEP];
        for (float[] f : fr) for (int i = 0; i < NCEP; i++) m[i] += f[i];
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < NCEP; i++) { m[i] /= Math.max(1, fr.size()); dot += m[i] * mu[i]; a += m[i] * m[i]; b += mu[i] * mu[i]; }
        return dot / Math.sqrt(a * b + 1e-12);
    }

    private static float sigmoid(double x) { return (float) (1 / (1 + Math.exp(-x))); }

    /**
     * Confidence 0..1 that these frames are the owner's voice.
     * @param staticMfcc static MFCCs (13) of the utterance's speech frames, in order
     * @param pitchMedian median pitch (Hz) of the utterance, 0 if unknown
     */
    public float confidence(List<float[]> staticMfcc, float pitchMedian) {
        if (staticMfcc == null || staticMfcc.size() < 4) return 0f;
        List<float[]> fr = withDeltas(staticMfcc);
        double ll = avgLL(fr, mean, var);
        double cs = cosine(fr, mean);
        float lConf = sigmoid(1.6 * ((ll - llMean) / llStd + 2.0));
        float cConf = sigmoid(1.6 * ((cs - cosMean) / cosStd + 2.0));
        float conf;
        if (pitchMedian > 0f && pitchHz > 0f) {
            double lr = Math.log(pitchMedian / pitchHz);
            float pConf = (float) Math.exp(-(lr * lr) / (2 * 0.16 * 0.16));
            conf = 0.55f * lConf + 0.25f * cConf + 0.20f * pConf;
            // A voice ~60%+ away from the owner's pitch (e.g. a woman vs a male owner) must not pass Medium/High
            // on MFCC similarity alone (pitch has only 20% weight above). Still allowed at Low strictness.
            if (Math.abs(lr) > PITCH_VETO_LR) conf = Math.min(conf, PITCH_VETO_CAP);
        } else {
            conf = 0.65f * lConf + 0.35f * cConf;
        }
        return Math.max(0f, Math.min(1f, conf));
    }

    /** Accept threshold for strictness 0=low, 1=medium, 2=high. */
    public static float threshold(int strictness) {
        return strictness <= 0 ? 0.35f : strictness >= 2 ? 0.62f : 0.50f;
    }

    /**
     * Daily adaptation: called after an owner utterance. Only nudges the voiceprint when confidence is high,
     * at most 50 times per day. Calibration stats and the backup are never touched.
     * @return true when the voiceprint was updated
     */
    public synchronized boolean adapt(List<float[]> staticMfcc, float pitchMedian, float conf) {
        if (ctx == null || conf < ADAPT_CONF || staticMfcc == null || staticMfcc.size() < 40) return false;
        SharedPreferences p = sp(ctx);
        String day = today();
        int n = day.equals(p.getString("adapt_day", "")) ? p.getInt("adapt_n", 0) : 0;
        if (n >= ADAPT_MAX_PER_DAY) return false;
        List<float[]> fr = withDeltas(staticMfcc);
        float[] um = new float[DIM], uv = new float[DIM];
        for (float[] f : fr) for (int i = 0; i < DIM; i++) um[i] += f[i];
        for (int i = 0; i < DIM; i++) um[i] /= fr.size();
        for (float[] f : fr) for (int i = 0; i < DIM; i++) { float d = f[i] - um[i]; uv[i] += d * d; }
        for (int i = 0; i < DIM; i++) {
            uv[i] = uv[i] / fr.size();
            float nm = (1 - ADAPT_W) * mean[i] + ADAPT_W * um[i];
            float dm = um[i] - mean[i];
            var[i] = Math.max(1e-3f, (1 - ADAPT_W) * var[i] + ADAPT_W * (uv[i] + dm * dm * (1 - ADAPT_W)));
            mean[i] = nm;
        }
        if (pitchMedian > 0f && pitchHz > 0f) pitchHz = (1 - ADAPT_W) * pitchHz + ADAPT_W * pitchMedian;
        p.edit().putString("adapt_day", day).putInt("adapt_n", n + 1)
                .putInt("adapt_total", p.getInt("adapt_total", 0) + 1).apply();
        save(false);
        return true;
    }

    // ================================================================== enrollment

    /** One recorded enrollment phrase: static MFCCs of its speech frames + voiced pitches. */
    public static class Phrase {
        public final List<float[]> mfcc = new ArrayList<>();
        public final List<Float> pitch = new ArrayList<>();
    }

    private static void fit(List<float[]> fr, float[] mu, float[] vr) {
        java.util.Arrays.fill(mu, 0f);
        java.util.Arrays.fill(vr, 0f);
        for (float[] f : fr) for (int i = 0; i < DIM; i++) mu[i] += f[i];
        for (int i = 0; i < DIM; i++) mu[i] /= Math.max(1, fr.size());
        for (float[] f : fr) for (int i = 0; i < DIM; i++) { float d = f[i] - mu[i]; vr[i] += d * d; }
        for (int i = 0; i < DIM; i++) vr[i] = Math.max(1e-3f, vr[i] / Math.max(1, fr.size()));
    }

    /**
     * Builds the voiceprint from the enrollment phrases, saves it (and the backup), and returns it; null when the
     * recordings did not contain enough speech.
     */
    public static SpeakerVerifier enroll(Context c, List<Phrase> phrases) {
        if (phrases == null || phrases.size() < 3) return null;
        List<List<float[]>> per = new ArrayList<>();
        List<float[]> all = new ArrayList<>();
        List<Float> pitches = new ArrayList<>();
        for (Phrase p : phrases) {
            if (p.mfcc.size() < SEG) continue;
            List<float[]> d = withDeltas(p.mfcc);
            per.add(d);
            all.addAll(d);
            pitches.addAll(p.pitch);
        }
        if (per.size() < 3 || all.size() < 120) return null;
        SpeakerVerifier v = new SpeakerVerifier();
        fit(all, v.mean, v.var);
        v.pitchHz = AudioEngine.median(pitches);

        // Calibrate on ~0.5 s segments, leave-one-phrase-out, so live short utterances are judged fairly.
        List<Double> lls = new ArrayList<>(), coss = new ArrayList<>();
        float[] mu = new float[DIM], vr = new float[DIM];
        for (int h = 0; h < per.size(); h++) {
            List<float[]> train = new ArrayList<>();
            for (int k = 0; k < per.size(); k++) if (k != h) train.addAll(per.get(k));
            fit(train, mu, vr);
            List<float[]> test = per.get(h);
            for (int s = 0; s + SEG <= test.size(); s += SEG / 2) {
                List<float[]> seg = test.subList(s, s + SEG);
                lls.add(avgLL(seg, mu, vr));
                coss.add(cosine(seg, mu));
            }
        }
        v.llMean = (float) meanOf(lls);
        v.llStd = (float) Math.max(0.15, stdOf(lls, v.llMean));
        v.cosMean = (float) meanOf(coss);
        v.cosStd = (float) Math.max(0.02, stdOf(coss, v.cosMean));
        v.ctx = c.getApplicationContext();
        sp(c).edit().putString("adapt_day", "").putInt("adapt_n", 0).apply();
        v.save(true);
        return v;
    }

    private static double meanOf(List<Double> x) { double s = 0; for (double d : x) s += d; return x.isEmpty() ? 0 : s / x.size(); }
    private static double stdOf(List<Double> x, double m) {
        double s = 0; for (double d : x) s += (d - m) * (d - m);
        return x.size() < 2 ? 0 : Math.sqrt(s / (x.size() - 1));
    }

    public float ownerPitch() { return pitchHz; }
}
