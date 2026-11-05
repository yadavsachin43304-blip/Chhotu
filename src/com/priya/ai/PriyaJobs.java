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
 * live Maya session (if open) gets a SYSTEM NOTE so she can report the real result.
 *
 * v36 duplex: while a background agent works, every step produces a short [PROGRESS] line ({@link #progress}) that is
 * paced ({@link Pacer}: merged when steps come < 1.5 s apart, held while the owner is talking, briefly held while Maya
 * is still speaking) and injected into the live session so she says it in one short sentence; the job notification
 * shows the same line. Final results go through the same pacer, so they never cut the owner off.
 */
final class PriyaJobs {

    interface LiveSink { void note(String systemNote); }
    /** Set by PriyaService while a live session runs. */
    static volatile LiveSink live;

    /**
     * Owner-order gate shared with background agents (written by PriyaService): when the owner's voice was last heard
     * and when a stranger's voice was last heard (0 = never). HeadlessAgent in live mode refuses acting steps when
     * the owner has been silent for more than ORDER_WINDOW_MS or a stranger was heard in the last STRANGER_HOLD_MS.
     */
    static volatile long ownerHeardMs = 0, strangerHeardMs = 0;
    static final long ORDER_WINDOW_MS = 20 * 60 * 1000L;
    static final long STRANGER_HOLD_MS = 10 * 1000L;

    interface Work { String run(Job j) throws Exception; }

    static final class Job {
        final String id, kind, title;
        final long started = System.currentTimeMillis();
        volatile long ended;
        volatile String status = "RUNNING";   // RUNNING | VERIFIED | DONE (agent's own report) | CANCELLED | FAILED
        volatile boolean cancel;              // owner said stop: background agents check this between steps
        volatile String stage = "shuru";
        volatile String result = "";
        volatile int steps;                   // progress lines emitted so far
        volatile boolean phone;               // drives the shared screen (live phone tools must wait)
        volatile long lastNotifyMs;
        Context app;
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
        j.app = app;
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
            if (live != null) {
                try {
                    String how = j.status.equals("DONE")
                            ? "\n(Ye background agent ki apni report hai. File/proof/link report mein ho to 'ho gaya' bolo, warna 'agent ne ye bataya' kaho.)"
                            : j.status.equals("CANCELLED") ? "\n(Owner ke kehne par roka gaya; bas ek chhoti line mein batao ki ruk gaya.)"
                            : "\n(Sirf jo VERIFIED hai wahi 'ho gaya' bolo.)";
                    Pacer.finalNote(j.id, "[SYSTEM NOTE, user ne nahi bola] Background job " + j.id + " (" + j.title + ") khatam: " + j.status
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

    /**
     * One step of a running background job finished / started: remember it for job_status, show it in the job
     * notification (max ~1/s) and hand it to the live session through the pacer as a [PROGRESS] note.
     */
    static void progress(Job j, String line) {
        if (j == null || line == null || line.trim().isEmpty() || j.cancel) return;
        line = line.trim();
        j.stage = line;
        j.steps++;
        long now = System.currentTimeMillis();
        if (j.app != null && now - j.lastNotifyMs > 1000) {
            j.lastNotifyMs = now;
            try { TaskRunnerService.keepAlive(j.app, j.title + " — " + line); } catch (Throwable ignored) { }
        }
        if (live != null) Pacer.add(j.id, j.title, line);
    }

    /** True while a background job that may drive the phone screen is running. */
    static Job phoneJobRunning() {
        synchronized (JOBS) {
            for (Job j : JOBS.values()) if (j.phone && j.status.equals("RUNNING")) return j;
        }
        return null;
    }

    /**
     * Paces what background jobs tell the live session so Maya does not talk over herself or over the owner.
     *  - progress lines: sent at once when the last note went out >= 1.5 s ago; lines arriving faster are merged
     *    into one sentence ({@link ProgressText#merge});
     *  - while the owner is talking (PriyaBus.userTalking) everything waits;
     *  - while Maya is still speaking, progress waits up to 6 s (then goes, merged);
     *  - a job's final note drops its stale progress lines and goes as soon as the owner is quiet (max hold 30 s).
     */
    static final class Pacer {
        static final long GAP_MS = 1500, SPEAK_HOLD_MS = 6000, FINAL_HOLD_MS = 30000;
        private static final class Item {
            final String job, title, text; final boolean fin; final long at = System.currentTimeMillis();
            Item(String job, String title, String text, boolean fin) { this.job = job; this.title = title; this.text = text; this.fin = fin; }
        }
        private static final List<Item> Q = new ArrayList<>();
        private static long lastSentMs = 0;
        private static java.util.concurrent.ScheduledExecutorService timer;

        private Pacer() {}

        static void add(String job, String title, String line) { put(new Item(job, title, line, false)); }

        static void finalNote(String job, String note) { put(new Item(job, "", note, true)); }

        private static void put(Item it) {
            synchronized (Q) {
                if (it.fin) {
                    for (int i = Q.size() - 1; i >= 0; i--) if (!Q.get(i).fin && Q.get(i).job.equals(it.job)) Q.remove(i);
                } else {
                    int n = 0;                                                 // keep at most 6 waiting lines per job
                    for (Item x : Q) if (!x.fin && x.job.equals(it.job)) n++;
                    if (n >= 6) for (int i = 0; i < Q.size(); i++) if (!Q.get(i).fin && Q.get(i).job.equals(it.job)) { Q.remove(i); break; }
                }
                Q.add(it);
                if (timer == null) {
                    timer = Executors.newSingleThreadScheduledExecutor();
                    timer.scheduleWithFixedDelay(Pacer::tick, 0, 250, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            }
            tick();
        }

        static void tick() {
            String send = null;
            synchronized (Q) {
                if (Q.isEmpty()) return;
                LiveSink s = live;
                if (s == null) { Q.clear(); return; }
                long now = System.currentTimeMillis();
                boolean ownerTalking = PriyaBus.userTalking;
                boolean speaking = PriyaBus.state == PriyaBus.SPEAKING;
                // finals first
                for (int i = 0; i < Q.size(); i++) {
                    Item it = Q.get(i);
                    if (!it.fin) continue;
                    if ((ownerTalking || now - lastSentMs < GAP_MS) && now - it.at < FINAL_HOLD_MS) return;
                    Q.remove(i);
                    send = it.text;
                    break;
                }
                if (send == null) {
                    if (ownerTalking || now - lastSentMs < GAP_MS) return;
                    long oldest = Long.MAX_VALUE;
                    for (Item it : Q) oldest = Math.min(oldest, it.at);
                    if (speaking && now - oldest < SPEAK_HOLD_MS) return;
                    // merge every waiting line, grouped per job (normally only one job runs on the screen)
                    Map<String, List<String>> by = new LinkedHashMap<>();
                    Map<String, String> titles = new LinkedHashMap<>();
                    for (Item it : Q) {
                        if (!by.containsKey(it.job)) by.put(it.job, new ArrayList<String>());
                        by.get(it.job).add(it.text);
                        titles.put(it.job, it.title);
                    }
                    Q.clear();
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, List<String>> e : by.entrySet()) {
                        String m = ProgressText.merge(e.getValue());
                        if (m == null) continue;
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append(by.size() > 1 ? titles.get(e.getKey()) + ": " : "").append(m);
                    }
                    if (sb.length() == 0) return;
                    send = progressNote(sb.toString());
                }
                lastSentMs = now;
            }
            LiveSink s = live;
            if (s != null && send != null) {
                try { s.note(send); } catch (Throwable ignored) { }
            }
        }
    }

    /** The [PROGRESS] note Maya reads out (the live prompt explains the format). */
    static String progressNote(String line) {
        return "[PROGRESS, user ne nahi bola] " + line
                + "\n(Ye background kaam ka step update hai: Sachin ko ABHI ek chhoti line mein bolo, apne shabdon mein, max ~15 shabd, phir chup. "
                + "Ye poora kaam hone ki khabar NAHI hai — 'kaam ho gaya' sirf final SYSTEM NOTE par. Sachin kuch poochh rahe the to pehle unka jawab.)";
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
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Maya kaam / results", NotificationManager.IMPORTANCE_DEFAULT));
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
