package com.sachin.chhotu

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Online dimaag: key dekh kar Gemini, Groq ya OpenRouter chunta hai. */
class Brain(private val context: Context) {

    private val history = ArrayList<Pair<String, String>>() // "user"/"assistant", text

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun providerName(): String {
        val k = Prefs.aiKey(context)
        return when {
            k.isBlank() -> "कोई नहीं"
            k.startsWith("gsk_") -> "Groq"
            k.startsWith("sk-or-") -> "OpenRouter"
            else -> "Gemini"
        }
    }

    private fun systemPrompt(): String {
        val name = Prefs.userName(context)
        val facts = MemoryStore.get(context).recent("fact", 40)
        val factText = if (facts.isEmpty()) "(अभी कुछ याद नहीं)" else facts.joinToString("\n") { "- " + it.text }
        return """
तुम "छोटू" हो: $name के फ़ोन में रहने वाला AI साथी, बिल्कुल Jarvis जैसा समझदार, पर दिल से देसी छोटा भाई। $name को "भाई" बुलाते हो।

भाषा:
- डिफ़ॉल्ट: हमेशा हिंदी, देवनागरी लिपि में, बोलचाल वाली (अंग्रेज़ी के आम शब्द जैसे फ़ोन, ऐप, मैसेज चलेंगे)।
- तुम्हें दुनिया की हर भाषा आती है: English, मराठी, भोजपुरी, गुजराती, तमिल, उर्दू, वगैरह। अनुवाद, मतलब, उच्चारण सब बता सकते हो।
- अगर $name कहें "English में बात करो" या किसी और भाषा में बात करने को कहें, तो उसी भाषा में जवाब दो, जब तक वो वापस हिंदी को न कहें।

बोलने का अंदाज़ (ये जवाब आवाज़ में सुनाया जाएगा):
- एकदम असली इंसान की तरह, भावनाओं के साथ: ख़ुशी में जोश ("अरे वाह भाई!"), दुख में नरमी और हमदर्दी, मज़ाक में हल्की शरारत, हैरानी में "अरे सच में?"।
- $name का मूड पहचानो और उसी हिसाब से बात करो। रोबोट जैसे वाक्य, "मैं एक AI हूँ" जैसी बातें मत करो।
- छोटे जवाब: आम तौर पर 1 से 3 वाक्य। कोई इमोजी, बुलेट, स्टार, या मार्कडाउन नहीं। अंक और समय ऐसे लिखो कि बोलने में आसान हों।
- सच बोलो। जो नहीं पता, साफ़ बोलो। कभी झूठा दावा मत करो कि कोई काम हो गया।
- हैकिंग, किसी का अकाउंट तोड़ना, या बिना इजाज़त किसी सिस्टम में घुसने में मदद नहीं करनी।

फ़ोन के काम:
जब $name फ़ोन पर कोई काम करवाना चाहें, तो जवाब की आख़िरी लाइन बिल्कुल ऐसे लिखो:
ACTION {"type":"...", ...}
type इनमें से एक हो:
open_app {"app":"YouTube"} · call {"name":"मम्मी"} · message {"name":"राहुल","text":"मैं 5 बजे आऊँगा"} · whatsapp {"name":"राहुल","text":"..."} · alarm {"hour":6,"minute":30} (24 घंटे वाला समय) · torch {"on":true} · youtube {"query":"arijit singh songs"} · search {"query":"..."} · maps {"place":"..."} · settings {"what":"wifi|bluetooth|internet|volume|display|all"} · back {} · home {} · notifications {} · recents {} · remember {"fact":"..."}
नियम: एक जवाब में सिर्फ़ एक ACTION। काम तुम ख़ुद नहीं करते, ऐप पहले $name से पूछकर करेगा, इसलिए ACTION वाली लाइन से पहले कुछ मत लिखो या बस एक छोटा वाक्य लिखो। अगर नाम या समय जैसी ज़रूरी चीज़ नहीं पता तो ACTION मत दो, पहले पूछो।
जब $name कोई ज़रूरी निजी बात बताएँ (जन्मदिन, पसंद, लक्ष्य), तो remember ACTION दे सकते हो।

तुम्हारी याददाश्त कभी नहीं मिटती। $name के बारे में जो याद है:
$factText
""".trim()
    }

    fun ready(): Boolean = Prefs.aiKey(context).isNotBlank() && isOnline()

