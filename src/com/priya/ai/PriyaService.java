package com.priya.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that keeps Priya listening in the background:
 * mic (local VAD gate) -> Gemini Live -> speaker, plus tool calls and memory.
 */
public class PriyaService extends Service implements GeminiLiveClient.Callback, AudioEngine.MicSink {

    public static final String ACTION_STOP = "com.priya.ai.STOP";
    private static final String CHANNEL = "priya_listening";
    private static final int NOTIF_ID = 1;

    private MemoryStore memory;
    private AudioEngine audio;
    private ActionExecutor actions;
    private GeminiLiveClient client;
    private PowerManager.WakeLock wake;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private volatile boolean running = false;
    private volatile boolean thinking = false;
    private String resumeHandle = "";
    private int attempt = 0;
    private int failures = 0;
    private int netRetries = 0;
    private String goodModel = null;
    private boolean goodFull = true;

    private final Object bufLock = new Object();
    private final StringBuilder userBuf = new StringBuilder();
    private final StringBuilder priyaBuf = new StringBuilder();

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        memory = new MemoryStore(this);
        audio = new AudioEngine(this);
        actions = new ActionExecutor(this, memory);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopEverything(false, "Priya band hai. Mic dabao aur baat karo.");
            return START_NOT_STICKY;
        }
        if (intent == null) { // restarted by the OS in the background: cannot reopen the mic from here
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!startForegroundSafe()) return START_NOT_STICKY;
        if (!running) startSession();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (running) stopEverything(false, "Priya band hai. Mic dabao aur baat karo.");
        pool.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ foreground notification

    private boolean startForegroundSafe() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Priya listening",
                    NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, PriyaService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("Priya AI sun rahi hai")
                    .setContentText("Kholne ke liye tap karo")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentIntent(open)
                    .setOngoing(true)
                    .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, 0x80); // FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                startForeground(NOTIF_ID, n);
            }
            return true;
        } catch (Exception e) {
            PriyaBus.state = PriyaBus.ERROR;
            PriyaBus.status = "Foreground service start nahi hua: " + e.getMessage();
            stopSelf();
            return false;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private void startSession() {
        running = true;
        attempt = 0;
        failures = 0;
        netRetries = 0;
        goodModel = null;
        PriyaBus.state = PriyaBus.CONNECTING;
        PriyaBus.status = "Priya connect ho rahi hai…";
        PriyaBus.userText = "";
        PriyaBus.priyaText = "";

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "priya:listen");
        wake.setReferenceCounted(false);
        wake.acquire();

        String echo = audio.start(this, Prefs.strictEcho(this), Prefs.sensitivity(this), Prefs.forceSpeaker(this));
        if (echo == null) {
            stopEverything(true, "Microphone nahi khul paya. Permissions mein Microphone allow karo.");
            return;
        }
        PriyaBus.echoInfo = echo;
        main.post(ticker);
        connect();
    }

    private void stopEverything(boolean error, String message) {
        if (!running && !error) { // already stopped; still make sure the service ends
            stopForeground(true);
            stopSelf();
            return;
        }
        running = false;
        main.removeCallbacksAndMessages(null);
        if (client != null) {
            client.close();
            client = null;
        }
        audio.stop();
        synchronized (bufLock) {
            flushUser();
            flushPriya();
        }
        if (wake != null && wake.isHeld()) wake.release();
        PriyaBus.state = error ? PriyaBus.ERROR : PriyaBus.OFF;
        PriyaBus.status = message;
        PriyaBus.micLevel = 0f;
        PriyaBus.outLevel = 0f;
        stopForeground(true);
        stopSelf();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            int s;
            if (audio.isSpeaking()) s = PriyaBus.SPEAKING;
            else if (thinking) s = PriyaBus.THINKING;
            else if (client != null && client.isReady()) s = PriyaBus.LISTENING;
            else s = PriyaBus.CONNECTING;
            PriyaBus.state = s;
            PriyaBus.outLevel *= 0.85f;
            switch (s) {
                case PriyaBus.SPEAKING: PriyaBus.status = "Priya bol rahi hai…"; break;
                case PriyaBus.THINKING: PriyaBus.status = "Priya soch rahi hai…"; break;
                case PriyaBus.LISTENING:
                    PriyaBus.status = PriyaBus.userTalking ? "Aap bol rahe ho…" : "Priya sun rahi hai…";
                    break;
                default: break; // CONNECTING keeps the detailed text set in connect()/onClosed()
            }
            main.postDelayed(this, 80);
        }
    };

    // ------------------------------------------------------------------ connecting

    private List<String> modelOrder() {
        List<String> order = new ArrayList<>();
        order.add(Prefs.model(this));
        String good = Prefs.lastGoodModel(this);
        if (!good.isEmpty() && !order.contains(good)) order.add(good);
        for (String m : Prefs.MODELS) if (!order.contains(m)) order.add(m);
        return order;
    }

    private void connect() {
        if (!running) return;
        String key = Prefs.apiKey(this);
        if (key.isEmpty()) {
            stopEverything(true, "API key set nahi hai. Menu > API mein Gemini key daalo.");
            return;
        }
        if (client != null) client.close();

        String model;
        boolean full;
        if (goodModel != null) {
            model = goodModel;
            full = goodFull;
        } else {
            List<String> order = modelOrder();
            model = order.get((attempt / 2) % order.size());
            full = attempt % 2 == 0;
        }
        PriyaBus.status = "Connecting: " + model + (full ? "" : " (simple setup)") + "…";
        client = new GeminiLiveClient(key, model, Prefs.voice(this), buildSystemPrompt(), full,
                full ? resumeHandle : "", this);
        client.connect();
    }

    private void scheduleReconnect(long delayMs) {
        main.postDelayed(this::connect, delayMs);
    }

    private boolean offline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm.getActiveNetwork() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private String buildSystemPrompt() {
        String name = Prefs.userName(this);
        String now = new SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", new Locale("en", "IN")).format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("Tum 'Priya' ho — ek dostana female AI voice assistant jo user ke Android phone par live voice se baat karti hai. ");
        sb.append("Hamesha female ki tarah bolo (main karti hoon, bolti hoon). ");
        sb.append("User Hindi ya Hinglish mein bole to usi mein jawab do; English mein bole to English mein. ");
        sb.append("Jawab chhote, natural aur warm rakho (aam taur par 1-3 vakya) jab tak user detail na maange. ");
        sb.append("Phone control ke liye tools use karo: app kholna, YouTube par gana chalana, home/back/scroll, screen par tap, type, call, volume, torch. ");
        sb.append("Call karne, message bhejne ya kuch delete karne jaise kaam se pehle user se ek baar confirm karo. ");
        sb.append("Jab bhi user koi vaada, plan, reminder ya zaroori baat bataye, ya tum koi vaada karo, turant 'remember' tool se save karo (kind=VAADA ya FACT). ");
        sb.append("Purani baat ya vaade ke baare mein poochhe to 'recall' tool se dhundho, aur zarurat ho to khud yaad dilao. ");
        sb.append("Tum hamesha sun rahi ho, isliye agar koi baat clearly tumse nahi ho rahi (TV, doosre log), to bahut chhota jawab do ya ignore karo. ");
        if (name != null && !name.isEmpty()) sb.append("User ka naam ").append(name).append(" hai. ");
        sb.append("Abhi ka samay: ").append(now).append(". ");
        if (PriyaAccessibilityService.get() == null) {
            sb.append("NOTE: Accessibility service abhi OFF hai, isliye tap/type/scroll/YouTube-play tab tak kaam nahi karenge; user ko Menu > Permissions mein on karne ko bolo. ");
        }
        String mem = memory.contextForPrompt();
        if (!mem.isEmpty()) sb.append("\n\n").append(mem);
        return sb.toString();
    }

    // ------------------------------------------------------------------ MicSink

    @Override public void onSpeechStart() { }

    @Override public void onSpeechChunk(byte[] pcm16k, int len) {
        GeminiLiveClient c = client;
        if (c != null) c.sendAudio(pcm16k, len);
    }

    @Override public void onSpeechEnd() {
        GeminiLiveClient c = client;
        if (c != null && c.isReady()) {
            c.sendAudioStreamEnd();
            thinking = true;
        }
    }

    // ------------------------------------------------------------------ Gemini callbacks

    @Override public void onSetupComplete() {
        failures = 0;
        netRetries = 0;
        GeminiLiveClient c = client;
        if (c != null) {
            goodModel = c.model();
            goodFull = c.isFull();
            Prefs.setLastGood(this, goodModel, goodFull);
        }
    }

    @Override public void onAudio(byte[] pcm24k) {
        thinking = false;
        audio.playChunk(pcm24k);
    }

    @Override public void onInputText(String t) {
        if (t == null || t.isEmpty()) return;
        synchronized (bufLock) {
            if (priyaBuf.length() > 0) flushPriya();
            if (userBuf.length() == 0) PriyaBus.priyaText = "";
            userBuf.append(t);
            PriyaBus.userText = lastChars(userBuf, 160);
        }
    }

    @Override public void onOutputText(String t) {
        if (t == null || t.isEmpty()) return;
        synchronized (bufLock) {
            if (userBuf.length() > 0) flushUser();
            priyaBuf.append(t);
            PriyaBus.priyaText = lastChars(priyaBuf, 200);
        }
    }

    @Override public void onTurnComplete() {
        thinking = false;
        synchronized (bufLock) {
            flushUser();
            flushPriya();
        }
    }

    @Override public void onInterrupted() {
        audio.interruptPlayback();
        thinking = false;
        synchronized (bufLock) {
            flushUser();
            flushPriya();
        }
    }

    @Override public void onToolCall(final String id, final String name, final JSONObject args) {
        thinking = true;
        final GeminiLiveClient c = client;
        pool.execute(() -> {
            String result = actions.run(name, args);
            if (c != null) c.sendToolResponse(id, name, result);
        });
    }

    @Override public void onResumeHandle(String handle) { resumeHandle = handle; }

    @Override public void onGoAway() { /* server will close soon; we reconnect with the resume handle */ }

    @Override public void onClosed(int code, String reason, boolean afterSetup) {
        if (!running) return;
        thinking = false;
        audio.interruptPlayback();
        String low = reason == null ? "" : reason.toLowerCase(Locale.ROOT);

        if (afterSetup) {
            PriyaBus.status = "Reconnect ho rahi hai…";
            scheduleReconnect(800);
            return;
        }

        // wrong / missing API key: the HTTP upgrade itself is rejected
        if (low.contains(" 400") || low.contains(" 401") || low.contains(" 403")
                || low.contains("api key not valid") || low.contains("api_key_invalid")
                || low.contains("api key expired")) {
            stopEverything(true, "API key reject hui: " + shorten(reason) + "  (Menu > API mein key check karo)");
            return;
        }
        if (offline() || low.contains("unknownhost") || low.contains("unable to resolve")
                || low.contains("timeout") || low.contains("timed out") || low.contains("unreachable")
                || low.contains("failed to connect") || low.contains("no route")) {
            netRetries++;
            PriyaBus.status = "Internet nahi mil raha, dobara try kar rahi hoon…";
            scheduleReconnect(Math.min(30000, 1500L * netRetries));
            return;
        }

        String err = "Gemini ne connection band kiya (" + code + "): " + shorten(reason);
        failures++;
        if (goodModel != null) {
            if (failures >= 4) { stopEverything(true, err); return; }
            scheduleReconnect(1500L * failures);
            return;
        }
        attempt++;
        if (attempt >= 2 * modelOrder().size()) {
            stopEverything(true, err);
            return;
        }
        scheduleReconnect(400);
    }

    // ------------------------------------------------------------------ memory buffers

    private void flushUser() {
        if (userBuf.length() > 0) {
            memory.addConversation("U", userBuf.toString().trim());
            userBuf.setLength(0);
        }
    }

    private void flushPriya() {
        if (priyaBuf.length() > 0) {
            memory.addConversation("P", priyaBuf.toString().trim());
            priyaBuf.setLength(0);
        }
    }

    private static String lastChars(StringBuilder sb, int n) {
        String s = sb.toString().trim();
        return s.length() <= n ? s : "…" + s.substring(s.length() - n);
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }
}
