package com.priya.ai;

import java.util.Locale;

/**
 * Turns one background-agent step (tool name + args + result) into a short, human Hinglish progress line that Maya
 * speaks to the owner right away, e.g. "WhatsApp khol liya, ab 'Rahul' ki chat dhoondh rahi hoon".
 * Pure Java (no Android / org.json), so it can be checked on a plain JVM (tests/ProgressTextCheck.java).
 *
 * A progress line is never a "task done" claim: "khol liya / tap kar diya" is said only when that one step's own
 * result was VERIFIED / successful; unclear results become "koshish ki, pakka nahi", blocked ones "safety ne roka".
 */
final class ProgressText {

    /** Argument lookup ("" when missing), so callers can pass JSONObject::optString. */
    interface Args { String get(String key); }

    static final int OK = 0, FAIL = 1, BLOCKED = 2, UNSURE = 3;

    private ProgressText() {}

    /** Steps that are pure bookkeeping: not worth saying out loud. */
    static boolean silent(String tool) {
        switch (tool == null ? "" : tool) {
            case "knowledge": case "recall": case "use_skill": case "remember": case "task_progress":
            case "job_status": case "notify_owner": case "list_schedules": case "agent_note":
                return true;
            default:
                return false;
        }
    }

    /** Classifies a tool result string. */
    static int outcome(String result) {
        if (result == null) return UNSURE;
        String r = result.trim();
        if (r.startsWith("VERIFIED")) return OK;               // VERIFIED, VERIFIED_SENT
        if (r.startsWith("BLOCKED")) return BLOCKED;
        if (r.startsWith("FAILED") || r.startsWith("NOT_SENT") || r.startsWith("NO_CHANGE") || r.startsWith("TIMEOUT")
                || r.startsWith("Error") || r.startsWith("LIMIT") || r.startsWith("CANCELLED")) return FAIL;
        if (r.startsWith("UNVERIFIED") || r.startsWith("PARTIAL") || r.startsWith("LIKELY_SENT")) return UNSURE;
        String l = r.toLowerCase(Locale.ROOT);
        if (l.matches("(?s).*(nahi mila|nahi mili|nahi khula|nahi khul |nahi khul paya|nahi hua|nahi hui|nahi ho paya|nahi ho paayi"
                + "|nahi dikha|nahi chala|nahi aaya|samajh nahi|permission nahi|nahi padh).*")) return FAIL;
        return OK;
    }

