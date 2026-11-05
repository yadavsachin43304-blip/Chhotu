package com.priya.ai;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * v33 manager mode: the AI Tools Guide (which app / website is best for which job, and how to drive it on the phone),
 * find_best_tool, and the daily "Roz AI tools seekho" learning job.
 *
 * Catalog source of truth = the editable skill "AI Tools Guide" (seeded from assets/ai_tools_guide.md); the asset is the
 * fallback. Daily notes: files/ai_tools_news.md (append-only); when it grows past the cap the OLDEST notes are moved
 * (appended) to files/ai_tools_news_archive.md — nothing is ever deleted.
 */
final class AiTools {

    private AiTools() {}

    static final String SKILL_NAME = "AI Tools Guide";
    static final String ASSET = "ai_tools_guide.md";
    static final String ACTION_LEARN = "com.priya.ai.AI_LEARN";
    static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");
    private static final int NEWS_CAP = 12000;     // chars kept in the live notes file
    private static final int NEWS_KEEP = 8000;     // after archiving, keep about this much (newest)

    static final class Tool {
        String name = "", app = "", web = "", best = "", keys = "", price = "", steps = "";
        boolean installed;
        int score;
    }

    // ------------------------------------------------------------------ catalog

    static String assetText(Context c) {
        try (InputStream in = c.getAssets().open(ASSET)) {
            return new String(GeminiRest.readAll(in, 512 * 1024), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Skill body for seeding: the asset without its '# AI Tools Guide' title line. */
    static String seedBody(Context c) {
        String t = assetText(c).replace("\r\n", "\n");
        if (t.startsWith("# ")) {
            int nl = t.indexOf('\n');
            t = nl < 0 ? "" : t.substring(nl + 1);
        }
        return t.trim();
    }

    static List<Tool> parse(String text) {
        List<Tool> out = new ArrayList<>();
        if (text == null) return out;
        Tool cur = null;
        for (String raw : text.replace("\r\n", "\n").split("\n")) {
            String l = raw.trim();
            if (l.startsWith("## ")) {
                cur = new Tool();
                cur.name = l.substring(3).trim();
                out.add(cur);
                continue;
            }
            if (cur == null) continue;
            int c = l.indexOf(':');
            if (c <= 0) continue;
            String k = l.substring(0, c).trim().toLowerCase(Locale.ROOT);
            String v = l.substring(c + 1).trim();
            switch (k) {
                case "app": cur.app = v; break;
                case "web": cur.web = v; break;
                case "best": cur.best = v; break;
                case "keys": cur.keys = v; break;
                case "price": cur.price = v; break;
                case "steps": cur.steps = v; break;
                default: break;
            }
        }
        return out;
    }

    /** Sachin's edited skill wins; the built-in asset is the fallback. */
    static List<Tool> catalog(Context c) {
        try {
            SkillStore.Skill s = new SkillStore(c).find(SKILL_NAME);
            if (s != null) {
                List<Tool> l = parse(s.instructions);
                if (l.size() >= 3) return l;
            }
        } catch (Throwable ignored) { }
        return parse(assetText(c));
    }

    static String pkgOf(Tool t) {
        String a = t.app == null ? "" : t.app.trim();
        int sp = a.indexOf(' ');
        if (sp > 0) a = a.substring(0, sp);
        return a.matches("[a-zA-Z][\\w]*(\\.[\\w]+)+") ? a : "";
    }

    static boolean installed(Context c, String pkg) {
        if (pkg.isEmpty()) return false;
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getLaunchIntentForPackage(pkg) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Filler words (Hinglish/English) that must not drive the match ('banana' = to make, not Nano Banana). */
    private static final Set<String> STOP = new java.util.HashSet<>(java.util.Arrays.asList(
            "banana", "banao", "banado", "bana", "karo", "karna", "kardo", "karke", "saath", "mein", "chahiye", "wala", "wali",
            "mera", "meri", "mere", "liye", "aur", "for", "the", "and", "with", "please", "make", "create", "abhi", "jaldi", "kuch", "ek", "ke", "ka", "ki"));

    private static Set<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) return out;
        for (String t : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (t.length() >= 3 && !STOP.contains(t)) out.add(t);
        }
        return out;
    }

    /** find_best_tool: top 2 tools for the task, installed ones preferred, with phone steps + latest learned notes. */
    static String findBest(Context c, String task) {
        task = task == null ? "" : task.trim();
        if (task.isEmpty()) return "Kaam batao (jaise 'YouTube thumbnail banana', 'reel edit', 'voiceover', 'website', 'leads').";
        List<Tool> all = catalog(c);
        if (all.isEmpty()) return "FAILED: AI Tools Guide nahi mila.";
        Set<String> q = tokens(task);
        for (Tool t : all) t.installed = installed(c, pkgOf(t));
        List<Tool> top = rank(all, task, 2);
        if (top.isEmpty()) {
            return "Is kaam ke liye guide mein koi tool match nahi hua. research tool se 'best AI tool for " + task
                    + "' dekho, ya general kaam ke liye ChatGPT/Gemini app use karo.";
        }
        StringBuilder sb = new StringBuilder("BEST TOOLS for \"").append(GeminiRest.clip(task, 120)).append("\":\n");
        int i = 1;
        for (Tool t : top) {
            String pkg = pkgOf(t);
            sb.append(i++).append(". ").append(t.name)
                    .append(pkg.isEmpty() ? " [website]" : (t.installed ? " [INSTALLED: " + pkg + "]" : " [app installed NAHI: " + pkg + "]"))
                    .append("\n   best: ").append(t.best);
            if (!t.web.isEmpty()) sb.append("\n   web: ").append(t.web);
            if (!t.price.isEmpty()) sb.append("\n   price: ").append(t.price);
            sb.append("\n   steps: ").append(t.steps).append('\n');
        }
        String notes = notesAbout(c, q, 3);
        if (!notes.isEmpty()) sb.append("LATEST NOTES (roz seekha):\n").append(notes);
        sb.append("NEXT: Sachin ko ek line mein batao kaunsa tool use kar rahi ho, phir INSTALLED app ho to open_app, warna web par open_link "
                + "(app install karna ho to pehle Sachin se poochho). Steps follow karo, har step read_screen/look_at_screen se verify, "
                + "result save/share karke hi 'ho gaya' bolo. Login/paid plan maange to ruk kar Sachin se poochho — kabhi khud kharid ya subscribe nahi.");
        return sb.toString();
    }

    /** Pure scoring (testable): name hit 6, key hit 3 (word start) / 2, 'best' hit 2, installed +3; top n with score > 0. */
    static List<Tool> rank(List<Tool> all, String task, int n) {
        Set<String> q = tokens(task);
        for (Tool t : all) {
            int s = 0;
            String name = t.name.toLowerCase(Locale.ROOT);
            String keys = " " + t.keys.toLowerCase(Locale.ROOT) + " ";
            String best = t.best.toLowerCase(Locale.ROOT);
            for (String w : q) {
                if (name.contains(w)) s += 6;
                if (keys.contains(" " + w)) s += 3;
                else if (keys.contains(w)) s += 2;
                if (best.contains(w)) s += 2;
            }
            if (s > 0 && t.installed) s += 3;
            t.score = s;
        }
        List<Tool> top = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            Tool b = null;
            for (Tool t : all) if (t.score > 0 && !top.contains(t) && (b == null || t.score > b.score)) b = t;
            if (b != null) top.add(b);
        }
        return top;
    }

    // ------------------------------------------------------------------ learned notes (append-only)

    static File newsFile(Context c) { return new File(c.getFilesDir(), "ai_tools_news.md"); }
    static File archiveFile(Context c) { return new File(c.getFilesDir(), "ai_tools_news_archive.md"); }

    static synchronized String readNews(Context c) {
        File f = newsFile(c);
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            return new String(GeminiRest.readAll(in, 2L * 1024 * 1024), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Appends one dated note; moves the oldest notes to the archive file when the live file passes the cap. */
    static synchronized void appendNote(Context c, String note) {
        if (note == null || note.trim().isEmpty()) return;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd (EEE)", Locale.ROOT);
        f.setTimeZone(IST);
        String entry = "### " + f.format(new Date()) + "\n" + note.trim() + "\n\n";
        try (FileOutputStream o = new FileOutputStream(newsFile(c), true)) {
            o.write(entry.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        String all = readNews(c);
        if (all.length() <= NEWS_CAP) return;
        // split into entries, move oldest to the archive (append), rewrite live file with the newest
        int cut = 0;
        while (all.length() - cut > NEWS_KEEP) {
            int next = all.indexOf("\n### ", cut + 1);
            if (next < 0) break;
            cut = next + 1;
        }
        if (cut <= 0) return;
        String old = all.substring(0, cut), keep = all.substring(cut);
        try (FileOutputStream a = new FileOutputStream(archiveFile(c), true)) {
            a.write(old.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return; // archive failed: keep everything in the live file rather than lose notes
        }
        try (FileOutputStream o = new FileOutputStream(newsFile(c), false)) {
            o.write(keep.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** Newest note(s), clipped, for the live system prompt. */
    static String latestNotes(Context c, int maxChars) {
        String all = readNews(c).trim();
        if (all.isEmpty()) return "";
        int i = all.lastIndexOf("### ");
        String last = i >= 0 ? all.substring(i) : all;
        return GeminiRest.clip(last, maxChars);
    }

    private static String notesAbout(Context c, Set<String> q, int maxLines) {
        String all = readNews(c);
        if (all.isEmpty() || q.isEmpty()) return "";
        String[] lines = all.split("\n");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = lines.length - 1; i >= 0 && n < maxLines; i--) {
            String l = lines[i].trim();
            if (l.isEmpty() || l.startsWith("###")) continue;
            String low = l.toLowerCase(Locale.ROOT);
            for (String w : q) {
                if (low.contains(w)) { sb.append("- ").append(GeminiRest.clip(l.replaceFirst("^[-*•]\\s*", ""), 220)).append('\n'); n++; break; }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ daily learning job

    /** One learning run (background thread): research this week's AI tool news, condense, append. Returns a short report. */
    static String learnNow(Context c) {
        SimpleDateFormat f = new SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH);
        f.setTimeZone(IST);
        String today = f.format(new Date());
        Prefs.setAiLearnTry(c, System.currentTimeMillis());
        String raw;
        try {
            raw = GeminiRest.research(c, "Aaj " + today + " hai. Pichhle 7 din mein kaun se NAYE AI tools aaye ya bade AI apps "
                    + "(ChatGPT, Gemini, Claude, Meta AI, Perplexity, Canva, Leonardo, ElevenLabs, Suno, video editors, website builders, "
                    + "image/video generators) mein kaun se naye features/updates aaye? Sirf wo jo Android phone ya website par India mein "
                    + "use ho sakte hain aur creators / chhote business ke kaam aayein. Har item: tool naam, kya naya hai, kis kaam ka best, "
                    + "free ya paid, link. Jo pakka confirm na ho use mat likho.");
        } catch (Exception e) {
            return "FAILED: research nahi hua — " + e.getMessage();
        }
        String note;
        try {
            note = GeminiRest.text(c, "Tum Maya ki notes likhti ho. Sirf diye gaye research se, kuch naya mat jodo.",
                    "Is research ko 4-7 chhote Hinglish bullet points mein badlo (har line '- ToolNaam: kya naya — kis kaam ka — free/paid'). "
                            + "Kul 900 characters se kam. Koi intro/outro nahi. Agar kuch naya confirm nahi hua to sirf '- Is hafte koi bada confirmed update nahi mila.' likho.\n\n"
                            + GeminiRest.clip(raw, 8000), 90);
        } catch (Exception e) {
            note = GeminiRest.clip(raw, 900);
        }
        note = note == null ? "" : note.trim();
        if (note.isEmpty()) return "FAILED: research khaali aaya.";
        note = GeminiRest.clip(note, 1200);
        appendNote(c, note);
        Prefs.setAiLearnLast(c, System.currentTimeMillis());
        return "VERIFIED: aaj ke AI tools notes save ho gaye.\n" + note;
    }

    private static PendingIntent learnPi(Context c) {
        Intent i = new Intent(c, ScheduleReceiver.class).setAction(ACTION_LEARN);
        return PendingIntent.getBroadcast(c, 0x41494c, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Next hh:mm in Asia/Kolkata strictly after now. */
    static long nextLearnTime(long now, int hour, int minute) {
        Calendar cal = Calendar.getInstance(IST);
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        while (cal.getTimeInMillis() <= now + 5000) cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    /** (Re)arms the daily learning alarm, or cancels it when the toggle is off. A day missed (phone off) runs ~3 min later. */
    static void armLearning(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent p = learnPi(c);
        if (!Prefs.aiLearn(c)) {
            am.cancel(p);
            return;
        }
        long now = System.currentTimeMillis();
        long at = nextLearnTime(now, Prefs.aiLearnHour(c), Prefs.aiLearnMinute(c));
        long last = Prefs.aiLearnLast(c);
        if (last > 0 && now - last > 30L * 3600 * 1000 && now - Prefs.aiLearnTry(c) > 6L * 3600 * 1000) at = Math.min(at, now + 3 * 60 * 1000L);
        try {
            if (Scheduler.exactAllowed(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        }
    }
}
