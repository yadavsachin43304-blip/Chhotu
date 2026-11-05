package com.priya.ai;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Plain REST calls to the Gemini Interactions API (v1beta/interactions), using the same API key the app already has.
 * Docs (checked 2026-11-04): ai.google.dev/gemini-api/docs/interactions-overview, text-generation, image-generation,
 * speech-generation, audio, function-calling. Response = {"id", "steps":[{type:function_call|model_output|...}]}.
 */
final class GeminiRest {

    static final String URL_INTERACTIONS = "https://generativelanguage.googleapis.com/v1beta/interactions";
    static final String API_REVISION = "2026-05-20";

    /**
     * Text / reasoning / audio understanding / function calling models, best first.
     * gemini-4-argon (stable, 2026-10-13): flagship reasoning, Interactions API, text output only, NO Live API,
     * paid tier only (no free tier), ignores temperature/top_p/top_k, thinking_level low|medium|high.
     * If the key has no billing, Argon answers 4xx and we fall through to 3.8 Flash and remember it for a while.
     */
    static final String ARGON = "gemini-4-argon";
    static final String[] TEXT_MODELS = {ARGON, "gemini-3.8-flash", "gemini-3.6-flash"};
    /** Argon refused (no billing / quota): skip it until this time, so every call isn't slowed by a failing try. */
    static volatile long argonSkipUntil = 0;

    /** Models to try now, in order (Argon dropped while it is in its skip window). */
    static String[] textModels() {
        if (System.currentTimeMillis() < argonSkipUntil) {
            String[] r = new String[TEXT_MODELS.length - 1];
            System.arraycopy(TEXT_MODELS, 1, r, 0, r.length);
            return r;
        }
        return TEXT_MODELS;
    }

    /** Call after a model failed with a try-next error. */
    static void noteFail(String model, Exception e) {
        if (ARGON.equals(model) && e instanceof ApiError) {
            int code = ((ApiError) e).code;
            if (code == 400 || code == 401 || code == 403 || code == 404 || code == 429) argonSkipUntil = System.currentTimeMillis() + 30 * 60 * 1000L;
        }
    }

    /** Adds Argon's thinking level (only Argon gets it; others keep their defaults). */
    static JSONObject withThinking(JSONObject b, String model, String level) throws org.json.JSONException {
        if (ARGON.equals(model)) {
            JSONObject g = b.optJSONObject("generation_config");
            if (g == null) { g = new JSONObject(); b.put("generation_config", g); }
            g.put("thinking_level", level);
        }
        return b;
    }
    /** Nano Banana image models, best first. */
    static final String[] IMAGE_MODELS = {"gemini-nano-banana-2.1", "gemini-3.1-flash-image", "gemini-2.5-flash-image"};
    /** TTS models, best first. Voice pinned to Priya's voice (Kore). */
    static final String[] TTS_MODELS = {"gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts"};

    static final class ApiError extends Exception {
        final int code;
        ApiError(int code, String msg) { super(msg); this.code = code; }
    }

    private GeminiRest() {}

    static String key(Context c) {
        return Prefs.apiKey(c);
    }

    /** POST one interaction. Throws ApiError with the server's message on non-2xx. */
    static JSONObject create(Context c, JSONObject body, int readTimeoutSec) throws Exception {
        String k = key(c);
        if (k.isEmpty()) throw new ApiError(401, "API key set nahi hai (Menu > API).");
        HttpURLConnection h = (HttpURLConnection) new URL(URL_INTERACTIONS).openConnection();
        h.setRequestMethod("POST");
        h.setConnectTimeout(20000);
        h.setReadTimeout(Math.max(30, readTimeoutSec) * 1000);
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        h.setRequestProperty("x-goog-api-key", k);
        h.setRequestProperty("Api-Revision", API_REVISION);
        byte[] out = Owner.fix(c, body.toString()).getBytes(StandardCharsets.UTF_8);
        h.setFixedLengthStreamingMode(out.length);
        try (OutputStream os = h.getOutputStream()) { os.write(out); }
        int code = h.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? h.getInputStream() : h.getErrorStream();
        String s = in == null ? "" : new String(readAll(in, 64L * 1024 * 1024), StandardCharsets.UTF_8);
        h.disconnect();
        if (code < 200 || code >= 300) {
            String msg = s;
            try { msg = new JSONObject(s).getJSONObject("error").optString("message", s); } catch (Exception ignored) { }
            throw new ApiError(code, "HTTP " + code + ": " + clip(msg, 400));
        }
        return new JSONObject(s);
    }

    static byte[] readAll(InputStream in, long max) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int r;
        long n = 0;
        while ((r = in.read(b)) > 0) {
            bo.write(b, 0, r);
            n += r;
            if (n > max) break;
        }
        in.close();
        return bo.toByteArray();
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /** Model or feature not available for this key/model: try the next model. */
    static boolean tryNext(Exception e) {
        if (!(e instanceof ApiError)) return false;
        int c = ((ApiError) e).code;
        return c == 404 || c == 400 || c == 403 || c == 429 || c >= 500;
    }

