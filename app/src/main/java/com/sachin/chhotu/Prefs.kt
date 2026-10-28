package com.sachin.chhotu

import android.content.Context

object Prefs {
    private const val FILE = "chhotu_prefs"
    fun groqKey(c: Context): String = c.getSharedPreferences(FILE, 0).getString("groq_key", "") ?: ""
    fun model(c: Context): String = c.getSharedPreferences(FILE, 0).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun userName(c: Context): String = c.getSharedPreferences(FILE, 0).getString("user_name", "Sachin") ?: "Sachin"
    fun save(c: Context, key: String, model: String, name: String) {
        c.getSharedPreferences(FILE, 0).edit()
            .putString("groq_key", key.trim())
            .putString("model", model.trim().ifEmpty { DEFAULT_MODEL })
            .putString("user_name", name.trim().ifEmpty { "Sachin" })
            .apply()
    }
    const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
}
