package com.priya.ai;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Executes the function calls Gemini makes (open app, YouTube, phone control, calls, memory...). */
public class ActionExecutor {

    private final Context ctx;
    private final MemoryStore memory;
    private final AudioManager am;
    private final SkillStore skills;

    /** Words Priya must never act on: delete / remove / reset and their cousins (Hindi + English). */
    private static final String[] FORBIDDEN = {
            "delete", "remove", "reset", "uninstall", "erase", "wipe", "format", "factory",
            "clear data", "clear storage", "clear all data", "deactivate", "close account",
            "हटा", "डिलीट", "मिटा", "रीसेट", "अनइंस्टॉल", "hatao", "hata do", "mitao", "mita do"
    };

    static boolean forbidden(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT);
        for (String w : FORBIDDEN) if (t.contains(w)) return true;
        return false;
    }

    private static final String BLOCKED = "BLOCKED: Delete, remove, reset (aur uninstall/format/clear data) Priya ke role ke khilaf hai. "
            + "Ye kaam nahi kiya gaya. User ko pyaar se batao ki ye safety ke liye tum nahi karti, wo chahe to khud kar sakta hai.";

    private static final Map<String, String> ALIASES = new HashMap<>();
    static {
        ALIASES.put("yt", "youtube");
        ALIASES.put("youtubemusic", "ytmusic");
        ALIASES.put("insta", "instagram");
        ALIASES.put("fb", "facebook");
        ALIASES.put("gpay", "googlepay");
        ALIASES.put("whatsap", "whatsapp");
        ALIASES.put("vatsapp", "whatsapp");
        ALIASES.put("gallery", "photos");
        ALIASES.put("camera", "camera");
        ALIASES.put("setting", "settings");
        ALIASES.put("chrome", "chrome");
        ALIASES.put("map", "maps");
        ALIASES.put("playstore", "playstore");
        ALIASES.put("capcutvideoeditor", "capcut");
        ALIASES.put("kepkat", "capcut");
        ALIASES.put("capcat", "capcut");
    }

    /** Lets look_at_screen hand a screenshot to the live Gemini session. */
    public interface ImageSink { void sendImage(byte[] jpeg); }
    private volatile ImageSink imageSink;
    public void setImageSink(ImageSink s) { imageSink = s; }

    // manager-mode plan + coding retry counter
    private final java.util.List<String> plan = new java.util.ArrayList<>();
    private String planGoal = "";
    private int codeFails = 0;
    private static final int MAX_CODE_FAILS = 5;

    public ActionExecutor(Context ctx, MemoryStore memory) {
        this.ctx = ctx.getApplicationContext();
        this.memory = memory;
        this.am = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
        this.skills = new SkillStore(this.ctx);
    }

    /** Runs one tool call and returns a short result string for the model. Never throws. */
    public String run(String name, JSONObject args) {
        try {
            switch (name) {
                case "open_app":        return openApp(args.optString("app_name", ""));
                case "youtube_play":    return youtubePlay(args.optString("query", ""));
                case "phone_control":   return phoneControl(args.optString("action", ""));
                case "click_on_screen": return clickOnScreen(args.optString("text", ""),
                        args.optInt("index", 1), args.optBoolean("long_press", false));
                case "type_text":       return typeText(args.optString("text", ""), args.optString("field", ""),
                        args.optBoolean("press_enter", false));
                case "press_send":      return pressSend(args.optString("expect_text", ""));
                case "read_screen":     return readScreen();
                case "look_at_screen":  return lookAtScreen();
                case "tap_at":          return tapAt(args.optDouble("x", -1), args.optDouble("y", -1),
                        args.optBoolean("long_press", false));
                case "swipe":           return swipeTool(args);
                case "scroll_until":    return scrollUntil(args.optString("text", ""), args.optString("direction", "down"),
                        args.optInt("max_swipes", 8));
                case "wait_for_text":   return waitForText(args.optString("text", ""), args.optInt("timeout_sec", 10));
                case "dismiss_popup":   return dismissPopup();
                case "list_apps":       return listApps(args.optString("filter", ""));
                case "task_plan":       return taskPlan(args.optString("goal", ""), args.optJSONArray("steps"));
                case "task_progress":   return taskProgress(args.optInt("step", 0), args.optString("status", "done"),
                        args.optString("note", ""));
                case "termux_check":    return termuxCheck();
                case "code_write_file": return codeWriteFile(args.optString("path", ""), args.optString("content", ""));
                case "code_read_file":  return codeReadFile(args.optString("path", ""));
                case "code_run":        return codeRun(args.optString("command", ""), args.optString("workdir", ""),
                        args.optInt("timeout_sec", 60));
                case "call_contact":    return callContact(args.optString("name_or_number", ""));
                case "web_search":      return webSearch(args.optString("query", ""));
                case "knowledge":       return knowledge(args.optString("query", ""));
                case "use_skill": {
                    SkillStore.Skill sk = skills.find(args.optString("name", ""));
                    return sk == null ? "Is naam ka skill nahi hai. Skills: \n" + skills.summaryForPrompt()
                            : "SKILL '" + sk.name + "' instructions:\n" + sk.instructions;
                }
                case "create_skill": {
                    String nm = args.optString("name", "");
                    SkillStore.Skill sk = skills.save(nm, args.optString("description", ""), args.optString("instructions", ""));
                    return sk == null ? "Skill save nahi hua." : "Naya skill '" + sk.name + "' save ho gaya. Ab isi ko use karke kaam karo.";
                }
                case "set_alarm":       return setAlarm(args.optInt("hour", -1), args.optInt("minute", 0), args.optString("label", "Priya"));
                case "open_link":       return openLink(args.optString("url", ""));
                case "whatsapp_chat":   return whatsappChat(args.optString("phone", ""), args.optString("text", ""));
                case "remember":
                    memory.addNote(args.optString("kind", "NOTE"), args.optString("text", ""));
                    return "Memory mein save ho gaya.";
                case "recall": {
                    String r = memory.search(args.optString("query", ""), 8);
                    return r.isEmpty() ? "Memory mein kuch nahi mila." : r;
                }
                default:
                    return "Unknown tool: " + name;
            }
        } catch (Exception e) {
            return "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final String NEED_A11Y =
            "Accessibility service band hai. User ko bolo: Menu > Permissions > Accessibility on kare.";

    private boolean launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            PriyaAccessibilityService a = PriyaAccessibilityService.get();
            if (a != null) a.startActivity(i);
            else ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String norm(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ tools

    private String setAlarm(int hour, int minute, String label) {
        if (hour < 0 || hour > 23) return "Alarm ka sahi time (hour 0-23) chahiye.";
        Intent i = new Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); return String.format(Locale.ROOT, "Alarm app ko %02d:%02d ka alarm lagane ki request bheji (clock app ne confirm nahi kiya; zarurat ho to read_screen se dekho).", hour, minute); }
        catch (Exception e) { return "Alarm app nahi khula."; }
    }

    private String openLink(String url) {
        if (url.trim().isEmpty()) return "Link nahi mila.";
        if (!url.startsWith("http")) url = "https://" + url.trim();
        try { ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "Link kholne ki request bheji (read_screen se dekho ki page khula)."; }
        catch (Exception e) { return "Link nahi khula."; }
    }

    private String whatsappChat(String phone, String text) {
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() == 10) digits = "91" + digits;
        String url = "https://wa.me/" + digits + (text.isEmpty() ? "" : "?text=" + Uri.encode(text));
        try {
            if (!launch(new Intent(Intent.ACTION_VIEW, Uri.parse(url)))) return "FAILED: WhatsApp nahi khula.";
        } catch (Exception e) { return "FAILED: WhatsApp nahi khula."; }
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return "UNVERIFIED: WhatsApp kholne ki request bheji, par Accessibility band hai isliye check nahi kar sakti. Message bheja NAHI gaya.";
        boolean inWa = false, typed = text.isEmpty();
        for (int i = 0; i < 16; i++) {
            SystemClock.sleep(500);
            String pkg = PriyaAccessibilityService.currentPackage;
            inWa = pkg.startsWith("com.whatsapp");
            if (inWa && !typed) { String in = a.inputText(); typed = in != null && PriyaAccessibilityService.sameText(in, text); }
            if (inWa && typed) break;
        }
        if (!inWa) return "FAILED: WhatsApp chat screen par nahi khula (abhi app: " + PriyaAccessibilityService.currentPackage
                + "). read_screen se dekho — number galat ho sakta hai ya WhatsApp ne 'invite' dikhaya.";
        if (!typed) return "PARTIAL: WhatsApp chat khula par message box mein text nahi dikha. type_text se likho, phir Sachin ki haan ke baad press_send.";
        return "VERIFIED: WhatsApp chat khula aur message box mein text likha hai. Abhi BHEJA NAHI gaya — Sachin ki haan ke baad press_send karo, tabhi 'bhej diya' bolna.";
    }

    private String openApp(String query) throws Exception {
        if (query.trim().isEmpty()) return "App ka naam nahi mila.";
        String q = norm(query);
        String alias = ALIASES.get(q);
        if (alias != null) q = alias;

        PackageManager pm = ctx.getPackageManager();
        if (query.trim().matches("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")) { // a package name, e.g. com.lemon.lvoverseas
            Intent byPkg = pm.getLaunchIntentForPackage(query.trim());
            if (byPkg != null && launch(byPkg)) {
                SystemClock.sleep(1600);
                return query.trim().equals(PriyaAccessibilityService.currentPackage)
                        ? "VERIFIED: " + query.trim() + " screen par khul gaya."
                        : "UNVERIFIED: " + query.trim() + " kholne ki request bheji, screen par confirm nahi hua. read_screen se check karo.";
            }
        }
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        ResolveInfo best = null;
        String bestLabel = "";
        int bestScore = 0;
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm));
            String n = norm(label);
            int s = 0;
            if (n.equals(q)) s = 100;
            else if (n.startsWith(q)) s = 80;
            else if (n.contains(q)) s = 60;
            else if (n.length() >= 3 && q.contains(n)) s = 50;
            if (s > bestScore) {
                bestScore = s;
                best = ri;
                bestLabel = label;
            }
        }
        if (best == null) return "'" + query + "' naam ka koi app nahi mila.";
        String pkg = best.activityInfo.packageName;
        Intent li = pm.getLaunchIntentForPackage(pkg);
        if (li == null || !launch(li)) return bestLabel + " launch nahi ho paya.";

        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a != null) {
            SystemClock.sleep(1600);
            for (int i = 0; i < 4 && !pkg.equals(PriyaAccessibilityService.currentPackage); i++) SystemClock.sleep(500);
            if (!pkg.equals(PriyaAccessibilityService.currentPackage)) {
                return "FAILED: " + bestLabel + " kholne ki request bheji, par screen par nahi khula (abhi: "
                        + PriyaAccessibilityService.currentPackage + "). "
                        + "Agar app nahi khula to Permissions mein 'Display over other apps' on karna hoga.";
            }
            return "VERIFIED: " + bestLabel + " screen par khul gaya.";
        }
        return "UNVERIFIED: " + bestLabel + " kholne ki request bheji (Accessibility band hai, check nahi kar sakti).";
    }

    private String youtubePlay(String q) throws Exception {
        if (q.trim().isEmpty()) return "Gaane ka naam batao.";
        Intent i = new Intent(Intent.ACTION_SEARCH);
        i.setPackage("com.google.android.youtube");
        i.putExtra("query", q);
        boolean ok = launch(i);
        if (!ok) {
            ok = launch(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))));
        }
        if (!ok) return "YouTube nahi khul paya.";

        if (PriyaAccessibilityService.get() == null) {
            return "YouTube par '" + q + "' ka search khul gaya, par Accessibility service band hai "
                    + "isliye gana khud play nahi hua. Menu > Permissions mein Accessibility on karein.";
        }
        boolean clicked = false;
        for (int attempt = 0; attempt < 7 && !clicked; attempt++) {
            SystemClock.sleep(1200);
            PriyaAccessibilityService a = PriyaAccessibilityService.get();
            if (a == null) break;
            clicked = a.clickFirstVideoResult();
        }
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (!clicked || a == null) {
            return "YouTube par '" + q + "' search khul gaya, par sahi video (bina ad ke) pehchaan nahi paayi. "
                    + "read_screen karke video ka title dekho aur click_on_screen se usi title par tap karo. Ad par tap mat karna.";
        }
        // verify + skip pre-roll ads for up to ~12 s
        boolean playing = false;
        for (int k = 0; k < 12; k++) {
            SystemClock.sleep(1000);
            a = PriyaAccessibilityService.get();
            if (a == null) break;
            a.skipAdIfShown();
            if (!playing) playing = a.videoPlayerOpen();
            if (playing && k >= 5) break;
        }
        return playing ? "VERIFIED: YouTube par '" + q + "' ka video chal raha hai (ad aaya to skip kiya)."
                : "Video par tap kiya par pakka nahi ki player khula. read_screen se check karo, aur zarurat ho to sahi title par tap karo.";
    }

    private String clickOnScreen(String text, int index, boolean longPress) {
        if (forbidden(text)) return BLOCKED;
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        boolean send = !longPress && PriyaAccessibilityService.sendLike(text);
        String inputBefore = send ? a.inputText() : null;
        String sigBefore = a.screenSig();
        String r = a.clickSmart(text, Math.max(0, index - 1), longPress, false);
        if (r.startsWith("OK")) {
            if (send) return r + ". " + a.verifySent(inputBefore, inputBefore == null ? "" : inputBefore.trim());
            return r + ". " + afterEffect(a, sigBefore);
        }
        switch (r) {
            case "FORBIDDEN": return BLOCKED;
            case "AD": return "BLOCKED: Ye ad/sponsored cheez hai — Priya ads par kabhi tap nahi karti. Asli item chuno.";
            case "NOT_FOUND": return "'" + text + "' screen par nahi mila. read_screen se asli label dekho, scroll_until try karo, "
                    + "ya icon ho to look_at_screen karke tap_at se position par tap karo.";
            default: return "'" + text + "' mila par tap nahi ho paya. tap_at se uske @x,y par tap karo.";
        }
    }

    /** Waits briefly and reports honestly whether the screen changed after an action. */
    private static String afterEffect(PriyaAccessibilityService a, String sigBefore) {
        String sig = sigBefore;
        for (int i = 0; i < 6; i++) {
            SystemClock.sleep(250);
            sig = a.screenSig();
            if (!sig.equals(sigBefore)) break;
        }
        if (!sig.equals(sigBefore)) {
            return "SCREEN CHANGED (app: " + PriyaAccessibilityService.currentPackage + "). Kaam pakka hua ya nahi, read_screen se dekh kar hi bolo.";
        }
        return "NO_CHANGE: tap ke baad screen bilkul nahi badli — shayad galat jagah tap hua ya kuch nahi hua. "
                + "Ise 'ho gaya' mat samjho; read_screen/look_at_screen se dekho aur doosra tareeka try karo.";
    }

    private String pressSend(String expect) {
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        return a.pressSendVerified(expect);
    }

    private String typeText(String text, String field, boolean enter) {
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        String r = a.typeInto(text, field, enter);
        if (r.startsWith("OK")) return "Type kar diya." + r.substring(2) + ". (Ye sirf likha hai, BHEJA NAHI — bhejna ho to press_send.)";
        if (r.startsWith("SET_TEXT_UNVERIFIED")) return "FAILED: " + r + ". Field par tap karke dobara type_text karo.";
        if (r.equals("NO_FIELD")) return "Koi text field nahi mila. Pehle search box / input par click_on_screen ya tap_at karo, phir type_text.";
        return "Field mila par text set nahi hua. Field par tap karke dobara try karo.";
    }

    private String readScreen() {
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        String s = a.dumpScreen(4500);
        return s.trim().isEmpty() ? "Screen par padhne layak text nahi mila — look_at_screen se screenshot dekho." : s;
    }

    private String lookAtScreen() {
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        if (android.os.Build.VERSION.SDK_INT < 30) return "Screenshot dekhna Android 11+ par hi hota hai. read_screen use karo.";
        ImageSink sink = imageSink;
        if (sink == null) return "Live session ready nahi hai, read_screen use karo.";
        byte[] jpg = a.screenshotJpeg(720);
        if (jpg == null) return "Screenshot nahi mila (Accessibility ko ek baar OFF karke ON karo taaki screenshot ki permission mile). Abhi read_screen use karo.";
        sink.sendImage(jpg);
        return "Screenshot bhej diya (image dekho). Screen " + a.screenW() + "x" + a.screenH()
                + " px. Kisi cheez par tap karne ke liye image mein uski jagah ka andaza 0-1 fraction mein lo "
                + "(x = left se, y = upar se; jaise x=0.5,y=0.9 = neeche beech) aur tap_at / swipe mein wahi do.";
    }

    /** x/y <= 1.0 are fractions of the screen, larger values are pixels. */
    private static float px(double v, int size) {
        return (float) (v <= 1.0 ? v * size : v);
    }

    private String tapAt(double x, double y, boolean longPress) {
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        if (x < 0 || y < 0) return "x aur y dono chahiye (0-1 fraction ya pixel).";
        float fx = px(x, a.screenW()), fy = px(y, a.screenH());
        String bad = a.unsafeAt(Math.round(fx), Math.round(fy));
        if (bad != null) return bad.startsWith("AD") ? "BLOCKED: wahan ad hai (" + bad + "). Tap nahi kiya." : BLOCKED + " (" + bad + ")";
        String what = a.labelAt(Math.round(fx), Math.round(fy));
        boolean send = !longPress && PriyaAccessibilityService.sendLike(what);
        String inputBefore = send ? a.inputText() : null;
        String sigBefore = a.screenSig();
        boolean ok = longPress ? a.longPress(fx, fy, 900) : a.tap(fx, fy);
        if (!ok) return "FAILED: gesture nahi chala, kuch nahi hua.";
        String head = (longPress ? "Long-press" : "Tap") + " kiya @" + Math.round(fx) + "," + Math.round(fy)
                + (what.isEmpty() ? "" : " ('" + what + "')") + ". ";
        if (send) return head + a.verifySent(inputBefore, inputBefore == null ? "" : inputBefore.trim());
        return head + afterEffect(a, sigBefore);
    }

    private String swipeTool(JSONObject args) {
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        double x1 = args.optDouble("x1", -1), y1 = args.optDouble("y1", -1);
        double x2 = args.optDouble("x2", -1), y2 = args.optDouble("y2", -1);
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return "x1,y1,x2,y2 chahiye (0-1 fraction ya pixel).";
        int dur = Math.max(80, Math.min(args.optInt("duration_ms", 400), 10000));
        int hold = Math.max(0, Math.min(args.optInt("hold_ms", 0), 5000));
        int w = a.screenW(), h = a.screenH();
        float fx1 = px(x1, w), fy1 = px(y1, h), fx2 = px(x2, w), fy2 = px(y2, h);
        if (hold > 0) {
            String bad = a.unsafeAt(Math.round(fx1), Math.round(fy1));
            if (bad != null) return "BLOCKED: drag shuru karne ki jagah par " + bad + " hai.";
        }
        boolean ok = a.drag(fx1, fy1, fx2, fy2, hold, dur);
        SystemClock.sleep(400);
        return ok ? (hold > 0 ? "Hold karke drag kiya" : "Swipe kiya") + " (" + Math.round(fx1) + "," + Math.round(fy1)
                + ") se (" + Math.round(fx2) + "," + Math.round(fy2) + "). Ab look_at_screen/read_screen se result check karo."
                : "Swipe/drag nahi chala.";
    }

    private String scrollUntil(String text, String dir, int max) {
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        if (text.trim().isEmpty()) return "Kya dhundhna hai, wo text do.";
        int n = a.scrollUntil(text, dir, Math.max(1, Math.min(max, 25)));
        return n >= 0 ? "'" + text + "' screen par dikh gaya (" + n + " scroll). Ab click_on_screen karo."
                : "'" + text + "' " + max + " scroll ke baad bhi nahi mila. Doosra naam/search try karo.";
    }

    private String waitForText(String text, int sec) {
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        if (text.trim().isEmpty()) return "Kis text ka intezaar karna hai?";
        sec = Math.max(1, Math.min(sec, 300));
        // several alternatives allowed: "Saved|Done|Export done"
        String[] opts = text.split("\\|");
        long end = SystemClock.uptimeMillis() + sec * 1000L;
        while (true) {
            for (String o : opts) {
                if (!o.trim().isEmpty() && a.textVisible(o.trim())) return "VERIFIED: '" + o.trim() + "' screen par aa gaya.";
            }
            if (SystemClock.uptimeMillis() > end) break;
            SystemClock.sleep(500);
        }
        return "TIMEOUT: " + sec + " sec mein '" + text + "' nahi dikha. read_screen/look_at_screen se dekho kya hua (popup? error?).";
    }

    private String dismissPopup() {
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        String t = a.dismissPopup();
        if (t == null) return "Koi 'Not now/Skip/Close' wala popup button nahi mila. Popup ho to read_screen se dekho; "
                + "X icon ho to look_at_screen + tap_at. Allow/Buy/Install/Subscribe khud mat dabana.";
        SystemClock.sleep(500);
        return "Popup band kiya ('" + t + "' dabaya). read_screen se check karo.";
    }

    private String listApps(String filter) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String f = norm(filter);
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm));
            String pkg = ri.activityInfo.packageName;
            if (!f.isEmpty() && !norm(label).contains(f) && !pkg.contains(filter.trim().toLowerCase(Locale.ROOT))) continue;
            sorted.put(label + " (" + pkg + ")", pkg);
        }
        if (sorted.isEmpty()) return f.isEmpty() ? "Koi app nahi mila." : "'" + filter + "' se milta koi app installed nahi hai.";
        StringBuilder sb = new StringBuilder("Installed apps (" + sorted.size() + "):\n");
        for (String k : sorted.keySet()) {
            if (sb.length() > 4000) { sb.append("…"); break; }
            sb.append(k).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ manager mode

    private synchronized String taskPlan(String goal, org.json.JSONArray steps) {
        plan.clear();
        planGoal = goal;
        codeFails = 0;
        if (steps != null) for (int i = 0; i < steps.length(); i++) {
            String st = steps.optString(i, "").trim();
            if (!st.isEmpty()) plan.add(st);
        }
        if (plan.isEmpty()) return "Plan mein steps do (list).";
        StringBuilder sb = new StringBuilder("PLAN save: " + goal + "\n");
        for (int i = 0; i < plan.size(); i++) sb.append(i + 1).append(". ").append(plan.get(i)).append('\n');
        sb.append("Sachin ko ek line mein plan batao, phir Step 1 shuru karo. Har step ke baad verify karke task_progress call karo.");
        return sb.toString();
    }

    private synchronized String taskProgress(int step, String status, String note) {
        if (plan.isEmpty()) return "Koi plan active nahi. Bade kaam ke liye pehle task_plan banao.";
        if (step < 1 || step > plan.size()) return "Step number 1-" + plan.size() + " ke beech hona chahiye.";
        if ("failed".equals(status)) {
            return "Step " + step + " fail (" + note + "). Recover karo: back / dismiss_popup / doosra label / look_at_screen. "
                    + "Ye step 3 baar fail ho chuka ho to ruk jao aur Sachin ko batao kya dikkat hai.";
        }
        if ("retrying".equals(status)) return "Theek hai, step " + step + " dobara karo: " + plan.get(step - 1);
        if (step == plan.size()) {
            String g = planGoal;
            plan.clear();
            return "SAB STEPS POORE: '" + g + "'. Ab Sachin ko 1-2 line mein report do (kya kiya, result kahan hai). Aur kuch khud se mat karna.";
        }
        return "Step " + step + " done. Agla Step " + (step + 1) + ": " + plan.get(step) + " (chhota sa status bolo aur karo).";
    }

    // ------------------------------------------------------------------ coding (Termux)

    private static final java.util.regex.Pattern DANGER_CMD = java.util.regex.Pattern.compile(
            "(^|[;&|`(\\s])(rm|rmdir|unlink|shred|mkfs[.\\w]*|dd|truncate|wipefs|fdisk|srm)(\\s|$)"
            + "|git\\s+(clean|reset|rm|push\\s+.*--force|branch\\s+-[dD])"
            + "|\\s-delete\\b|--delete|rimraf"
            + "|(pip3?|npm|yarn|pnpm|pkg|apt|apt-get|dpkg)\\s+(uninstall|remove|rm|purge|autoremove|prune|clean|-r|-P)"
            + "|chmod\\s+-R\\s+0+\\s|>\\s*/dev/(sd|block)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final String[] DANGER_CODE = {
            "rm -rf", "rm -r ", "rm -f ", "shutil.rmtree", "os.remove(", "os.unlink(", "os.rmdir(", "os.removedirs(",
            ".unlink(", "unlinksync", "rmsync", "fs.rm(", "fs.rmdir(", "fs.unlink(", "rimraf", ".delete()",
            "deleterecursively", "files.delete", "drop table", "drop database", "truncate table", "format c:",
            "mkfs", "/system/", "su -c"};

    private static boolean dangerousCommand(String cmd) {
        return forbidden(cmd) || DANGER_CMD.matcher(cmd).find();
    }

    private static String dangerousCode(String code) {
        String low = code.toLowerCase(Locale.ROOT);
        for (String d : DANGER_CODE) if (low.contains(d)) return d;
        return null;
    }

    private static final String CODE_BLOCKED = "BLOCKED: Is code/command mein delete/remove/reset jaisa kaam hai — "
            + "ye Priya ke role ke khilaf hai, nahi chalaya. Bina delete wala tareeka likho, ya Sachin ko batao ki ye kaam wo khud karein.";

    /** Turns a user path into an absolute path inside ~/PriyaCode (or /sdcard/PriyaCode). Null if it escapes. */
    private static String safePath(String p) {
        if (p == null) return null;
        p = p.trim().replace('\\', '/');
        if (p.isEmpty()) return null;
        for (String seg : p.split("/")) if (seg.equals("..")) return null;
        if (p.startsWith("/sdcard/PriyaCode") || p.startsWith("/storage/emulated/0/PriyaCode")) return p;
        if (p.startsWith(TermuxBridge.ROOT)) return p;
        if (p.startsWith("~/")) p = p.substring(2);
        if (p.startsWith("/")) return null;
        if (p.startsWith("PriyaCode/")) p = p.substring("PriyaCode/".length());
        return TermuxBridge.ROOT + "/" + p;
    }

    private String termuxCheck() {
        TermuxBridge.Result r = TermuxBridge.bash(ctx,
                "echo PRIYA_OK; uname -m; for t in python python3 node npm git clang java; do "
                        + "command -v $t >/dev/null 2>&1 && printf '%s ' $t; done; echo; mkdir -p \"$HOME/PriyaCode\" && echo workspace=$HOME/PriyaCode",
                null, 25);
        if (r.setupProblem != null) return r.setupProblem;
        if (!r.ok || !r.stdout.contains("PRIYA_OK")) {
            return "Termux ne jawab nahi diya. " + r.err + "\n" + TermuxBridge.SETUP_HELP;
        }
        return "TERMUX READY.\n" + r.stdout.trim()
                + "\n(Upar wali line mein installed tools hain. Jo chahiye aur nahi hai uske liye code_run se 'pkg install -y <naam>' chalao.)";
    }

    private String codeWriteFile(String path, String content) {
        String abs = safePath(path);
        if (abs == null) return "Path galat hai. Project folder ke andar relative path do, jaise 'calculator/main.py'.";
        String bad = dangerousCode(content);
        if (bad != null) return CODE_BLOCKED + " (mila: '" + bad + "')";
        if (content.length() > 200000) return "File bahut badi hai (200 KB se zyada). Chhote files mein todo.";
        TermuxBridge.Result r = TermuxBridge.bash(ctx,
                "mkdir -p \"$(dirname \"$1\")\" && printf '%s' \"$2\" | base64 -d > \"$1\" && echo \"saved $(wc -c < \"$1\") bytes, $(wc -l < \"$1\") lines\"",
                new String[]{abs, TermuxBridge.b64(content)}, 30);
        if (r.setupProblem != null) return r.setupProblem;
        if (!r.ok) return "File save nahi hui: " + r.err;
        if (r.exitCode != 0) return "File save nahi hui (exit " + r.exitCode + "): " + TermuxBridge.clip(r.stderr, 800);
        return "File likh di: " + abs + " — " + r.stdout.trim() + ". Ab code_run se chalao.";
    }

    private String codeReadFile(String path) {
        String abs = safePath(path);
        if (abs == null) return "Path galat hai.";
        TermuxBridge.Result r = TermuxBridge.bash(ctx,
                "if [ -d \"$1\" ]; then ls -la \"$1\"; else cat -n \"$1\" | head -c 7000; fi", new String[]{abs}, 20);
        if (r.setupProblem != null) return r.setupProblem;
        if (!r.ok) return "File nahi padh payi: " + r.err;
        if (r.exitCode != 0) return "File nahi mili / padh nahi payi: " + TermuxBridge.clip(r.stderr, 600);
        return r.stdout.isEmpty() ? "(file khaali hai)" : r.stdout;
    }

    private synchronized String codeRun(String cmd, String workdir, int timeoutSec) {
        if (cmd.trim().isEmpty()) return "Kaunsi command chalani hai?";
        if (dangerousCommand(cmd)) return CODE_BLOCKED;
        if (codeFails >= MAX_CODE_FAILS) {
            return "LIMIT: Code " + MAX_CODE_FAILS + " baar fail ho chuka hai. Ab aur try mat karo — Sachin ko asli error aur ab tak "
                    + "kya-kya try kiya wo short mein batao, aur unse poochho aage kya karna hai. (Naya kaam ho to naya task_plan banao.)";
        }
        String wd = workdir.trim().isEmpty() ? TermuxBridge.ROOT : safePath(workdir);
        if (wd == null) return "Workdir galat hai. Project folder ka naam do, jaise 'calculator'.";
        timeoutSec = Math.max(5, Math.min(timeoutSec, 600));
        TermuxBridge.Result r = TermuxBridge.bash(ctx,
                "mkdir -p \"$1\" && cd \"$1\" || exit 97\n"
                        + "export PYTHONUNBUFFERED=1 DEBIAN_FRONTEND=noninteractive\n"
                        + "timeout -k 5 \"$2\" bash -c \"$3\" < /dev/null\n"
                        + "rc=$?; [ $rc -eq 124 ] && echo \"[PRIYA] TIMEOUT: $2 sec se zyada chala, roka gaya\" >&2; exit $rc",
                new String[]{wd, String.valueOf(timeoutSec), cmd}, timeoutSec + 20);
        if (r.setupProblem != null) return r.setupProblem;
        if (!r.ok) return "Command ka result nahi aaya: " + r.err;
        boolean fail = r.exitCode != 0;
        if (fail) codeFails++; else codeFails = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("exit_code=").append(r.exitCode).append(fail ? " (FAIL " + codeFails + "/" + MAX_CODE_FAILS + ")" : " (SUCCESS)").append('\n');
        if (!r.stdout.isEmpty()) sb.append("STDOUT:\n").append(TermuxBridge.clip(r.stdout, 2500)).append('\n');
        if (!r.stderr.isEmpty()) sb.append("STDERR:\n").append(TermuxBridge.clip(r.stderr, 2500)).append('\n');
        if (!r.err.isEmpty()) sb.append("TERMUX: ").append(TermuxBridge.clip(r.err, 300)).append('\n');
        if (fail) sb.append(codeFails >= MAX_CODE_FAILS
                ? "Ye aakhri koshish thi. Ab ruk kar Sachin ko error batao."
                : "Error padho, wajah samjho, code_read_file / code_write_file se fix karo aur dobara code_run karo.");
        else sb.append("Chal gaya. Output check karo ki Sachin ne jo maanga wahi hai, phir short report do.");
        return sb.toString();
    }

    private String webSearch(String q) {
        if (q.trim().isEmpty()) return "Kya search karna hai?";
        boolean ok = launch(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))));
        return ok ? "Google par '" + q + "' search khol diya." : "Browser nahi khul paya.";
    }

    private String callContact(String s) {
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "Call permission nahi hai. Menu > Permissions mein 'Phone calls' allow karein.";
        }
        String number = null;
        String label = s;
        if (s.matches("[+0-9 ()\\-]{5,}")) {
            number = s.replaceAll("[ ()\\-]", "");
        } else {
            if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return "Contacts permission nahi hai. Menu > Permissions mein 'Contacts' allow karein.";
            }
            try (Cursor c = ctx.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + s + "%"}, null)) {
                if (c != null && c.moveToFirst()) {
                    label = c.getString(0);
                    number = c.getString(1);
                }
            }
            if (number == null) return "'" + s + "' naam ka contact nahi mila.";
        }
        boolean ok = launch(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))));
        return ok ? label + " ko call laga rahi hoon." : "Call start nahi ho paya.";
    }

    private void mediaKey(int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private String global(int action, String okMsg) {
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        return a.performGlobalAction(action) ? okMsg : "Ye action is phone par nahi chal paya.";
    }

    private String phoneControl(String action) throws Exception {
        switch (action) {
            case "home":          return global(AccessibilityService.GLOBAL_ACTION_HOME, "Home par aa gayi.");
            case "back":          return global(AccessibilityService.GLOBAL_ACTION_BACK, "Back kar diya.");
            case "recents":       return global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps khol diye.");
            case "notifications": return global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications khol diye.");
            case "quick_settings":return global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings khol diye.");
            case "lock_screen":   return global(8, "Screen lock kar di.");   // GLOBAL_ACTION_LOCK_SCREEN (API 28)
            case "screenshot":    return global(9, "Screenshot le liya.");   // GLOBAL_ACTION_TAKE_SCREENSHOT (API 28)
            case "scroll_down":
            case "scroll_up": {
                PriyaAccessibilityService a = PriyaAccessibilityService.get();
                if (a == null) return NEED_A11Y;
                return a.scroll(action.equals("scroll_down")) ? "Scroll kar diya." : "Scroll nahi ho paya.";
            }
            case "scroll_left":
            case "scroll_right": {
                PriyaAccessibilityService a = PriyaAccessibilityService.get();
                if (a == null) return NEED_A11Y;
                return a.scrollHorizontal(action.equals("scroll_left")) ? "Side scroll kar diya." : "Scroll nahi ho paya.";
            }
            case "volume_up":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return "Volume badha diya.";
            case "volume_down":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return "Volume kam kar diya.";
            case "mute":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
                return "Mute kar diya.";
            case "media_play_pause": mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return "Play/pause kar diya.";
            case "media_next":       mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);       return "Next gaana.";
            case "media_previous":   mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);   return "Pichla gaana.";
            case "flashlight_on":
            case "flashlight_off": {
                boolean on = action.equals("flashlight_on");
                CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
                for (String id : cm.getCameraIdList()) {
                    Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (f != null && f) {
                        cm.setTorchMode(id, on);
                        return on ? "Torch on kar di." : "Torch off kar di.";
                    }
                }
                return "Is phone mein flash nahi mila.";
            }
            case "wifi_settings":
                return launch(new Intent(Settings.ACTION_WIFI_SETTINGS)) ? "Wi-Fi settings khol di." : "Settings nahi khuli.";
            case "bluetooth_settings":
                return launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) ? "Bluetooth settings khol di." : "Settings nahi khuli.";
            default:
                return "Ye action samajh nahi aaya: " + action;
        }
    }

    // ------------------------------------------------------------------ learned skills (assets/knowledge.txt)

    private static java.util.List<String> kLines;

    private synchronized java.util.List<String> kLoad() {
        if (kLines != null) return kLines;
        java.util.List<String> l = new java.util.ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                ctx.getAssets().open("knowledge.txt"), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) if (!line.trim().isEmpty()) l.add(line);
        } catch (Exception ignored) { }
        kLines = l;
        return l;
    }

    private String knowledge(String query) {
        java.util.List<String> lines = kLoad();
        if (lines.isEmpty()) return "Knowledge file nahi mila.";
        java.util.List<String> toks = new java.util.ArrayList<>();
        for (String t : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (t.length() >= 3) toks.add(t);
        if (toks.isEmpty()) return "Query mein keywords do.";
        int[] score = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            String low = lines.get(i).toLowerCase(Locale.ROOT);
            for (String t : toks) if (low.contains(t)) score[i] += 2;
        }
        StringBuilder sb = new StringBuilder();
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int k = 0; k < 8; k++) {
            int best = -1;
            for (int i = 0; i < score.length; i++) if (score[i] > 0 && !used.contains(i) && (best < 0 || score[i] > score[best])) best = i;
            if (best < 0) break;
            for (int j = best; j <= Math.min(lines.size() - 1, best + 1); j++) {
                if (used.add(j)) sb.append(lines.get(j)).append('\n');
            }
        }
        if (sb.length() == 0) return "Is baare mein seekhi hui notes mein kuch nahi mila; apni general knowledge se jawab do.";
        return sb.length() > 3000 ? sb.substring(0, 3000) : sb.toString();
    }
}