    // ------------------------------------------------------------------ response helpers

    static JSONArray steps(JSONObject r) {
        JSONArray st = r.optJSONArray("steps");
        if (st == null) st = r.optJSONArray("outputs"); // legacy schema
        return st == null ? new JSONArray() : st;
    }

    /** All text the model produced (model_output steps), joined. */
    static String outputText(JSONObject r) {
        StringBuilder sb = new StringBuilder();
        JSONArray st = steps(r);
        for (int i = 0; i < st.length(); i++) {
            JSONObject s = st.optJSONObject(i);
            if (s == null) continue;
            String t = s.optString("type", "");
            if (t.equals("text") && s.has("text")) { sb.append(s.optString("text")); continue; } // legacy outputs
            if (!t.equals("model_output")) continue;
            JSONArray c = s.optJSONArray("content");
            if (c == null) continue;
            for (int j = 0; j < c.length(); j++) {
                JSONObject p = c.optJSONObject(j);
                if (p != null && "text".equals(p.optString("type")) ) sb.append(p.optString("text", ""));
            }
        }
        if (sb.length() == 0 && r.has("output_text")) sb.append(r.optString("output_text"));
        return sb.toString().trim();
    }

    /** First inline media object (image/audio) anywhere in the response: {data, mime_type}. */
    static JSONObject findMedia(Object node, String kind) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String mime = o.optString("mime_type", o.optString("mimeType", ""));
            String type = o.optString("type", "");
            if (o.has("data") && (mime.startsWith(kind + "/") || type.equals(kind))) return o;
            JSONArray names = o.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    JSONObject f = findMedia(o.opt(names.optString(i)), kind);
                    if (f != null) return f;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = a.length() - 1; i >= 0; i--) { // last generated block wins
                JSONObject f = findMedia(a.opt(i), kind);
                if (f != null) return f;
            }
        }
        return null;
    }

    /** Collects source urls (url_citation annotations, search results) for a short source list. */
    static void collectUrls(Object node, java.util.LinkedHashSet<String> out) {
        if (out.size() >= 8) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String u = o.optString("url", o.optString("uri", ""));
            if (u.startsWith("http")) out.add(u);
            JSONArray names = o.names();
            if (names != null) for (int i = 0; i < names.length(); i++) collectUrls(o.opt(names.optString(i)), out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectUrls(a.opt(i), out);
        }
    }

    // ------------------------------------------------------------------ high level calls

    /** Plain text generation (scripts, captions, prompts, plans). */
    static String text(Context c, String system, String prompt, int timeoutSec) throws Exception {
        Exception last = null;
        for (String m : textModels()) {
            try {
                JSONObject b = withThinking(new JSONObject().put("model", m).put("input", prompt).put("store", false), m, "low");
                if (system != null && !system.isEmpty()) b.put("system_instruction", system);
                return outputText(create(c, b, ARGON.equals(m) ? Math.max(timeoutSec, 180) : timeoutSec));
            } catch (Exception e) {
                last = e;
                noteFail(m, e);
                if (!tryNext(e)) throw e;
            }
        }
        throw last;
    }

    /** Slow, careful reasoning for hard questions: decompose, solve, self-check, answer. Result is spoken, so it stays short. */
    static String think(Context c, String problem, String background, boolean search) throws Exception {
        String sys = "You are Priya's deep-reasoning engine. The problem may be hard: strategy, maths, code design, planning with many constraints. "
                + "Work like a careful senior engineer/consultant: (1) restate the goal and every constraint, (2) split into sub-problems, "
                + "(3) solve each, trying at least two approaches when the choice matters, (4) CHECK the result yourself (arithmetic, edge cases, "
                + "contradictions, safety), fix mistakes, (5) give the final answer. Never invent facts: if something is unknown say what is missing. "
                + "Reply in the user's language (Hinglish if the input is Hinglish) in this shape: FINAL (3-6 short spoken sentences), "
                + "STEPS (numbered, one line each, max 8), RISKS (max 3 lines, or 'none'), UNSURE (what to verify). No markdown tables.";
        String in = "PROBLEM:\n" + problem + (background == null || background.trim().isEmpty() ? "" : "\n\nCONTEXT:\n" + background);
        Exception last = null;
        for (String m : textModels()) {
            try {
                JSONObject b = withThinking(new JSONObject().put("model", m).put("input", in).put("store", false).put("system_instruction", sys), m, "high");
                if (search) b.put("tools", new JSONArray().put(new JSONObject().put("type", "google_search")));
                String t = outputText(create(c, b, ARGON.equals(m) ? 600 : 90));
                if (!t.trim().isEmpty()) return t;
                last = new Exception("model ne khaali jawab diya");
            } catch (Exception e) {
                last = e;
                noteFail(m, e);
                if (!tryNext(e)) throw e;
            }
        }
        throw last == null ? new Exception("model nahi mila") : last;
    }

    /** Google-Search-grounded answer with sources. */
    static String research(Context c, String question) throws Exception {
        Exception last = null;
        for (String m : textModels()) {
            try {
                JSONObject b = new JSONObject().put("model", m).put("input", question).put("store", false)
                        .put("tools", new JSONArray().put(new JSONObject().put("type", "google_search")))
                        .put("system_instruction", "You are a precise research assistant. Use Google Search. Answer in the user's language "
                                + "(Hinglish if the question is Hinglish), concise and factual, with concrete names, numbers, dates, links. "
                                + "Say clearly if something could not be confirmed.");
                withThinking(b, m, "medium");
                JSONObject r = create(c, b, ARGON.equals(m) ? 420 : 120);
                String t = outputText(r);
                java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
                collectUrls(steps(r), urls);
                StringBuilder sb = new StringBuilder(t);
                if (!urls.isEmpty()) {
                    sb.append("\nSOURCES:");
                    for (String u : urls) sb.append("\n- ").append(u);
                }
                return sb.toString();
            } catch (Exception e) {
                last = e;
                noteFail(m, e);
                if (!tryNext(e)) throw e;
            }
        }
        throw last;
    }

    /** Audio understanding with inline audio (request must stay under ~20 MB). */
    static String audioText(Context c, byte[] audio, String mime, String prompt) throws Exception {
        Exception last = null;
        String b64 = Base64.encodeToString(audio, Base64.NO_WRAP);
        for (String m : textModels()) {
            try {
                JSONArray in = new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", prompt))
                        .put(new JSONObject().put("type", "audio").put("data", b64).put("mime_type", mime));
                JSONObject b = withThinking(new JSONObject().put("model", m).put("input", in).put("store", false), m, "low");
                return outputText(create(c, b, ARGON.equals(m) ? 420 : 240));
            } catch (Exception e) {
                last = e;
                noteFail(m, e);
                if (!tryNext(e)) throw e;
            }
        }
        throw last;
    }

    static final class Media {
        byte[] bytes;
        String mime;
        String model;
    }

    /** Image generation (Nano Banana). aspect like "9:16", "16:9", "1:1". */
    static Media image(Context c, String prompt, String aspect) throws Exception {
        Exception last = null;
        for (String m : IMAGE_MODELS) {
            for (int variant = 0; variant < 2; variant++) {
                try {
                    JSONObject b = new JSONObject().put("model", m).put("store", false)
                            .put("input", new JSONArray().put(new JSONObject().put("type", "text").put("text", prompt)));
                    if (variant == 0 && aspect != null && !aspect.isEmpty()) {
                        b.put("response_format", new JSONObject().put("type", "image").put("aspect_ratio", aspect));
                    }
                    JSONObject r = create(c, b, 180);
                    JSONObject med = findMedia(r, "image");
                    if (med == null) {
                        throw new ApiError(422, "Model ne image nahi di. Text: " + clip(outputText(r), 200));
                    }
                    Media out = new Media();
                    out.bytes = Base64.decode(med.optString("data"), Base64.DEFAULT);
                    out.mime = med.optString("mime_type", med.optString("mimeType", "image/png"));
                    out.model = m;
                    if (out.bytes.length < 100) throw new ApiError(422, "Image khaali aayi.");
                    return out;
                } catch (Exception e) {
                    last = e;
                    boolean badFormat = e instanceof ApiError && ((ApiError) e).code == 400
                            && String.valueOf(e.getMessage()).toLowerCase().contains("response_format");
                    if (variant == 0 && badFormat) continue; // retry same model without response_format
                    if (e instanceof ApiError && ((ApiError) e).code == 422) break; // refused/no image: next model
                    if (!tryNext(e)) throw e;
                    break;
                }
            }
        }
        throw last;
    }

    /** Text-to-speech in Priya's pinned voice. Returns WAV bytes. */
    static Media tts(Context c, String text, String style) throws Exception {
        Exception last = null;
        for (String m : TTS_MODELS) {
            try {
                JSONObject t = new JSONObject().put("type", "text").put("text", text);
                if (style != null && !style.isEmpty()) {
                    t.put("annotations", new JSONArray().put(new JSONObject().put("type", "speech_metadata").put("style", style)));
                }
                JSONObject b = new JSONObject().put("model", m).put("store", false)
                        .put("input", new JSONArray().put(new JSONObject().put("type", "user_input")
                                .put("content", new JSONArray().put(t))))
                        .put("response_format", new JSONObject().put("type", "audio"))
                        .put("generation_config", new JSONObject().put("speech_config",
                                new JSONArray().put(new JSONObject().put("voice", Prefs.VOICE))));
                JSONObject r = create(c, b, 240);
                JSONObject med = findMedia(r, "audio");
                if (med == null) throw new ApiError(422, "TTS ne audio nahi diya.");
                Media out = new Media();
                out.bytes = Base64.decode(med.optString("data"), Base64.DEFAULT);
                out.mime = med.optString("mime_type", "audio/wav");
                out.model = m;
                return out;
            } catch (Exception e) {
                last = e;
                if (!tryNext(e)) throw e;
            }
        }
        throw last;
    }
}
