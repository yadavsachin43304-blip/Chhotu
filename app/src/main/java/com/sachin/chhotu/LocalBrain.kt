package com.sachin.chhotu

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Engine as LlmEngine

/**
 * Chhotu ka apna dimaag: Gemma 4 E2B phone ke andar (LiteRT-LM).
 * Na API key, na internet. GPU pe chalta hai (tez), GPU na mile to CPU.
 * Ek hi baat-cheet zinda rakhte hain taaki har baar poora prompt dobara na padhna pade (fast).
 */
object LocalBrain {
    @Volatile private var engine: LlmEngine? = null
    @Volatile private var convo: Conversation? = null
    @Volatile private var turns = 0
    @Volatile var backendName = ""; private set

    fun installed(c: Context) = ModelStore.ready(c, ModelStore.BRAIN)
    fun awake() = engine != null

    /** Pehli baar dimaag load hone me (cache banne me) 1-2 minute lag sakte hain. UI ko batane ke liye. */
    @Volatile var loading = false; private set
    @Volatile var lastError = ""; private set

    @OptIn(ExperimentalApi::class)
    @Synchronized private fun engine(c: Context): LlmEngine {
        engine?.let { return it }
        val path = ModelStore.file(c, ModelStore.BRAIN).absolutePath
        val prefs = c.getSharedPreferences("chhotu_brain", Context.MODE_PRIVATE)
        loading = true
        try {
            try { ExperimentalFlags.enableSpeculativeDecoding = true } catch (_: Throwable) {}
            // GPU kuch phones pe app ko hi gira deta hai. Isliye koshish se pehle nishaan lagate hain;
            // agar app GPU ke dauraan gir gaya to agli baar seedha CPU.
            val gpuOk = !prefs.getBoolean("gpu_trying", false) && !prefs.getBoolean("gpu_bad", false)
            var e: LlmEngine? = null
            if (gpuOk) {
                prefs.edit().putBoolean("gpu_trying", true).commit()
                try {
                    e = LlmEngine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = c.cacheDir.path)).also { it.initialize() }
                    backendName = "GPU"
                } catch (t: Throwable) {
                    lastError = "GPU: " + t.message
                    prefs.edit().putBoolean("gpu_bad", true).apply()
                    e = null
                } finally { prefs.edit().putBoolean("gpu_trying", false).commit() }
            } else if (prefs.getBoolean("gpu_trying", false)) {
                prefs.edit().putBoolean("gpu_trying", false).putBoolean("gpu_bad", true).commit()
            }
            if (e == null) {
                e = try {
                    LlmEngine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = c.cacheDir.path)).also { it.initialize() }
                } catch (t: Throwable) {
                    // speculative decoding ke bina ek aur koshish
                    try { ExperimentalFlags.enableSpeculativeDecoding = false } catch (_: Throwable) {}
                    LlmEngine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = c.cacheDir.path)).also { it.initialize() }
                }
                backendName = "CPU"
            }
            engine = e
            lastError = ""
            return e!!
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + (t.message ?: "")
            throw t
        } finally { loading = false }
    }

    /** App khulte hi background me dimaag garam kar do, taaki pehla jawab bhi tez aaye. */
    fun warmUp(c: Context) {
        if (!installed(c) || engine != null) return
        Thread { try { synchronized(this) { engine(c.applicationContext) } } catch (_: Throwable) {} }.start()
    }

    @Synchronized fun reset() { try { convo?.close() } catch (_: Throwable) {}; convo = null; turns = 0 }

    @Synchronized fun close() { reset(); try { engine?.close() } catch (_: Throwable) {}; engine = null }

    /** Alag se ek baar ka sawaal (seekhne ke liye), asli baat-cheet ko chhede bina. */
    @Synchronized fun oneShot(c: Context, prompt: String): String? = try {
        engine(c).createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3))).use { it.sendMessage(prompt).toString().trim() }
    } catch (_: Throwable) { null }

    /** Background thread se. system sirf nayi baat-cheet shuru hone par lagta hai. */
    @Synchronized fun ask(c: Context, system: String, userText: String): String {
        val e = engine(c)
        if (convo == null || turns >= 16) {
            reset()
            convo = e.createConversation(ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
            ))
        }
        turns++
        val out = try { convo!!.sendMessage(userText).toString() } catch (t: Throwable) {
            // baat-cheet kharab ho gayi (jaise context bhar gaya): nayi shuru karke ek baar aur
            reset()
            convo = e.createConversation(ConversationConfig(systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8)))
            turns = 1
            convo!!.sendMessage(userText).toString()
        }
        return out.replace(Regex("<[^>]{1,20}>"), "").trim()
    }
}
