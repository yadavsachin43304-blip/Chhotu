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
        t.setSpeechRate(0.97f)
        t.setPitch(1.05f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                val parts = id?.split(":") ?: return
                val myToken = parts.getOrNull(1)?.toIntOrNull() ?: return
                if (parts[0] == "file") {
                    val f = File(context.cacheDir, "chhotu_tts.wav")
                    main.post { if (myToken == token) { if (f.exists() && f.length() > 100) playWav(f.readBytes(), myToken) else finish(myToken) } }
                } else if (parts[0] == "live") {
                    main.post { finish(myToken) }
                }
            }
            @Deprecated("old api")
            override fun onError(id: String?) { onErr(id) }
            override fun onError(id: String?, errorCode: Int) { onErr(id) }
            private fun onErr(id: String?) {
                val parts = id?.split(":") ?: return
                val myToken = parts.getOrNull(1)?.toIntOrNull() ?: return
                main.post { if (myToken == token) finish(myToken) }
            }
        })
        ttsReady = true
    }

    fun isSpeaking() = speaking

    fun speak(raw: String) {
        stop()
        val text = clean(raw)
        if (text.isEmpty()) { onDone(); return }
        val my = ++token
        speaking = true
        val key = Prefs.sarvamKey(context)
        val lang = Lang.detect(text)
        val kokoroOk = NaturalVoice.installed(context) && (lang == "hi-IN" || lang.startsWith("en"))
        if (kokoroOk && (key.isBlank() || Prefs.preferOfflineVoice(context))) {
            Thread {
                val wav = try { NaturalVoice.synth(context, text, lang, Prefs.offlineVoice(context), Prefs.voiceSpeed(context)) } catch (e: Throwable) { null }
                main.post {
                    if (my != token) return@post
                    if (wav != null) playWav(wav, my) else androidSpeak(text, my)
                }
            }.start()
        } else if (key.isNotBlank()) {
            Thread {
                val wav = try { sarvam(key, text) } catch (e: Exception) { null }
                main.post {
                    if (my != token) return@post
                    if (wav != null) playWav(wav, my)
                    else if (kokoroOk) Thread { val w2 = try { NaturalVoice.synth(context, text, lang, Prefs.offlineVoice(context)) } catch (e: Throwable) { null }; main.post { if (my == token) { if (w2 != null) playWav(w2, my) else androidSpeak(text, my) } } }.start()
                    else androidSpeak(text, my)
                }
            }.start()
        } else {
            androidSpeak(text, my)
        }
    }

    private fun androidSpeak(text: String, my: Int) {
        val t = tts
        if (t == null || !ttsReady) { finish(my); return }
        val lang = Lang.detect(text)
        try {
            val parts = lang.split("-")
            val loc = Locale(parts[0], parts.getOrElse(1) { "IN" })
            if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
                t.language = loc
                if (lang == "hi-IN") t.voices?.filter { it.locale.language == "hi" && !it.isNetworkConnectionRequired }?.maxByOrNull { it.quality }?.let { t.voice = it }
            } else t.language = Locale("hi", "IN")
        } catch (_: Exception) { }
        val f = File(context.cacheDir, "chhotu_tts.wav")
        f.delete()
        val r = t.synthesizeToFile(text, Bundle(), f, "file:$my")
        if (r != TextToSpeech.SUCCESS) {
            // file nahi bani to seedha bolo, hoth andaaze se chalenge
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "live:$my")
            simulate(my)
        }
    }

    private fun simulate(my: Int) {
        val start = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (my != token || !speaking) return
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
            val f = File(context.cacheDir, "chhotu_play.wav")
            f.writeBytes(bytes)
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { finish(my) }
            mp.setOnErrorListener { _, _, _ -> finish(my); true }
            mp.prepare()
            mp.start()
            player = mp
            val tick = object : Runnable {
                override fun run() {
                    val p = player ?: return
                    if (my != token || !speaking) return
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
            finish(my)
        }
    }

    private fun finish(my: Int) {
        if (my != token) return
        releasePlayer()
        speaking = false
        onLevel(0f)
        onDone()
    }

    fun stop() {
        token++
        speaking = false
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
