package com.priya.ai;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * v27 "smart brain" tools: research, web pages, text/script, images, voiceover, faceless video, recent audio,
 * email compose, notifications, scheduled tasks. Declarations are appended to the Live tool list; calls are routed
 * here from ActionExecutor's default branch (so the delete hard-block and the owner-order gate still wrap them).
 */
final class BrainTools {

    private BrainTools() {}

    /** Tools that only read/think (no phone action): allowed without a fresh owner order. */
    static final Set<String> READ_ONLY = new HashSet<>(Arrays.asList(
            "web_fetch", "research", "generate_text", "job_status", "list_schedules", "list_recent_audio", "find_best_tool"));

    // ------------------------------------------------------------------ declaration helpers (Live API schema, upper-case types)

    private static JSONObject str(String d) throws JSONException { return new JSONObject().put("type", "STRING").put("description", d); }
    private static JSONObject integer(String d) throws JSONException { return new JSONObject().put("type", "INTEGER").put("description", d); }
    private static JSONObject bool(String d) throws JSONException { return new JSONObject().put("type", "BOOLEAN").put("description", d); }
    private static JSONObject enumStr(String d, String... v) throws JSONException {
        JSONArray a = new JSONArray();
        for (String s : v) a.put(s);
        return new JSONObject().put("type", "STRING").put("description", d).put("enum", a);
    }
    private static JSONObject fn(String name, String desc, JSONObject props, String... req) throws JSONException {
        JSONObject f = new JSONObject().put("name", name).put("description", desc);
        if (props != null) {
            JSONObject p = new JSONObject().put("type", "OBJECT").put("properties", props);
            if (req.length > 0) {
                JSONArray r = new JSONArray();
                for (String s : req) r.put(s);
                p.put("required", r);
            }
            f.put("parameters", p);
        }
        return f;
    }

