package com.sachin.chhotu

/** Text ki lipi dekh kar bhasha ka code (Sarvam / TTS / sunne ke liye). */
object Lang {
    fun detect(text: String): String {
        val counts = HashMap<String, Int>()
        for (ch in text) {
            val code = when (ch.code) {
                in 0x0900..0x097F -> "hi-IN"
                in 0x0980..0x09FF -> "bn-IN"
                in 0x0A00..0x0A7F -> "pa-IN"
                in 0x0A80..0x0AFF -> "gu-IN"
                in 0x0B00..0x0B7F -> "od-IN"
                in 0x0B80..0x0BFF -> "ta-IN"
                in 0x0C00..0x0C7F -> "te-IN"
                in 0x0C80..0x0CFF -> "kn-IN"
                in 0x0D00..0x0D7F -> "ml-IN"
                in 'a'.code..'z'.code, in 'A'.code..'Z'.code -> "en-IN"
                else -> null
            } ?: continue
            counts[code] = (counts[code] ?: 0) + 1
        }
        if (counts.isEmpty()) return "hi-IN"
        val total = counts.values.sum()
        val en = counts["en-IN"] ?: 0
        val best = counts.filterKeys { it != "en-IN" }.maxByOrNull { it.value }
        // Hinglish/code-mix me Indic lipi ho to wahi
        return if (best != null && best.value * 4 >= total) best.key else if (en > 0) "en-IN" else best?.key ?: "hi-IN"
    }
}
