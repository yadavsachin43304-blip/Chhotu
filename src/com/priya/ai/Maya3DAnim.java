package com.priya.ai;

import java.util.Random;

/**
 * Face animation state for the 3D head (pure Java so the offline preview runs exactly this code).
 * Inputs every frame: is she speaking, her output level and the 4 voice-band shares ({@link VoiceBands}).
 * Outputs: jaw open + lip shapes (A open, O/U round, E/I wide, M/B/P pressed), blink, gaze (saccades; looks at the
 * camera while speaking), brow lift on emphasis, head yaw/pitch/roll (idle drift + nods on speech peaks), breathing,
 * and the background effect level. All channels are smoothed (attack ~30 ms, release ~90 ms for the mouth).
 */
public final class Maya3DAnim {
    // ---- outputs
    public float open, round, wide, press, blink, gazeX, gazeY, brow, yaw, pitch, roll, breath, fx, talk;

    // ---- internals
    private final Random rnd;
    private float t = 0f;
    private float slowLvl = 0f, sylGain = 1f, lastLvl = 0f, nodV = 0f, nod = 0f, browT = 0f;
    private float lastOnset = -9f, pressHold = 0f, voicedFor = 0f, silentFor = 9f;
    private float nextBlink = 1.2f, blinkT = -1f, nextGaze = 0.8f, gTx = 0f, gTy = 0f;
    private boolean doubleBlink = false;
    private float peak = 0.3f;
    private final float[] vw = new float[7];
    // band-share prototypes (VoiceBands, female voice) for A, O, U, E, I, M/N (hum), S/SH (fricative)
    private static final float[][] PROTO = {
            {0.04f, 0.39f, 0.46f, 0.11f}, {0.24f, 0.55f, 0.17f, 0.04f}, {0.45f, 0.40f, 0.12f, 0.03f},
            {0.21f, 0.45f, 0.23f, 0.11f}, {0.37f, 0.18f, 0.20f, 0.25f}, {0.75f, 0.18f, 0.05f, 0.01f},
            {0.00f, 0.02f, 0.08f, 0.90f}};
    private static final float[] P_OPEN = {1.0f, 0.55f, 0.30f, 0.45f, 0.25f, 0.0f, 0.12f};
    private static final float[] P_ROUND = {0f, 0.8f, 1.0f, 0f, 0f, 0f, 0f};
    private static final float[] P_WIDE = {0f, 0f, 0f, 0.75f, 1.0f, 0f, 0.8f};
    private float n1, n2, n3;      // slow noise phases

    /** Head-turn overrides for previews/tests (NaN = automatic). Radians. */
    public float forceYaw = Float.NaN, forcePitch = Float.NaN;

    public Maya3DAnim(long seed) {
        rnd = new Random(seed);
        n1 = rnd.nextFloat() * 100f; n2 = rnd.nextFloat() * 100f; n3 = rnd.nextFloat() * 100f;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : v > 1f ? 1f : v; }

    private static float approach(float cur, float target, float dt, float attack, float release) {
        float tau = target > cur ? attack : release;
        return cur + (target - cur) * Math.min(1f, dt / tau);
    }

    private static float sstep(float a, float b, float x) { float u = clamp01((x - a) / (b - a)); return u * u * (3 - 2 * u); }

