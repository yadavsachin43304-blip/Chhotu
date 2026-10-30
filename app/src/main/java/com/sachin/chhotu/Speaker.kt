package com.sachin.chhotu

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Chhotu ki awaaz. Sarvam key ho to asli insaani Hindi awaaz (Bulbul), warna phone ki Hindi TTS.
 * Dono me audio ka WAV banakar uski loudness se hothon (lip-sync) ko chalaya jata hai.
 */
class Speaker(private val context: Context) : TextToSpeech.OnInitListener {

    var onLevel: (Float) -> Unit = {}
    var onDone: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var ttsReady = false
    var hindiAvailable = false
        private set
    private var player: MediaPlayer? = null
    private var envelope: FloatArray? = null
    private var speaking = false
    private var token = 0
    private val frameMs = 40

    // ---- Lagaataar bolna: vaakya aate jaate hain, peeche awaaz banti rehti hai, aage bajti rehti hai ----
    private class Item(val text: String, val wav: java.util.concurrent.Future<ByteArray?>)
    private val queue = ArrayDeque<Item>()
    private val synth = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var streamOpen = false
    private var busy = false
    private var seq = 0

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val t = tts ?: return
        val r = t.setLanguage(Locale("hi", "IN"))
        hindiAvailable = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        try {
            val best: Voice? = t.voices?.filter { it.locale.language == "hi" && !it.isNetworkConnectionRequired }
                ?.maxByOrNull { it.quality }
            if (best != null) t.voice = best
        } catch (_: Exception) { }
        t.setSpeechRate(1.0f)
        t.setPitch(1.05f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { segmentDone(id) } }
            @Deprecated("old api")
            override fun onError(id: String?) { main.post { segmentDone(id) } }
            override fun onError(id: String?, errorCode: Int) { main.post { segmentDone(id) } }
        })
        ttsReady = true
    }

    fun isSpeaking() = speaking

    /** Ek poora jawab bolo. */
    fun speak(raw: String) { begin(); add(raw); end() }

    /** Naya jawab shuru: purana sab chup. Main thread se. */
    fun begin() {
        stop()
        streamOpen = true
        speaking = true
    }

    /** Ek aur vaakya line me lagao. Awaaz turant peeche banni shuru. Main thread se. */
    fun add(raw: String) {
        val text = clean(raw)
        if (text.isEmpty()) return
        if (!streamOpen && !busy && queue.isEmpty()) { streamOpen = true; speaking = true }
        speaking = true
        val wav = synth.submit<ByteArray?> { try { makeWav(text) } catch (_: Throwable) { null } }
        queue.addLast(Item(text, wav))
        if (!busy) next()
    }

    /** Jawab poora ho gaya; line khaali hote hi onDone. Main thread se. */
    fun end() {
        streamOpen = false
        if (!busy && queue.isEmpty()) finish(token)
    }

    /** Phone me rakhi insaani awaaz (Piper), ya Sarvam (net + key). Dono na hon to null = phone ki TTS. */
    private fun makeWav(text: String): ByteArray? {
        val lang = Lang.detect(text)
        val key = Prefs.sarvamKey(context)
        val offline = NaturalVoice.installed(context)
        if (offline && (key.isBlank() || Prefs.preferOfflineVoice(context))) {
            NaturalVoice.synth(context, text, lang, Prefs.offlineVoice(context), Prefs.voiceSpeed(context))?.let { return it }
        }
        if (key.isNotBlank()) { try { sarvam(key, text) } catch (_: Exception) { null }?.let { return it } }
        if (offline) return NaturalVoice.synth(context, text, lang, Prefs.offlineVoice(context), Prefs.voiceSpeed(context))
        return null
    }

    private fun next() {
        val my = token
        val item = queue.removeFirstOrNull()
        if (item == null) { busy = false; if (!streamOpen) finish(my); return }
        busy = true
        Thread {
            val wav = try { item.wav.get(60, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) { null }
            main.post {
                if (my != token) return@post
                if (wav != null && wav.size > 100) playWav(wav, my) else androidSpeak(item.text, my)
            }
        }.start()
    }

    /** Ek tukda bajna khatam: agla. */
    private fun advance(my: Int) {
        if (my != token) return
        releasePlayer()
        onLevel(0f)
        next()
    }

    private fun segmentDone(id: String?) {
        val parts = id?.split(":") ?: return
        val myToken = parts.getOrNull(1)?.toIntOrNull() ?: return
        val mySeq = parts.getOrNull(2)?.toIntOrNull() ?: return
        if (myToken == token && mySeq == seq) { seq++; advance(myToken) }
    }

    /** Phone ki apni TTS se seedha bolna (file banaye bina, kyunki kuch phones (Vivo) file nahi banate). */
    private fun androidSpeak(text: String, my: Int) {
        val t = tts
        if (t == null || !ttsReady) { advance(my); return }
        val lang = Lang.detect(text)
        try {
            val parts = lang.split("-")
            val loc = Locale(parts[0], parts.getOrElse(1) { "IN" })
            if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
                t.language = loc
                if (lang == "hi-IN") t.voices?.filter { it.locale.language == "hi" && !it.isNetworkConnectionRequired }?.maxByOrNull { it.quality }?.let { t.voice = it }
            } else t.language = Locale("hi", "IN")
        } catch (_: Exception) { }
        val mySeq = ++seq
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC) }
        val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, params, "live:$my:$mySeq")
        if (r != TextToSpeech.SUCCESS) { seq++; advance(my); return }
        simulate(my, mySeq)
        // Kuch phones onDone nahi bhejte: andaaze ke time ke baad khud aage badho, taaki chhotu atke nahi
        val guess = 2500L + text.length * 110L
        main.postDelayed({ if (my == token && seq == mySeq) { seq++; advance(my) } }, guess)
    }

    private fun simulate(my: Int, mySeq: Int) {
        val start = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (my != token || !speaking || seq != mySeq) return
                val tSec = (System.currentTimeMillis() - start) / 1000f
                onLevel((0.25f + 0.6f * abs(sin(tSec * 17f)) * (0.6f + 0.4f * abs(sin(tSec * 3.3f)))).coerceIn(0f, 1f))
                main.postDelayed(this, 40)
            }
        }
        main.post(tick)
    }

    private fun playWav(bytes: ByteArray, my: Int) {
        envelope = computeEnvelope(bytes)
        try {
            val f = File(context.cacheDir, "chhotu_play_${my % 2}.wav")
            f.writeBytes(bytes)
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { advance(my) }
            mp.setOnErrorListener { _, _, _ -> advance(my); true }
            mp.prepare()
            mp.start()
            player = mp
            val tick = object : Runnable {
                override fun run() {
                    val p = player ?: return
                    if (my != token || !speaking || p !== mp) return
                    val env = envelope
                    val level = try {
                        if (env != null && env.isNotEmpty()) {
                            val idx = (p.currentPosition / frameMs).coerceIn(0, env.size - 1)
                            env[idx]
                        } else 0.5f
                    } catch (e: Exception) { 0f }
                    onLevel(level)
                    main.postDelayed(this, 30)
                }
            }
            main.post(tick)
        } catch (e: Exception) {
            advance(my)
        }
    }

    private fun finish(my: Int) {
        if (my != token) return
        releasePlayer()
        busy = false
        speaking = false
        onLevel(0f)
        onDone()
    }

    fun stop() {
        token++
        speaking = false
        streamOpen = false
        busy = false
        queue.forEach { it.wav.cancel(true) }
        queue.clear()
        try { tts?.stop() } catch (_: Exception) { }
        releasePlayer()
        onLevel(0f)
    }

    private fun releasePlayer() {
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        synth.shutdownNow()
    }

    private fun clean(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp < 0x10000 && cp !in 0x2600..0x27BF) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString().replace(Regex("[*#_`~>|]"), " ").replace(Regex("\\s+"), " ").trim().take(2400)
    }

    private fun sarvam(key: String, text: String): ByteArray? {
        fun body(langField: String) = JSONObject()
            .put("text", text)
            .put(langField, Lang.detect(text))
            .put("model", "bulbul:v3")
            .put("speaker", Prefs.voice(context))
            .put("pace", 1.0)
            .put("temperature", 0.75)
            .put("speech_sample_rate", 22050)
            .toString()
        var (code, resp) = Brain.post("https://api.sarvam.ai/text-to-speech", mapOf("api-subscription-key" to key), body("language_code"))
        if (code == 422 || code == 400) {
            val r2 = Brain.post("https://api.sarvam.ai/text-to-speech", mapOf("api-subscription-key" to key), body("target_language_code"))
            code = r2.first; resp = r2.second
        }
        if (code !in 200..299) return null
        val b64 = JSONObject(resp).optJSONArray("audios")?.optString(0) ?: return null
        return Base64.decode(b64, Base64.DEFAULT)
    }

    /** WAV (16-bit PCM) se har 40ms ki loudness 0..1 */
    private fun computeEnvelope(b: ByteArray): FloatArray? {
        try {
            if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            var pos = 12
            var channels = 1; var rate = 22050; var bits = 16
            var dataStart = -1; var dataLen = 0
            while (pos + 8 <= b.size) {
                val id = String(b, pos, 4, Charsets.US_ASCII)
                val size = le32(b, pos + 4)
                if (id == "fmt ") {
                    channels = le16(b, pos + 10)
                    rate = le32(b, pos + 12)
                    bits = le16(b, pos + 22)
                } else if (id == "data") {
                    dataStart = pos + 8
                    dataLen = if (size <= 0 || dataStart + size > b.size) b.size - dataStart else size
                    break
                }
                if (size < 0) return null
                pos += 8 + size + (size and 1)
            }
            if (dataStart < 0 || bits != 16 || channels < 1 || rate <= 0) return null
            val frameSamples = rate * frameMs / 1000
            val bytesPerFrame = frameSamples * channels * 2
            val n = dataLen / bytesPerFrame
            if (n <= 0) return null
            val out = FloatArray(n)
            var maxV = 1f
            for (f in 0 until n) {
                var sum = 0.0
                val base = dataStart + f * bytesPerFrame
                var s = 0
                while (s < frameSamples) {
                    val o = base + s * channels * 2
                    val v = ((b[o + 1].toInt() shl 8) or (b[o].toInt() and 0xff)).toShort().toInt()
                    sum += v.toDouble() * v
                    s++
                }
                val rms = sqrt(sum / frameSamples).toFloat()
                out[f] = rms
                if (rms > maxV) maxV = rms
            }
            for (i in out.indices) out[i] = (out[i] / maxV * 1.25f).coerceIn(0f, 1f)
            return out
        } catch (e: Exception) {
            return null
        }
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}
