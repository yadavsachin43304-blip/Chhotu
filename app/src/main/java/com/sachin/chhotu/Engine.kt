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
        misses = 0
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
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        }
        setState(State.LISTENING, if (pending != null) "हाँ या ना बोलो…" else "सुन रहा हूँ…")
        try { r.startListening(intent) } catch (e: Exception) {
            recognizer?.destroy(); recognizer = null
            setState(State.IDLE, "माइक शुरू नहीं हुआ")
        }
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
            misses = 0
            handle(text)
        }
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    misses++
                    if (conversation && misses < 3) main.postDelayed({ if (conversation) listen() }, 250)
                    else { conversation = false; pending = null; setState(State.IDLE, "छोटू तैयार है (टैप करके बोलो)") }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    recognizer?.destroy(); recognizer = null
                    if (conversation) main.postDelayed({ if (conversation) listen() }, 600)
                    else setState(State.IDLE, "छोटू तैयार है")
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    conversation = false
                    setState(State.IDLE, "माइक की इजाज़त चाहिए")
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    conversation = false
                    say("आवाज़ पहचानने में नेटवर्क की दिक़्क़त है। फ़ोन की सेटिंग में Hindi का offline speech पैक डाउनलोड कर लो, या लिख कर बताओ।")
                }
                else -> {
                    conversation = false
                    setState(State.IDLE, "छोटू तैयार है")
                }
            }
        }
    }

    // ---------- thinking ----------

    private val stopWords = listOf("बस करो", "बस कर", "बाय", "bye", "चुप हो जा", "चुप", "stop", "band karo", "बंद करो बात", "रुक जा", "ruk ja", "so ja", "सो जा", "बाद में बात")
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
        if (conversation && matches(text, stopWords) && text.length < 25) {
            conversation = false
            say("ठीक है भाई, जब बुलाओ तब हाज़िर!")
            return
        }
        // Online ho to AI samjhega (Jarvis jaisa), offline ho to apne niyam
        val smart = brain.ready()
        val first = router.handle(text, fastOnly = smart)
        if (first != Outcome.AskCloud) { act(first); return }
        setState(State.THINKING, "सोच रहा हूँ…")
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
                val lines = r.text.lines()
                val actionLine = lines.lastOrNull { it.trim().startsWith("ACTION") }
                val spoken = lines.filterNot { it.trim().startsWith("ACTION") }.joinToString(" ").trim()
                if (actionLine != null) {
                    val json = try { JSONObject(actionLine.substring(actionLine.indexOf('{'))) } catch (e: Exception) { null }
                    val o = json?.let { router.fromAction(it) }
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

    private fun afterSpeaking() {
        if (conversation || pending != null) {
            main.postDelayed({ if ((conversation || pending != null) && state == State.SPEAKING) listen() }, 200)
        } else {
            setState(State.IDLE, "छोटू तैयार है")
        }
    }
}
