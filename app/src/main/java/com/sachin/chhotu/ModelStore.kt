package com.sachin.chhotu

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Chhotu ke model (dimaag, awaaz) ek baar download hote hain, phir hamesha phone me. Download beech me tute to wahin se aage. */
object ModelStore {
    data class Model(val id: String, val title: String, val url: String, val size: Long, val fileName: String, val untar: Boolean = false)

    // Dimaag: Gemma 4 E2B (Google, Apache-2.0, 140+ bhashayen), LiteRT-LM universal build (CPU + GPU dono).
    // v0.5 me "-gpu" wali file thi jo sirf GPU pe chalti thi; jin phones ka GPU nahi chala unpe dimaag band rehta tha.
    val BRAIN = Model("brain", "छोटू का दिमाग़ (Gemma 4)",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        2588147712L, "chhotu-brain-gemma4-universal.litertlm")

    // Awaaz: Kokoro multi-lang (Hindi awaazein: hf_alpha, hf_beta, hm_omega, hm_psi), sherpa-onnx
    val VOICE = Model("voice", "छोटू की असली आवाज़ (Kokoro)",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
        132353410L, "kokoro-int8-multi-lang-v1_0.tar.bz2", untar = true)

    @Volatile private var busy = HashSet<String>()

    fun dir(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "models").apply { mkdirs() }
    fun file(c: Context, m: Model) = File(dir(c), m.fileName)
    fun unpackedDir(c: Context, m: Model) = File(dir(c), m.fileName.substringBefore(".tar"))
    fun ready(c: Context, m: Model): Boolean =
        if (m.untar) File(unpackedDir(c, m), "done.flag").exists() else file(c, m).length() == m.size
    fun downloading(m: Model) = synchronized(busy) { m.id in busy }

    /** Background thread se. progress 0..100. null = safal. */
    fun download(c: Context, m: Model, progress: (Int) -> Unit): String? {
        synchronized(busy) { if (m.id in busy) return "पहले से डाउनलोड हो रहा है"; busy.add(m.id) }
        try {
            if (!ready(c, m) && file(c, m).length() != m.size) {
                val part = File(file(c, m).path + ".part")
                var have = part.length()
                var url = m.url; var hops = 0
                var conn: HttpURLConnection
                while (true) {
                    conn = URL(url).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 20000; conn.readTimeout = 60000
                    if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                    val code = conn.responseCode
                    if (code in 301..308 && hops++ < 8) { url = URL(URL(url), conn.getHeaderField("Location")).toString(); conn.disconnect(); continue }
                    if (code == 416 && have == m.size) break
                    if (code == 200) have = 0 else if (code != 206) return "डाउनलोड नहीं हुआ (HTTP $code)"
                    conn.inputStream.use { inp ->
                        FileOutputStream(part, have > 0).use { out ->
                            val buf = ByteArray(1 shl 16); var done = have; var last = -1
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                val p = (done * 100 / m.size).toInt(); if (p != last) { last = p; progress(p) }
                            }
                        }
                    }
                    break
                }
                if (part.length() != m.size) return "डाउनलोड अधूरा रहा, फिर दबाओ, वहीं से आगे चलेगा"
                part.renameTo(file(c, m))
            }
            if (m.untar) {
                progress(100)
                val out = unpackedDir(c, m); out.mkdirs()
                TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(file(c, m))))).use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: break
                        // archive ka pehla folder hata do
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isEmpty() || rel.contains("..")) continue
                        val f = File(out, rel)
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); FileOutputStream(f).use { tar.copyTo(it) } }
                    }
                }
                File(out, "done.flag").writeText("ok")
                file(c, m).delete() // tar.bz2 ki jagah khali
            }
            return null
        } catch (e: Exception) {
            return "नेटवर्क टूट गया, फिर दबाओ, वहीं से आगे चलेगा (" + (e.message ?: "") + ")"
        } finally { synchronized(busy) { busy.remove(m.id) } }
    }

    fun delete(c: Context, m: Model) {
        file(c, m).delete(); File(file(c, m).path + ".part").delete(); unpackedDir(c, m).deleteRecursively()
    }

    /** v0.4 wala purana Qwen dimaag hata do (1.6 GB) */
    fun cleanupOld(c: Context) {
        val base = c.getExternalFilesDir(null) ?: c.filesDir
        File(base, "chhotu-brain.task").delete(); File(base, "chhotu-brain.task.part").delete()
        // v0.5 ki sirf-GPU wali file (2 GB) ab kaam ki nahi
        val d = File(base, "models"); File(d, "chhotu-brain-gemma4.litertlm").delete(); File(d, "chhotu-brain-gemma4.litertlm.part").delete()
    }
}
