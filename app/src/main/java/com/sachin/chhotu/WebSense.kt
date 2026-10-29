package com.sachin.chhotu

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Internet ki jaankari bina kisi API key ke: Wikipedia (hindi + english) aur DuckDuckGo Instant Answer.
 * Chhotu ka apna dimaag sawaal samajhta hai, ye bas taaza jaankari la kar deta hai.
 */
object WebSense {
    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Chhotu/0.5 (personal Android assistant)")
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (_: Exception) { null }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun wiki(lang: String, q: String): String? {
        val s = get("https://$lang.wikipedia.org/w/api.php?action=query&list=search&srlimit=1&format=json&srsearch=" + enc(q)) ?: return null
        val title = try { JSONObject(s).getJSONObject("query").getJSONArray("search").getJSONObject(0).getString("title") } catch (_: Exception) { return null }
        val sum = get("https://$lang.wikipedia.org/api/rest_v1/page/summary/" + enc(title.replace(' ', '_'))) ?: return null
        return try { val j = JSONObject(sum); "$title: " + j.optString("extract").take(700) } catch (_: Exception) { null }
    }

    private fun ddg(q: String): String? {
        val s = get("https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&q=" + enc(q)) ?: return null
        return try {
            val j = JSONObject(s)
            listOf(j.optString("Answer"), j.optString("AbstractText"), j.optString("Definition")).firstOrNull { it.isNotBlank() }?.take(700)
        } catch (_: Exception) { null }
    }

    private val weatherWords = listOf("मौसम", "weather", "बारिश", "barish", "तापमान", "temperature", "mausam", "गर्मी", "ठंड")
    private val newsWords = listOf("ख़बर", "खबर", "ख़बरें", "खबरें", "news", "समाचार", "khabar", "headlines")

    /** wttr.in: bina key mausam. Jagah na ho to phone ke internet se jagah khud pehchanta hai. */
    private fun weather(q: String): String? {
        var place = q
        for (w in weatherWords + listOf("आज", "कल", "का", "की", "के", "में", "कैसा", "कैसी", "है", "क्या", "aaj", "ka", "kaisa", "hai", "today", "in", "what", "is", "the")) place = place.replace(Regex("(?i)(^|\\s)" + Regex.escape(w) + "(?=\\s|$)"), " ")
        place = place.replace(Regex("[?।,.!]"), " ").trim()
        val s = get("https://wttr.in/" + enc(place) + "?format=j1&lang=hi") ?: return null
        return try {
            val j = JSONObject(s); val c = j.getJSONArray("current_condition").getJSONObject(0)
            val d = j.getJSONArray("weather").getJSONObject(0)
            val area = try { j.getJSONArray("nearest_area").getJSONObject(0).getJSONArray("areaName").getJSONObject(0).getString("value") } catch (_: Exception) { place }
            val desc = c.optJSONArray("lang_hi")?.optJSONObject(0)?.optString("value")?.ifBlank { null } ?: c.getJSONArray("weatherDesc").getJSONObject(0).getString("value")
            "$area का मौसम अभी: ${c.getString("temp_C")}°C (महसूस ${c.getString("FeelsLikeC")}°C), $desc, नमी ${c.getString("humidity")}%. आज अधिकतम ${d.getString("maxtempC")}°C, न्यूनतम ${d.getString("mintempC")}°C."
        } catch (_: Exception) { null }
    }

    /** Google News RSS (hindi): taaza surkhiyan, bina key. */
    private fun news(q: String): String? {
        var topic = q
        for (w in newsWords + listOf("आज", "की", "का", "के", "ताज़ा", "ताजा", "क्या", "है", "बताओ", "latest", "today", "aaj", "ki")) topic = topic.replace(Regex("(?i)(^|\\s)" + Regex.escape(w) + "(?=\\s|$)"), " ")
        topic = topic.replace(Regex("[?।,.!]"), " ").trim()
        val url = if (topic.isBlank()) "https://news.google.com/rss?hl=hi&gl=IN&ceid=IN:hi"
            else "https://news.google.com/rss/search?q=" + enc(topic) + "&hl=hi&gl=IN&ceid=IN:hi"
        val s = get(url) ?: return null
        val titles = Regex("<title>(.*?)</title>").findAll(s).map { it.groupValues[1].replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'") }
            .drop(1).take(5).map { it.substringBeforeLast(" - ") }.toList()
        return if (titles.isEmpty()) null else "आज की ताज़ा ख़बरें: " + titles.joinToString(" | ")
    }

    fun isFreshInfo(q: String): Boolean { val l = q.lowercase(); return weatherWords.any { l.contains(it) } || newsWords.any { l.contains(it) } }

    /** Background thread se. Kuch na mile to null. */
    fun lookup(query: String): String? {
        val low = query.lowercase()
        if (weatherWords.any { low.contains(it) }) weather(query)?.let { return it }
        if (newsWords.any { low.contains(it) }) news(query)?.let { return it }
        val parts = listOfNotNull(ddg(query), wiki("hi", query), wiki("en", query))
        return if (parts.isEmpty()) null else parts.joinToString("\n").take(1500)
    }
}
