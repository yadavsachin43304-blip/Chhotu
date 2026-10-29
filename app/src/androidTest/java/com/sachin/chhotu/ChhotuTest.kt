package com.sachin.chhotu

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Asli test: emulator me Chhotu ka dimaag (Gemma 4) aur awaaz (Kokoro) chala kar dekhte hain
 * ki wo sach me baat karta hai, sahi ACTION deta hai, aur awaaz banti hai.
 * Report: files/test-report.txt
 */
@RunWith(AndroidJUnit4::class)
class ChhotuTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val report = StringBuilder()
    private fun log(s: String) { Log.i("CHHOTU_TEST", s); report.append(s).append('\n'); save() }
    private fun save() { try { File(ctx.getExternalFilesDir(null), "test-report.txt").writeText(report.toString()) } catch (_: Exception) {} }

    /** CI ne model /data/local/tmp me rakhe hain; app khud unhe apni jagah copy karta hai (jaise phone pe download ke baad hota). */
    private fun stageModels() {
        val src = File("/data/local/tmp/chhotu-brain-gemma4-universal.litertlm")
        val dst = ModelStore.file(ctx, ModelStore.BRAIN)
        if (src.exists() && dst.length() != src.length()) { val t = System.currentTimeMillis(); src.copyTo(dst, true); log("brain copied in ${System.currentTimeMillis() - t}ms") }
        val tar = File("/data/local/tmp/kokoro.tar")
        val out = ModelStore.dir(ctx)
        if (tar.exists() && !NaturalVoice.installed(ctx)) {
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(tar.inputStream().buffered()).use { t ->
                while (true) {
                    val e = t.nextEntry ?: break
                    val f = File(out, e.name)
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { t.copyTo(it) } }
                }
            }
            log("voice unpacked")
        }
    }

    @Test fun brainVoiceAndEngine() {
        val failures = ArrayList<String>()
        stageModels()
        log("brain installed=" + LocalBrain.installed(ctx) + " voice installed=" + NaturalVoice.installed(ctx))
        assertTrue("brain model missing", LocalBrain.installed(ctx))

        val brain = Brain(ctx)
        log("usesLocal=" + brain.usesLocal() + " online=" + brain.isOnline())

        data class Case(val q: String, val expectType: String?)
        val cases = listOf(
            Case("हेलो छोटू, कैसे हो?", null),
            Case("यार आज मेरा मूड बहुत ख़राब है, एग्ज़ाम में कम नंबर आए", null),
            Case("मुझे कल सुबह 7 बजे उठा देना", "alarm"),
            Case("राहुल को व्हाट्सएप पर बोलो मैं 5 बजे आऊँगा", null),
            Case("आज पुणे का मौसम कैसा है?", null),
            Case("आज की ताज़ा ख़बर बताओ", null),
            Case("भारत की राजधानी क्या है?", null),
            Case("speak in English please, what is the capital of Japan?", null),
            Case("एक छोटा सा चुटकुला सुनाओ", null),
        )
        for (c in cases) {
            val t0 = System.currentTimeMillis()
            val r = brain.ask(c.q)
            val ms = System.currentTimeMillis() - t0
            val act = ActionParser.parse(r.text)
            log("Q: ${c.q}\nA: ${r.text}\n   ok=${r.ok} action=${act} backend=${LocalBrain.backendName} time=${ms}ms ${r.detail}\n")
            if (!r.ok) failures.add("not ok: ${c.q} -> ${r.detail}")
            if (ActionParser.spoken(r.text).isBlank() && act == null) failures.add("empty: ${c.q}")
            if (r.text.contains("[याद") || r.text.contains("(संदर्भ")) failures.add("echo: ${c.q}")
            if (c.expectType != null && act?.optString("type") != c.expectType) failures.add("expected ${c.expectType}: ${c.q} got $act")
        }

        // Awaaz
        if (NaturalVoice.installed(ctx)) {
            val t0 = System.currentTimeMillis()
            val wav = NaturalVoice.synth(ctx, "नमस्ते भाई, मैं छोटू हूँ। बताओ आज क्या करना है?", "hi-IN", "hm_omega")
            log("voice wav bytes=${wav?.size} time=${System.currentTimeMillis() - t0}ms")
            if (wav == null || wav.size < 20000) failures.add("voice failed")
        } else failures.add("voice model missing")

        // Poora Engine: typed baat -> router/dimaag -> confirm -> haan
        val msgs = ArrayList<String>()
        var latch = CountDownLatch(1)
        val l = object : Engine.Listener {
            override fun onMessage(text: String, fromUser: Boolean) { if (!fromUser && !text.startsWith("ⓘ")) { synchronized(msgs) { msgs.add(text) }; latch.countDown() } }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Engine.init(ctx); Engine.autoListen = false; Engine.addListener(l) }
        fun turn(q: String): String? {
            latch = CountDownLatch(1); val before = synchronized(msgs) { msgs.size }
            Engine.handleTyped(q)
            latch.await(240, TimeUnit.SECONDS)
            return synchronized(msgs) { msgs.drop(before).lastOrNull() }
        }
        val a1 = turn("यूट्यूब खोलो"); log("ENGINE Q: यूट्यूब खोलो -> $a1")
        if (a1 == null || !a1.contains("?")) failures.add("engine confirm missing: $a1")
        val a2 = turn("हाँ"); log("ENGINE Q: हाँ -> $a2")
        if (a2 == null) failures.add("engine yes no reply")
        val a3 = turn("तुम्हारा नाम क्या है?"); log("ENGINE Q: तुम्हारा नाम क्या है? -> $a3")
        if (a3 == null || a3.contains("चल नहीं पाया")) failures.add("engine chat failed: $a3")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Engine.stopAll() }

        log(if (failures.isEmpty()) "RESULT: PASS" else "RESULT: FAIL\n" + failures.joinToString("\n"))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
