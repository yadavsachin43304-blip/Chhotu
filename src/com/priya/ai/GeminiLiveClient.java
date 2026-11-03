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
            path = PATH + URLEncoder.encode(apiKey, "UTF-8");
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
            gen.put("speechConfig", new JSONObject().put("voiceConfig",
                    new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice))));
            setup.put("generationConfig", gen);

            setup.put("systemInstruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", systemText))));
            setup.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", toolDeclarations())));
            setup.put("inputAudioTranscription", new JSONObject());
            setup.put("outputAudioTranscription", new JSONObject());

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
        a.put(fn("open_app", "Open an installed Android app by its name (e.g. WhatsApp, Chrome, Camera, Settings).",
                new JSONObject().put("app_name", str("App name as the user says it")), "app_name"));
        a.put(fn("youtube_play", "Search YouTube for a song or video and start playing the first result.",
                new JSONObject().put("query", str("Song or video name, e.g. 'Arijit Singh Tum Hi Ho'")), "query"));
        a.put(fn("phone_control", "Control the phone: navigation, scrolling, volume, media keys, flashlight, lock screen, screenshot, settings screens.",
                new JSONObject().put("action", enumStr("Action to perform",
                        "home", "back", "recents", "notifications", "quick_settings",
                        "scroll_down", "scroll_up", "lock_screen", "screenshot",
                        "volume_up", "volume_down", "mute",
                        "media_play_pause", "media_next", "media_previous",
                        "flashlight_on", "flashlight_off", "wifi_settings", "bluetooth_settings")), "action"));
        a.put(fn("click_on_screen", "Tap a button or item on the current screen that shows the given text or label.",
                new JSONObject().put("text", str("Visible text or content description of the item to tap")), "text"));
        a.put(fn("type_text", "Type text into the currently focused input field on the screen.",
                new JSONObject().put("text", str("Text to type")), "text"));
        a.put(fn("read_screen", "Read the visible text of the current screen so you can describe it or decide what to tap.", null));
        a.put(fn("call_contact", "Place a phone call to a saved contact name or a phone number. Confirm with the user before calling.",
                new JSONObject().put("name_or_number", str("Contact name or phone number")), "name_or_number"));
        a.put(fn("web_search", "Open a Google search for the query in the browser.",
                new JSONObject().put("query", str("Search query")), "query"));
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
