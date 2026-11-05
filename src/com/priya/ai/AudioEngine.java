package com.priya.ai;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Microphone capture + speaker playback.
 *
 * Echo handling (Priya must not hear herself):
 *  1. Mic uses VOICE_COMMUNICATION source + AcousticEchoCanceler + NoiseSuppressor + AGC (hardware/OS AEC).
 *  2. Playback uses the voice-communication audio path (so the AEC has a reference signal).
 *  3. "Strict echo guard": mic is not streamed while Priya talks (plus a short tail).
 *
 * Always-listening cost control: a local, adaptive voice-activity gate. Audio is only streamed to
 * Gemini while someone is actually talking (with 200 ms pre-roll), then audioStreamEnd is sent.
 */
public class AudioEngine {

    public interface MicSink {
        void onSpeechStart();
        void onSpeechChunk(byte[] pcm16k, int len);
        void onSpeechEnd();
        /** A voice that is not the owner's kept talking (voice lock on). */
        default void onStranger() { }
    }

    private static final int IN_RATE = 16000;
    private static final int OUT_RATE = 24000;
    private static final int FRAME = 320;          // 20 ms @ 16 kHz
    private static final int PREROLL_FRAMES = 10;  // 200 ms
    // end-of-utterance silence is adaptive: see hangFor() (short commands end sooner = faster replies)
    private static final float MIN_THR = 500f;     // absolute RMS floor (16-bit scale)
    private static final long TAIL_MS = 350;       // keep mic closed a bit after Priya stops

    private final Context ctx;
    private final AudioManager am;

    private AudioRecord rec;
    private AudioTrack track;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private AutomaticGainControl agc;

    private Thread micThread;
    private Thread playThread;
    private volatile boolean running = false;
    private final LinkedBlockingQueue<byte[]> playQueue = new LinkedBlockingQueue<>();
    private volatile long speakEnd = 0;
    private boolean commMode = false;

    private boolean strictEcho = true;
    private boolean forceSpeaker = true;
    /** true = play through the normal assistant/media path (full-quality 24 kHz, no call processing). */
    private boolean natural = true;
    private float mult = 2.15f;

    // ---- barge-in (rok-tok) tuning, set from Settings through setBargeLevel(); defaults = "Turant"
    private boolean legacyBarge = false;
    private int bargeNeed = 12;            // ~240 ms of sustained loud voice (20 ms frames)
    private float bargeBaseMul = 3.0f, bargeFloorMul = 3.5f, bargeEchoMul = 1.7f;
    private long bargeGraceMs = 400;
    private int hangShort = 20, hangLong = 28; // silence frames that end an utterance (short / long utterances)
    /** Learned ratio mic-level / her-playback-level while she speaks (= how much of her own voice the mic hears). */
    private float couple = 0.8f;

    /** 0 = Dheere (old behaviour), 1 = Normal, 2 = Turant (default). */
    public void setBargeLevel(int level) {
        switch (level) {
            case 0:
                legacyBarge = true; bargeNeed = natural ? 35 : 28; bargeGraceMs = 900; hangShort = 28; hangLong = 32;
                break;
            case 1:
                legacyBarge = false; bargeNeed = 20; bargeBaseMul = 3.5f; bargeFloorMul = 4.5f; bargeEchoMul = 2.0f;
                bargeGraceMs = 600; hangShort = 24; hangLong = 30;
                break;
            default:
                legacyBarge = false; bargeNeed = 12; bargeBaseMul = 3.0f; bargeFloorMul = 3.5f; bargeEchoMul = 1.7f;
                bargeGraceMs = 400; hangShort = 20; hangLong = 28;
                break;
        }
    }

    private int hangFor(int uttFrames) { return uttFrames < 150 ? hangShort : hangLong; }

    public AudioEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.am = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    public boolean isSpeaking() {
        return SystemClock.elapsedRealtime() < speakEnd + TAIL_MS || !playQueue.isEmpty();
    }

    /** @return human readable echo-cancel status, or null if the microphone could not be opened. */
    public String start(MicSink sink, boolean strictEcho, int sensitivity, boolean forceSpeaker) {
        return start(sink, strictEcho, sensitivity, forceSpeaker, true);
    }

    public String start(MicSink sink, boolean strictEcho, int sensitivity, boolean forceSpeaker, boolean naturalVoice) {
        this.strictEcho = strictEcho;
        this.natural = naturalVoice;
        this.forceSpeaker = forceSpeaker;
        this.mult = Math.max(1.3f, 3.0f - 0.17f * sensitivity);

        int minBuf = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 2, IN_RATE);
        int[] sources = {MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            try {
                AudioRecord r = new AudioRecord(src, IN_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    rec = r;
                    break;
                }
                r.release();
            } catch (Exception ignored) { }
        }
        if (rec == null) return null;

