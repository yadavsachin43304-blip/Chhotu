package com.sachin.chhotu

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bina internet, bina key, insaan jaisi Hindi awaaz: Piper "pratham" (IIT Madras IndicTTS data se), sherpa-onnx se phone ke andar.
 * Kokoro se kai guna tez (1 second awaaz ~0.1 second me) aur Hindi me saaf.
 */
object NaturalVoice {
    private var tts: OfflineTts? = null
    val VOICES = linkedMapOf("pratham" to 0)

    fun installed(c: Context) = ModelStore.ready(c, ModelStore.VOICE)

    @Synchronized private fun get(c: Context): OfflineTts {
        tts?.let { return it }
        val d = ModelStore.unpackedDir(c, ModelStore.VOICE)
        val onnx = d.listFiles()?.firstOrNull { it.name.endsWith(".onnx") } ?: File(d, "hi_IN-pratham-medium.onnx")
        val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = onnx.absolutePath,
                tokens = File(d, "tokens.txt").absolutePath,
                dataDir = File(d, "espeak-ng-data").absolutePath,
            ),
            numThreads = 3,
        ))
        return OfflineTts(config = cfg).also { tts = it }
    }

    /** Background thread se. 16-bit WAV bytes, ya null. */
    @Synchronized fun synth(c: Context, text: String, lang: String, voice: String, speed: Float = 1.0f): ByteArray? {
        if (!installed(c)) return null
        val a = get(c).generate(text, sid = 0, speed = speed)
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
