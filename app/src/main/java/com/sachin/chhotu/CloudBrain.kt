package com.sachin.chhotu

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Online dimaag: Groq (OpenAI jaisa API). */
class CloudBrain(private val context: Context) {

    private val history = ArrayList<Pair<String, String>>() // role, content

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun systemPrompt(): String {
        val name = Prefs.userName(context)
        val facts = MemoryStore.get(context).recent("fact", 40)
        val factText = if (facts.isEmpty()) "(abhi kuch yaad nahi)" else facts.joinToString("\n") { "- " + it.text }
        return """
Tum "Chhotu" ho, $name ke Android phone ka apna AI dost aur assistant.
Hamesha Hindi me jawab do, Devanagari lipi me. Agar $name English shabd bole to wo shabd chal sakte hain.
Baat garmjoshi se, apnepan se aur thode mazaak ke saath karo, jaise chhota bhai. Jawab chhote rakho (2-4 vakya), kyunki ye bol kar sunaya jayega.
Kabhi jhooth mat bolo. Hacking, kisi ka account todna ya bina ijazat kisi system me ghusne me madad nahi karni.
Tumhari yaaddasht kabhi delete nahi hoti. $name ke baare me jo yaad hai:
$factText
""".trim()
    }

    /** Network call. Background thread se hi bulana. */
    fun ask(userText: String): String {
        val key = Prefs.groqKey(context)
        if (key.isBlank()) return "भाई, अभी मेरा ऑनलाइन दिमाग़ चालू नहीं है। Settings में Groq API key डाल दो, फिर मैं हर बात का जवाब दूँगा।"
        if (!isOnline()) return "अभी इंटरनेट नहीं है भाई। फ़ोन वाले काम (ऐप खोलना, कॉल, अलार्म, टॉर्च) मैं बिना इंटरनेट भी कर सकता हूँ।"

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt()))
        for ((role, content) in history.takeLast(12)) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))

        val body = JSONObject()
            .put("model", Prefs.model(context))
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 400)

        return try {
            val conn = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code !in 200..299) {
                return if (code == 401) "API key ग़लत लग रही है भाई, Settings में दोबारा देख लो।"
                else "ऑनलाइन दिमाग़ से जवाब नहीं आया (कोड $code)। थोड़ी देर बाद फिर पूछना।"
            }
            val reply = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            history.add("user" to userText)
            history.add("assistant" to reply)
            reply
        } catch (e: Exception) {
            "नेटवर्क में दिक़्क़त आ गई: ${e.message ?: "पता नहीं क्या"}"
        }
    }
}