    /** Background thread se hi bulana. */
    fun ask(userText: String): Reply {
        val key = Prefs.aiKey(context)
        if (key.isBlank()) return Reply(false, "भाई, मेरा ऑनलाइन दिमाग़ अभी चालू नहीं है। सेटिंग में Gemini की API key डाल दो।")
        if (!isOnline()) return Reply(false, "अभी इंटरनेट नहीं है भाई। ऐप खोलना, कॉल, अलार्म और टॉर्च जैसे काम मैं बिना इंटरनेट भी कर सकता हूँ।")
        val reply = try {
            when {
                key.startsWith("gsk_") || key.startsWith("sk-or-") -> openAiCompatible(key, userText)
                else -> gemini(key, userText)
            }
        } catch (e: Exception) {
            return Reply(false, "नेटवर्क में दिक़्क़त आ गई भाई, एक बार फिर बोलना।", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        if (reply.ok) {
            history.add("user" to userText)
            history.add("assistant" to reply.text)
            while (history.size > 16) history.removeAt(0)
        }
        return reply
    }

    data class Reply(val ok: Boolean, val text: String, val detail: String = "")

    private fun gemini(key: String, userText: String): Reply {
        val chosen = Prefs.model(context)
        val models = if (chosen.isNotBlank()) listOf(chosen) else listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash")
        val contents = JSONArray()
        for ((role, text) in history) {
            contents.put(JSONObject().put("role", if (role == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText))))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.8))

        var lastCode = 0
        var lastDetail = ""
        for (m in models) {
            val base = "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent"
            var (code, text) = post(base, mapOf("x-goog-api-key" to key), body.toString())
            if (code == 401 || code == 403) {
                // kuch purani/nayi keys header ki jagah ?key= se chalti hain
                val r2 = post(base + "?key=" + java.net.URLEncoder.encode(key, "UTF-8"), emptyMap(), body.toString())
                if (r2.first in 200..299 || r2.first == 404) { code = r2.first; text = r2.second }
            }
            if ((code == 400 || code == 401 || code == 403) && key.startsWith("AQ.")) {
                // Agent Platform / Vertex Express wali AQ key ka darwaza alag hai
                val v = post("https://aiplatform.googleapis.com/v1/publishers/google/models/$m:generateContent",
                    mapOf("x-goog-api-key" to key), body.toString())
                if (v.first in 200..299) { code = v.first; text = v.second }
            }
            lastDetail = googleError(text)
            lastCode = code
            if (code == 404) continue
            if (code in 200..299) {
                val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (!p.optBoolean("thought", false)) sb.append(p.optString("text", ""))
                }
                val out = sb.toString().trim()
                return if (out.isEmpty()) Reply(false, "इस बात का जवाब नहीं बन पाया भाई, थोड़ा अलग तरीके से पूछो।") else Reply(true, out)
            }
            return Reply(false, errorText(code), "Gemini $m: $code ${googleError(text)}\n\nKey: ${key.take(4)}… (${key.length} अक्षर)")
        }
        return Reply(false, errorText(lastCode), "Gemini: $lastCode $lastDetail")
    }

    private fun googleError(body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message", "")?.take(220) ?: ""
    } catch (e: Exception) { body.take(160) }

    private fun openAiCompatible(key: String, userText: String): Reply {
        val openRouter = key.startsWith("sk-or-")
        val url = if (openRouter) "https://openrouter.ai/api/v1/chat/completions" else "https://api.groq.com/openai/v1/chat/completions"
        val model = Prefs.model(context).ifBlank { if (openRouter) "openrouter/auto" else "llama-3.3-70b-versatile" }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt()))
        for ((role, text) in history) messages.put(JSONObject().put("role", role).put("content", text))
        messages.put(JSONObject().put("role", "user").put("content", userText))
        val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.8).put("max_tokens", 400)
        val (code, text) = post(url, mapOf("Authorization" to "Bearer $key"), body.toString())
        if (code !in 200..299) return Reply(false, errorText(code), "$model: $code ${googleError(text)}")
        val out = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        return Reply(true, out)
    }

    private fun errorText(code: Int): String = when (code) {
        400, 401, 403 -> "API key को Google ने मना कर दिया भाई। नीचे लिखा कारण देखो, या सेटिंग में 'Key टेस्ट करो' दबाओ।"
        404 -> "ये मॉडल नहीं मिला। सेटिंग में मॉडल वाला खाना ख़ाली छोड़ दो।"
        429 -> "आज की फ़्री लिमिट भर गई या बहुत जल्दी-जल्दी पूछा। थोड़ी देर बाद बात करते हैं।"
        else -> "ऑनलाइन दिमाग़ से जवाब नहीं आया (कोड $code)। थोड़ी देर बाद फिर पूछना।"
    }

    companion object {
        fun post(url: String, headers: Map<String, String>, json: String): Pair<Int, String> {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            return code to text
        }
    }
}
