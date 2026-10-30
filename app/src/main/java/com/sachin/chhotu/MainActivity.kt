package com.sachin.chhotu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class MainActivity : AppCompatActivity(), Engine.Listener {

    private lateinit var face: FaceView
    private lateinit var statusText: TextView
    private lateinit var providerText: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: android.widget.EditText
    private lateinit var mic: TextView
    private lateinit var power: SwitchCompat

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModelStore.cleanupOld(applicationContext)
        LocalBrain.warmUp(applicationContext)
        // Awaaz chhoti (67 MB) hai: dimaag ho aur awaaz na ho to apne aap le aao, taaki chhotu bol sake
        if (LocalBrain.installed(applicationContext) && !NaturalVoice.installed(applicationContext) && !ModelStore.downloading(ModelStore.VOICE))
            Thread { try { ModelStore.download(applicationContext, ModelStore.VOICE) { } } catch (_: Throwable) {} }.start()
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        Engine.init(this)
        buildUi()
        loadHistory()
        askPermissions()
        Engine.addListener(this)
        if (Prefs.powerOn(this) && !ChhotuService.running && hasMic()) ChhotuService.start(this)
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(10), dp(6))
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(Ui.text(this, "छोटू", 24f, Ui.TEXT, true))
        providerText = Ui.text(this, "", 12f, Ui.MUTED)
        titleCol.addView(providerText)
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton("🧠") { startActivity(Intent(this, MemoryActivity::class.java)) })
        header.addView(iconButton("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) })
        power = SwitchCompat(this).apply {
            text = "Power  "
            setTextColor(Ui.TEXT)
            isChecked = Prefs.powerOn(this@MainActivity)
            setOnCheckedChangeListener { _, on -> setPower(on) }
        }
        header.addView(power)
        root.addView(header)

        // face card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.round(Ui.CARD, dp(24).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(14))
        }
        face = FaceView(this)
        face.setOnClickListener { Engine.toggle() }
        card.addView(face, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)))
        statusText = Ui.text(this, "छोटू तैयार है", 15f, Ui.MUTED).apply { gravity = Gravity.CENTER; maxLines = 2 }
        card.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(14), dp(6), dp(14), dp(6))
        })

        // chat
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(8)) }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // input bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(14))
        }
        input = Ui.field(this, "", "छोटू से कुछ भी कहो…").apply {
            background = Ui.round(Ui.CARD2, dp(26).toFloat())
            setPadding(dp(18), dp(14), dp(18), dp(14))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val send = roundIcon("➤", Ui.CARD2, 46) { sendTyped() }
        bar.addView(send, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(8) })
        mic = roundIcon("🎤", Ui.ACCENT, 58) {
            if (!hasMic()) askPermissions() else Engine.toggle()
        }
        bar.addView(mic, LinearLayout.LayoutParams(dp(58), dp(58)).apply { leftMargin = dp(8) })
        root.addView(bar)

        setContentView(root)
    }

    private fun iconButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 20f; gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { onClick() }
    }

    private fun roundIcon(t: String, color: Int, sizeDp: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = if (sizeDp > 50) 24f else 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = Ui.circle(color)
        setOnClickListener { onClick() }
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = list.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    private fun setPower(on: Boolean) {
        if (on) {
            if (!hasMic()) { askPermissions(); power.isChecked = false; return }
            Prefs.setPower(this, true)
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("हर स्क्रीन पर छोटू")
                    .setMessage("होम स्क्रीन और दूसरे ऐप्स के ऊपर छोटू का चेहरा दिखाने के लिए 'Display over other apps' की इजाज़त दो। फिर Power दोबारा ON करना।")
                    .setPositiveButton("इजाज़त दो") { _, _ ->
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("बाद में", null)
                    .show()
            }
            ChhotuService.start(this)
            Engine.startConversation()
        } else {
            Prefs.setPower(this, false)
            ChhotuService.stop(this)
            Engine.stopAll()
        }
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isNotEmpty()) { input.setText(""); Engine.handleTyped(t) }
    }

    private fun loadHistory() {
        val past = MemoryStore.get(this).all().filter { it.type == "chat_user" || it.type == "chat_chhotu" }.takeLast(20)
        if (past.isEmpty()) {
            addBubble("नमस्ते ${Prefs.userName(this)} भाई! मैं छोटू हूँ। Power ON करो या 🎤 दबाओ, फिर बात करते-करते काम करवाओ। हर काम से पहले मैं पूछूँगा।", false)
        } else past.forEach { addBubble(it.text, it.type == "chat_user") }
    }

    private fun addBubble(text: String, fromUser: Boolean) {
        val tv = Ui.text(this, text, 15f, if (fromUser) Color.WHITE else Ui.TEXT).apply {
            background = Ui.round(if (fromUser) Ui.ACCENT else Ui.CARD, dp(18).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setLineSpacing(0f, 1.15f)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(8)
            if (fromUser) leftMargin = dp(56) else rightMargin = dp(56)
        }
        chat.addView(tv, lp)
        while (chat.childCount > 80) chat.removeViewAt(0)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- Engine.Listener ----
    override fun onState(state: Engine.State, status: String) {
        runOnUiThread {
            statusText.text = status
            face.mood = when (state) {
                Engine.State.IDLE -> FaceView.Mood.IDLE
                Engine.State.LISTENING -> FaceView.Mood.LISTENING
                Engine.State.THINKING -> FaceView.Mood.THINKING
                Engine.State.SPEAKING -> FaceView.Mood.SPEAKING
            }
            mic.text = if (state == Engine.State.IDLE) "🎤" else "■"
            mic.background = Ui.circle(if (state == Engine.State.IDLE) Ui.ACCENT else Ui.TEAL)
        }
    }

    override fun onMessage(text: String, fromUser: Boolean) { runOnUiThread { addBubble(text, fromUser) } }

    override fun onLevel(level: Float) { face.level = level }

    override fun onResume() {
        super.onResume()
        providerText.text = "दिमाग़: ${Engine.providerName()}  ·  आवाज़: " + (if (Prefs.sarvamKey(this).isNotBlank()) "Sarvam (इंसानी)" else "फ़ोन की")
        power.setOnCheckedChangeListener(null)
        power.isChecked = Prefs.powerOn(this)
        power.setOnCheckedChangeListener { _, on -> setPower(on) }
    }

    override fun onDestroy() {
        Engine.removeListener(this)
        if (!ChhotuService.running) Engine.stopAll()
        super.onDestroy()
    }
}