    /**
     * @param level  her output level 0..1 (PriyaBus.outLevel), 0 when not speaking
     * @param bands  VoiceBands shares (low, F1, F2, fricative) at the playhead, may be null
     */
    public void step(float dt, boolean speaking, float level, float[] bands) {
        if (dt <= 0f) dt = 0.033f;
        dt = Math.min(dt, 0.1f);
        t += dt;
        float lvl = speaking ? clamp01(level) : 0f;
        float b0 = 0.25f, b1 = 0.25f, b2 = 0.25f, b3 = 0.25f;
        if (bands != null && bands.length >= 4 && bands[0] + bands[1] + bands[2] + bands[3] > 0.5f) {
            b0 = bands[0]; b1 = bands[1]; b2 = bands[2]; b3 = bands[3];
        }
        boolean voiced = lvl > 0.035f;
        if (voiced) { voicedFor += dt; silentFor = 0f; } else { silentFor += dt; voicedFor = 0f; }
        talk = approach(talk, speaking ? 1f : 0f, dt, 0.15f, 1.2f);

        // syllable onsets: level jumps above its slow average -> new random syllable gain, head nod, maybe brows
        slowLvl += (lvl - slowLvl) * Math.min(1f, dt / 0.35f);
        if (voiced && lvl > slowLvl * 1.45f + 0.05f && lvl > lastLvl && t - lastOnset > 0.22f) {
            lastOnset = t;
            sylGain = 0.82f + 0.30f * rnd.nextFloat();
            if (lvl > 0.42f && rnd.nextFloat() < 0.55f) nodV += 0.9f + 0.6f * rnd.nextFloat();   // nod impulse
            if (lvl > 0.55f && rnd.nextFloat() < 0.35f) browT = 1f;                              // emphasis
            if (rnd.nextFloat() < 0.06f && blinkT < 0f) nextBlink = t;                          // blink on a beat
        }
        lastLvl = lvl;

        // ---- visemes: soft nearest-prototype match of the 4 band shares (A, O, U, E, I, M, S), weighted by loudness
        peak = Math.max(Math.max(lvl, 0.15f), peak * (float) Math.exp(-dt / 1.5f));
        float loud = clamp01((lvl - 0.03f) * 1.7f);
        loud = (float) Math.sqrt(loud);                               // compress: quiet syllables still read
        float openT = 0f, roundT = 0f, wideT = 0f, pressT = 0f;
        if (voiced) {
            float rel = lvl / peak, sum = 0f;
            for (int v = 0; v < PROTO.length; v++) {
                float d0 = b0 - PROTO[v][0], d1 = b1 - PROTO[v][1], d2 = b2 - PROTO[v][2], d3 = b3 - PROTO[v][3];
                float w = (float) Math.exp(-(d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3) / 0.025f);
                if (v == 5) w *= 1f - sstep(0.30f, 0.60f, rel);       // M/N hum is much quieter than a vowel
                vw[v] = w;
                sum += w;
            }
            if (sum < 1e-4f) { java.util.Arrays.fill(vw, 0f); vw[3] = 1f; sum = 1f; }   // unknown sound: neutral 'e'
            float o = 0f, r = 0f, wi = 0f;
            for (int v = 0; v < PROTO.length; v++) {
                float w = vw[v] / sum;
                o += w * P_OPEN[v]; r += w * P_ROUND[v]; wi += w * P_WIDE[v];
            }
            float hum = vw[5] / sum;
            openT = Math.min(0.85f, loud * sylGain * (0.12f + 0.78f * o));
            roundT = r * (0.6f + 0.4f * loud);
            wideT = wi;
            pressT = hum;
            openT *= 0.94f + 0.06f * (float) Math.sin(t * 7.3f + n1);   // natural micro-variation (slow, small)
        } else if (speaking && silentFor < 0.12f && pressHold <= 0f && lastOnset > t - 0.5f) {
            pressHold = 0.09f;   // a stop between syllables (b, p, m): lips meet briefly
        }
        if (pressHold > 0f) { pressHold -= dt; pressT = Math.max(pressT, 1f); openT *= 0.2f; }
        open = approach(open, openT, dt, 0.030f, 0.090f);
        round = approach(round, roundT, dt, 0.060f, 0.120f);
        wide = approach(wide, wideT, dt, 0.050f, 0.110f);
        press = approach(press, pressT, dt, 0.030f, 0.090f);
        if (open < 0.002f) open = 0f;

        // ---- blink (every 2.5-6 s, sometimes double), 150 ms
        if (blinkT < 0f && t >= nextBlink) { blinkT = 0f; doubleBlink = rnd.nextFloat() < 0.18f; }
        if (blinkT >= 0f) {
            blinkT += dt;
            float dur = 0.15f;
            float bt = blinkT;
            if (bt < dur) blink = bump(bt / dur);
            else if (doubleBlink && bt < dur * 2.25f) blink = bt < dur * 1.25f ? 0f : bump((bt - dur * 1.25f) / dur);
            else { blink = 0f; blinkT = -1f; nextBlink = t + 2.5f + 3.5f * rnd.nextFloat(); }
        }

        // ---- gaze: saccades every 1-4 s; at the camera (with tiny jitter) while speaking
        if (t >= nextGaze) {
            if (speaking || talk > 0.5f) {
                gTx = (rnd.nextFloat() - 0.5f) * 0.25f; gTy = (rnd.nextFloat() - 0.5f) * 0.15f;
                nextGaze = t + 0.8f + 1.6f * rnd.nextFloat();
            } else {
                boolean centre = rnd.nextFloat() < 0.35f;
                gTx = centre ? 0f : (rnd.nextFloat() * 2f - 1f) * 0.9f;
                gTy = centre ? 0f : (rnd.nextFloat() * 2f - 1f) * 0.5f;
                nextGaze = t + 1f + 3f * rnd.nextFloat();
            }
        }
        gazeX = approach(gazeX, gTx, dt, 0.035f, 0.035f);
        gazeY = approach(gazeY, gTy, dt, 0.035f, 0.035f);

        // ---- brows: rise on emphasis, settle
        browT = Math.max(0f, browT - dt * 1.6f);
        brow = approach(brow, Math.min(1f, browT * 1.2f), dt, 0.08f, 0.35f);

        // ---- head: slow idle drift, more lively while speaking, nods (damped spring) on speech peaks
        float live = 0.6f + 0.6f * talk;
        nodV += (-nod * 60f - nodV * 9f) * dt;
        nod += nodV * dt * 0.06f;
        float y = 0.040f * (float) Math.sin(t * 0.31f + n1) + 0.020f * (float) Math.sin(t * 0.83f + n2);
        float p = 0.018f * (float) Math.sin(t * 0.27f + n3) + 0.010f * (float) Math.sin(t * 0.71f + n1);
        yaw = Float.isNaN(forceYaw) ? y * live : forceYaw;
        pitch = Float.isNaN(forcePitch) ? p * live + nod * 0.9f + 0.012f * open * talk : forcePitch;
        roll = (0.016f * (float) Math.sin(t * 0.23f + n2) + 0.4f * y * 0.2f) * live;
        breath = 0.5f + 0.5f * (float) Math.sin(t * 1.55f);
        fx = approach(fx, speaking ? Math.min(1f, 0.25f + lvl * 1.4f) : 0f, dt, 0.05f, 0.40f);
    }

    private static float bump(float x) {   // 0 -> 1 -> 0, quick close, slightly slower open
        if (x <= 0f || x >= 1f) return 0f;
        return x < 0.4f ? sstep(0f, 0.4f, x) : 1f - sstep(0.4f, 1f, x);
    }

    /** Pose overrides for previews: open, round, wide, press, blink. */
    public void pose(float o, float r, float w, float pr, float b) {
        open = o; round = r; wide = w; press = pr; blink = b;
    }
}
