package com.sachin.chhotu

import org.json.JSONObject

/**
 * Dimaag ke jawab se ACTION nikalna. Chhota model alag-alag tarah likhta hai:
 *  ACTION {"type":"call","name":"मम्मी"}
 *  ACTION: call {"name":"मम्मी"}
 *  ACTION: back   /   ACTION: none   /   ACTION:
 */
object ActionParser {
    private val line = Regex("^\\W*ACTION\\b\\s*:?\\s*([A-Za-z_]+)?\\s*(\\{.*\\})?\\s*$")

    fun isActionLine(s: String) = line.matches(s.trim()) || s.trim().startsWith("ACTION")

    fun spoken(text: String): String = text.lines().filterNot { isActionLine(it) }.joinToString(" ").replace(Regex("\\s+"), " ").trim()

    fun parse(text: String): JSONObject? {
        val l = text.lines().map { it.trim() }.lastOrNull { line.matches(it) } ?: return null
        val m = line.find(l) ?: return null
        val word = m.groupValues[1].lowercase()
        val json = m.groupValues[2].takeIf { it.isNotBlank() }?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: JSONObject()
        if (!json.has("type")) {
            if (word.isBlank() || word in setOf("none", "null", "no", "koi")) return null
            json.put("type", word)
        }
        return if (json.optString("type").isBlank() || json.optString("type") == "none") null else json
    }
}
