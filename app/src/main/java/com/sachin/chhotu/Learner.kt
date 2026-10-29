package com.sachin.chhotu

import android.content.Context

/**
 * Chhotu khud seekhta hai (honest tareeka): model ke weights phone pe nahi badalte,
 * par har baat-cheet se Sachin ke baare me pakki baatein, pasand, galti-sudhaar aur aadatein
 * yaadon (append-only, kabhi na mitne wali) me jud jaati hain, aur har jawab se pehle
 * sawaal se milti-julti yaadein dimaag ko di jaati hain.
 */
class Learner(private val context: Context) {
    private val mem get() = MemoryStore.get(context)
    private var sinceLearn = 0
    private val buffer = ArrayList<String>()

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()

    /** Sawaal se sabse milti-julti yaadein (facts + sudhaar), sabse nayi ko thoda zyada wazan. */
    fun relevant(query: String, limit: Int = 8): List<String> {
        val q = words(query)
        val all = mem.recent("fact", 400) + mem.recent("lesson", 200)
        val scored = all.mapIndexed { i, m ->
            val overlap = words(m.text).intersect(q).size
            m.text to (overlap * 10 + (if (m.type == "lesson") 3 else 0) - i / 50)
        }
        val top = scored.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        val always = mem.recent("lesson", 3).map { it.text }
        return (always + top).distinct().take(limit)
    }

    /** Har jawab ke baad bulao (background). Galti-sudhaar turant, baaki har 4 baat ke baad dimaag se nichod. */
    fun observe(user: String, reply: String, think: (String) -> String?) {
        val u = user.lowercase()
        val correction = listOf("galat", "ग़लत", "गलत", "aisa nahi", "ऐसा नहीं", "nahi yaar", "मत किया कर", "aage se", "आगे से", "हमेशा", "kabhi mat")
        if (correction.any { u.contains(it) }) mem.add("lesson", "Sachin ने सिखाया: $user")
        buffer.add("Sachin: $user\nछोटू: $reply")
        if (++sinceLearn < 4) return
        sinceLearn = 0
        val talk = buffer.joinToString("\n"); buffer.clear()
        val known = mem.recent("fact", 60).joinToString("\n") { "- " + it.text }
        val out = think("""नीचे की बातचीत से Sachin के बारे में सिर्फ़ नई, पक्की और काम की बातें निकालो (नाम, रिश्ते, पसंद, आदतें, लक्ष्य, ज़रूरी तारीख़ें)। जो पहले से पता है उसे मत दोहराओ। हर बात एक अलग लाइन में "- " से शुरू करो। कुछ नया न हो तो सिर्फ़ NONE लिखो।
पहले से पता:
$known
बातचीत:
$talk""") ?: return
        out.lines().map { it.trim() }.filter { it.startsWith("- ") && it.length in 6..200 }.take(5)
            .forEach { mem.add("fact", it.removePrefix("- ").trim()) }
    }
}
