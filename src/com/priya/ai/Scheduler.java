package com.priya.ai;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Scheduled tasks: stored in files/schedules.json, fired by exact alarms, re-armed after boot.
 * Each schedule keeps the owner's EXACT task text and the tool scopes the owner allowed at creation.
 * Run history: files/schedule_runs.jsonl (append-only, never deleted).
 */
final class Scheduler {

    static final String ACTION_RUN = "com.priya.ai.RUN_SCHEDULE";
    static final String[] SCOPES = {"phone", "content", "messages", "coding"};
    static final String[] DAYS = {"sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};

    private Scheduler() {}

    // ------------------------------------------------------------------ storage

    private static File file(Context c) { return new File(c.getFilesDir(), "schedules.json"); }
    private static File runsFile(Context c) { return new File(c.getFilesDir(), "schedule_runs.jsonl"); }

    static synchronized JSONArray load(Context c) {
        try (FileInputStream in = new FileInputStream(file(c))) {
            return new JSONArray(new String(GeminiRest.readAll(in, 4L * 1024 * 1024), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static synchronized void store(Context c, JSONArray a) {
        try (FileOutputStream o = new FileOutputStream(file(c))) {
            o.write(a.toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    static synchronized JSONObject get(Context c, String id) {
        JSONArray a = load(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optString("id").equalsIgnoreCase(id.trim())) return o;
        }
        return null;
    }

    static synchronized void put(Context c, JSONObject s) {
        JSONArray a = load(c);
        boolean found = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optString("id").equals(s.optString("id"))) {
                try { a.put(i, s); } catch (Exception ignored) { }
                found = true;
                break;
            }
        }
        if (!found) a.put(s);
        store(c, a);
    }

    static synchronized void logRun(Context c, String id, String task, String status, String result) {
        try (FileOutputStream o = new FileOutputStream(runsFile(c), true)) {
            JSONObject r = new JSONObject().put("id", id).put("task", GeminiRest.clip(task, 120)).put("at", System.currentTimeMillis())
                    .put("status", status).put("result", GeminiRest.clip(result, 1500));
            o.write((r.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    static List<JSONObject> recentRuns(Context c, int n) {
        List<JSONObject> out = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(runsFile(c))) {
            String[] lines = new String(GeminiRest.readAll(in, 8L * 1024 * 1024), StandardCharsets.UTF_8).split("\n");
            for (int i = lines.length - 1; i >= 0 && out.size() < n; i--) {
                try { out.add(new JSONObject(lines[i])); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ------------------------------------------------------------------ create

    /** Creates a schedule. Returns the stored object, or throws IllegalArgumentException with a human message. */
    static JSONObject create(Context c, String task, String repeat, int hour, int minute, String date, String weekday,
                             List<String> scopes, String recipients) {
        task = task == null ? "" : task.trim();
        if (task.isEmpty()) throw new IllegalArgumentException("Task ka text chahiye.");
        if (ActionExecutor.forbidden(task)) throw new IllegalArgumentException("BLOCKED: is task mein delete/remove/reset jaisa kaam hai — Priya ye schedule nahi karti.");
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) throw new IllegalArgumentException("Time galat hai (HH:MM 24h).");
        repeat = repeat == null ? "once" : repeat.toLowerCase(Locale.ROOT);
        if (!repeat.equals("daily") && !repeat.equals("weekly")) repeat = "once";
        int dow = -1;
        if (repeat.equals("weekly")) {
            String w = weekday == null ? "" : weekday.toLowerCase(Locale.ROOT).trim();
            for (int i = 0; i < DAYS.length; i++) if (!w.isEmpty() && (DAYS[i].startsWith(w.length() >= 3 ? w.substring(0, 3) : w))) dow = i + 1;
            if (w.startsWith("som")) dow = Calendar.MONDAY;
            if (w.startsWith("mang")) dow = Calendar.TUESDAY;
            if (w.startsWith("budh")) dow = Calendar.WEDNESDAY;
            if (w.startsWith("guru") || w.startsWith("veer")) dow = Calendar.THURSDAY;
            if (w.startsWith("shuk")) dow = Calendar.FRIDAY;
            if (w.startsWith("shan")) dow = Calendar.SATURDAY;
            if (w.startsWith("rav") || w.startsWith("itv")) dow = Calendar.SUNDAY;
            if (dow < 0) throw new IllegalArgumentException("Weekly ke liye din batao (monday..sunday).");
        }
        long dateMs = -1;
        if (repeat.equals("once") && date != null && !date.trim().isEmpty()) {
            try {
                Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(date.trim());
                dateMs = d.getTime();
            } catch (Exception e) {
                throw new IllegalArgumentException("Date YYYY-MM-DD mein do.");
            }
        }
        JSONArray sc = new JSONArray();
        if (scopes != null) for (String s : scopes) for (String ok : SCOPES) if (ok.equalsIgnoreCase(s.trim())) sc.put(ok);
        boolean msg = false;
        for (int i = 0; i < sc.length(); i++) if ("messages".equals(sc.optString(i))) msg = true;
        if (msg && (recipients == null || recipients.trim().isEmpty())) {
            throw new IllegalArgumentException("'messages' scope ke liye recipients (naam/number/email) batana zaroori hai — sirf unhi ko message jayega.");
        }
        try {
            JSONObject s = new JSONObject()
                    .put("id", "S" + Long.toString(System.currentTimeMillis() % 10000000L, 36).toUpperCase(Locale.ROOT))
                    .put("task", task).put("repeat", repeat).put("hour", hour).put("minute", minute)
                    .put("date", dateMs).put("dow", dow).put("scopes", sc)
                    .put("recipients", recipients == null ? "" : recipients.trim())
                    .put("status", "active").put("created", System.currentTimeMillis())
                    .put("lastRun", 0).put("lastResult", "");
            long next = nextTime(s, System.currentTimeMillis());
            if (next <= 0) throw new IllegalArgumentException("Ye time to nikal chuka hai. Aage ka time/date do.");
            s.put("next", next);
            put(c, s);
            arm(c, s);
            return s;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Schedule save nahi hua: " + e.getMessage());
        }
    }

    static String createFromTool(Context c, JSONObject a) {
        String time = a.optString("time", "").trim();
        int h, m;
        try {
            String[] p = time.split("[:.]");
            h = Integer.parseInt(p[0].trim());
            m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
        } catch (Exception e) {
            return "Time HH:MM (24h) mein do, jaise 09:00 ya 21:30.";
        }
        List<String> scopes = new ArrayList<>();
        JSONArray sa = a.optJSONArray("scopes");
        if (sa != null) for (int i = 0; i < sa.length(); i++) scopes.add(sa.optString(i));
        try {
            JSONObject s = create(c, a.optString("task", ""), a.optString("repeat", "once"), h, m, a.optString("date", ""),
                    a.optString("weekday", ""), scopes, a.optString("recipients", ""));
            return "VERIFIED: schedule save ho gaya — " + describe(s) + (exactAllowed(c) ? "" :
                    "\nNOTE: 'Alarms & reminders' (exact alarm) permission off hai, isliye time thoda aage-peeche ho sakta hai. Sachin Menu > Schedules mein Allow kare.");
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    // ------------------------------------------------------------------ timing

    static long nextTime(JSONObject s, long now) {
        Calendar cal = Calendar.getInstance();
        int h = s.optInt("hour"), m = s.optInt("minute");
        String rep = s.optString("repeat");
        if (rep.equals("once")) {
            long d = s.optLong("date", -1);
            if (d > 0) cal.setTimeInMillis(d);
            else cal.setTimeInMillis(now);
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, m);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (d <= 0 && cal.getTimeInMillis() <= now + 5000) cal.add(Calendar.DAY_OF_YEAR, 1);
            return cal.getTimeInMillis() > now + 5000 ? cal.getTimeInMillis() : -1;
        }
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (rep.equals("weekly")) {
            int dow = s.optInt("dow", Calendar.MONDAY);
            while (cal.get(Calendar.DAY_OF_WEEK) != dow || cal.getTimeInMillis() <= now + 5000) cal.add(Calendar.DAY_OF_YEAR, 1);
        } else {
            while (cal.getTimeInMillis() <= now + 5000) cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    static boolean exactAllowed(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    private static PendingIntent pi(Context c, String id) {
        Intent i = new Intent(c, ScheduleReceiver.class).setAction(ACTION_RUN).putExtra("id", id);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void arm(Context c, JSONObject s) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent p = pi(c, s.optString("id"));
        if (!"active".equals(s.optString("status")) || s.optLong("next", -1) <= 0) {
            am.cancel(p);
            return;
        }
        long at = s.optLong("next");
        try {
            if (exactAllowed(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        }
    }

    /** After boot / app update / permission change. */
    static void rearmAll(Context c) {
        JSONArray a = load(c);
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s == null || !"active".equals(s.optString("status"))) continue;
            long next = s.optLong("next", -1);
            if (next < now - 60000) { // missed while phone was off
                try {
                    if (s.optString("repeat").equals("once")) {
                        s.put("status", "missed");
                        s.put("lastResult", "Phone band/restart tha, time nikal gaya — run nahi hua.");
                    } else {
                        s.put("next", nextTime(s, now));
                    }
                } catch (Exception ignored) { }
                changed = true;
            }
            arm(c, s);
        }
        if (changed) store(c, a);
    }

    /** Called when a run finishes: records the result and arms the next occurrence. */
    static void finished(Context c, String id, String status, String result, boolean manual) {
        JSONObject s = get(c, id);
        if (s == null) return;
        if (manual) {
            try { s.put("lastRun", System.currentTimeMillis()); s.put("lastResult", "(test run) " + GeminiRest.clip(result, 600)); } catch (Exception ignored) { }
            put(c, s);
            logRun(c, id, s.optString("task"), status + " (abhi chalaya)", result);
            return;
        }
        try {
            s.put("lastRun", System.currentTimeMillis());
            s.put("lastResult", GeminiRest.clip(result, 600));
            if (s.optString("repeat").equals("once")) {
                if ("active".equals(s.optString("status"))) s.put("status", "done");
                s.put("next", -1);
            } else if ("active".equals(s.optString("status"))) {
                s.put("next", nextTime(s, System.currentTimeMillis() + 60000));
            }
        } catch (Exception ignored) { }
        put(c, s);
        arm(c, s);
        logRun(c, id, s.optString("task"), status, result);
    }

    // ------------------------------------------------------------------ controls

    static String setStatus(Context c, String id, String status) {
        JSONObject s = get(c, id);
        if (s == null) return "Is id ka schedule nahi mila. list_schedules dekho.";
        if (!status.equals("paused") && !status.equals("active") && !status.equals("cancelled")) return "Status paused/active/cancelled mein se do.";
        try {
            s.put("status", status);
            if (status.equals("active")) {
                long n = nextTime(s, System.currentTimeMillis());
                if (n <= 0) return "Ye ek-baar wala task hai aur time nikal chuka hai — naya schedule banao.";
                s.put("next", n);
            }
        } catch (Exception ignored) { }
        put(c, s);
        arm(c, s);
        return "VERIFIED: " + s.optString("id") + " ab " + status + " hai. " + describe(s);
    }

    static String runNow(Context c, String id) {
        JSONObject s = get(c, id);
        if (s == null) return "Is id ka schedule nahi mila.";
        TaskRunnerService.runSchedule(c, s.optString("id"), true);
        return "JOB_STARTED: schedule " + s.optString("id") + " abhi chal raha hai (background). Result notification aur Schedules screen ke log mein aayega — abhi 'ho gaya' mat bolo.";
    }

    static String describe(JSONObject s) {
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM, HH:mm", new Locale("en", "IN"));
        String rep = s.optString("repeat");
        String when = String.format(Locale.ROOT, "%02d:%02d", s.optInt("hour"), s.optInt("minute"));
        if (rep.equals("daily")) when = "roz " + when;
        else if (rep.equals("weekly")) when = "har " + DAYS[Math.max(0, s.optInt("dow", 2) - 1)] + " " + when;
        else when = "ek baar " + when;
        long next = s.optLong("next", -1);
        JSONArray sc = s.optJSONArray("scopes");
        StringBuilder scopes = new StringBuilder("research, likhna, notes");
        if (sc != null) for (int i = 0; i < sc.length(); i++) scopes.append(", ").append(sc.optString(i));
        return s.optString("id") + " [" + s.optString("status") + "] \"" + GeminiRest.clip(s.optString("task"), 160) + "\" — " + when
                + (next > 0 ? " | agla run: " + f.format(new Date(next)) : "")
                + " | allowed: " + scopes
                + (s.optString("recipients").isEmpty() ? "" : " | sirf inko message: " + s.optString("recipients"))
                + (s.optLong("lastRun", 0) > 0 ? " | last: " + GeminiRest.clip(s.optString("lastResult"), 140) : "");
    }

    static String describeAll(Context c) {
        JSONArray a = load(c);
        if (a.length() == 0) return "Koi scheduled task nahi hai.";
        StringBuilder sb = new StringBuilder("SCHEDULES:\n");
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s != null && !"cancelled".equals(s.optString("status"))) sb.append("- ").append(describe(s)).append('\n');
        }
        return sb.toString();
    }
}
