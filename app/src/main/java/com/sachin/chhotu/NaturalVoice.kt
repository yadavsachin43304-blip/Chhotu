package com.sachin.chhotu

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bina internet, bina key, insaan jaisi awaaz: Kokoro (sherpa-onnx) phone ke andar.
 * Hindi awaazein: hm_omega (33, ladka), hm_psi (34, ladka), hf_alpha (31, ladki), hf_beta (32, ladki).
 */
object NaturalVoice {
    private var tts: OfflineTts? = null
    private var tLang = ""
    val VOICES = linkedMapOf("hm_omega" to 33, "hm_psi" to 34, "hf_alpha" to 31, "hf_beta" to 32)

    fun installed(c: Context) = ModelStore.ready(c, ModelStore.VOICE)

    @Synchronized private fun get(c: Context, lang: String): OfflineTts {
        tts?.let { if (tLang == lang) return it; it.release() }
        val d = ModelStore.unpackedDir(c, ModelStore.VOICE)
        val lex = listOf("lexicon-us-en.txt", "lexicon-zh.txt").map { File(d, it) }.filter { it.exists() }.joinToString(",") { it.absolutePath }
        val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = File(d, "model.int8.onnx").let { if (it.exists()) it else File(d, "model.onnx") }.absolutePath,
                voices = File(d, "voices.bin").absolutePath,
                tokens = File(d, "tokens.txt").absolutePath,
                dataDir = File(d, "espeak-ng-data").absolutePath,
                lexicon = lex,
                lang = lang,
            ),
            numThreads = 4,
        ))
        return OfflineTts(config = cfg).also { tts = it; tLang = lang }
    }

    /** Background thread se. 16-bit WAV bytes, ya null. */
    @Synchronized fun synth(c: Context, text: String, lang: String, voice: String, speed: Float = 1.0f): ByteArray? {
        if (!installed(c)) return null
        val l = if (lang.startsWith("en")) "en-us" else "hi"
        val sid = VOICES[voice] ?: if (l == "en-us") 0 else 33
        val a = get(c, l).generate(text, sid = if (l == "en-us" && voice.startsWith("h")) 0 else sid, speed = speed)
        if (a.samples.isEmpty()) return null
        return wav(a.samples, a.sampleRate)
    }

    private fun wav(s: FloatArray, rate: Int): ByteArray {
        val pcm = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in s) pcm.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val data = pcm.array()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + data.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(data.size)
        return ByteArrayOutputStream().apply { write(h.array()); write(data) }.toByteArray()
    }
}