        StringBuilder info = new StringBuilder();
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(rec.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
        } catch (Exception ignored) { }
        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(rec.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
            }
        } catch (Exception ignored) { }
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(rec.getAudioSessionId());
                if (agc != null) agc.setEnabled(true);
            }
        } catch (Exception ignored) { }
        info.append("AEC ").append(aec != null ? "on" : "n/a")
                .append(" | NS ").append(ns != null ? "on" : "n/a")
                .append(" | AGC ").append(agc != null ? "on" : "n/a")
                .append(strictEcho ? " | echo guard on" : " | echo guard off")
                .append(natural ? " | HD voice 24kHz" : " | call-mode voice");

        int outMin = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        // Call path (VOICE_COMMUNICATION) band-limits + processes speech on many phones,
                        // which makes Priya sound tinny/robotic. HD mode keeps her real 24 kHz voice untouched.
                        .setUsage(natural ? AudioAttributes.USAGE_ASSISTANT : AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(OUT_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                // >= 4x min buffer and >= 400 ms: a late network packet or a GC pause can't drain the speaker
                .setBufferSizeInBytes(Math.max(outMin * 4, OUT_RATE / 1000 * 400 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                // NONE (not LOW_LATENCY): the fast path's tiny buffers underrun on many phones = chopped voice
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build();
        track.play();
        if (natural) ensureMediaVolume();
        else setCommMode(true); // stays on for the whole session: no audio-route switch at every reply

        framesWritten = 0;
        headBase = 0;
        lvlR = lvlW = 0;
        cushionMs = CUSHION_START_MS;
        couple = 0.8f;
        flushReq++;
        running = true;
        micThread = new Thread(() -> micLoop(sink), "priya-mic");
        playThread = new Thread(this::playLoop, "priya-play");
        micThread.start();
        playThread.start();
        return info.toString();
    }

    public void stop() {
        running = false;
        playQueue.clear();
        try { if (micThread != null) micThread.join(500); } catch (InterruptedException ignored) { }
        try { if (playThread != null) playThread.join(500); } catch (InterruptedException ignored) { }
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) { }
        try { if (aec != null) aec.release(); } catch (Exception ignored) { }
        try { if (ns != null) ns.release(); } catch (Exception ignored) { }
        try { if (agc != null) agc.release(); } catch (Exception ignored) { }
        try { if (track != null) { track.stop(); track.release(); } } catch (Exception ignored) { }
        rec = null; track = null; aec = null; ns = null; agc = null;
        setCommMode(false);
    }

    // ------------------------------------------------------------------ playback
    //
    // Smooth playback design (v30):
    //  * Network (ws-reader) thread only enqueues decoded PCM. ALL AudioTrack calls (write, fade, pause,
    //    flush) happen on ONE dedicated URGENT_AUDIO thread, so an interruption can never race a write.
    //  * Writes go out in 20 ms slices, so an interrupt / barge-in is honoured within ~20 ms and no stale
    //    tail of an old reply is written after a flush.
    //  * "Is Priya speaking?" comes from the real playback head (frames written - frames played), not from
    //    when packets arrived. The old arrival-based estimate ended ~0.3-0.6 s early, the mic re-opened while
    //    her last words were still coming out of the speaker, her own echo was sent to Gemini as user speech,
    //    and the server "interrupted" her -> she stopped/stuttered mid-sentence.
    //  * Adaptive jitter buffer: rebuffer ONLY when the AudioTrack has really drained (not when one network
    //    packet is a bit late while 400 ms is still queued). Start cushion 250 ms; every real underrun grows
    //    it by 100 ms up to 600 ms; a clean reply shrinks it back by 50 ms.
    //  * Fades only at reply start (10 ms), resume after a real underrun (3 ms, from silence) and on
    //    interruption (15 ms fade-out) - never at ordinary chunk boundaries.
    //  * No allocations per chunk on the audio thread (reused cushion buffer, level ring).

    private static final String TAG = "PriyaAudio";
    private static final int FPMS = OUT_RATE / 1000;          // frames per ms (24)
    private static final int SLICE_BYTES = 20 * FPMS * 2;     // 20 ms write slices
    private static final int CUSHION_START_MS = 250, CUSHION_MAX_MS = 600, CUSHION_MIN_MS = 200;

    /** True while the server is still sending audio for the current reply. */
    private volatile boolean generating = false;
    /** After the user cuts Priya off, drop the rest of that old reply. */
    private volatile boolean dropOld = false;
    /** Bumped by interruptPlayback(); the play thread fades out + flushes when it changes. */
    private volatile int flushReq = 0;
    /** Smoothed level of what is actually leaving the speaker now (16-bit RMS scale); barge-in reference. */
    private volatile float outRms = 0f;
    private volatile long replyStart = 0;
    private volatile int cushionMs = CUSHION_START_MS;
    /** Underruns detected by this engine (real drains mid-reply). */
    public static volatile int underruns = 0;
    /** AudioTrack.getUnderrunCount() (framework-level), for debugging. */
    public static volatile int trackUnderruns = 0;

    public void setGenerating(boolean g) {
        generating = g;
        if (!g) dropOld = false;
    }

    private void bargeIn() {
        if (generating) dropOld = true;
        interruptPlayback();
    }

    /** Output level 0..1 of the audio playing right now (also published as PriyaBus.outLevel). */
    public float outputLevel() { return PriyaBus.outLevel; }

    /** Called on the WebSocket thread: only bookkeeping + enqueue, never touches the AudioTrack. */
    public void playChunk(byte[] pcm24k) {
        if (!running || pcm24k == null || pcm24k.length < 2 || dropOld) return;
        long now = SystemClock.elapsedRealtime();
        if (now > speakEnd + 300) replyStart = now;
        if (!natural) setCommMode(true);
        // mark "speaking" right away (echo guard closes the mic); the play thread then keeps speakEnd
        // in step with the real playback head.
        speakEnd = Math.max(speakEnd, now + 120);
        playQueue.offer(pcm24k);
    }

    /** Thread-safe, non-blocking: the play thread fades out + flushes within ~20 ms. */
    public void interruptPlayback() {
        playQueue.clear();
        flushReq++;
        speakEnd = SystemClock.elapsedRealtime();
    }

    /** Linear fade-in over the first ms of 24 kHz 16-bit mono PCM (in place). */
    private static void fadeIn(byte[] b, int len, int ms) {
        int n = Math.min(len / 2, FPMS * ms);
        for (int i = 0; i < n; i++) {
            int s = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
            s = s * i / n;
            b[2 * i] = (byte) (s & 0xff);
            b[2 * i + 1] = (byte) ((s >> 8) & 0xff);
        }
    }

    // play-thread state
    private long framesWritten = 0;
    private long headBase = 0;
    private byte[] cushion = new byte[64 * 1024];
    private static final int RING = 128;
    private final long[] lvlFrame = new long[RING];
    private final float[] lvlVal = new float[RING];
    private final float[] lvlWide = new float[RING], lvlRound = new float[RING];
    private int lvlW = 0, lvlR = 0;

    private long head(AudioTrack t) {
        try { return (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) - headBase; } catch (Throwable e) { return framesWritten; }
    }

    /** Frames written but not yet played. */
    private long pendingFrames(AudioTrack t) {
        long p = framesWritten - head(t);
        return p < 0 ? 0 : Math.min(p, framesWritten);
    }

    /** Publish the level of the slice now at the playback head (lip-sync for the face / 3D avatar). */
    private void publishLevel(AudioTrack t, long now) {
        long h = framesWritten - pendingFrames(t);
        float lv = -1f;
        while (lvlR != lvlW && lvlFrame[lvlR % RING] <= h) { lv = lvlVal[lvlR % RING]; lvlR++; }
        if (lv >= 0f) {
            outRms = Math.max(lv, outRms * 0.97f); // slow decay: echo between her words never looks like a barge-in
            PriyaBus.outLevel = Math.min(1f, lv / 7000f);
            int k = (lvlR - 1) % RING;
            PriyaBus.mouthWide = lvlWide[k];
            PriyaBus.mouthRound = lvlRound[k];
        }
        long pend = pendingFrames(t);
        if (pend > 0) speakEnd = Math.max(speakEnd, now + pend / FPMS);
    }

    private void doFlush(AudioTrack t, boolean audible) {
        try {
            if (audible) {
                for (int k = 5; k >= 0; k--) { t.setVolume(k / 5f); SystemClock.sleep(3); } // ~15 ms fade-out
            }
            t.pause();
            t.flush();
            headBase = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            framesWritten = 0;
            t.setVolume(1f);
            t.play();
        } catch (Throwable ignored) { }
        lvlR = lvlW;
        outRms = 0f;
        PriyaBus.outLevel = 0f;
        PriyaBus.mouthWide = 0f;
        PriyaBus.mouthRound = 0f;
        speakEnd = SystemClock.elapsedRealtime();
    }

    /** Writes len bytes in 20 ms slices. Returns false if an interrupt arrived (rest dropped). */
    private boolean writeSliced(AudioTrack t, byte[] b, int len, int myFlush) {
        int off = 0;
        while (off < len && running) {
            if (flushReq != myFlush) return false;
            int n = Math.min(SLICE_BYTES, len - off);
            n &= ~1;
            if (n <= 0) break;
            // level of this slice, stamped with the frame index where it will play
            double sum = 0, dsum = 0;
            int prev = 0;
            for (int i = off; i + 1 < off + n; i += 2) {
                int s = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
                sum += (double) s * s;
                int d = s - prev;
                dsum += (double) d * d;
                prev = s;
            }
            float sliceRms = (float) Math.sqrt(sum / (n / 2));
            lvlFrame[lvlW % RING] = framesWritten;
            lvlVal[lvlW % RING] = sliceRms;
            // mouth shape from the spectrum's "brightness" (RMS of the first difference / RMS): low = o,u (round lips),
            // mid = aa, high = s, sh, e (spread lips, teeth). An estimate, not a phoneme recogniser.
            float wide = 0f, round = 0f;
            if (sliceRms > 250f) {
                float br = (float) (Math.sqrt(dsum / Math.max(1, n / 2 - 1)) / sliceRms);
                float tw = Math.max(0f, Math.min(1f, (br - 0.10f) / 0.20f));
                float tr = Math.max(0f, Math.min(1f, (br - 0.07f) / 0.08f));
                wide = tw * tw * (3f - 2f * tw);
                round = (1f - tr * tr * (3f - 2f * tr)) * Math.min(1f, sliceRms / 2300f);
            }
            lvlWide[lvlW % RING] = wide;
            lvlRound[lvlW % RING] = round;
            lvlW++;
            if (lvlW - lvlR > RING) lvlR = lvlW - RING;
            int w = t.write(b, off, n); // blocking; at most ~one slice of waiting
            if (w < 0) return false;
            off += w;
            framesWritten += w / 2;
            publishLevel(t, SystemClock.elapsedRealtime());
        }
        return true;
    }

    private void playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int handledFlush = flushReq;
        boolean started = false;     // false = track drained, next audio needs a cushion
        boolean hadUnderrun = false; // in the current reply
        long lastAudioEnd = 0;       // when the track last drained
        boolean drainedWhileGen = false;
        while (running) {
            AudioTrack t = track;
            if (t == null) break;
            long now = SystemClock.elapsedRealtime();
            if (flushReq != handledFlush) {
                handledFlush = flushReq;
                doFlush(t, pendingFrames(t) > 0);
                started = false;
                hadUnderrun = false;
                lastAudioEnd = now;
                continue;
            }
            publishLevel(t, now);

            byte[] b;
            try { b = playQueue.poll(10, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { break; }

            if (b == null) {
                if (started && pendingFrames(t) < FPMS * 15) {
                    // the track has (almost) run dry: either the reply ended or the network fell behind
                    started = false;
                    lastAudioEnd = SystemClock.elapsedRealtime();
                    drainedWhileGen = generating;
                    PriyaBus.outLevel = 0f;
                    PriyaBus.mouthWide = 0f;
                    PriyaBus.mouthRound = 0f;
                    try { trackUnderruns = t.getUnderrunCount(); } catch (Throwable ignored) { }
                }
                continue;
            }
            int len = b.length & ~1;
            byte[] data = b;
            if (!started) {
                // Build a cushion before (re)starting, so the voice never chops. Stops early when the reply has
                // fully arrived (short replies start at once) or after a timeout.
                boolean newReply = SystemClock.elapsedRealtime() - lastAudioEnd > 600 || lastAudioEnd == 0;
                if (!newReply && drainedWhileGen) {
                    // audio of the SAME reply came back after the speaker ran dry = a real underrun
                    hadUnderrun = true;
                    underruns++;
                    cushionMs = Math.min(CUSHION_MAX_MS, cushionMs + 100);
                    android.util.Log.w(TAG, "underrun #" + underruns + " (AudioTrack " + trackUnderruns + "), cushion -> " + cushionMs + " ms");
                } else if (newReply && lastAudioEnd != 0) {
                    if (!hadUnderrun) cushionMs = Math.max(CUSHION_MIN_MS, cushionMs - 50);
                    android.util.Log.i(TAG, "new reply; underruns=" + underruns + " AudioTrack=" + trackUnderruns + " cushion=" + cushionMs + " ms");
                    hadUnderrun = false;
                }
                drainedWhileGen = false;
                int want = (newReply ? cushionMs : Math.max(150, cushionMs)) * FPMS * 2;
                long until = SystemClock.elapsedRealtime() + want / (FPMS * 2) + 150;
                int clen = 0;
                clen = append(clen, b, len);
                boolean cut = false;
                while (clen < want && running && SystemClock.elapsedRealtime() < until) {
                    if (flushReq != handledFlush) { cut = true; break; }
                    byte[] more;
                    try { more = playQueue.poll(10, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { more = null; }
                    if (more != null) clen = append(clen, more, more.length & ~1);
                    else if (!generating && playQueue.isEmpty()) break; // whole reply is here
                }
                if (cut || !running) continue;
                fadeIn(cushion, clen, newReply ? 10 : 3); // from silence only: no click
                data = cushion;
                len = clen;
                started = true;
            }
            if (!writeSliced(t, data, len, handledFlush)) continue; // interrupted: loop top flushes
        }
    }

    private int append(int clen, byte[] src, int n) {
        if (clen + n > cushion.length) {
            byte[] c = new byte[Math.max(cushion.length * 2, clen + n)];
            System.arraycopy(cushion, 0, c, 0, clen);
            cushion = c;
        }
        System.arraycopy(src, 0, cushion, clen, n);
        return clen + n;
    }

    private static float rmsBytes(byte[] b) {
        int n = b.length / 2;
        if (n == 0) return 0f;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int s = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
            sum += (double) s * s;
        }
        return (float) Math.sqrt(sum / n);
    }

    // ------------------------------------------------------------------ audio routing

    /** HD mode plays on the media/assistant stream: make sure it is audible (only raises a very low volume). */
    private void ensureMediaVolume() {
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < max * 0.3) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (int) (max * 0.6), 0);
            }
        } catch (Exception ignored) { }
    }

    private synchronized void setCommMode(boolean on) {
        if (am == null || on == commMode) return;
        commMode = on;
        try {
            if (on) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                if (forceSpeaker && !headsetConnected()) am.setSpeakerphoneOn(true);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                if (am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) < max * 0.6) {
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (int) (max * 0.8), 0);
                }
            } else {
                am.setSpeakerphoneOn(false);
                am.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Exception ignored) { }
    }

    private boolean headsetConnected() {
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    // ------------------------------------------------------------------ microphone

    private int readFrame(short[] frame) {
        int off = 0;
        while (off < frame.length && running) {
            AudioRecord r = rec;
            if (r == null) return -1;
            int n = r.read(frame, off, frame.length - off);
            if (n < 0) return n;
            off += n;
        }
        return off;
    }

    private static byte[] toBytes(short[] f) {
        byte[] b = new byte[f.length * 2];
        for (int i = 0; i < f.length; i++) {
            b[2 * i] = (byte) (f[i] & 0xff);
            b[2 * i + 1] = (byte) ((f[i] >> 8) & 0xff);
        }
        return b;
    }

    private final byte[] sendBuf = new byte[FRAME * 2 * 2];
    private int sendLen = 0;

    private void emit(MicSink sink, byte[] frameBytes) {
        System.arraycopy(frameBytes, 0, sendBuf, sendLen, frameBytes.length);
        sendLen += frameBytes.length;
        if (sendLen >= sendBuf.length) {
            sink.onSpeechChunk(sendBuf, sendLen);
            sendLen = 0;
        }
    }

    private void flushSend(MicSink sink) {
        if (sendLen > 0) {
            sink.onSpeechChunk(sendBuf, sendLen);
            sendLen = 0;
        }
    }

    // ------------------------------------------------------------------ voice lock (owner's voice only)

    private float ownerF0 = 0f; // 0 = voice lock off
    /** MFCC voiceprint (null = old pitch-only lock or lock off). */
    private volatile SpeakerVerifier verifier = null;
    private volatile float acceptConf = 0.5f;
    /** Confidence of the last utterance check (shown in Settings / debugging). */
    public static volatile float lastConfidence = -1f;

    /** Pitch-only lock (old voiceprints): f0 = owner pitch, 0 = off. */
    public void setVoiceLock(float f0) { ownerF0 = f0; verifier = null; }

    /**
     * Voice lock with the MFCC voiceprint. Pitch stays one of the features.
     * @param strictness 0 low, 1 medium, 2 high
     */
    public void setVoiceLock(boolean on, SpeakerVerifier v, float fallbackF0, int strictness) {
        if (!on) { ownerF0 = 0f; verifier = null; return; }
        verifier = v;
        acceptConf = SpeakerVerifier.threshold(strictness);
        ownerF0 = v != null && v.ownerPitch() > 0f ? v.ownerPitch() : (fallbackF0 > 0f ? fallbackF0 : (v != null ? 150f : 0f));
    }

    private boolean lockOn() { return ownerF0 > 0f || verifier != null; }

    /** Pitch (Hz) of a 40 ms window by normalised autocorrelation; 0 when unvoiced. */
    static float pitch(short[] prev, short[] cur) {
        // work at 8 kHz (average sample pairs): 4x cheaper, so the audio threads never starve
        int n = (prev.length + cur.length) / 2;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            int a = 2 * i, b = a + 1;
            float va = a < prev.length ? prev[a] : cur[a - prev.length];
            float vb = b < prev.length ? prev[b] : cur[b - prev.length];
            x[i] = (va + vb) * 0.5f;
        }
        double e0 = 0;
        for (float v : x) e0 += v * v;
        if (e0 < 5e5) return 0f;
        final int SR = IN_RATE / 2;
        int minLag = SR / 450, maxLag = SR / 65;
        double best = 0; int bestLag = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double c = 0, e1 = 0, e2 = 0;
            for (int i = 0; i + lag < n; i++) { c += x[i] * x[i + lag]; e1 += x[i] * x[i]; e2 += x[i + lag] * x[i + lag]; }
            double r = c / Math.sqrt(e1 * e2 + 1e-9);
            if (r > best) { best = r; bestLag = lag; }
        }
        return (best > 0.55 && bestLag > 0) ? (float) SR / bestLag : 0f;
    }

    static float median(java.util.List<Float> v) {
        if (v.isEmpty()) return 0f;
        java.util.List<Float> c = new java.util.ArrayList<>(v);
        java.util.Collections.sort(c);
        return c.get(c.size() / 2);
    }

    /** Owner check: MFCC voiceprint when enrolled (pitch is one of its features), else the old pitch range. */
    private boolean ownerVoice(java.util.List<Float> pitches, java.util.List<float[]> mfcc) {
        if (!lockOn()) return true;
        SpeakerVerifier v = verifier;
        if (v != null && mfcc != null && mfcc.size() >= 4) {
            float c = v.confidence(mfcc, median(pitches));
            lastConfidence = c;
            return c >= acceptConf;
        }
        return ownerVoice(pitches);
    }

    /** Barge-in check while Priya speaks: owner voice at a raised threshold (verifier) / normal pitch range. */
    private boolean bargeOwner(java.util.List<Float> pitches, java.util.List<float[]> mfcc) {
        SpeakerVerifier v = verifier;
        if (v != null) {
            if (mfcc == null || mfcc.size() < 10) return false;
            float c = v.confidence(mfcc, median(pitches));
            lastConfidence = c;
            return c >= Math.min(0.9f, acceptConf + 0.1f);
        }
        return ownerVoice(pitches);
    }

    private boolean ownerVoice(java.util.List<Float> pitches) {
        if (ownerF0 <= 0f) return true;
        float m = median(pitches);
        if (m <= 0f) return false;
        float r = m / ownerF0;
        return r > 0.70f && r < 1.45f; // wide: a command said louder/excited raises the pitch
    }

    /**
     * Records one enrollment phrase through the SAME mic path as live listening (voice-communication source +
     * NS/AGC), so the voiceprint matches what the live check hears. Returns null on mic failure.
     */
    public static SpeakerVerifier.Phrase recordPhrase(int seconds) {
        AudioRecord r = null;
        NoiseSuppressor ns = null;
        AutomaticGainControl agc = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int[] sources = {MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC};
            for (int src : sources) {
                try {
                    AudioRecord t = new AudioRecord(src, IN_RATE, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 2, IN_RATE));
                    if (t.getState() == AudioRecord.STATE_INITIALIZED) { r = t; break; }
                    t.release();
                } catch (Exception ignored) { }
            }
            if (r == null) return null;
            try { if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(r.getAudioSessionId()); if (ns != null) ns.setEnabled(true); } } catch (Exception ignored) { }
            try { if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(r.getAudioSessionId()); if (agc != null) agc.setEnabled(true); } } catch (Exception ignored) { }
            r.startRecording();
            short[] prev = new short[FRAME], cur = new short[FRAME];
            SpeakerVerifier.Phrase ph = new SpeakerVerifier.Phrase();
            float floor = 300f;
            int i = 0;
            long end = SystemClock.elapsedRealtime() + seconds * 1000L;
            while (SystemClock.elapsedRealtime() < end) {
                int off = 0;
                while (off < FRAME) { int k = r.read(cur, off, FRAME - off); if (k < 0) return null; off += k; }
                double sum = 0;
                for (short v : cur) sum += (double) v * v;
                float rms = (float) Math.sqrt(sum / FRAME);
                if (i++ < 10) floor = Math.max(120f, Math.min(floor, rms)); // first 200 ms: room noise
                if (rms > Math.max(MIN_THR, floor * 2.5f)) {
                    ph.mfcc.add(SpeakerVerifier.mfcc(prev, cur));
                    float f = pitch(prev, cur);
                    if (f > 0) ph.pitch.add(f);
                }
                short[] t = prev; prev = cur; cur = t;
            }
            return ph;
        } catch (Throwable e) {
            return null;
        } finally {
            try { if (ns != null) ns.release(); } catch (Throwable ignored) { }
            try { if (agc != null) agc.release(); } catch (Throwable ignored) { }
            try { if (r != null) { r.stop(); r.release(); } } catch (Throwable ignored) { }
        }
    }

    private void micLoop(MicSink sink) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] frame = new short[FRAME];
        short[] prevFrame = new short[FRAME];
        ArrayDeque<byte[]> preroll = new ArrayDeque<>();
        java.util.List<byte[]> pending = new java.util.ArrayList<>();
        java.util.List<Float> uttPitch = new java.util.ArrayList<>();
        java.util.ArrayDeque<Float> recentPitch = new java.util.ArrayDeque<>();
        java.util.List<float[]> uttMfcc = new java.util.ArrayList<>();
        java.util.ArrayDeque<float[]> recentMfcc = new java.util.ArrayDeque<>();
        boolean ownerUtt = false;
        boolean inSpeech = false, deciding = false, rejected = false;
        int uttFrames = 0;
        long lastStranger = 0;
        int loud = 0;
        int bargeLoud = 0;
        int hang = 0;
        float floor = 300f;
        float smooth = 0f;

        try {
            rec.startRecording();
        } catch (Exception e) {
            return;
        }

        while (running) {
            int n;
            try { n = readFrame(frame); } catch (Throwable t) { break; }
            if (n <= 0) {
                if (!running) break;
                SystemClock.sleep(10);
                continue;
            }
            double sum = 0;
            for (int i = 0; i < n; i++) sum += (double) frame[i] * frame[i];
            float rms = (float) Math.sqrt(sum / n);
            smooth += (Math.min(1f, rms / 6000f) - smooth) * 0.35f;
            PriyaBus.micLevel = smooth;
            boolean needPitch = lockOn() && rms > MIN_THR && (deciding || ownerUtt || !inSpeech || isSpeaking());
            float f0 = needPitch ? pitch(prevFrame, frame) : 0f;
            float[] mf = (needPitch && verifier != null) ? SpeakerVerifier.mfcc(prevFrame, frame) : null;
            System.arraycopy(frame, 0, prevFrame, 0, FRAME);
            if (f0 > 0) { recentPitch.addLast(f0); if (recentPitch.size() > 12) recentPitch.removeFirst(); }
            if (mf != null) { recentMfcc.addLast(mf); if (recentMfcc.size() > 25) recentMfcc.removeFirst(); }

            boolean speaking = isSpeaking();

            if (speaking && strictEcho) {
                // Barge-in (roka-toki): a clearly loud, sustained voice (the owner's, if voice lock is on) cuts Priya off.
                if (outRms > 600f) {
                    // learn how loud her own voice comes back into the mic: the LOWEST ratio seen is pure echo,
                    // the owner's voice only ever raises it (so the threshold follows her volume and the room)
                    float ratio = rms / Math.max(outRms, 1f);
                    couple += (ratio - couple) * (ratio < couple ? 0.15f : 0.004f);
                    couple = Math.max(0.05f, Math.min(2.5f, couple));
                }
                float bargeThr = legacyBarge
                        ? Math.max(MIN_THR * 5f, Math.max(floor * mult * 6f, outRms * (natural ? 2.4f : 1.5f)))
                        : Math.max(MIN_THR * bargeBaseMul, Math.max(floor * mult * bargeFloorMul, couple * outRms * bargeEchoMul + floor * 1.5f));
                boolean grace = SystemClock.elapsedRealtime() - replyStart < bargeGraceMs;
                bargeLoud = (!grace && rms > bargeThr) ? bargeLoud + 1 : Math.max(0, bargeLoud - 2);
                // sustained (~0.7 s) loud speech, and with voice lock: the owner's voice at a STRICTER confidence
                // than normal listening, so her own echo through the speaker can't cut her off.
                if (bargeLoud >= bargeNeed && (!lockOn() || bargeOwner(new java.util.ArrayList<>(recentPitch), new java.util.ArrayList<>(recentMfcc)))) {
                    bargeLoud = 0;
                    bargeIn();
                    inSpeech = true; deciding = false; rejected = false; uttFrames = 0; ownerUtt = false;
                    hang = hangFor(0);
                    loud = 0;
                    PriyaBus.userTalking = true;
                    sink.onSpeechStart();
                    for (byte[] b : preroll) emit(sink, b);
                    preroll.clear();
                    emit(sink, toBytes(frame));
                    continue;
                }
                preroll.addLast(toBytes(frame));
                if (preroll.size() > PREROLL_FRAMES) preroll.removeFirst();
                if (inSpeech) {
                    inSpeech = false;
                    if (!deciding && !rejected) { flushSend(sink); sink.onSpeechEnd(); }
                    deciding = false; rejected = false; ownerUtt = false; pending.clear();
                    PriyaBus.userTalking = false;
                }
                loud = 0;
                hang = 0;
                sendLen = 0;
                continue;
            }
            bargeLoud = 0;

            float thr = Math.max(MIN_THR, floor * mult);
            boolean voice = rms > thr;

            if (!inSpeech) {
                floor = Math.min(5000f, Math.max(120f, floor * 0.99f + rms * 0.01f));
                preroll.addLast(toBytes(frame));
                if (preroll.size() > PREROLL_FRAMES) preroll.removeFirst();
                loud = voice ? loud + 1 : 0;
                if (loud >= 3) {
                    inSpeech = true;
                    hang = hangFor(0);
                    loud = 0;
                    uttFrames = 0;
                    rejected = false;
                    ownerUtt = false;
                    if (lockOn()) {
                        // hold the audio until we know whose voice it is (~250-400 ms); then it streams instantly
                        deciding = true;
                        pending.clear();
                        pending.addAll(preroll);
                        uttPitch.clear();
                        uttPitch.addAll(recentPitch);
                        uttMfcc.clear();
                        // only the pre-roll's own frames (the last few), not older room noise
                        int skip = Math.max(0, recentMfcc.size() - 6), q = 0;
                        for (float[] m : recentMfcc) if (q++ >= skip) uttMfcc.add(m);
                    } else {
                        deciding = false;
                        PriyaBus.userTalking = true;
                        sink.onSpeechStart();
                        for (byte[] b : preroll) emit(sink, b);
                    }
                    preroll.clear();
                }
            } else {
                uttFrames++;
                boolean ended = false;
                if (voice) hang = hangFor(uttFrames);
                else if (--hang <= 0) ended = true;

                if (deciding) {
                    pending.add(toBytes(frame));
                    if (f0 > 0) uttPitch.add(f0);
                    if (mf != null) uttMfcc.add(mf);
                    boolean enough = verifier != null
                            ? (uttMfcc.size() >= 20 || (ended && uttMfcc.size() >= 6))
                            : (uttPitch.size() >= 12 || (ended && uttPitch.size() >= 4));
                    if (enough) {
                        deciding = false;
                        if (ownerVoice(uttPitch, uttMfcc)) {
                            ownerUtt = verifier != null;
                            PriyaBus.userTalking = true;
                            sink.onSpeechStart();
                            for (byte[] b : pending) emit(sink, b);
                        } else {
                            rejected = true;
                        }
                        pending.clear();
                    } else if (ended) {
                        deciding = false;
                        rejected = true;
                        pending.clear();
                    }
                } else if (!rejected) {
                    emit(sink, toBytes(frame));
                    if (ownerUtt) {
                        if (f0 > 0) uttPitch.add(f0);
                        if (mf != null && uttMfcc.size() < 300) uttMfcc.add(mf);
                    }
                }

                if (ended) {
                    inSpeech = false;
                    if (rejected) {
                        long now = SystemClock.elapsedRealtime();
                        if (uttFrames > 70 && now - lastStranger > 60000) { // a stranger clearly spoke for ~1.4 s+
                            lastStranger = now;
                            sink.onStranger();
                        }
                    } else {
                        flushSend(sink);
                        sink.onSpeechEnd();
                        // daily adaptation: a clearly-owner utterance gently refreshes the voiceprint
                        SpeakerVerifier v = verifier;
                        if (ownerUtt && v != null && uttMfcc.size() >= 40) {
                            try {
                                float c = v.confidence(uttMfcc, median(uttPitch));
                                v.adapt(new java.util.ArrayList<>(uttMfcc), median(uttPitch), c);
                            } catch (Throwable ignored) { }
                        }
                    }
                    ownerUtt = false;
                    rejected = false;
                    deciding = false;
                    PriyaBus.userTalking = false;
                }
            }
        }
        PriyaBus.userTalking = false;
        PriyaBus.micLevel = 0f;
    }
}
