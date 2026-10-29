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

    /** Background thread se. Kuch na mile to null. */
    fun lookup(query: String): String? {
        val parts = listOfNotNull(ddg(query), wiki("hi", query), wiki("en", query))
        return if (parts.isEmpty()) null else parts.joinToString("\n").take(1500)
    }
}