    /** Short quoted value, safe to speak. */
    static String q(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\'', ' ').replaceAll("\\s+", " ").trim();
        if (s.length() > max) s = s.substring(0, max).trim() + "…";
        return s;
    }

    private static String host(String url) {
        String u = url == null ? "" : url.trim().replaceFirst("(?i)^https?://", "").replaceFirst("^www\\.", "");
        int k = u.indexOf('/');
        if (k > 0) u = u.substring(0, k);
        return u.isEmpty() ? "website" : u;
    }

    private static String app(Args a) {
        String n = q(a.get("app_name"), 24);
        if (n.contains(".") && !n.contains(" ")) {                       // package name -> last useful part
            String[] p = n.split("\\.");
            n = p[p.length - 1];
            if (n.length() > 0) n = Character.toUpperCase(n.charAt(0)) + n.substring(1);
        }
        return n.isEmpty() ? "app" : n;
    }

    private static String phoneAction(String act, boolean done) {
        switch (act == null ? "" : act) {
            case "home": return done ? "home screen par aa gayi" : "home screen par ja rahi hoon";
            case "back": return done ? "ek screen peeche aa gayi" : "peeche ja rahi hoon";
            case "recents": return done ? "recent apps khol liye" : "recent apps khol rahi hoon";
            case "notifications": return done ? "notifications khol li" : "notifications khol rahi hoon";
            case "quick_settings": return done ? "quick settings khol li" : "quick settings khol rahi hoon";
            case "scroll_down": return done ? "neeche scroll kiya" : "neeche scroll kar rahi hoon";
            case "scroll_up": return done ? "upar scroll kiya" : "upar scroll kar rahi hoon";
            case "scroll_left": case "scroll_right": return done ? "side mein scroll kiya" : "side mein scroll kar rahi hoon";
            case "lock_screen": return done ? "screen lock kar di" : "screen lock kar rahi hoon";
            case "screenshot": return done ? "screenshot le liya" : "screenshot le rahi hoon";
            case "volume_up": return done ? "volume badha diya" : "volume badha rahi hoon";
            case "volume_down": return done ? "volume kam kar diya" : "volume kam kar rahi hoon";
            case "mute": return done ? "mute kar diya" : "mute kar rahi hoon";
            case "flashlight_on": return done ? "torch jala di" : "torch jala rahi hoon";
            case "flashlight_off": return done ? "torch band kar di" : "torch band kar rahi hoon";
            case "wifi_settings": return done ? "WiFi settings khol li" : "WiFi settings khol rahi hoon";
            case "bluetooth_settings": return done ? "Bluetooth settings khol li" : "Bluetooth settings khol rahi hoon";
            default: return done ? "phone par " + q(act, 20) + " kiya" : "phone par " + q(act, 20) + " kar rahi hoon";
        }
    }

    /** What is being done now ("WhatsApp khol rahi hoon"). */
    static String doing(String tool, Args a) {
        switch (tool == null ? "" : tool) {
            case "open_app": return app(a) + " khol rahi hoon";
            case "open_link": return host(a.get("url")) + " khol rahi hoon";
            case "list_apps": return "phone mein app dhoondh rahi hoon";
            case "read_screen": return "screen padh rahi hoon";
            case "look_at_screen": return "screen dekh rahi hoon";
            case "click_on_screen": return "'" + q(a.get("text"), 30) + "' par tap kar rahi hoon";
            case "tap_at": return "screen par tap kar rahi hoon";
            case "swipe": return "screen par swipe kar rahi hoon";
            case "scroll_until": return "'" + q(a.get("text"), 30) + "' dhoondhne ke liye scroll kar rahi hoon";
            case "wait_for_text": return "'" + q(a.get("text").split("\\|")[0], 30) + "' ka intezaar kar rahi hoon";
            case "dismiss_popup": return "popup band kar rahi hoon";
            case "type_text": {
                String f = a.get("field").toLowerCase(Locale.ROOT);
                String t = q(a.get("text"), 30);
                return (f.contains("search") || f.contains("khoj") || "true".equals(a.get("press_enter")) ? "'" + t + "' search kar rahi hoon"
                        : "'" + t + "' type kar rahi hoon");
            }
            case "press_send": return "message bhej rahi hoon";
            case "phone_control": return phoneAction(a.get("action"), false);
            case "youtube_play": return "YouTube par '" + q(a.get("query"), 30) + "' dhoondh rahi hoon";
            case "web_search": return "Google par '" + q(a.get("query"), 30) + "' khoj rahi hoon";
            case "set_alarm": return "alarm laga rahi hoon";
            case "whatsapp_chat": return "WhatsApp chat khol rahi hoon";
            case "email_compose": return "Gmail mein email likh rahi hoon";
            case "research": return "'" + q(a.get("question"), 30) + "' par research kar rahi hoon";
            case "web_fetch": return host(a.get("url")) + " ka page padh rahi hoon";
            case "generate_text": return "likh rahi hoon";
            case "generate_image": return "image bana rahi hoon";
            case "text_to_speech": return "voiceover bana rahi hoon";
            case "make_faceless_video": return "video banana shuru kar rahi hoon";
            case "list_recent_audio": return "audio files dhoondh rahi hoon";
            case "find_best_tool": return "is kaam ke liye sahi AI tool chun rahi hoon";
            case "task_plan": return "plan bana rahi hoon";
            case "termux_check": return "Termux check kar rahi hoon";
            case "code_write_file": return "code file '" + q(a.get("path"), 30) + "' likh rahi hoon";
            case "code_read_file": return "code file padh rahi hoon";
            case "code_run": return "command chala rahi hoon";
            case "agent_table_read": return "agent ki table padh rahi hoon";
            case "agent_table_upsert": return "table mein entries likh rahi hoon";
            case "agent_request_approval": return "aapki haan ke liye request bhej rahi hoon";
            case "agent_save_pdf": return "PDF bana rahi hoon";
            case "agent_handoff": return "'" + q(a.get("agent"), 30) + "' agent ko kaam de rahi hoon";
            default: return q(tool, 24) + " chala rahi hoon";
        }
    }

    /** What just finished, honest about the outcome. null = nothing worth saying. */
    static String done(String tool, Args a, String result) {
        if (silent(tool)) return null;
        int o = outcome(result);
        if (o == BLOCKED) return "ek step safety rule ki wajah se roka gaya";
        String t = tool == null ? "" : tool;
        if (o == FAIL) {
            switch (t) {
                case "open_app": return app(a) + " nahi khula";
                case "open_link": return host(a.get("url")) + " nahi khuli";
                case "click_on_screen": return "'" + q(a.get("text"), 30) + "' nahi mila ya tap nahi hua";
                case "tap_at": return "tap se screen nahi badli";
                case "scroll_until": return "'" + q(a.get("text"), 30) + "' scroll karke bhi nahi mila";
                case "wait_for_text": return "'" + q(a.get("text").split("\\|")[0], 30) + "' abhi tak nahi aaya";
                case "type_text": return "type nahi ho paya";
                case "press_send": return "message abhi gaya NAHI";
                case "whatsapp_chat": return "WhatsApp chat nahi khuli";
                case "email_compose": return "email app nahi khula";
                case "code_run": return "command mein error aaya";
                default: return q(t, 24) + " nahi ho paya";
            }
        }
        if (o == UNSURE) {
            if (t.equals("press_send")) return "message ki koshish ki, par gaya ya nahi abhi pakka nahi";
            return doing(t, a).replace(" rahi hoon", "ne ki koshish ki") + ", par abhi pakka nahi";
        }
        switch (t) {
            case "open_app": return app(a) + " khol liya";
            case "open_link": return host(a.get("url")) + " khol li";
            case "list_apps": return "apps dekh liye";
            case "read_screen": return "screen padh li";
            case "look_at_screen": return "screen dekh li";
            case "click_on_screen": return "'" + q(a.get("text"), 30) + "' par tap kar diya";
            case "tap_at": return "screen par tap kar diya";
            case "swipe": return "swipe kar diya";
            case "scroll_until": return "'" + q(a.get("text"), 30) + "' mil gaya";
            case "wait_for_text": return "screen par wo aa gaya jiska intezaar tha";
            case "dismiss_popup": return "popup band kar diya";
            case "type_text": {
                String f = a.get("field").toLowerCase(Locale.ROOT);
                if (f.contains("search") || f.contains("khoj") || "true".equals(a.get("press_enter"))) return "'" + q(a.get("text"), 30) + "' search kar diya";
                return "'" + q(a.get("text"), 30) + "' likh diya (bheja nahi)";
            }
            case "press_send": return result != null && result.trim().startsWith("VERIFIED") ? "message chala gaya, screen par dikh raha hai"
                    : "send dabaya, par pakka nahi";
            case "phone_control": return phoneAction(a.get("action"), true);
            case "youtube_play": return "YouTube par '" + q(a.get("query"), 30) + "' khul gaya";
            case "web_search": return "Google search khul gayi";
            case "set_alarm": return "alarm app mein alarm bhar diya";
            case "whatsapp_chat": return "WhatsApp chat khol li, message box mein likha hai (bheja nahi)";
            case "email_compose": return "Gmail mein email bhar diya (bheja nahi)";
            case "research": return "research ho gayi";
            case "web_fetch": return host(a.get("url")) + " ka page padh liya";
            case "generate_text": return "likh liya";
            case "generate_image": return "image ban gayi";
            case "text_to_speech": return "voiceover ban gaya";
            case "make_faceless_video": return "video ka kaam shuru ho gaya";
            case "list_recent_audio": return "audio files mil gayi";
            case "find_best_tool": return "sahi tool chun liya";
            case "task_plan": return "plan bana liya";
            case "termux_check": return "Termux check ho gaya";
            case "code_write_file": return "code file likh di";
            case "code_read_file": return "code file padh li";
            case "code_run": return "command chal gayi";
            case "agent_table_read": return "table padh li";
            case "agent_table_upsert": return "table update kar di";
            case "agent_request_approval": return "aapse haan ki request bhej di";
            case "agent_save_pdf": return "PDF save ho gayi";
            case "agent_handoff": return "'" + q(a.get("agent"), 30) + "' agent ka kaam khatam hua";
            default: return q(t, 24) + " ho gaya";
        }
    }

    /**
     * One step's line: what finished (previous tool) + what is happening now (next tool).
     * prevTool == null for the first step. Returns null when nothing is worth saying.
     */
    static String step(String prevTool, Args prevArgs, String prevResult, String nextTool, Args nextArgs) {
        String d = prevTool == null ? null : done(prevTool, prevArgs, prevResult);
        String n = nextTool == null || silent(nextTool) ? null : doing(nextTool, nextArgs);
        if (d == null && n == null) return null;
        if (d == null) return cap(prevTool == null ? "Kaam shuru: " + n : "Ab " + n);
        if (n == null) return cap(d);
        return cap(d + ", ab " + n);
    }

    /** Merges lines that arrived close together into one sentence (keeps the newest "ab ..." part). */
    static String merge(java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;
        if (lines.size() == 1) return lines.get(0);
        StringBuilder done = new StringBuilder();
        String now = null;
        for (String l : lines) {
            String low = l;
            int k = low.indexOf(", ab ");
            String d = k >= 0 ? low.substring(0, k) : (low.startsWith("Ab ") || low.startsWith("Kaam shuru: ") ? null : low);
            if (k >= 0) now = low.substring(k + 5);
            else if (low.startsWith("Ab ")) now = low.substring(3);
            else if (low.startsWith("Kaam shuru: ")) now = low.substring(12);
            if (d != null && !d.isEmpty()) {
                if (done.length() > 0) done.append(", ");
                done.append(d);
            }
        }
        String s = done.length() == 0 ? (now == null ? lines.get(lines.size() - 1) : "Ab " + now)
                : (now == null ? done.toString() : done + ", ab " + now);
        return cap(s.length() > 220 ? s.substring(0, 219) + "…" : s);
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
