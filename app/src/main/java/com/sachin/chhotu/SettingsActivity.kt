package com.sachin.chhotu

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Chhotu Settings"
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun label(t: String) = TextView(this).apply { text = t; textSize = 15f; setPadding(0, pad, 0, 0) }

        col.addView(label("आपका नाम"))
        val name = EditText(this).apply { setText(Prefs.userName(this@SettingsActivity)) }
        col.addView(name)

        col.addView(label("Groq API key (console.groq.com से मुफ़्त मिलती है)"))
        val key = EditText(this).apply {
            setText(Prefs.groqKey(this@SettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "gsk_..."
        }
        col.addView(key)

        col.addView(label("मॉडल"))
        val model = EditText(this).apply { setText(Prefs.model(this@SettingsActivity)) }
        col.addView(model)

        col.addView(Button(this).apply {
            text = "सेव करो"
            setOnClickListener {
                Prefs.save(this@SettingsActivity, key.text.toString(), model.text.toString(), name.text.toString())
                Toast.makeText(this@SettingsActivity, "सेव हो गया", Toast.LENGTH_SHORT).show()
            }
        })

        col.addView(label("फ़ोन कंट्रोल (वापस, होम, नोटिफ़िकेशन) के लिए:"))
        col.addView(Button(this).apply {
            text = "Accessibility चालू करो"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(label("हिंदी आवाज़ बेहतर करने के लिए:"))
        col.addView(Button(this).apply {
            text = "Text-to-speech सेटिंग खोलो"
            setOnClickListener {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        })
        status = label("")
        col.addView(status)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        status.text = "Accessibility: " + (if (ChhotuAccessibilityService.isOn()) "✅ चालू" else "❌ बंद") +
            "\nयादें सेव: " + MemoryStore.get(this).count()
    }
}
