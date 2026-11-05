package com.priya.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Long background jobs (faceless video etc.). A job never claims success by itself: it ends VERIFIED only when
 * its own check (file exists, size > 0, duration ok) passed. When it ends, the owner gets a notification and the
 * live Priya session (if open) gets a SYSTEM NOTE so she can report the real result.
 */
final class PriyaJobs {

    interface LiveSink { void note(String systemNote); }
    /** Set by PriyaService while a live session runs. */
    static volatile LiveSink live;

    interface Work { String run(Job j) throws Exception; }

    static final class Job {
        final String id, kind, title;
        final long started = System.currentTimeMillis();
        volatile long ended;
        volatile String status = "RUNNING";   // RUNNING | VERIFIED | DONE (agent's own report) | CANCELLED | FAILED
        volatile boolean cancel;              // owner said stop: background agents check this between steps
        volatile String stage = "shuru";
        volatile String result = "";
        Job(String id, String kind, String title) { this.id = id; this.kind = kind; this.title = title; }
        String line() {
            String t = new SimpleDateFormat("d MMM HH:mm", Locale.ROOT).format(new Date(started));
            return id + " [" + status + "] " + title + " — " + (status.equals("RUNNING") ? stage : GeminiRest.clip(result, 300)) + " (" + t + ")";
        }
    }

    static final Map<String, Job> JOBS = new LinkedHashMap<>();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    /** Background tasks started from the live chat run one after another (they may drive the phone screen). */
    private static final ExecutorService EXEC_BG = Executors.newSingleThreadExecutor();
    static final String CHANNEL = "priya_tasks";

    private PriyaJobs() {}

    static Job start(final Context ctx, String kind, String title, final Work w) {
        final Context app = ctx.getApplicationContext();
        String id = kind.substring(0, Math.min(3, kind.length())) + "-" + new SimpleDateFormat("HHmmss", Locale.ROOT).format(new Date());
        final Job j = new Job(id, kind, title);
        synchronized (JOBS) { JOBS.put(id, j); }
        TaskRunnerService.keepAlive(app, title);
        (kind.equals("bg") ? EXEC_BG : EXEC).execute(() -> {
            try {
                String r = j.cancel ? "CANCELLED: shuru hone se pehle hi roka gaya." : w.run(j);
                j.result = r == null ? "" : r;
                if (j.result.startsWith("VERIFIED")) j.status = "VERIFIED";
                else if (j.result.startsWith("CANCELLED")) j.status = "CANCELLED";
                else if (kind.equals("bg") && !j.result.startsWith("FAILED") && !j.result.startsWith("BLOCKED")) j.status = "DONE";
                else j.status = "FAILED";
            } catch (Throwable e) {
                j.status = "FAILED";
                j.result = "FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            j.ended = System.currentTimeMillis();
            notifyOwner(app, (j.status.equals("VERIFIED") || j.status.equals("DONE") ? "✅ " : "⚠️ ") + j.title,
                    GeminiRest.clip(j.result, 600), j.id.hashCode());
            LiveSink s = live;
            if (s != null) {
                try {
                    String how = j.status.equals("DONE")
                            ? "\n(Ye background agent ki apni report hai. File/proof/link report mein ho to 'ho gaya' bolo, warna 'agent ne ye bataya' kaho.)"
                            : j.status.equals("CANCELLED") ? "\n(Owner ke kehne par roka gaya; bas ek chhoti line mein batao ki ruk gaya.)"
                            : "\n(Sirf jo VERIFIED hai wahi 'ho gaya' bolo.)";
                    s.note("[SYSTEM NOTE, user ne nahi bola] Background job " + j.id + " (" + j.title + ") khatam: " + j.status
                            + ". Result: " + GeminiRest.clip(j.result, 900)
                            + "\nSachin ko 1-2 line ka professional report do, abhi ki baat ke beech mein tameez se." + how);
                } catch (Throwable ignored) { }
            }
            TaskRunnerService.releaseIfIdle(app);
        });
        return j;
    }

    static void stage(Job j, String s) {
        j.stage = s;
    }

    static boolean anyRunning() {
        synchronized (JOBS) {
            for (Job j : JOBS.values()) if (j.status.equals("RUNNING")) return true;
        }
        return false;
    }

    static Job get(String id) {
        synchronized (JOBS) {
            if (id == null || id.isEmpty()) {
                Job last = null;
                for (Job j : JOBS.values()) last = j;
                return last;
            }
            return JOBS.get(id.trim());
        }
    }

    /** Owner said stop. Empty id = every running job. Returns what was asked to stop. */
    static String cancel(String id) {
        int n = 0;
        StringBuilder sb = new StringBuilder();
        synchronized (JOBS) {
            for (Job j : JOBS.values()) {
                if (!j.status.equals("RUNNING")) continue;
                if (id != null && !id.trim().isEmpty() && !j.id.equals(id.trim())) continue;
                j.cancel = true;
                n++;
                sb.append(j.id).append(" (").append(j.title).append(") ");
            }
        }
        if (n == 0) return "Koi chalta hua kaam nahi mila" + (id == null || id.trim().isEmpty() ? "." : " (" + id + ").");
        return "CANCEL_REQUESTED: " + sb + "— agle step par ruk jayega (jo step chal raha hai wo poora hone mein kuch second lag sakte hain).";
    }

    static List<Job> all() {
        synchronized (JOBS) { return new ArrayList<>(JOBS.values()); }
    }

    /** Status, optionally waiting up to waitSec for the job to finish. */
    static String status(String id, int waitSec) {
        Job j = get(id);
        if (j == null) return "Koi job nahi mila" + (id == null || id.isEmpty() ? "." : " (" + id + ").");
        long until = System.currentTimeMillis() + Math.max(0, Math.min(waitSec, 600)) * 1000L;
        while (j.status.equals("RUNNING") && System.currentTimeMillis() < until) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        if (j.status.equals("RUNNING")) {
            long sec = (System.currentTimeMillis() - j.started) / 1000;
            return "RUNNING (abhi poora NAHI hua): " + j.title + " — stage: " + j.stage + " (" + sec + " sec se chal raha hai).";
        }
        return j.status + ": " + j.result;
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Priya kaam / results", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    static void notifyOwner(Context c, String title, String text, int id) {
        try {
            ensureChannel(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            PendingIntent open = PendingIntent.getActivity(c, 7, new Intent(c, BrainActivity.class).putExtra("page", "schedules")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, CHANNEL)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build();
            nm.notify(1000 + (id & 0xffff), n);
        } catch (Throwable ignored) { }
    }
}
