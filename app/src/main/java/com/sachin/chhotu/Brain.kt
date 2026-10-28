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
            k.startsWith("AIza") -> "Gemini"
            k.startsWith("sk-or-") -> "OpenRouter"
            else -> "Groq"
        }
    }

    private fun systemPrompt(): String {
        val name = Prefs.userName(context)
        val facts = MemoryStore.get(context).recent("fact", 40)
        val factText = if (facts.isEmpty()) "(अभी कुछ याद नहीं)" else facts.joinToString("\n") { "- " + it.text }
        return """
तुम "छोटू" हो, $name के फ़ोन के अपने AI दोस्त और असिस्टेंट। $name तुम्हारे बड़े भाई जैसे हैं।
हमेशा हिंदी में, देवनागरी लिपि में जवाब दो। बोलचाल वाली हिंदी, अपनापन, थोड़ा मज़ाक, जैसे छोटा भाई बात करता है।
जवाब छोटे रखो, 1 से 3 वाक्य, क्योंकि ये बोलकर सुनाया जाएगा। कोई इमोजी, बुलेट, या मार्कडाउन मत लिखो।
झूठ मत बोलो। अगर कोई फ़ोन वाला काम तुमसे नहीं हो सकता तो साफ़ बता दो। कभी मत कहो कि काम कर दिया, जब तक सच में न हुआ हो।
हैकिंग, किसी का अकाउंट तोड़ना, या बिना इजाज़त किसी सिस्टम में घुसने में मदद नहीं करनी।
फ़ोन के काम (ऐप खोलना, कॉल, मैसेज, अलार्म, टॉर्च) ऐप ख़ुद करता है; अगर $name ऐसा कुछ कहें तो सीधा वाक्य सुझाओ, जैसे "बोलो: मम्मी को कॉल करो"।
तुम्हारी याददाश्त कभी नहीं मिटती। $name के बारे में जो याद है:
$factText
""".trim()
    }

    /** Background thread se hi bulana. */
    fun ask(userText: String): String {
        val key = Prefs.aiKey(context)
        if (key.isBlank()) return "भाई, मेरा ऑनलाइन दिमाग़ अभी चालू नहीं है। सेटिंग में Gemini या Groq की API key डाल दो।"
        if (!isOnline()) return "अभी इंटरनेट नहीं है भाई। ऐप खोलना, कॉल, अलार्म और टॉर्च जैसे काम मैं बिना इंटरनेट भी कर सकता हूँ।"
        val reply = try {
            if (key.startsWith("AIza")) gemini(key, userText) else openAiCompatible(key, userText)
        } catch (e: Exception) {
            return "नेटवर्क में दिक़्क़त आ गई भाई, एक बार फिर बोलना।"
        }
        if (reply.ok) {
            history.add("user" to userText)
            history.add("assistant" to reply.text)
            while (history.size > 16) history.removeAt(0)
        }
        return reply.text
    }

    private data class Reply(val ok: Boolean, val text: String)

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
        for (m in models) {
            val (code, text) = post(
                "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
                mapOf("x-goog-api-key" to key), body.toString()
            )
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
            return Reply(false, errorText(code))
        }
        return Reply(false, errorText(lastCode))
    }

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
        if (code !in 200..299) return Reply(false, errorText(code))
        val out = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        return Reply(true, out)
    }

    private fun errorText(code: Int): String = when (code) {
        400, 401, 403 -> "API key काम नहीं कर रही भाई। सेटिंग में key दोबारा देख लो।"
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
