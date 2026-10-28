package com.sachin.chhotu

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        Engine.init(this)
        val d = { v: Int -> Ui.dp(this, v) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(18), d(18), d(18), d(30)) }
        col.addView(Ui.text(this, "सेटिंग", 24f, Ui.TEXT, true))

        fun section(title: String): LinearLayout {
            col.addView(Ui.text(this, title, 13f, Ui.ACCENT, true).apply { setPadding(0, d(22), 0, d(8)) })
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.round(Ui.CARD, d(18).toFloat())
                setPadding(d(14), d(10), d(14), d(14))
            }
            col.addView(card)
            return card
        }
        fun label(card: LinearLayout, t: String) = card.addView(Ui.text(this, t, 13f, Ui.MUTED).apply { setPadding(0, d(10), 0, d(6)) })
        fun gap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = d(10) }

        val me = section("आप")
        label(me, "आपका नाम")
        val name = Ui.field(this, Prefs.userName(this), "Sachin")
        me.addView(name)

        val brain = section("दिमाग़ (ऑनलाइन AI)")
        label(brain, "API key: Gemini की कोई भी key (AQ… या AIza…), या Groq (gsk_…), या OpenRouter (sk-or-…)। छोटू key देखकर अपने-आप सही वाला चुन लेगा। Gemini key: aistudio.google.com/api-keys")
        val key = Ui.field(this, Prefs.aiKey(this), "यहाँ key पेस्ट करो").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        brain.addView(key)
        label(brain, "मॉडल (ख़ाली छोड़ो तो अपने-आप)")
        val model = Ui.field(this, Prefs.model(this), "जैसे gemini-3.8-flash")
        brain.addView(model)

        val voice = section("आवाज़")
        label(voice, "Sarvam API key (ऑप्शनल): असली इंसान जैसी हिंदी आवाज़ के लिए। dashboard.sarvam.ai से मिलती है। ख़ाली रहे तो फ़ोन की हिंदी आवाज़ चलेगी।")
        val sarvam = Ui.field(this, Prefs.sarvamKey(this), "Sarvam key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        voice.addView(sarvam)
        label(voice, "आवाज़ का नाम (Sarvam): shubh, aditya, rahul, rohan, kabir, priya, neha…")
        val speaker = Ui.field(this, Prefs.voice(this), "shubh")
        voice.addView(speaker)
        brain.addView(Ui.button(this, "🔑 Key टेस्ट करो", false).apply {
            setOnClickListener {
                Prefs.save(this@SettingsActivity, name.text.toString(), key.text.toString(), model.text.toString(), sarvam.text.toString(), speaker.text.toString())
                text = "टेस्ट हो रहा है…"
                Engine.testKey { res ->
                    text = "🔑 Key टेस्ट करो"
                    androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity).setTitle("Key टेस्ट").setMessage(res).setPositiveButton("ठीक", null).show()
                    refresh()
                }
            }
        }, gap())


        col.addView(Ui.button(this, "सेव करो").apply {
            setOnClickListener {
                Prefs.save(this@SettingsActivity, name.text.toString(), key.text.toString(), model.text.toString(), sarvam.text.toString(), speaker.text.toString())
                Toast.makeText(this@SettingsActivity, "सेव हो गया", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = d(18) })
        col.addView(Ui.button(this, "🔊 आवाज़ टेस्ट करो", false).apply {
            setOnClickListener {
                Prefs.save(this@SettingsActivity, name.text.toString(), key.text.toString(), model.text.toString(), sarvam.text.toString(), speaker.text.toString())
                Engine.speaker.speak("नमस्ते ${Prefs.userName(this@SettingsActivity)} भाई, मैं छोटू हूँ। ऐसी लगती है मेरी आवाज़।")
            }
        }, gap())

        val perms = section("फ़ोन कंट्रोल की इजाज़तें")
        perms.addView(Ui.button(this, "हर स्क्रीन पर चेहरा (Overlay)", false).apply {
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 23) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }, gap())
        perms.addView(Ui.button(this, "Accessibility चालू करो", false).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, gap())
        perms.addView(Ui.button(this, "फ़ोन की हिंदी आवाज़ (TTS) सेटिंग", false).apply {
            setOnClickListener {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        }, gap())
        status = Ui.text(this, "", 14f, Ui.MUTED).apply { setPadding(0, d(12), 0, 0) }
        perms.addView(status)

        col.addView(Ui.text(this, "आपकी keys सिर्फ़ इसी फ़ोन में रहती हैं।", 12f, Ui.MUTED).apply { setPadding(0, d(18), 0, 0) })

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    private fun refresh() {
        val overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        status.text = "Overlay: " + (if (overlay) "✅" else "❌") +
            "   Accessibility: " + (if (ChhotuAccessibilityService.isOn()) "✅" else "❌") +
            "\nदिमाग़: " + Engine.providerName() +
            "   ·   यादें: " + MemoryStore.get(this).count()
    }

    override fun onResume() { super.onResume(); refresh() }
}
