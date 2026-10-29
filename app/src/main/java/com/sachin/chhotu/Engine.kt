package com.sachin.chhotu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Chhotu ka dil: sunna -> samajhna -> (zarurat ho to poochna) -> karna -> bolna -> phir sunna.
 * Activity aur floating bubble dono isi se baat karte hain.
 */
object Engine {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    interface Listener {
        fun onState(state: State, status: String) {}
        fun onMessage(text: String, fromUser: Boolean) {}
        fun onLevel(level: Float) {}
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private lateinit var router: CommandRouter
    private lateinit var brain: Brain
    private lateinit var memory: MemoryStore
    lateinit var speaker: Speaker
        private set
    private var recognizer: SpeechRecognizer? = null
    private var initialized = false

    var state = State.IDLE
        private set
    var status = "छोटू तैयार है"
        private set
    /** Baat-cheet mode: bolne ke baad apne aap phir sunna */
    var conversation = false
        private set
    private var pending: Outcome.Confirm? = null
    private var misses = 0
    private var netErrors = 0
    /** Chhotu jis bhasha me bola, agli baar usi me sunna */
    private var listenLang = "hi-IN"

    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        router = CommandRouter(app)
        brain = Brain(app)
        memory = MemoryStore.get(app)
        speaker = Speaker(app)
        speaker.onLevel = { l -> listeners.forEach { it.onLevel(l) } }
        speaker.onDone = { afterSpeaking() }
        initialized = true
    }