    static void declare(JSONArray a) throws JSONException {
        a.put(fn("find_best_tool", "MANAGER MODE, call FIRST for any creative/production job (image, thumbnail, poster, video/reel edit, voiceover, song, script, presentation, website, research, leads). Returns the top 2 AI apps/websites for the job from Priya's AI Tools Guide, whether each app is INSTALLED on this phone, step-by-step phone steps, and the latest learned AI-tool notes.",
                new JSONObject().put("task", str("The job in Sachin's words, e.g. 'YouTube thumbnail banana', 'reel edit with captions', 'Hindi voiceover'")), "task"));
        a.put(fn("research", "Research the web with Google Search and get a factual answer with source links. Use whenever you lack knowledge, need latest info, prices, how-to steps, business leads (e.g. 'cafes in Pune with email'), or are unsure how to do a task.",
                new JSONObject().put("question", str("Exact question, with place/time/details")), "question"));
        a.put(fn("web_fetch", "Open a web page (HTTP GET) and read its text (title + readable content, capped). Use to check a website, read an article, find a contact email on a business site, verify facts.",
                new JSONObject().put("url", str("Full URL"))
                        .put("max_chars", integer("Max characters to return, default 6000, max 20000")), "url"));
        a.put(fn("generate_text", "Write text with a Gemini text model (scripts, captions, emails, proposals, reports, prompts for other AI apps, plans); long results saved to Documents/Priya. OK for prompts/briefs that Priya then types into an AI app, and for emails. For big creative deliverables Sachin wants made in an app (ChatGPT/Claude/Canva...), operate that app instead unless he asks Priya to write it herself.",
                new JSONObject().put("task", str("Exactly what to write, with topic, language (Hinglish/Hindi/English), length, tone, audience"))
                        .put("kind", str("script | caption | email | prompt | report | plan | other"))
                        .put("save_file", bool("true = also save as a .txt file")), "task"));
        a.put(fn("generate_image", "FALLBACK image maker (Nano Banana API), saves to Pictures/Priya. Manager rule: for images first use find_best_tool and operate the right app/website (Canva, Gemini, ChatGPT, Leonardo, Designer...). Use this only when Sachin asks Priya to make it herself, or no suitable app/website is available/working.",
                new JSONObject().put("prompt", str("Detailed English prompt: subject, setting, lighting, camera, style; say 'no text' if no text wanted"))
                        .put("aspect", str("9:16 (shorts/reels, default), 16:9 (YouTube), 1:1, 4:5"))
                        .put("folder", str("Optional sub-folder name")), "prompt"));
        a.put(fn("text_to_speech", "FALLBACK voiceover (WAV, Priya's own voice) saved to Music/Priya. Manager rule: for voiceovers first find_best_tool (ElevenLabs app etc.); use this only if Sachin asks for Priya's voice or no app is available/working.",
                new JSONObject().put("text", str("Exact words to speak"))
                        .put("style", str("Delivery, e.g. 'warm and energetic', 'calm storyteller'")), "text"));
        a.put(fn("list_recent_audio", "List recent audio files on the phone (Downloads, WhatsApp audio/voice notes, Recordings, Music, picked files) with full paths, newest first.",
                new JSONObject().put("limit", integer("How many, default 8"))));
        a.put(fn("pick_audio_file", "Open the Android file picker so Sachin can choose an audio file himself. The chosen file's path arrives later as a SYSTEM NOTE.", null));
        a.put(fn("make_faceless_video", "Make a complete FACELESS video (MP4) from Sachin's voiceover: understands the audio, splits it into scenes, generates one AI image per scene, assembles images + his audio (ken-burns zoom) with ffmpeg in Termux, saves to Movies/Priya and verifies the file. Runs in background: returns JOB_STARTED; the real result comes as a SYSTEM NOTE / job_status. If no audio, can write narration with script_if_no_audio (Priya's voice).",
                new JSONObject().put("audio_path", str("Full path of the audio (from list_recent_audio / picker), or 'latest'. Empty = last picked file."))
                        .put("topic", str("What the video is about (helps scene images)"))
                        .put("style", str("Visual style, e.g. 'cinematic realistic', 'anime', '3D cartoon', 'dark documentary'"))
                        .put("aspect", str("9:16 (default, Shorts/Reels) or 16:9 (YouTube) or 1:1"))
                        .put("ken_burns", bool("Slow zoom motion on images, default true"))
                        .put("script_if_no_audio", str("Only when there is NO audio: the narration text to voice with TTS"))));
        a.put(fn("job_status", "Check a background job (video etc.). wait_sec>0 waits for it to finish (max 600).",
                new JSONObject().put("job_id", str("Job id from JOB_STARTED; empty = latest job"))
                        .put("wait_sec", integer("Seconds to wait, default 0"))));
        a.put(fn("email_compose", "Open the Gmail app with a new email already filled (to, subject, body). Does NOT send. To send: read_screen to check the From account and the To address, then click_on_screen 'Send' (only with Sachin's yes or a standing GrowthPilot approval), then wait_for_text 'Sent|Sending'.",
                new JSONObject().put("to", str("Recipient email address"))
                        .put("subject", str("Subject"))
                        .put("body", str("Full email body")), "to", "subject", "body"));
        a.put(fn("notify_owner", "Post a phone notification to Sachin (result, reminder, report).",
                new JSONObject().put("title", str("Short title")).put("text", str("Message")), "title", "text"));
        a.put(fn("schedule_task", "Schedule ANY task for later or repeating (e.g. 'kal subah 9 baje', 'roz 8 baje', 'har Monday'). Priya runs it headlessly at that time with ONLY the allowed tool scopes, then notifies the result. Before calling, confirm with Sachin: the exact task, time, repeat, and which scopes are allowed.",
                new JSONObject().put("task", str("Exact task text in Sachin's words (what to do, for whom, what output)"))
                        .put("repeat", enumStr("Repeat", "once", "daily", "weekly"))
                        .put("time", str("24h time HH:MM in phone local time, e.g. 09:00"))
                        .put("date", str("For once: YYYY-MM-DD (resolve 'kal' etc. from today's date). Empty = next occurrence of the time."))
                        .put("weekday", str("For weekly: monday..sunday"))
                        .put("scopes", new JSONObject().put("type", "ARRAY").put("items",
                                new JSONObject().put("type", "STRING").put("enum", new JSONArray().put("phone").put("content").put("messages").put("coding")))
                                .put("description", "Allowed tool groups: phone (open apps, tap, type, read screen), content (images, voiceover, faceless video), messages (WhatsApp/email ONLY to the recipients), coding (Termux). Research, writing, notes and notifications are always allowed."))
                        .put("recipients", str("Only for 'messages': the exact names / phone numbers / emails she may message in this task")),
                "task", "repeat", "time"));
        a.put(fn("list_schedules", "List scheduled tasks with status, next run and last result.", null));
        a.put(fn("set_schedule_status", "Pause, resume or cancel a scheduled task (cancel keeps it in the log, nothing is deleted).",
                new JSONObject().put("id", str("Schedule id")).put("status", enumStr("New status", "paused", "active", "cancelled")), "id", "status"));
        a.put(fn("run_schedule_now", "Run a scheduled task right now (to test it).",
                new JSONObject().put("id", str("Schedule id")), "id"));
    }

    // ------------------------------------------------------------------ dispatch

