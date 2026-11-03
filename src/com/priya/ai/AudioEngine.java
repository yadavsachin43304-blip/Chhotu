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
    }

    private static final int IN_RATE = 16000;
    private static final int OUT_RATE = 24000;
    private static final int FRAME = 320;          // 20 ms @ 16 kHz
    private static final int PREROLL_FRAMES = 10;  // 200 ms
    private static final int HANG_FRAMES = 28;     // 560 ms of silence ends the utterance (fast replies)
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
    private float mult = 2.15f;

    public AudioEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.am = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    public boolean isSpeaking() {
        return SystemClock.elapsedRealtime() < speakEnd + TAIL_MS;
    }

    /** @return human readable echo-cancel status, or null if the microphone could not be opened. */
    public String start(MicSink sink, boolean strictEcho, int sensitivity, boolean forceSpeaker) {
        this.strictEcho = strictEcho;
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
                .append(strictEcho ? " | echo guard on" : " | echo guard off");

        int outMin = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(OUT_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(outMin * 2, OUT_RATE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        setCommMode(true); // stays on for the whole session: no audio-route switch at every reply

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

    /** True while the server is still sending audio for the current reply. */
    private volatile boolean generating = false;
    /** After the user cuts Priya off, drop the rest of that old reply. */
    private volatile boolean dropOld = false;

    public void setGenerating(boolean g) {
        generating = g;
        if (!g) dropOld = false;
    }

    private void bargeIn() {
        if (generating) dropOld = true;
        interruptPlayback();
    }

    private volatile float outRms = 0f;
    private volatile long replyStart = 0;

    public void playChunk(byte[] pcm24k) {
        if (!running || pcm24k == null || pcm24k.length == 0 || dropOld) return;
        float r = rmsBytes(pcm24k);
        outRms = Math.max(r, outRms * 0.9f);
        if (SystemClock.elapsedRealtime() > speakEnd + 300) replyStart = SystemClock.elapsedRealtime();
        setCommMode(true);
        long now = SystemClock.elapsedRealtime();
        long ms = pcm24k.length / 2 / (OUT_RATE / 1000);
        speakEnd = Math.max(speakEnd, now) + ms;
        PriyaBus.outLevel = Math.min(1f, rmsBytes(pcm24k) / 7000f);
        playQueue.offer(pcm24k);
    }

    public void interruptPlayback() {
        playQueue.clear();
        playIdle = true;
        speakEnd = SystemClock.elapsedRealtime();
        try {
            if (track != null) {
                track.pause();
                track.flush();
                track.play();
            }
        } catch (Exception ignored) { }
    }

    private volatile boolean playIdle = true;

    private void playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        while (running) {
            byte[] b;
            try {
                b = playQueue.poll(200, TimeUnit.MILLISECONDS);
                if (b == null) { playIdle = true; continue; }
                if (playIdle) {
                    java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
                    acc.write(b, 0, b.length);
                    long until = SystemClock.elapsedRealtime() + 180;
                    while (acc.size() < OUT_RATE * 3 / 10 && SystemClock.elapsedRealtime() < until && running) {
                        byte[] more = playQueue.poll(20, TimeUnit.MILLISECONDS);
                        if (more != null) acc.write(more, 0, more.length);
                    }
                    b = acc.toByteArray();
                    playIdle = false;
                }
            } catch (InterruptedException e) {
                break;
            }
            if (b == null) continue;
            AudioTrack t = track;
            if (t == null) break;
            int off = 0;
            while (off < b.length && running) {
                int w = t.write(b, off, b.length - off);
                if (w < 0) break;
                off += w;
            }
        }
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

    private void micLoop(MicSink sink) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] frame = new short[FRAME];
        ArrayDeque<byte[]> preroll = new ArrayDeque<>();
        boolean inSpeech = false;
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
            int n = readFrame(frame);
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

            boolean speaking = isSpeaking();

            if (speaking && strictEcho) {
                // Barge-in (roka-toki): a clearly loud, sustained voice cuts Priya off right away.
                // Priya's own voice comes back through the speaker, so the bar sits well above
                // both the room noise and her current output level, after a short grace period.
                float bargeThr = Math.max(MIN_THR * 5f, Math.max(floor * mult * 6f, outRms * 1.3f));
                boolean grace = SystemClock.elapsedRealtime() - replyStart < 900;
                bargeLoud = (!grace && rms > bargeThr) ? bargeLoud + 1 : Math.max(0, bargeLoud - 2);
                if (bargeLoud >= 25) { // ~500 ms of clearly louder speech
                    bargeLoud = 0;
                    bargeIn();
                    inSpeech = true;
                    hang = HANG_FRAMES;
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
                    flushSend(sink);
                    sink.onSpeechEnd();
                    PriyaBus.userTalking = false;
                }
                loud = 0;
                hang = 0;
                sendLen = 0;
                continue;
            }
            bargeLoud = 0;

            float thr = Math.max(MIN_THR, floor * mult);
            if (speaking) thr *= 2.5f; // barge-in only with a clearly louder voice
            boolean voice = rms > thr;

            if (!inSpeech) {
                floor = Math.min(5000f, Math.max(120f, floor * 0.99f + rms * 0.01f));
                preroll.addLast(toBytes(frame));
                if (preroll.size() > PREROLL_FRAMES) preroll.removeFirst();
                loud = voice ? loud + 1 : 0;
                if (loud >= 3) {
                    inSpeech = true;
                    hang = HANG_FRAMES;
                    loud = 0;
                    PriyaBus.userTalking = true;
                    sink.onSpeechStart();
                    for (byte[] b : preroll) emit(sink, b);
                    preroll.clear();
                }
            } else {
                emit(sink, toBytes(frame));
                if (voice) {
                    hang = HANG_FRAMES;
                } else if (--hang <= 0) {
                    inSpeech = false;
                    flushSend(sink);
                    sink.onSpeechEnd();
                    PriyaBus.userTalking = false;
                }
            }
        }
        PriyaBus.userTalking = false;
        PriyaBus.micLevel = 0f;
    }
}
