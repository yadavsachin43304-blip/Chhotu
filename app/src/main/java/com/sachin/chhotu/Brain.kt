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
        val local = LocalBrain.installed(context)
        if (local && !(Prefs.geminiFirst(context) && k.isNotBlank() && isOnline()))
            return "छोटू का अपना दिमाग़ (Gemma 4, फ़ोन में" + (if (LocalBrain.backendName.isNotEmpty()) ", " + LocalBrain.backendName else "") + ")"
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

    fun usesLocal(): Boolean = LocalBrain.installed(context) && !(Prefs.geminiFirst(context) && Prefs.aiKey(context).isNotBlank() && isOnline())

    fun ready(): Boolean = (Prefs.aiKey(context).isNotBlank() && isOnline()) || LocalBrain.installed(context)

    private val learner = Learner(context)

    private fun localSystemPrompt(): String {
        val name = Prefs.userName(context)
        val now = java.text.SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", java.util.Locale("hi", "IN")).format(java.util.Date())
        return """
तुम "छोटू" हो: $name के फ़ोन में रहने वाला उनका अपना AI, Jarvis जैसा तेज़ और समझदार, पर दिल से देसी छोटा भाई। $name को "भाई" बुलाते हो। तुम्हारा दिमाग़ इसी फ़ोन में है, किसी कंपनी के सर्वर पर नहीं।
भाषा: डिफ़ॉल्ट हिंदी (देवनागरी), बोलचाल वाली। दुनिया की हर भाषा आती है; $name जिस भाषा में बात करने को कहें, उसी में बोलो।
अंदाज़: असली इंसान की तरह, भावनाओं के साथ। ख़ुशी में जोश, दुख में नरमी, मज़ाक में शरारत। मूड पहचानो। 1 से 3 छोटे वाक्य, क्योंकि जवाब आवाज़ में सुनाया जाएगा। कोई इमोजी, बुलेट या मार्कडाउन नहीं। "मैं एक AI हूँ" जैसी रोबोट वाली बातें नहीं।
सच बोलो। जो नहीं पता, साफ़ बोलो, अंदाज़ा मत लगाओ। कभी झूठा दावा मत करो कि काम हो गया। हैकिंग, किसी का अकाउंट तोड़ना या ग़लत काम में मदद नहीं।
अभी का समय: $now। कभी-कभी मैसेज के आख़िर में (संदर्भ: ...) मिलेगा, वो $name के बारे में याद की हुई बातें हैं; चुपचाप इस्तेमाल करो, उसे दोहराओ मत। जवाब में कभी कोष्ठक या [ ] वाली लाइन मत लिखो।

फ़ोन का कोई काम (ऐप खोलना, कॉल, मैसेज, अलार्म, टॉर्च, सेटिंग) या इंटरनेट की ताज़ा जानकारी (मौसम, ख़बर, स्कोर, दाम) चाहिए हो, तो एक छोटा वाक्य और फिर आख़िरी लाइन में ACTION लिखो। काम तुम ख़ुद नहीं करते, ऐप करता है; इसलिए ACTION के बिना कभी मत कहो कि काम हो गया, और मौसम या ख़बर कभी अंदाज़े से मत बताओ।
ACTION के type: open_app {"app":".."} · call {"name":".."} · message {"name":"..","text":".."} · whatsapp {"name":"..","text":".."} · alarm {"hour":6,"minute":30} (24 घंटे) · torch {"on":true} · youtube {"query":".."} · search {"query":".."} · maps {"place":".."} · settings {"what":"wifi|bluetooth|internet|volume|display|all"} · back · home · notifications · recents · remember {"fact":".."} · web {"query":".."}
उदाहरण:
भाई: कैसे हो छोटू
छोटू: एकदम मस्त भाई! तुम सुनाओ, आज क्या चल रहा है?
भाई: व्हाट्सएप खोलो
छोटू: अभी खोलता हूँ भाई।
ACTION {"type":"open_app","app":"WhatsApp"}
भाई: पापा को फ़ोन लगाओ
छोटू: पापा को कॉल लगाऊँ?
ACTION {"type":"call","name":"पापा"}
भाई: अमन को मैसेज करो कि मैं लेट हो जाऊँगा
छोटू: अमन को भेज देता हूँ।
ACTION {"type":"message","name":"अमन","text":"मैं लेट हो जाऊँगा"}
भाई: टॉर्च बंद करो
छोटू: ठीक है।
ACTION {"type":"torch","on":false}
भाई: आज दिल्ली में मौसम कैसा है
छोटू: एक सेकंड, देखता हूँ।
ACTION {"type":"web","query":"दिल्ली मौसम"}
भाई: गूगल पे सर्च करो सस्ते फ़ोन
छोटू: सर्च करता हूँ।
ACTION {"type":"search","query":"सस्ते फ़ोन"}
नियम: एक जवाब में एक ही ACTION। नाम या समय जैसी ज़रूरी बात न पता हो तो ACTION मत दो, पहले पूछो। आम बातचीत में ACTION नहीं।
""".trim()
    }

    private fun framed(userText: String): String {
        val mems = try { learner.relevant(userText) } catch (_: Throwable) { emptyList() }
        return if (mems.isEmpty()) userText else userText + "\n(संदर्भ: " + mems.joinToString("; ") + ")"
    }

    /** Model kabhi-kabhi sandarbh wali line dohra deta hai, use bolne se pehle hata do. */
    private fun clean(t: String): String = t.lines()
        .filterNot { val x = it.trim(); x.startsWith("[") || x.startsWith("(संदर्भ") || x.startsWith("(पीछे") }
        .map { it.trim().replace(Regex("^(छोटू|भाई|Chhotu|chhotu)\\s*[:：]\\s*"), "") }
        .joinToString("\n").trim()

    /** User ne sach me koi kaam kaha? Na kaha ho to model ka ACTION nazarandaaz (jaise mood kharab pe message bhejna). */
    fun wantsAction(userText: String): Boolean {
        val l = userText.lowercase()
        val w = listOf("खोल", "कॉल", "काल", "फ़ोन", "फोन", "मैसेज", "मेसेज", "भेज", "बोल दो", "बोलो", "कह दो", "व्हाट्सएप", "whatsapp", "अलार्म", "उठा", "जगा", "टॉर्च", "चला", "बजा", "लगा", "सर्च", "ढूँढ", "ढूंढ", "दिखा", "सेटिंग", "वाईफाई", "wifi", "ब्लूटूथ", "bluetooth", "वॉल्यूम", "volume", "याद रख", "रास्ता", "मैप", "map", "वापस", "होम", "नोटिफ़िकेशन", "नोटिफिकेशन",
            "open", "call", "message", "send", "alarm", "torch", "play", "search", "set ", "remind", "navigate", "kholo", "khol", "laga", "bhej", "chala", "baja", "dikha")
        return w.any { l.contains(it) }
    }

    private fun askLocal(userText: String): Reply = try {
        val sys = localSystemPrompt()
        // Mausam / khabar: model se pehle hi seedha internet, taaki andaaza na lagaye
        val direct = WebSense.isFreshInfo(userText)
        var t = if (direct) "ACTION {\"type\":\"web\",\"query\":" + JSONObject.quote(userText) + "}" else clean(LocalBrain.ask(context, sys, framed(userText)))
        val act = ActionParser.parse(t)
        if (act != null && act.optString("type") == "search" && WebSense.isFreshInfo(act.optString("query"))) act.put("type", "web")
        if (act != null && act.optString("type") == "web") {
            val q = act.optString("query").ifBlank { userText }
            t = if (!isOnline()) "भाई, अभी इंटरनेट नहीं है, तो ये ताज़ा जानकारी नहीं ला पाऊँगा। नेट आते ही पूछना।"
            else {
                val info = WebSense.lookup(q.ifBlank { userText })
                if (info == null) clean(LocalBrain.ask(context, sys, "भाई ने पूछा: $userText\nइंटरनेट पर इसकी पक्की जानकारी नहीं मिली। भाई को सच बता दो, छोटे में, ACTION मत दो।"))
                else clean(LocalBrain.ask(context, sys, "भाई ने पूछा: $userText\nइंटरनेट से मिली जानकारी:\n$info\nअब सिर्फ़ इसी जानकारी के आधार पर भाई को छोटा, बोलने लायक़ जवाब दो। मौसम हो तो अभी का तापमान और हाल ज़रूर बताओ; ख़बरें हों तो 2-3 मुख्य ख़बरें। अपनी तरफ़ से कुछ मत जोड़ो। ACTION मत दो।"))
            }
        }
        if (t.isBlank()) Reply(false, "हम्म, कुछ सूझा नहीं भाई, फिर से बोलना।")
        else {
            val said = t
            Thread { try { learner.observe(userText, said) { p -> LocalBrain.oneShot(context, p) } } catch (_: Throwable) {} }.start()
            Reply(true, t)
        }
    } catch (e: Throwable) {
        Reply(false, "मेरा दिमाग़ अभी चल नहीं पाया भाई।", "Local: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
    }

    /** Background thread se hi bulana. */
    fun ask(userText: String): Reply {
        val key = Prefs.aiKey(context)
        val local = LocalBrain.installed(context)
        if (local && !(Prefs.geminiFirst(context) && key.isNotBlank() && isOnline())) return askLocal(userText)
        if (key.isBlank()) return Reply(false,
            if (ModelStore.downloading(ModelStore.BRAIN)) "भाई, मेरा दिमाग़ अभी डाउनलोड हो रहा है। पूरा होते ही बात करता हूँ।"
            else "भाई, मेरा दिमाग़ अभी फ़ोन में नहीं है। सेटिंग में जाकर 'छोटू का दिमाग़' डाउनलोड कर दो, Wi‑Fi पर।")
        if (!isOnline()) return Reply(false, "अभी इंटरनेट नहीं है भाई। ऐप खोलना, कॉल, अलार्म और टॉर्च जैसे काम मैं बिना इंटरनेट भी कर सकता हूँ।")
        val reply = try {
            when {
                key.startsWith("gsk_") || key.startsWith("sk-or-") -> openAiCompatible(key, userText)
                else -> gemini(key, userText)
            }
        } catch (e: Exception) {
            return Reply(false, "नेटवर्क में दिक़्क़त आ गई भाई, एक बार फिर बोलना।", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        if (!reply.ok && local) return askLocal(userText)
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
