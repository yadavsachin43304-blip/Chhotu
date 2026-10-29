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

    @OptIn(ExperimentalApi::class)
    @Synchronized private fun engine(c: Context): LlmEngine {
        engine?.let { return it }
        val path = ModelStore.file(c, ModelStore.BRAIN).absolutePath
        try { ExperimentalFlags.enableSpeculativeDecoding = true } catch (_: Throwable) {}
        val e = try {
            LlmEngine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = c.cacheDir.path)).also { it.initialize(); backendName = "GPU" }
        } catch (t: Throwable) {
            LlmEngine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = c.cacheDir.path)).also { it.initialize(); backendName = "CPU" }
        }
        engine = e
        return e
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
        val out = convo!!.sendMessage(userText).toString()
        return out.replace(Regex("<[^>]{1,20}>"), "").trim()
    }
}
