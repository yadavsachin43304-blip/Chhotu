package com.priya.ai;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;

/** Gemini Live API (BidiGenerateContent) over WebSocket. Audio in: PCM16 16 kHz. Audio out: PCM16 24 kHz. */
public class GeminiLiveClient {

    public interface Callback {
        void onSetupComplete();
        void onAudio(byte[] pcm24k);
        void onInputText(String text);
        void onOutputText(String text);
        void onTurnComplete();
        void onInterrupted();
        void onToolCall(String id, String name, JSONObject args);
        void onResumeHandle(String handle);
        void onGoAway();
        /** afterSetup=false means the server/network rejected us before the session was ready. */
        void onClosed(int code, String reason, boolean afterSetup);
    }

    private static final String HOST = "generativelanguage.googleapis.com";
    private static final String PATH =
            "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=";
    private static final String PATH_ALPHA =
            "/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=";

    /** Affective (emotion-aware) dialog is only offered on native-audio Live models. */
    private boolean affective() {
        return model != null && model.contains("native-audio");
    }

    private final String apiKey;
    private final String model;
    private final String voice;
    private final String systemText;
    private final boolean full;
    private final String resumeHandle;
    private final Callback cb;

    private MiniWebSocket ws;
    private volatile boolean ready = false;
    private volatile boolean closedByUs = false;

    /**
     * @param full true = sliding-window compression + session resumption in setup;
     *             false = minimal setup (used as fallback if the server rejects the full one).
     */
    public GeminiLiveClient(String apiKey, String model, String voice, String systemText,
                            boolean full, String resumeHandle, Callback cb) {
        this.apiKey = apiKey;
        this.model = model;
        this.voice = voice;
        this.systemText = systemText;
        this.full = full;
        this.resumeHandle = resumeHandle;
        this.cb = cb;
    }

    public boolean isReady() { return ready && ws != null && ws.isOpen(); }
    public String model() { return model; }
    public boolean isFull() { return full; }

    public void connect() {
        String path;
        try {
            path = ((full && affective()) ? PATH_ALPHA : PATH) + URLEncoder.encode(apiKey, "UTF-8");
        } catch (Exception e) {
            path = PATH + apiKey;
        }
        ws = new MiniWebSocket(HOST, 443, path, true, new MiniWebSocket.Listener() {
            @Override public void onOpen() {
                ws.send(buildSetup());
            }
            @Override public void onMessage(String text) {
                try {
                    handle(text);
                } catch (Exception e) {
                    // never let one bad message kill the socket thread
                }
            }
            @Override public void onClosed(int code, String reason) {
                boolean was = ready;
                ready = false;
                if (!closedByUs) cb.onClosed(code, reason, was);
            }
        });
        ws.connect();
    }

    public void close() {
        closedByUs = true;
        ready = false;
        if (ws != null) ws.close();
    }

    // ------------------------------------------------------------------ outgoing

    public void sendAudio(byte[] pcm16k, int len) {
        if (!isReady()) return;
        String b64 = Base64.encodeToString(pcm16k, 0, len, Base64.NO_WRAP);
        ws.send("{\"realtimeInput\":{\"audio\":{\"data\":\"" + b64
                + "\",\"mimeType\":\"audio/pcm;rate=16000\"}}}");
    }

