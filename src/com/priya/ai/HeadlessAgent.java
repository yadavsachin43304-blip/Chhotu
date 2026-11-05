package com.priya.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runs one scheduled task without the owner present: Gemini text model + function calling over the Interactions API.
 * SAFETY: only the tools of the scopes the owner chose at creation; delete/remove/reset hard block (ActionExecutor) stays;
 * no payments; messages/emails only to the recipients named in the schedule; no calls, no new schedules, no new skills.
 * Live mode (background_task from the voice chat) additionally keeps the owner-order gate: acting steps are refused
 * when the owner's voice has not been heard for 20 min or a stranger's voice was heard in the last 10 s, and every
 * step is announced to the owner through PriyaJobs.progress (ProgressText).
 */
final class HeadlessAgent {

    static final Set<String> ALWAYS = new HashSet<>(Arrays.asList(
            "knowledge", "recall", "use_skill", "remember", "task_plan", "task_progress",
            "research", "web_fetch", "generate_text", "notify_owner", "job_status", "find_best_tool"));
    static final Set<String> PHONE = new HashSet<>(Arrays.asList(
            "open_app", "list_apps", "read_screen", "click_on_screen", "tap_at", "swipe", "scroll_until", "wait_for_text",
            "dismiss_popup", "type_text", "phone_control", "youtube_play", "open_link", "web_search", "set_alarm"));
    static final Set<String> CONTENT = new HashSet<>(Arrays.asList(
            "generate_image", "text_to_speech", "make_faceless_video", "list_recent_audio"));
    static final Set<String> MESSAGES = new HashSet<>(Arrays.asList("whatsapp_chat", "press_send", "email_compose"));
    static final Set<String> CODING = new HashSet<>(Arrays.asList("termux_check", "code_write_file", "code_read_file", "code_run"));

    private static final Pattern PAY = Pattern.compile(
            "(?i)(\\bpay\\b|payment|\\bbuy\\b|purchase|checkout|place order|order now|\\bupi\\b|subscribe|donate|add money|send money|"
                    + "recharge|wallet|₹|rupees|भुगतान|खरीद|पेमेंट)");

    private final Context ctx;
    private final JSONObject sched;
    private final Set<String> allowed = new HashSet<>(ALWAYS);
    private final String recipients;
    private final String task;
    private final ActionExecutor exec;
    private boolean recipientOpened = false;
    private final StringBuilder trace = new StringBuilder();
    /** true = started by the owner from the live chat (background_task), false = scheduled run. */
    private final boolean live;
    private PriyaJobs.Job job;
    /** Previous step, for the "X ho gaya, ab Y kar rahi hoon" progress line. */
    private String prevName;
    private JSONObject prevArgs;
    private String prevRes;

    HeadlessAgent(Context c, JSONObject schedule) {
        ctx = c.getApplicationContext();
        sched = schedule;
        task = schedule.optString("task", "");
        recipients = schedule.optString("recipients", "");
        live = schedule.optBoolean("live", false);
        JSONArray sc = schedule.optJSONArray("scopes");
        if (sc != null) {
            for (int i = 0; i < sc.length(); i++) {
                switch (sc.optString(i)) {
                    case "phone": allowed.addAll(PHONE); break;
                    case "content": allowed.addAll(CONTENT); break;
                    case "messages": allowed.addAll(MESSAGES); break;
                    case "coding": allowed.addAll(CODING); break;
                    default: break;
                }
            }
        }
        exec = new ActionExecutor(ctx, new MemoryStore(ctx));
    }

    /** Lets the owner cancel this run (job.cancel) and shows the current step in job_status. */
    HeadlessAgent attach(PriyaJobs.Job j) {
        job = j;
        if (j != null) {
            for (String t : PHONE) if (allowed.contains(t)) { j.phone = true; break; }
        }
        return this;
    }

    private static ProgressText.Args argsOf(final JSONObject o) {
        return k -> o == null ? "" : o.optString(k, "");
    }

