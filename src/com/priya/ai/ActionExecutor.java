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
    }

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
                case "click_on_screen": return clickOnScreen(args.optString("text", ""));
                case "type_text":       return typeText(args.optString("text", ""));
                case "read_screen":     return readScreen();
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
        try { ctx.startActivity(i); return String.format(Locale.ROOT, "Alarm %02d:%02d par laga diya.", hour, minute); }
        catch (Exception e) { return "Alarm app nahi khula."; }
    }

    private String openLink(String url) {
        if (url.trim().isEmpty()) return "Link nahi mila.";
        if (!url.startsWith("http")) url = "https://" + url.trim();
        try { ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "Link khol diya."; }
        catch (Exception e) { return "Link nahi khula."; }
    }

    private String whatsappChat(String phone, String text) {
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() == 10) digits = "91" + digits;
        String url = "https://wa.me/" + digits + (text.isEmpty() ? "" : "?text=" + Uri.encode(text));
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "WhatsApp chat khul gaya, message type ho gaya hai. User ki haan ke baad hi 'Send' par click karna.";
        } catch (Exception e) { return "WhatsApp nahi khula."; }
    }

    private String openApp(String query) throws Exception {
        if (query.trim().isEmpty()) return "App ka naam nahi mila.";
        String q = norm(query);
        String alias = ALIASES.get(q);
        if (alias != null) q = alias;

        PackageManager pm = ctx.getPackageManager();
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
            if (!pkg.equals(PriyaAccessibilityService.currentPackage)) {
                return bestLabel + " kholne ki request bheji, par screen par confirm nahi hua. "
                        + "Agar app nahi khula to Permissions mein 'Display over other apps' on karna hoga.";
            }
        }
        return bestLabel + " khul gaya.";
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
        for (int attempt = 0; attempt < 6; attempt++) {
            SystemClock.sleep(1300);
            PriyaAccessibilityService a = PriyaAccessibilityService.get();
            if (a == null) break;
            if (a.clickFirstVideoResult()) return "YouTube par '" + q + "' play ho raha hai.";
        }
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a != null) a.tapFractional(0.5f, 0.30f);
        return "YouTube par '" + q + "' search karke pehle video par tap kiya. Agar play na ho to batao.";
    }

    private String clickOnScreen(String text) {
        if (forbidden(text)) return BLOCKED;
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        return a.clickByText(text) ? "'" + text + "' par tap kar diya." : "'" + text + "' screen par nahi mila.";
    }

    private String typeText(String text) {
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        return a.typeText(text) ? "Type kar diya." : "Koi text field focus mein nahi mila.";
    }

    private String readScreen() {
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return NEED_A11Y;
        String s = a.dumpScreen(3000);
        return s.isEmpty() ? "Screen par padhne layak text nahi mila." : s;
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