    /** Returns null when the tool is not one of ours. */
    static String run(Context ctx, String name, JSONObject args) {
        switch (name) {
            case "research": {
                String q = args.optString("question", "");
                if (q.trim().isEmpty()) return "Sawaal do.";
                try { return "RESEARCH RESULT:\n" + GeminiRest.research(ctx, q); }
                catch (Exception e) { return "FAILED: research nahi hua — " + e.getMessage() + ". web_fetch se kisi site ko seedha padho ya web_search se browser kholo."; }
            }
            case "find_best_tool":   return AiTools.findBest(ctx, args.optString("task", ""));
            case "web_fetch":        return Studio.webFetch(args.optString("url", ""), args.optInt("max_chars", 6000));
            case "generate_text":    return Studio.generateText(ctx, args.optString("task", ""), args.optString("kind", ""), args.optBoolean("save_file", false));
            case "generate_image":   return Studio.generateImage(ctx, args.optString("prompt", ""), args.optString("aspect", "9:16"), args.optString("folder", ""));
            case "text_to_speech":   return Studio.tts(ctx, args.optString("text", ""), args.optString("style", ""));
            case "list_recent_audio":return Studio.listRecentAudio(ctx, args.optInt("limit", 8));
            case "pick_audio_file":  return pickAudio(ctx);
            case "make_faceless_video":
                return Studio.startFacelessVideo(ctx, args.optString("audio_path", ""), args.optString("topic", ""), args.optString("style", ""),
                        args.optString("aspect", "9:16"), args.optBoolean("ken_burns", true), args.optString("script_if_no_audio", ""));
            case "job_status":       return PriyaJobs.status(args.optString("job_id", ""), args.optInt("wait_sec", 0));
            case "email_compose":    return emailCompose(ctx, args.optString("to", ""), args.optString("subject", ""), args.optString("body", ""));
            case "notify_owner":
                PriyaJobs.notifyOwner(ctx, "Priya: " + args.optString("title", ""), args.optString("text", ""), (int) SystemClock.uptimeMillis());
                return "VERIFIED: notification bhej di.";
            case "schedule_task":    return Scheduler.createFromTool(ctx, args);
            case "list_schedules":   return Scheduler.describeAll(ctx);
            case "set_schedule_status": return Scheduler.setStatus(ctx, args.optString("id", ""), args.optString("status", ""));
            case "run_schedule_now": return Scheduler.runNow(ctx, args.optString("id", ""));
            default: return null;
        }
    }

    private static String pickAudio(Context ctx) {
        try {
            Intent i = new Intent(ctx, BrainActivity.class).putExtra("page", "pick_audio").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PriyaAccessibilityService a = PriyaAccessibilityService.get();
            if (a != null) a.startActivity(i); else ctx.startActivity(i);
            return "File picker khol diya. Sachin file chunega; chuni hui file ka path SYSTEM NOTE mein aayega — tab tak intezaar karo (abhi kuch 'ho gaya' mat bolo).";
        } catch (Exception e) {
            return "FAILED: picker nahi khula. Sachin Menu > Studio se audio chun sakte hain.";
        }
    }

    private static String emailCompose(Context ctx, String to, String subject, String body) {
        to = to.trim();
        if (!to.matches("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+")) return "Sahi email address do (ek hi).";
        String uri = "mailto:" + Uri.encode(to) + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body);
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse(uri))
                .putExtra(Intent.EXTRA_EMAIL, new String[]{to})
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, body)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setPackage("com.google.android.gm");
        try {
            PriyaAccessibilityService a = PriyaAccessibilityService.get();
            if (a != null) a.startActivity(i); else ctx.startActivity(i);
        } catch (Exception e) {
            try {
                i.setPackage(null);
                ctx.startActivity(i);
            } catch (Exception e2) {
                return "FAILED: Gmail / email app nahi khula.";
            }
        }
        PriyaAccessibilityService a = PriyaAccessibilityService.get();
        if (a == null) return "UNVERIFIED: email compose kholne ki request bheji (Accessibility band hai, check nahi kar sakti). Email BHEJA NAHI gaya.";
        String user = to.toLowerCase(Locale.ROOT);
        for (int k = 0; k < 14; k++) {
            SystemClock.sleep(500);
            String s = a.dumpScreen(3000).toLowerCase(Locale.ROOT);
            if (s.contains(user) || (s.contains(user.substring(0, user.indexOf('@'))) && s.contains("compose"))) {
                return "VERIFIED: Gmail compose khula, To = " + to + ", subject aur body bhare hain. Email abhi BHEJA NAHI gaya. "
                        + "Bhejne se pehle read_screen se 'From' account dekho (GrowthPilot ke liye growthpilotconsulting1@gmail.com), "
                        + "phir click_on_screen 'Send' aur wait_for_text 'Sent|Sending|भेजा' — tabhi 'bhej diya' bolna.";
            }
        }
        return "PARTIAL: email app khula par screen par To address confirm nahi hua (abhi app: " + PriyaAccessibilityService.currentPackage
                + "). read_screen se dekho; zarurat ho to type_text se To/Subject/Body bharo. Email BHEJA NAHI gaya.";
    }
}
