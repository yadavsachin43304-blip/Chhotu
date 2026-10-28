package com.sachin.chhotu

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "chhotu_prefs"
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences(FILE, 0)

    /** Gemini (AIza...) ya Groq (gsk_...) ya OpenRouter (sk-or-...) key. */
    fun aiKey(c: Context): String {
        val s = sp(c)
        val k = s.getString("ai_key", null)
        if (k != null) return k
        return s.getString("groq_key", "") ?: "" // v0.1 se purani key
    }
    fun model(c: Context): String = sp(c).getString("model", "") ?: ""
    fun sarvamKey(c: Context): String = sp(c).getString("sarvam_key", "") ?: ""
    fun voice(c: Context): String = sp(c).getString("voice", "shubh") ?: "shubh"
    fun userName(c: Context): String = sp(c).getString("user_name", "Sachin") ?: "Sachin"
    fun powerOn(c: Context): Boolean = sp(c).getBoolean("power_on", false)
    fun setPower(c: Context, on: Boolean) = sp(c).edit().putBoolean("power_on", on).apply()

    /** Copy-paste me aaye space, new line, quotes, chhupe hue characters hata do */
    fun cleanKey(k: String): String = k.filter { it.code in 0x21..0x7E && it != '"' && it != '\'' && it != '`' }

    fun save(c: Context, name: String, aiKey: String, model: String, sarvamKey: String, voice: String) {
        sp(c).edit()
            .putString("user_name", name.trim().ifEmpty { "Sachin" })
            .putString("ai_key", cleanKey(aiKey))
            .putString("model", model.trim())
            .putString("sarvam_key", cleanKey(sarvamKey))
            .putString("voice", voice.trim().lowercase().ifEmpty { "shubh" })
            .apply()
    }
}