    fun addListener(l: Listener) { listeners.add(l); l.onState(state, status) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun providerName() = brain.providerName()

    private fun setState(s: State, text: String) {
        state = s; status = text
        listeners.forEach { it.onState(s, text) }
    }

    private fun emit(text: String, fromUser: Boolean) {
        memory.add(if (fromUser) "chat_user" else "chat_chhotu", text)
        listeners.forEach { it.onMessage(text, fromUser) }
    }

    // ---------- public controls ----------

    /** Bubble / mic / chehre par tap */
    fun toggle() = main.post {
        if (state == State.IDLE) startConversation() else stopAll(sayBye = false)
    }

    fun startConversation() = main.post {
        conversation = true
        misses = 0; netErrors = 0
        speaker.stop()
        listen()
    }

    fun stopAll(sayBye: Boolean = false) = main.post {
        conversation = false
        pending = null
        recognizer?.cancel()
        speaker.stop()
        if (sayBye) say("ठीक है भाई, जब बुलाओ तब हाज़िर!") else setState(State.IDLE, "छोटू तैयार है")
    }

    fun handleTyped(text: String) = main.post {
        recognizer?.cancel()
        speaker.stop()
        handle(text)
    }

    fun release() = main.post {
        conversation = false
        recognizer?.destroy(); recognizer = null
        speaker.stop()
        setState(State.IDLE, "छोटू बंद है")
    }

    // ---------- listening ----------

    private fun listen() {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            conversation = false
            say("माइक की इजाज़त नहीं है भाई। ऐप खोल कर माइक की इजाज़त दे दो।")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            conversation = false
            say("इस फ़ोन में आवाज़ पहचानने वाला सिस्टम नहीं मिला। Google ऐप अपडेट कर लो।")
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(app).also {
            it.setRecognitionListener(recListener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, listenLang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, listenLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (!brain.isOnline()) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        }
        setState(State.LISTENING, if (pending != null) "हाँ या ना बोलो…" else "सुन रहा हूँ…")
        muteBeep()
        try { r.startListening(intent) } catch (e: Exception) {
            recognizer?.destroy(); recognizer = null
            setState(State.IDLE, "माइक शुरू नहीं हुआ")
        }
    }

    /** Har baar sunna shuru hone pe phone "ting" karta hai; lagataar baat-cheet me wo chidh jaata hai, to thodi der ke liye chup. */
    private fun muteBeep() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val streams = intArrayOf(android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.STREAM_SYSTEM)
        try { for (st in streams) am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_MUTE, 0) } catch (_: Exception) { return }
        main.postDelayed({ try { for (st in streams) am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {} }, 900)
    }

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (state == State.LISTENING) {
                val l = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                listeners.forEach { it.onLevel(l) }
            }
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { setState(State.THINKING, "समझ रहा हूँ…") }
        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!p.isNullOrBlank()) setState(State.LISTENING, p)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return }
            misses = 0; netErrors = 0
            handle(text)
        }
        override fun onError(error: Int) {
            when (error) {
                // Baat-cheet mode me chhotu kabhi khud se sunna band nahi karta. Sirf Power Off / stop se band.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    misses++
                    if (conversation || pending != null) {
                        if (pending != null && misses >= 3) { pending = null }
                        main.postDelayed({ if (conversation || pending != null) listen() }, 150)
                    } else setState(State.IDLE, "छोटू तैयार है")
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    conversation = false
                    setState(State.IDLE, "माइक की इजाज़त चाहिए")
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    recognizer?.destroy(); recognizer = null
                    netErrors++
                    if (netErrors == 1) say("आवाज़ पहचानने में नेटवर्क की दिक़्क़त है भाई। फ़ोन की सेटिंग में Hindi का offline speech पैक डाउनलोड कर लो, तब बिना नेट भी सुन लूँगा।")
                    else if (conversation) main.postDelayed({ if (conversation) listen() }, (1500L * netErrors).coerceAtMost(8000L))
                    else setState(State.IDLE, "छोटू तैयार है")
                }
                else -> {
                    // busy / client / audio jaisi gadbad: recognizer naya banao aur phir suno
                    recognizer?.destroy(); recognizer = null
                    if (conversation || pending != null) main.postDelayed({ if (conversation || pending != null) listen() }, 700)
                    else setState(State.IDLE, "छोटू तैयार है")
                }
            }
        }
    }

    // ---------- thinking ----------

    /** Sirf saaf "band ho jao" pe hi chhotu band hota hai, warna baat chalti rehti hai. */
    private val powerOffWords = listOf("छोटू बंद", "बंद हो जाओ", "बंद हो जा", "power off", "पावर ऑफ", "chhotu band", "band ho jao", "band ho ja", "सो जाओ छोटू")
    private val yesWords = listOf("हाँ", "हां", "हा ", "haan", "han", "haa", "yes", "कर दो", "kar do", "करो", "karo", "ठीक है", "theek", "thik", "बिल्कुल", "बिलकुल", "bilkul", "ok", "okay", "ओके", "चलो", "chalo", "जी", "ji", "sure", "खोलो", "लगाओ", "भेजो")
    private val noWords = listOf("नहीं", "नही", "nahi", "nahin", "na ", "ना", "no", "mat", "मत", "रहने दो", "rehne do", "रहने", "cancel", "कैंसल", "छोड़ो", "chhodo")

    private fun matches(t: String, words: List<String>): Boolean {
        val x = " " + t.lowercase().replace(Regex("[।,.!?]"), " ") + " "
        return words.any { w -> x.contains(" " + w.trim() + " ") || (w.length > 3 && x.contains(w)) }
    }

    private fun handle(text: String) {
        emit(text, true)
        val p = pending
        if (p != null) {
            when {
                matches(text, noWords) -> { pending = null; say("ठीक है, नहीं किया।") }
                matches(text, yesWords) -> { pending = null; say(p.action()) }
                else -> say("समझा नहीं भाई। " + p.question)
            }
            return
        }
        if (matches(text, powerOffWords) && text.length < 30) {
            conversation = false
            say("ठीक है भाई, मैं बंद हो रहा हूँ। जब चाहो Power ON कर देना।")
            main.postDelayed({ Prefs.setPower(app, false); ChhotuService.stop(app) }, 3500)
            return
        }
        // Online ho to AI samjhega (Jarvis jaisa), offline ho to apne niyam
        val smart = brain.ready()
        // Apne (phone wale) dimaag ke saath pehle pakke niyam (call, app, alarm...), kyunki chhota model kabhi-kabhi chook jaata hai.
        // Cloud dimaag ho to wo khud samajh leta hai.
        val first = router.handle(text, fastOnly = smart && !brain.usesLocal())
        if (first != Outcome.AskCloud) { act(first); return }
        setState(State.THINKING, if (LocalBrain.installed(app) && !LocalBrain.awake()) "दिमाग़ जग रहा है (पहली बार 1-2 मिनट)…" else "सोच रहा हूँ…")
        Thread {
            val r = brain.ask(text)
            main.post {
                if (!r.ok) {
                    val local = if (smart) router.handle(text) else Outcome.AskCloud
                    if (local != Outcome.AskCloud) act(local)
                    else {
                        if (r.detail.isNotBlank()) listeners.forEach { it.onMessage("ⓘ " + r.detail, false) }
                        say(r.text)
                    }
                    return@post
                }
                val spoken = ActionParser.spoken(r.text)
                val json = ActionParser.parse(r.text)
                if (json != null) {
                    val o = try { router.fromAction(json) } catch (_: Exception) { null }
                    if (o != null) { act(o); return@post }
                }
                say(spoken.ifEmpty { "हम्म, फिर से बोलना भाई।" })
            }
        }.start()
    }

    private fun act(o: Outcome) {
        when (o) {
            is Outcome.Say -> say(o.text)
            is Outcome.Confirm -> { pending = o; say(o.question) }
            is Outcome.NeedPermission -> say(o.text)
            Outcome.AskCloud -> say("हम्म, ये समझ नहीं आया भाई।")
        }
    }

    /** Settings ke "Key test" ke liye */
    fun testKey(cb: (String) -> Unit) {
        Thread {
            val r = brain.ask("बस एक छोटा सा नमस्ते बोलो।")
            main.post { cb(if (r.ok) "✅ चल गया (${brain.providerName()}): ${r.text}" else "❌ ${r.text}\n\n${r.detail}") }
        }.start()
    }

    private fun say(text: String) {
        listenLang = Lang.detect(text).let { if (it == "hi-IN" || it == "en-IN") it else listenLang }
        emit(text, false)
        setState(State.SPEAKING, "बोल रहा हूँ…")
        speaker.speak(text)
    }

    /** Test ke liye: bolne ke baad apne aap mic na khole */
    @Volatile var autoListen = true

    private fun afterSpeaking() {
        if (autoListen && (conversation || pending != null)) {
            main.postDelayed({ if ((conversation || pending != null) && state == State.SPEAKING) listen() }, 200)
        } else {
            setState(State.IDLE, "छोटू तैयार है")
        }
    }
}
