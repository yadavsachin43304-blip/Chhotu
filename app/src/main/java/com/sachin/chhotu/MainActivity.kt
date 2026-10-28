package com.sachin.chhotu

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var router: CommandRouter
    private lateinit var brain: CloudBrain
    private lateinit var memory: MemoryStore
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) process(text)
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        router = CommandRouter(this)
        brain = CloudBrain(this)
        memory = MemoryStore.get(this)
        tts = TextToSpeech(this, this)
        buildUi()

        permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE))

        val name = Prefs.userName(this)
        addBubble("नमस्ते $name भाई! मैं छोटू हूँ। 🎤 दबा कर बोलो या लिखो। जैसे: \"WhatsApp खोलो\", \"मम्मी को कॉल करो\", \"सुबह 6 बजे का अलार्म\", \"याद रख कि...\"", false)
        if (Prefs.groqKey(this).isBlank()) addBubble("ऑनलाइन बातचीत के लिए ऊपर ⚙ Settings में Groq API key डाल देना।", false)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this)
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "छोटू से कुछ भी कहो…"
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val send = Button(this).apply { text = "भेजो"; setOnClickListener { sendTyped() } }
        bar.addView(send)
        val mic = Button(this).apply { text = "🎤"; textSize = 20f; setOnClickListener { listen() } }
        bar.addView(mic)
        root.addView(bar)
        setContentView(root)
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isNotEmpty()) { input.setText(""); process(t) }
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)); return
        }
        tts?.stop()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "बोलो भाई, छोटू सुन रहा है…")
        }
        try { speechLauncher.launch(i) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "इस फ़ोन में आवाज़ पहचानने वाला ऐप (Google) नहीं मिला", Toast.LENGTH_LONG).show()
        }
    }

    private fun process(text: String) {
        addBubble(text, true)
        memory.add("chat_user", text)
        when (val o = router.handle(text)) {
            is Outcome.Say -> reply(o.text)
            is Outcome.ConfirmCall -> confirmCall(o.name, o.number)
            is Outcome.NeedPermission -> { permLauncher.launch(arrayOf(o.permission)); reply(o.text) }
            Outcome.AskCloud -> {
                val thinking = addBubble("सोच रहा हूँ…", false)
                Thread {
                    val ans = brain.ask(text)
                    runOnUiThread { chat.removeView(thinking); reply(ans) }
                }.start()
            }
        }
    }

    private fun confirmCall(name: String, number: String) {
        reply("$name को कॉल करूँ?")
        AlertDialog.Builder(this)
            .setTitle("कॉल करें?")
            .setMessage("$name\n$number")
            .setPositiveButton("हाँ, कॉल करो") { _, _ ->
                val hasPerm = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                val action = if (hasPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
                try {
                    startActivity(Intent(action, Uri.parse("tel:" + Uri.encode(number))))
                    memory.add("action", "Call: $name ($number)")
                } catch (e: Exception) { reply("कॉल नहीं लग पाई।") }
            }
            .setNegativeButton("नहीं") { _, _ -> reply("ठीक है, कॉल नहीं की।") }
            .show()
    }

    private fun reply(text: String) {
        addBubble(text, false)
        memory.add("chat_chhotu", text)
        speak(text)
    }

    private fun addBubble(text: String, fromUser: Boolean): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(if (fromUser) Color.WHITE else Color.BLACK)
            setBackgroundColor(if (fromUser) Color.parseColor("#FF6D28") else Color.parseColor("#EDEDED"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            if (!fromUser) setTypeface(typeface, Typeface.NORMAL)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(6)
            if (fromUser) leftMargin = dp(48) else rightMargin = dp(48)
        }
        chat.addView(tv, lp)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        return tv
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("hi", "IN"))
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(0.95f)
            tts?.setPitch(1.08f)
            if (!ttsReady) Toast.makeText(this, "फ़ोन में हिंदी आवाज़ नहीं है। Settings > Text-to-speech में Hindi डाउनलोड करो।", Toast.LENGTH_LONG).show()
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        val clean = text.replace(Regex("[*#_`]"), "")
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "chhotu")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "🧠 यादें").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, "⚙").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> startActivity(Intent(this, MemoryActivity::class.java))
            2 -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
