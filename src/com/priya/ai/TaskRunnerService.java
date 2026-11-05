package com.priya.ai;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Foreground service for work that must survive without the live voice session:
 * scheduled task runs (headless Gemini agent) and long background jobs (faceless video).
 */
public class TaskRunnerService extends Service {

    static final String ACTION_SCHEDULE = "com.priya.ai.TASK_SCHEDULE";
    static final String ACTION_KEEPALIVE = "com.priya.ai.TASK_KEEPALIVE";
    static final String ACTION_RELEASE = "com.priya.ai.TASK_RELEASE";
    static final String ACTION_LEARN = "com.priya.ai.TASK_LEARN";
    private static final int NOTIF_ID = 2;

    private static final AtomicInteger ACTIVE_RUNS = new AtomicInteger();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wake;

    static void runSchedule(Context c, String id) { runSchedule(c, id, false); }

    /** manual = "run now" from the owner: the schedule's own next time / status are left untouched. */
    static void runSchedule(Context c, String id, boolean manual) {
        Intent i = new Intent(c, TaskRunnerService.class).setAction(ACTION_SCHEDULE).putExtra("id", id).putExtra("manual", manual);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable e) {
            // background start refused (rare with exact alarms): tell the owner instead of failing silently
            JSONObject s = Scheduler.get(c, id);
            String task = s == null ? id : s.optString("task");
            PriyaJobs.notifyOwner(c, "⏰ Scheduled task nahi chal paaya", "\"" + GeminiRest.clip(task, 120)
                    + "\" — Android ne background start roka (" + e.getClass().getSimpleName() + "). Maya app kholo ya Schedules > 'Abhi chalao'.", id.hashCode());
            Scheduler.finished(c, id, "FAILED", "Android ne background start roka: " + e.getMessage(), manual);
        }
    }

    /** Daily "Roz AI tools seekho" run (alarm) or a manual "Abhi seekho" from Settings. */
    static void runLearning(Context c, boolean manual) {
        Intent i = new Intent(c, TaskRunnerService.class).setAction(ACTION_LEARN).putExtra("manual", manual);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable e) {
            // background start refused: try again at the next daily time
            try { AiTools.armLearning(c.getApplicationContext()); } catch (Throwable ignored) { }
        }
    }

    static void keepAlive(Context c, String what) {
        Intent i = new Intent(c, TaskRunnerService.class).setAction(ACTION_KEEPALIVE).putExtra("what", what);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable ignored) { /* job still runs in-process */ }
    }

    static void releaseIfIdle(Context c) {
        if (ACTIVE_RUNS.get() > 0 || PriyaJobs.anyRunning()) return;
        try { c.startService(new Intent(c, TaskRunnerService.class).setAction(ACTION_RELEASE)); } catch (Throwable ignored) { }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void foreground(String text) {
        PriyaJobs.ensureChannel(this);
        PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, BrainActivity.class).putExtra("page", "schedules"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, PriyaJobs.CHANNEL)
                .setContentTitle("Maya kaam kar rahi hai")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, 1 /* FOREGROUND_SERVICE_TYPE_DATA_SYNC */);
        else startForeground(NOTIF_ID, n);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        try {
            foreground(ACTION_SCHEDULE.equals(action) ? "Scheduled task chal raha hai…"
                    : ACTION_LEARN.equals(action) ? "Maya naye AI tools seekh rahi hai…" : "Background kaam: " + (intent == null ? "" : intent.getStringExtra("what")));
        } catch (Throwable e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "priya:tasks");
            wake.setReferenceCounted(false);
            wake.acquire(45 * 60 * 1000L);
        }
        if (ACTION_SCHEDULE.equals(action)) {
            final String id = intent.getStringExtra("id");
            final boolean manual = intent.getBooleanExtra("manual", false);
            ACTIVE_RUNS.incrementAndGet();
            pool.execute(() -> {
                try {
                    runOne(id, manual);
                } finally {
                    ACTIVE_RUNS.decrementAndGet();
                    maybeStop();
                }
            });
        } else if (ACTION_LEARN.equals(action)) {
            final boolean manual = intent.getBooleanExtra("manual", false);
            ACTIVE_RUNS.incrementAndGet();
            pool.execute(() -> {
                try {
                    String r;
                    try { r = AiTools.learnNow(this); } catch (Throwable e) { r = "FAILED: " + e.getMessage(); }
                    if (manual || r.startsWith("VERIFIED")) {
                        PriyaJobs.notifyOwner(this, r.startsWith("VERIFIED") ? "📚 Maya ne aaj naye AI tools seekhe" : "📚 AI tools seekhna fail hua",
                                GeminiRest.clip(r.replaceFirst("^VERIFIED: [^\\n]*\\n?", ""), 600), 0x41494c);
                    }
                } finally {
                    try { AiTools.armLearning(this); } catch (Throwable ignored) { }
                    ACTIVE_RUNS.decrementAndGet();
                    maybeStop();
                }
            });
        } else if (ACTION_RELEASE.equals(action)) {
            maybeStop();
        }
        return START_NOT_STICKY;
    }

    private void runOne(String id, boolean manual) {
        JSONObject s = Scheduler.get(this, id);
        if (s == null) return;
        String st = s.optString("status");
        if (!manual && !"active".equals(st)) return;     // paused / cancelled / done: alarm never runs it
        if (manual && "cancelled".equals(st)) return;     // cancelled: never runs again
        String result;
        String status;
        try {
            String agentId = s.optString("agent_id", "");
            result = agentId.isEmpty() ? new HeadlessAgent(this, s).run() : AgentRunner.runScheduled(this, agentId, manual);
            status = result.startsWith("FAILED") || result.startsWith("BLOCKED") ? "FAILED" : "DONE";
        } catch (Throwable e) {
            result = "FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            status = "FAILED";
        }
        Scheduler.finished(this, id, status, result, manual);
        PriyaJobs.notifyOwner(this, (status.equals("DONE") ? "⏰✅ " : "⏰⚠️ ") + GeminiRest.clip(s.optString("task"), 60),
                GeminiRest.clip(result, 700), id.hashCode());
    }

    private void maybeStop() {
        if (ACTIVE_RUNS.get() > 0 || PriyaJobs.anyRunning()) return;
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        pool.shutdown();
        super.onDestroy();
    }
}