    public void sendAudioStreamEnd() {
        if (!isReady()) return;
        ws.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}");
    }

    public void sendText(String text) {
        if (!isReady()) return;
        try {
            ws.send(new JSONObject().put("realtimeInput", new JSONObject().put("text", text)).toString());
        } catch (JSONException ignored) { }
    }

    /** One screen frame for the model to look at (Live API realtime video input). */
    public void sendImage(byte[] jpeg) {
        if (!isReady() || jpeg == null) return;
        String b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP);
        ws.send("{\"realtimeInput\":{\"video\":{\"data\":\"" + b64 + "\",\"mimeType\":\"image/jpeg\"}}}");
    }

    public void sendToolResponse(String id, String name, String result) {
        if (!isReady()) return;
        try {
            JSONObject fr = new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("response", new JSONObject().put("result", result));
            JSONObject msg = new JSONObject().put("toolResponse",
                    new JSONObject().put("functionResponses", new JSONArray().put(fr)));
            ws.send(msg.toString());
        } catch (JSONException ignored) { }
    }

    // ------------------------------------------------------------------ setup

    private String buildSetup() {
        try {
            JSONObject setup = new JSONObject();
            setup.put("model", "models/" + model);

            JSONObject gen = new JSONObject();
            gen.put("responseModalities", new JSONArray().put("AUDIO"));
            gen.put("temperature", 0.9);
            gen.put("speechConfig", new JSONObject().put("voiceConfig",
                    new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice))));
            setup.put("generationConfig", gen);

            setup.put("systemInstruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", systemText))));
            setup.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", toolDeclarations())));
            setup.put("inputAudioTranscription", new JSONObject());
            setup.put("outputAudioTranscription", new JSONObject());

            if (model != null && model.startsWith("gemini-2.5")) {
                // no hidden "thinking" pass: replies start much faster
                gen.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
            }
            if (full && affective()) {
                // Emotion-aware voice: tone follows the user's mood (native-audio models, v1alpha).
                gen.put("enableAffectiveDialog", true);
                setup.put("proactivity", new JSONObject().put("proactiveAudio", true));
            }
            if (full) {
                setup.put("contextWindowCompression", new JSONObject().put("slidingWindow", new JSONObject()));
                JSONObject sr = new JSONObject();
                if (resumeHandle != null && !resumeHandle.isEmpty()) sr.put("handle", resumeHandle);
                setup.put("sessionResumption", sr);
            }
            return new JSONObject().put("setup", setup).toString();
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private static JSONObject str(String desc) throws JSONException {
        return new JSONObject().put("type", "STRING").put("description", desc);
    }

    private static JSONObject num(String desc) throws JSONException {
        return new JSONObject().put("type", "NUMBER").put("description", desc);
    }

    private static JSONObject integer(String desc) throws JSONException {
        return new JSONObject().put("type", "INTEGER").put("description", desc);
    }

    private static JSONObject bool(String desc) throws JSONException {
        return new JSONObject().put("type", "BOOLEAN").put("description", desc);
    }

    private static JSONObject enumStr(String desc, String... values) throws JSONException {
        JSONArray a = new JSONArray();
        for (String v : values) a.put(v);
        return new JSONObject().put("type", "STRING").put("description", desc).put("enum", a);
    }

    private static JSONObject fn(String name, String desc, JSONObject props, String... required) throws JSONException {
        JSONObject f = new JSONObject().put("name", name).put("description", desc);
        if (props != null) {
            JSONArray req = new JSONArray();
            for (String r : required) req.put(r);
            JSONObject params = new JSONObject().put("type", "OBJECT").put("properties", props);
            if (req.length() > 0) params.put("required", req);
            f.put("parameters", params);
        }
        return f;
    }

    static JSONArray toolDeclarations() throws JSONException {
        JSONArray a = new JSONArray();
        a.put(fn("open_app", "Open an installed Android app by its name (e.g. WhatsApp, CapCut, Chrome, Settings) or its package name.",
                new JSONObject().put("app_name", str("App name as the user says it, or package name like com.lemon.lvoverseas")), "app_name"));
        a.put(fn("list_apps", "List installed (launchable) apps with package names, optionally filtered. Use when unsure an app is installed or what it is called.",
                new JSONObject().put("filter", str("Optional part of the app name, e.g. 'cap' or 'edit'"))));
        a.put(fn("youtube_play", "Search YouTube for a song or video and start playing the first result.",
                new JSONObject().put("query", str("Song or video name, e.g. 'Arijit Singh Tum Hi Ho'")), "query"));
        a.put(fn("phone_control", "Control the phone: navigation, scrolling, volume, media keys, flashlight, lock screen, screenshot, settings screens.",
                new JSONObject().put("action", enumStr("Action to perform",
                        "home", "back", "recents", "notifications", "quick_settings",
                        "scroll_down", "scroll_up", "scroll_left", "scroll_right", "lock_screen", "screenshot",
                        "volume_up", "volume_down", "mute",
                        "media_play_pause", "media_next", "media_previous",
                        "flashlight_on", "flashlight_off", "wifi_settings", "bluetooth_settings")), "action"));
        a.put(fn("click_on_screen", "Tap (or long-press) the element showing this text / content-description / view id, in any window incl. popups. Falls back to a real finger tap at its position. Refuses delete/remove/reset and ads.",
                new JSONObject()
                        .put("text", str("Visible text, icon label or id of the item, as read_screen shows it"))
                        .put("index", integer("Which match when several share the text: 1 = first (default)"))
                        .put("long_press", bool("true = long-press instead of tap")), "text"));
        a.put(fn("tap_at", "Tap or long-press at a screen position. Use for icons / thumbnails / editor timelines without text. x,y as 0-1 fractions of the screen (from look_at_screen) or pixels (from read_screen @x,y).",
                new JSONObject().put("x", num("x: 0-1 fraction from left, or pixels"))
                        .put("y", num("y: 0-1 fraction from top, or pixels"))
                        .put("long_press", bool("true = long-press")), "x", "y"));
        a.put(fn("swipe", "Swipe or drag from (x1,y1) to (x2,y2). hold_ms > 0 presses and holds first, then drags without lifting: use it to drag CapCut trim handles, sliders, clips. Coordinates as 0-1 fractions or pixels.",
                new JSONObject().put("x1", num("start x")).put("y1", num("start y"))
                        .put("x2", num("end x")).put("y2", num("end y"))
                        .put("duration_ms", integer("Move duration, default 400 (slower = more precise)"))
                        .put("hold_ms", integer("Hold before moving, e.g. 400 for drag; 0 = plain swipe")),
                "x1", "y1", "x2", "y2"));
        a.put(fn("scroll_until", "Scroll the screen repeatedly until the given text appears (max_swipes times).",
                new JSONObject().put("text", str("Text to find"))
                        .put("direction", enumStr("Scroll direction", "down", "up", "left", "right"))
                        .put("max_swipes", integer("Max swipes, default 8")), "text"));
        a.put(fn("wait_for_text", "Wait until a text appears on screen (app loading, export finishing). Several options with '|', e.g. 'Saved|Done|Export'.",
                new JSONObject().put("text", str("Text to wait for; alternatives separated by |"))
                        .put("timeout_sec", integer("Max seconds to wait, default 10, max 300")), "text"));
        a.put(fn("dismiss_popup", "Close a popup / dialog / promo by pressing a harmless button like Not now, Skip, Later, Got it, Close. Never presses Allow/Buy/Install/Subscribe/Delete.", null));
        a.put(fn("type_text", "Type text into an input field: the one matching 'field' (its text/hint/label), else the focused or first field. press_enter=true submits (search).",
                new JSONObject().put("text", str("Text to type"))
                        .put("field", str("Optional: hint/label of the field, e.g. 'Search'"))
                        .put("press_enter", bool("true = press Enter/Search after typing")), "text"));
        a.put(fn("read_screen", "Read the current screen: every visible element with [tap]/[input]/[scroll] tags and its centre @x,y in pixels, incl. popups. Use before acting and to verify after.", null));
        a.put(fn("look_at_screen", "Take a screenshot and look at it (image). Use when read_screen is not enough: icons without text, video thumbnails, CapCut timeline/handles, images. Then use tap_at / swipe with 0-1 fractions.", null));
        a.put(fn("task_plan", "Manager mode: before a big multi-step order, save the plan (goal + ordered steps). Only for a task Sachin explicitly ordered.",
                new JSONObject().put("goal", str("Sachin's order in one line"))
                        .put("steps", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "STRING"))
                                .put("description", "Ordered short steps")), "goal", "steps"));
        a.put(fn("task_progress", "Manager mode: report a step's result after verifying it; returns the next step.",
                new JSONObject().put("step", integer("Step number (1-based)"))
                        .put("status", enumStr("Result of this step", "done", "failed", "retrying"))
                        .put("note", str("Short note: what was verified or what went wrong")), "step", "status"));
        a.put(fn("termux_check", "Check that Termux is installed and allows Priya to run code; returns tools available or exact setup steps for Sachin.", null));
        a.put(fn("code_write_file", "Write a complete source file into Priya's coding workspace (~/PriyaCode in Termux). Overwrites the file with the full new content.",
                new JSONObject().put("path", str("Relative path inside the workspace, e.g. 'calculator/main.py'"))
                        .put("content", str("Full file content")), "path", "content"));
        a.put(fn("code_read_file", "Read a file (with line numbers) or list a folder in the coding workspace.",
                new JSONObject().put("path", str("Relative path, e.g. 'calculator/main.py' or 'calculator'")), "path"));
        a.put(fn("code_run", "Run a shell command in Termux inside a project folder and get exit code, stdout and stderr (e.g. 'python main.py', 'node app.js', 'pip install requests'). No interactive input: pipe test input with echo.",
                new JSONObject().put("command", str("Shell command"))
                        .put("workdir", str("Project folder inside the workspace, e.g. 'calculator'"))
                        .put("timeout_sec", integer("Max seconds, default 60, max 600")), "command"));
        a.put(fn("call_contact", "Place a phone call to a saved contact name or a phone number. Confirm with the user before calling.",
                new JSONObject().put("name_or_number", str("Contact name or phone number")), "name_or_number"));
        a.put(fn("web_search", "Open a Google search for the query in the browser.",
                new JSONObject().put("query", str("Search query")), "query"));
        a.put(fn("knowledge", "Search Priya's learned skill notes: 30 business skills (strategy, finance, pricing, marketing, funnels, SEO, automation, AI agents, SOPs, YouTube growth, scriptwriting, OKRs, negotiation) and the video editing + AI editing prompt handbook (cuts, transitions, keyframes, captions, colour, audio, prompt recipes).",
                new JSONObject().put("query", str("Keywords, e.g. 'ripple delete', 'pricing strategy', 'J cut', 'unit economics'")), "query"));
        a.put(fn("use_skill", "Load the full instructions of one of Priya's saved skills before doing a task that matches it.",
                new JSONObject().put("name", str("Skill name from the SKILLS list")), "name"));
        a.put(fn("create_skill", "Create and save a NEW skill for a kind of task. Only call after the user said yes to making the skill.",
                new JSONObject()
                        .put("name", str("Short skill name"))
                        .put("description", str("One line: when to use this skill"))
                        .put("instructions", str("Clear numbered steps Priya should follow for this kind of task")),
                "name", "description", "instructions"));
        a.put(fn("set_alarm", "Set an alarm on the phone.",
                new JSONObject().put("hour", new JSONObject().put("type", "INTEGER").put("description", "Hour 0-23"))
                        .put("minute", new JSONObject().put("type", "INTEGER").put("description", "Minute 0-59"))
                        .put("label", str("Alarm label")), "hour"));
        a.put(fn("open_link", "Open a website link in the browser.",
                new JSONObject().put("url", str("Website URL")), "url"));
        a.put(fn("whatsapp_chat", "Open a WhatsApp chat with a phone number and pre-type a message (does not send). Confirm with the user, then click 'Send'.",
                new JSONObject().put("phone", str("Phone number with or without country code"))
                        .put("text", str("Message text")), "phone"));
        a.put(fn("remember", "Save something to Priya's permanent memory: a promise (VAADA) the user made or Priya made, a fact about the user, or a note.",
                new JSONObject()
                        .put("kind", enumStr("Type of memory", "VAADA", "FACT", "NOTE"))
                        .put("text", str("What to remember, written as one clear sentence with names, dates and times")),
                "kind", "text"));
        a.put(fn("recall", "Search Priya's saved memory (notes, vaade and past conversations) for the given keywords.",
                new JSONObject().put("query", str("Keywords to search for")), "query"));
        return a;
    }

    // ------------------------------------------------------------------ incoming

    private void handle(String text) throws JSONException {
        JSONObject m = new JSONObject(text);

        if (m.has("setupComplete")) {
            ready = true;
            cb.onSetupComplete();
            return;
        }

        JSONObject sc = m.optJSONObject("serverContent");
        if (sc != null) {
            if (sc.optBoolean("interrupted")) cb.onInterrupted();
            JSONObject mt = sc.optJSONObject("modelTurn");
            if (mt != null) {
                JSONArray parts = mt.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject inl = parts.getJSONObject(i).optJSONObject("inlineData");
                        if (inl != null) {
                            String d = inl.optString("data", "");
                            if (!d.isEmpty()) cb.onAudio(Base64.decode(d, Base64.DEFAULT));
                        }
                    }
                }
            }
            JSONObject it = sc.optJSONObject("inputTranscription");
            if (it != null) cb.onInputText(it.optString("text", ""));
            JSONObject ot = sc.optJSONObject("outputTranscription");
            if (ot != null) cb.onOutputText(ot.optString("text", ""));
            if (sc.optBoolean("turnComplete")) cb.onTurnComplete();
        }

        JSONObject tc = m.optJSONObject("toolCall");
        if (tc != null) {
            JSONArray fcs = tc.optJSONArray("functionCalls");
            if (fcs != null) {
                for (int i = 0; i < fcs.length(); i++) {
                    JSONObject fc = fcs.getJSONObject(i);
                    JSONObject args = fc.optJSONObject("args");
                    cb.onToolCall(fc.optString("id", ""), fc.optString("name", ""),
                            args == null ? new JSONObject() : args);
                }
            }
        }

        if (m.has("goAway")) cb.onGoAway();

        JSONObject sru = m.optJSONObject("sessionResumptionUpdate");
        if (sru != null && sru.optBoolean("resumable", false) && sru.has("newHandle")) {
            cb.onResumeHandle(sru.optString("newHandle"));
        }
    }
}
