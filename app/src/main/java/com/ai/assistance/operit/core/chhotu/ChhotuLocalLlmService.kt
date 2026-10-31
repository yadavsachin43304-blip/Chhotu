package com.ai.assistance.operit.core.chhotu

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.ai.assistance.llama.LlamaSession
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.api.chat.llmprovider.LlamaProvider
import com.ai.assistance.operit.api.chat.llmprovider.MNNProvider
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterValueType
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Chhotu offline brain host. Runs in its own process (android:process=":llm").
 *
 * llama.cpp / MNN are native code: if they abort (SIGABRT, out of memory, bad model file) only this
 * process dies. The main app sees the binder die and answers the user with a clear message instead
 * of crashing. The loaded model is kept in memory between turns so later replies start fast.
 *
 * Protocol (Messenger, same app only, service is not exported):
 *   MSG_GENERATE arg1=requestId, data: provider, model, threads, ctx, roles[], contents[], maxTokens
 *   MSG_CANCEL   arg1=requestId
 * Replies to msg.replyTo: MSG_CHUNK(text) ... then MSG_DONE(providerModel) or MSG_ERROR(error)
 */
class ChhotuLocalLlmService : Service() {

    companion object {
        private const val TAG = "ChhotuLlmProc"

        const val MSG_GENERATE = 1
        const val MSG_CANCEL = 2
        const val MSG_CHUNK = 10
        const val MSG_DONE = 11
        const val MSG_ERROR = 12

        const val KEY_PROVIDER = "provider"
        const val KEY_MODEL = "model"
        const val KEY_THREADS = "threads"
        const val KEY_CTX = "ctx"
        const val KEY_ROLES = "roles"
        const val KEY_CONTENTS = "contents"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_TEXT = "text"
        const val KEY_ERROR = "error"
        const val KEY_PROVIDER_MODEL = "provider_model"
        /** Self-test only (sent by the DUMP-protected self-test receiver): abort this process. */
        const val KEY_CRASH_PROBE = "crash_probe"
        private const val SIGABRT = 6
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val providerLock = Any()
    private var provider: AIService? = null
    private var providerKey: String? = null
    @Volatile private var currentJob: Job? = null
    @Volatile private var currentRequestId: Int = -1

    private val handler =
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_GENERATE -> startGeneration(msg.arg1, Bundle(msg.data), msg.replyTo)
                    MSG_CANCEL -> cancelGeneration(msg.arg1)
                    else -> super.handleMessage(msg)
                }
            }
        }
    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "CHHOTU_LLM_PROCESS_START pid=${android.os.Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        synchronized(providerLock) {
            runCatching { provider?.release() }
            provider = null
            providerKey = null
        }
        super.onDestroy()
    }

    private fun reply(target: Messenger?, what: Int, requestId: Int, data: Bundle) {
        if (target == null) return
        val msg = Message.obtain(null, what, requestId, 0)
        msg.data = data
        try {
            target.send(msg)
        } catch (e: RemoteException) {
            AppLogger.w(TAG, "client gone, cancelling request $requestId")
            cancelGeneration(requestId)
        }
    }

    private fun cancelGeneration(requestId: Int) {
        if (requestId == currentRequestId || requestId < 0) {
            provider?.cancelStreaming()
            currentJob?.cancel()
        }
    }

    private fun obtainProvider(type: ApiProviderType, model: String, threads: Int, ctx: Int): AIService {
        val key = "${type.name}:$model:$threads:$ctx"
        synchronized(providerLock) {
            val existing = provider
            if (existing != null && providerKey == key) return existing
            runCatching { existing?.release() }
            val created: AIService =
                when (type) {
                    ApiProviderType.MNN ->
                        MNNProvider(
                            context = applicationContext,
                            modelName = model,
                            forwardType = 0,
                            threadCount = threads,
                            providerType = ApiProviderType.MNN,
                            enableToolCall = false,
                        )
                    ApiProviderType.LLAMA_CPP ->
                        LlamaProvider(
                            context = applicationContext,
                            modelName = model,
                            sessionConfig =
                                LlamaSession.Config(
                                    nThreads = threads.coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)),
                                    nCtx = ctx.coerceAtLeast(512),
                                    nBatch = 512,
                                    nUBatch = 512,
                                    nGpuLayers = 0,
                                    useMmap = false,
                                    flashAttention = false,
                                    kvUnified = true,
                                    offloadKqv = false,
                                ),
                            providerType = ApiProviderType.LLAMA_CPP,
                            enableToolCall = false,
                        )
                    else -> throw IllegalArgumentException("not a local provider: $type")
                }
            provider = created
            providerKey = key
            return created
        }
    }

    private fun floatParam(id: String, value: Float): ModelParameter<Float> =
        ModelParameter(
            id = id,
            name = id,
            apiName = id,
            defaultValue = value,
            currentValue = value,
            isEnabled = true,
            valueType = ParameterValueType.FLOAT,
        )

    private fun startGeneration(requestId: Int, data: Bundle, replyTo: Messenger?) {
        // one generation at a time: a new request replaces the old one
        cancelGeneration(currentRequestId)
        currentRequestId = requestId
        currentJob =
            scope.launch {
                val startedAt = System.currentTimeMillis()
                try {
                    if (data.getBoolean(KEY_CRASH_PROBE, false)) {
                        AppLogger.w(TAG, "CHHOTU_LLM_CRASH_PROBE sending SIGABRT to own process ${android.os.Process.myPid()}")
                        reply(replyTo, MSG_CHUNK, requestId, Bundle().apply { putString(KEY_TEXT, "") })
                        kotlinx.coroutines.delay(500)
                        android.os.Process.sendSignal(android.os.Process.myPid(), SIGABRT)
                        kotlinx.coroutines.delay(10_000)
                    }
                    val type =
                        ApiProviderType.fromProviderTypeId(data.getString(KEY_PROVIDER).orEmpty())
                            ?: throw IllegalArgumentException("unknown provider ${data.getString(KEY_PROVIDER)}")
                    val model = data.getString(KEY_MODEL).orEmpty()
                    val threads = data.getInt(KEY_THREADS, 4)
                    val ctx = data.getInt(KEY_CTX, 4096)
                    val maxTokens = data.getInt(KEY_MAX_TOKENS, 320)
                    val roles = data.getStringArray(KEY_ROLES) ?: emptyArray()
                    val contents = data.getStringArray(KEY_CONTENTS) ?: emptyArray()
                    val history =
                        roles.indices.map { i ->
                            val kind =
                                when (roles[i]) {
                                    "system" -> PromptTurnKind.SYSTEM
                                    "assistant" -> PromptTurnKind.ASSISTANT
                                    else -> PromptTurnKind.USER
                                }
                            PromptTurn(kind = kind, content = contents.getOrElse(i) { "" })
                        }
                    AppLogger.i(TAG, "generate request=$requestId model=${type.name}:$model turns=${history.size}")
                    val p = obtainProvider(type, model, threads, ctx)
                    val params: List<ModelParameter<*>> =
                        listOf(
                            floatParam("temperature", 0.7f),
                            floatParam("top_p", 0.9f),
                            ModelParameter(
                                id = "max_tokens",
                                name = "max_tokens",
                                apiName = "max_tokens",
                                defaultValue = maxTokens,
                                currentValue = maxTokens,
                                isEnabled = true,
                                valueType = ParameterValueType.INT,
                            ),
                        )
                    p.sendMessage(
                        context = applicationContext,
                        chatHistory = history,
                        modelParameters = params,
                        enableThinking = false,
                        stream = true,
                        availableTools = null,
                    ).collect { chunk ->
                        if (chunk.isNotEmpty()) {
                            reply(replyTo, MSG_CHUNK, requestId, Bundle().apply { putString(KEY_TEXT, chunk) })
                        }
                    }
                    AppLogger.i(TAG, "generate done request=$requestId in ${(System.currentTimeMillis() - startedAt) / 1000.0}s")
                    reply(replyTo, MSG_DONE, requestId, Bundle().apply { putString(KEY_PROVIDER_MODEL, p.providerModel) })
                } catch (e: CancellationException) {
                    reply(replyTo, MSG_DONE, requestId, Bundle().apply { putString(KEY_PROVIDER_MODEL, "cancelled") })
                } catch (e: Throwable) {
                    AppLogger.e(TAG, "generate failed request=$requestId", e)
                    // a broken session may be left behind; drop it so the next turn starts clean
                    synchronized(providerLock) {
                        runCatching { provider?.release() }
                        provider = null
                        providerKey = null
                    }
                    reply(
                        replyTo,
                        MSG_ERROR,
                        requestId,
                        Bundle().apply { putString(KEY_ERROR, "${e.javaClass.simpleName}: ${e.message}") },
                    )
                }
            }
    }
}