    /** Announces one step: what just finished (with its real outcome) and what starts now. */
    private void announce(String nextName, JSONObject nextArgs) {
        if (job == null || !live) return;
        try {
            String line = ProgressText.step(prevName, argsOf(prevArgs), prevRes, nextName, argsOf(nextArgs));
            if (line != null) PriyaJobs.progress(job, line);
        } catch (Throwable ignored) { }
    }

    private boolean cancelled() { return job != null && job.cancel; }

    // ------------------------------------------------------------------ schema conversion (Live -> Interactions)

    private static Object lower(Object o) throws Exception {
        if (o instanceof JSONObject) {
            JSONObject in = (JSONObject) o, out = new JSONObject();
            Iterator<String> it = in.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.equals("behavior")) continue;
                Object v = in.get(k);
                if (k.equals("type") && v instanceof String) out.put(k, ((String) v).toLowerCase(Locale.ROOT));
                else out.put(k, lower(v));
            }
            return out;
        }
        if (o instanceof JSONArray) {
            JSONArray in = (JSONArray) o, out = new JSONArray();
            for (int i = 0; i < in.length(); i++) out.put(lower(in.get(i)));
            return out;
        }
        return o;
    }

    private JSONArray tools(boolean withSearch) throws Exception {
        JSONArray all = GeminiLiveClient.toolDeclarations(false);
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject d = all.getJSONObject(i);
            String n = d.optString("name");
            if (!allowed.contains(n)) continue;
            JSONObject f = new JSONObject().put("type", "function").put("name", n).put("description", d.optString("description"));
            if (d.has("parameters")) f.put("parameters", lower(d.getJSONObject("parameters")));
            else f.put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()));
            out.put(f);
        }
        if (withSearch) out.put(new JSONObject().put("type", "google_search"));
        return out;
    }

    // ------------------------------------------------------------------ guards

    private boolean inRecipients(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String v = value.toLowerCase(Locale.ROOT).trim();
        String hay = (recipients + " " + task).toLowerCase(Locale.ROOT);
        String digits = v.replaceAll("[^0-9]", "");
        if (digits.length() >= 10) {
            String last10 = digits.substring(digits.length() - 10);
            return hay.replaceAll("[^0-9]", "").contains(last10) || recipients.replaceAll("[^0-9,; ]", "").contains(last10);
        }
        if (v.contains("@")) return recipients.toLowerCase(Locale.ROOT).contains(v) || task.toLowerCase(Locale.ROOT).contains(v);
        return v.length() >= 3 && recipients.toLowerCase(Locale.ROOT).contains(v);
    }

    private boolean screenShowsRecipient() {
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null || recipients.trim().isEmpty()) return false;
        String screen = a.dumpScreen(4000).toLowerCase(Locale.ROOT);
        for (String tok : recipients.split("[,;/|\\n]+")) {
            String t = tok.trim().toLowerCase(Locale.ROOT);
            if (t.length() >= 3 && screen.contains(t)) return true;
            String d = t.replaceAll("[^0-9]", "");
            if (d.length() >= 10 && screen.replaceAll("[^0-9]", "").contains(d.substring(d.length() - 10))) return true;
        }
        return false;
    }

    /** null = allowed; otherwise the refusal sent back to the model. */
    private String guard(String name, JSONObject args) {
        if (!allowed.contains(name)) {
            return "BLOCKED: '" + name + "' is scheduled task ke allowed scope mein nahi hai (owner ne creation par nahi diya). "
                    + "Bina is tool ke jo ho sakta hai wo karo, warna report mein likho ki Sachin ki permission chahiye.";
        }
        if (live && acts(name)) {
            long now = System.currentTimeMillis();
            if (Prefs.voiceLock(ctx) && PriyaJobs.strangerHeardMs > 0 && now - PriyaJobs.strangerHeardMs < PriyaJobs.STRANGER_HOLD_MS) {
                return "BLOCKED: abhi anjaan awaaz suni gayi thi — bina owner ke saaf order ke koi action nahi. Thoda ruk kar dobara koshish karo; na ho to report mein likho.";
            }
            if (PriyaJobs.ownerHeardMs > 0 && now - PriyaJobs.ownerHeardMs > PriyaJobs.ORDER_WINDOW_MS) {
                return "BLOCKED: Sachin ki awaaz 20 minute se nahi suni — owner ke taaze order ke bina aage koi action nahi. Ab tak ka report do.";
            }
        }
        switch (name) {
            case "click_on_screen": {
                String t = args.optString("text", "");
                if (PAY.matcher(t).find()) return "BLOCKED: scheduled run mein payment/kharid wala koi button nahi dabaya jaata.";
                if (PriyaAccessibilityService.sendLike(t)) return sendGuard();
                return null;
            }
            case "tap_at": {
                PriyaAccessibilityService a = PriyaAccessibilityService.get();
                if (a != null) {
                    double x = args.optDouble("x", -1), y = args.optDouble("y", -1);
                    int px = (int) Math.round(x <= 1.0 ? x * a.screenW() : x), py = (int) Math.round(y <= 1.0 ? y * a.screenH() : y);
                    String lbl = String.valueOf(a.labelAt(px, py));
                    if (PAY.matcher(lbl).find()) return "BLOCKED: wahan payment/kharid wala button hai — scheduled run mein mana.";
                    if (PriyaAccessibilityService.sendLike(lbl)) return sendGuard();
                }
                return null;
            }
            case "whatsapp_chat":
                if (!inRecipients(args.optString("phone", ""))) {
                    return "BLOCKED: ye number schedule ke recipients mein nahi hai (" + recipients + "). Sirf unhi ko message ja sakta hai.";
                }
                return null;
            case "email_compose":
                if (!inRecipients(args.optString("to", ""))) {
                    return "BLOCKED: ye email schedule ke recipients mein nahi hai (" + recipients + "). Draft/report bana kar Sachin ko notify karo.";
                }
                return null;
            case "press_send":
                return sendGuard();
            case "code_run":
            case "code_write_file":
                return null; // ActionExecutor blocks rm/delete/uninstall itself
            default:
                return null;
        }
    }

    /** Tools that only read/think (same idea as PriyaService's live gate): allowed without a fresh owner order. */
    private static final Set<String> READ_ONLY = new HashSet<>(Arrays.asList(
            "read_screen", "look_at_screen", "knowledge", "use_skill", "recall", "list_apps", "wait_for_text",
            "task_progress", "code_read_file", "termux_check", "task_plan", "remember", "notify_owner"));

    private static boolean acts(String name) {
        return !READ_ONLY.contains(name) && !BrainTools.READ_ONLY.contains(name);
    }

    private String sendGuard() {
        if (!allowed.contains("press_send")) return "BLOCKED: is scheduled task ko message/email bhejne ki permission nahi hai.";
        if (recipientOpened || screenShowsRecipient()) return null;
        return "BLOCKED: screen par schedule ka recipient (" + recipients + ") confirm nahi hua — bhejna mana. Pehle whatsapp_chat/email_compose se sahi recipient kholo.";
    }

    // ------------------------------------------------------------------ run

    String run() throws Exception {
        long deadline = System.currentTimeMillis() + (live ? 30 : 20) * 60 * 1000L;
        final int maxTurns = live ? 60 : 40;
        String system = Brain.headlessPrompt(ctx, sched, allowed);
        String input = (live ? "BACKGROUND TASK (Sachin ne abhi live baat mein order diya hai; wo tumse baat karta rehta hai, tum peeche kaam karo; har step ki chhoti khabar unhe apne aap sunai jaati hai): "
                : "SCHEDULED TASK (Sachin ne pehle se order diya tha, ab chalana hai): ") + task
                + "\nAllowed tools: " + allowed + (recipients.isEmpty() ? "" : "\nMessage/email sirf inko: " + recipients)
                + "\nKaam poora karo, har step verify karo, aur aakhir mein report do.";
        boolean withSearch = true;
        int modelIdx = 0;
        JSONObject r = null;
        Exception last = null;
        final String[] models = GeminiRest.textModels();
        while (modelIdx < models.length) {
            try {
                JSONObject b = GeminiRest.withThinking(new JSONObject().put("model", models[modelIdx]).put("system_instruction", system)
                        .put("tools", tools(withSearch)).put("input", input), models[modelIdx], "medium");
                r = GeminiRest.create(ctx, b, GeminiRest.ARGON.equals(models[modelIdx]) ? 600 : 180);
                break;
            } catch (GeminiRest.ApiError e) {
                last = e;
                if (e.code == 400 && withSearch) { withSearch = false; continue; } // search + functions not accepted: drop search
                GeminiRest.noteFail(models[modelIdx], e);
                if (GeminiRest.tryNext(e)) { modelIdx++; withSearch = true; continue; }
                throw e;
            }
        }
        if (r == null) throw last == null ? new Exception("model nahi mila") : last;
        String model = models[modelIdx];

        for (int turn = 0; turn < maxTurns; turn++) {
            if (cancelled()) return "CANCELLED: owner ne roka.\nAb tak:\n" + GeminiRest.clip(trace.toString(), 1200);
            JSONArray steps = GeminiRest.steps(r);
            JSONArray results = new JSONArray();
            for (int i = 0; i < steps.length(); i++) {
                JSONObject s = steps.optJSONObject(i);
                if (s == null || !"function_call".equals(s.optString("type"))) continue;
                String name = s.optString("name");
                Object argObj = s.opt("arguments");
                JSONObject args;
                if (argObj instanceof JSONObject) args = (JSONObject) argObj;
                else { try { args = new JSONObject(String.valueOf(argObj)); } catch (Exception e) { args = new JSONObject(); } }
                if (cancelled()) return "CANCELLED: owner ne roka.\nAb tak:\n" + GeminiRest.clip(trace.toString(), 1200);
                if (job != null) PriyaJobs.stage(job, "step " + (turn + 1) + ": " + name);
                announce(name, args);
                String res = guard(name, args);
                if (res == null) {
                    res = exec.run(name, args);
                    if ((name.equals("whatsapp_chat") || name.equals("email_compose")) && (res.startsWith("VERIFIED") || res.startsWith("PARTIAL"))) recipientOpened = true;
                    if (name.equals("open_app") || name.equals("open_link")) recipientOpened = false;
                }
                prevName = name;
                prevArgs = args;
                prevRes = res;
                trace.append(name).append(" → ").append(GeminiRest.clip(res.replace('\n', ' '), 90)).append('\n');
                results.put(new JSONObject().put("type", "function_result").put("name", name).put("call_id", s.optString("id"))
                        .put("result", new JSONArray().put(new JSONObject().put("type", "text").put("text", GeminiRest.clip(res, 12000)))));
            }
            if (results.length() == 0) {
                String text = GeminiRest.outputText(r);
                return text.isEmpty() ? "FAILED: model ne koi report nahi di.\nSteps:\n" + trace : text;
            }
            if (System.currentTimeMillis() > deadline) {
                return "FAILED: " + (live ? 30 : 20) + " minute ki limit poori — task adhoora raha.\nAb tak:\n" + GeminiRest.clip(trace.toString(), 1200);
            }
            JSONObject b = new JSONObject().put("model", model).put("system_instruction", system)
                    .put("tools", tools(withSearch)).put("previous_interaction_id", r.optString("id")).put("input", results);
            GeminiRest.withThinking(b, model, "medium");
            r = GeminiRest.create(ctx, b, GeminiRest.ARGON.equals(model) ? 600 : 240);
        }
        return "FAILED: bahut zyada steps (" + maxTurns + ") — task rok diya.\nAb tak:\n" + GeminiRest.clip(trace.toString(), 1200);
    }
}
