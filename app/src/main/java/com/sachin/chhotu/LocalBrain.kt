package com.sachin.chhotu

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Chhotu ka apna dimaag: phone ke andar chalne wala AI (Qwen2.5 1.5B, Apache-2.0).
 * Ek baar download (~1.6 GB), phir bina internet, bina API key.
 */
object LocalBrain {
    const val URL_MODEL = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    const val SIZE: Long = 1597913616L
    @Volatile private var engine: LlmInference? = null
    @Volatile var downloading = false; private set

    fun file(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "chhotu-brain.task")
    fun installed(c: Context): Boolean = file(c).length() == SIZE

    /** Resume hone wala download. progress(0..100). Background thread se. */
    fun download(c: Context, progress: (Int) -> Unit): String? {
        if (downloading) return "पहले से डाउनलोड हो रहा है"
        downloading = true
        try {
            val part = File(file(c).path + ".part")
            var url = URL_MODEL
            var have = part.length()
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20000; conn.readTimeout = 60000
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code in 301..308 && hops++ < 6) { url = URL(URL(url), conn.getHeaderField("Location")).toString(); conn.disconnect(); continue }
                if (code == 200) have = 0
                else if (code != 206) return "डाउनलोड नहीं हुआ (HTTP $code)"
                break
            }
            conn.inputStream.use { inp ->
                FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = have; var last = -1
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val p = (done * 100 / SIZE).toInt()
                        if (p != last) { last = p; progress(p) }
                    }
                }
            }
            if (part.length() != SIZE) return "डाउनलोड अधूरा रह गया, फिर दबाओ, वहीं से आगे चलेगा"
            part.renameTo(file(c))
            return null
        } catch (e: Exception) {
            return "नेटवर्क टूट गया, फिर दबाओ, वहीं से आगे चलेगा (" + (e.message ?: "") + ")"
        } finally { downloading = false }
    }

    fun delete(c: Context) { close(); file(c).delete(); File(file(c).path + ".part").delete() }

    fun close() { try { engine?.close() } catch (_: Exception) {}; engine = null }

    @Synchronized private fun get(c: Context): LlmInference {
        engine?.let { return it }
        val opts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file(c).absolutePath)
            .setMaxTokens(1280)
            .setMaxTopK(40)
            .build()
        return LlmInference.createFromOptions(c.applicationContext, opts).also { engine = it }
    }

    /** Background thread se hi bulana. */
    @Synchronized fun ask(c: Context, system: String, history: List<Pair<String, String>>, userText: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        for ((role, t) in history.takeLast(6)) sb.append("<|im_start|>").append(role).append("\n").append(t).append("<|im_end|>\n")
        sb.append("<|im_start|>user\n").append(userText).append("<|im_end|>\n<|im_start|>assistant\n")
        val out = get(c).generateResponse(sb.toString())
        return out.replace("<|im_end|>", "").substringBefore("<|im_start|>").trim()
    }
}
