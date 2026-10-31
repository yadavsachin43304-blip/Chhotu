package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.chhotu.ChhotuLocalLlmClient
import com.ai.assistance.operit.core.chhotu.ChhotuLocalLlmCrashedException
import com.ai.assistance.operit.data.mnn.MnnModelDownloadManager
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.preferences.CharacterCardBilingualData
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.Stream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Chhotu Master "online + offline brain".
 *
 * Wraps the user's cloud model (Gemini / OpenRouter / Groq / ...). Before every request it checks:
 *  - is there internet?
 *  - is the cloud model configured (API key present)?
 * If not, or if the cloud request fails before any text arrived, the same turn is answered by the
 * on-device model (MNN or llama.cpp) so voice and text chat keep working offline.
 *
 * The offline turn uses a short Chhotu system prompt and only the last few chat turns, because a
 * 1-2B phone model is slow on Operit's long tool prompt and has a small context window.
 * Nothing is sent anywhere except the chosen cloud provider; the offline path never uses network.
 */
class ChhotuOfflineFallbackService(
    private val appContext: Context,
    private val primary: AIService,
    private val primaryConfig: ModelConfigData,
    private val modelConfigManager: ModelConfigManager,
) : AIService {

    companion object {
        private const val TAG = "ChhotuBrain"
        private const val MAX_OFFLINE_TURNS = 6
        private const val MAX_OFFLINE_CHARS_PER_TURN = 800

        private val localProviderTypes = setOf(ApiProviderType.MNN, ApiProviderType.LLAMA_CPP)

        /** True when this config talks to a cloud/network model and should get an offline fallback. */
        fun shouldWrap(config: ModelConfigData): Boolean {
            val type = ApiProviderType.fromProviderTypeId(config.apiProviderTypeId.trim()) ?: return false
            return type !in localProviderTypes
        }

        fun isOnline(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return false
                val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } catch (e: Exception) {
                AppLogger.w(TAG, "network check failed", e)
                false
            }
        }

        /**
         * Finds an on-device model: first a model config the user created for MNN / llama.cpp,
         * then any downloaded MNN model folder, then any .gguf file in the llama models folder.
         */
        suspend fun findLocalModelConfig(modelConfigManager: ModelConfigManager): ModelConfigData? {
            val configured =
                runCatching {
                    modelConfigManager.configListFlow.first().mapNotNull { id ->
                        modelConfigManager.getModelConfig(id)
                    }
                }.getOrElse {
                    AppLogger.w(TAG, "reading model configs failed", it)
                    emptyList()
                }
            for (config in configured) {
                val type = ApiProviderType.fromProviderTypeId(config.apiProviderTypeId.trim()) ?: continue
                if (type !in localProviderTypes) continue
                val modelName = config.modelName.split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() }
                    ?: continue
                if (localModelExists(type, modelName)) {
                    return config.copy(modelName = modelName, enableToolCall = false)
                }
            }

            val mnnDir = MnnModelDownloadManager.MODEL_DIR
            val mnnModel =
                mnnDir.listFiles { file -> file.isDirectory && File(file, "llm.mnn").exists() }
                    ?.sortedWith(compareBy({ mnnPreferenceRank(it.name) }, { it.name }))
                    ?.firstOrNull()
            if (mnnModel != null) {
                return ModelConfigData(
                    id = "chhotu_offline_mnn",
                    name = "Chhotu offline (MNN)",
                    modelName = mnnModel.name,
                    apiProviderType = ApiProviderType.MNN,
                    apiProviderTypeId = ApiProviderType.MNN.name,
                    enableToolCall = false,
                    mnnThreadCount = 4,
                )
            }

            val llamaModel =
                LlamaProvider.getModelsDir()
                    .listFiles { file -> file.isFile && file.name.lowercase().endsWith(".gguf") }
                    ?.sortedBy { it.length() }
                    ?.lastOrNull()
            if (llamaModel != null) {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                return ModelConfigData(
                    id = "chhotu_offline_llama",
                    name = "Chhotu offline (llama.cpp)",
                    modelName = llamaModel.name,
                    apiProviderType = ApiProviderType.LLAMA_CPP,
                    apiProviderTypeId = ApiProviderType.LLAMA_CPP.name,
                    enableToolCall = false,
                    llamaThreadCount = threads,
                    llamaContextSize = 4096,
                )
            }
            return null
        }

        private fun mnnPreferenceRank(name: String): Int {
            val n = name.lowercase()
            return when {
                n.startsWith("qwen3-1.7b") -> 0
                n.startsWith("qwen3.5-2b") -> 1
                n.startsWith("qwen2.5-1.5b") -> 2
                n.startsWith("qwen3.5-0.8b") -> 3
                n.startsWith("qwen3-0.6b") -> 4
                else -> 10
            }
        }

        private fun localModelExists(type: ApiProviderType, modelName: String): Boolean {
            return when (type) {
                ApiProviderType.MNN -> File(MnnModelDownloadManager.MODEL_DIR, "$modelName/llm.mnn").exists()
                ApiProviderType.LLAMA_CPP -> File(LlamaProvider.getModelsDir(), modelName).exists()
                else -> false
            }
        }

        private val thinkRegex = Regex("(?s)<think>.*?</think>")
        private val tagRegex = Regex("<[^>]{1,200}>")

        internal fun buildOfflineHistory(chatHistory: List<PromptTurn>): List<PromptTurn> {
            val conversation =
                chatHistory
                    .filter { it.kind == PromptTurnKind.USER || it.kind == PromptTurnKind.ASSISTANT }
                    .map { turn ->
                        val cleaned =
                            turn.content
                                .replace(thinkRegex, " ")
                                .replace(tagRegex, " ")
                                .replace(Regex("\\s+"), " ")
                                .trim()
                        PromptTurn(kind = turn.kind, content = cleaned.takeLast(MAX_OFFLINE_CHARS_PER_TURN))
                    }
                    .filter { it.content.isNotBlank() }
                    .takeLast(MAX_OFFLINE_TURNS)
                    .dropWhile { it.kind != PromptTurnKind.USER }
            return listOf(
                PromptTurn(kind = PromptTurnKind.SYSTEM, content = CharacterCardBilingualData.CHHOTU_OFFLINE_SYSTEM_PROMPT)
            ) + conversation
        }
    }

    /** Result of the last offline turn, read by the self-test. */
    enum class OfflineOutcome { NONE, OK, FAILED, CRASHED }

    @Volatile private var offlineActive = false
    @Volatile private var offlineProviderModel: String = ""
    @Volatile private var offlineOutputTokens: Long = 0L
    @Volatile var lastOfflineOutcome: OfflineOutcome = OfflineOutcome.NONE
        private set
    @Volatile var lastOfflineError: String? = null
        private set

    override val inputTokenCount: Long
        get() = if (offlineActive) 0L else primary.inputTokenCount

    override val cachedInputTokenCount: Long
        get() = if (offlineActive) 0L else primary.cachedInputTokenCount

    override val outputTokenCount: Long
        get() = if (offlineActive) offlineOutputTokens else primary.outputTokenCount

    override val providerModel: String
        get() = if (offlineActive) offlineProviderModel else primary.providerModel

    override fun resetTokenCounts() {
        primary.resetTokenCounts()
        offlineOutputTokens = 0L
    }

    override fun cancelStreaming() {
        primary.cancelStreaming()
        ChhotuLocalLlmClient.cancelAll()
    }

    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
        return primary.getModelsList(context)
    }

    override suspend fun testConnection(context: Context): Result<String> {
        return primary.testConnection(context)
    }

    override suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?
    ): Long {
        return primary.calculateInputTokens(chatHistory, availableTools)
    }

    override fun release() {
        primary.release()
        ChhotuLocalLlmClient.cancelAll()
    }

    private fun cloudUnavailableReason(): String? {
        if (!isOnline(appContext)) return "no internet"
        val readiness = ChatConfigReadiness.evaluate(primaryConfig, 0, emptySet())
        if (!readiness.isReady) return "cloud model not configured (${readiness.issue})"
        return null
    }

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Long, cachedInput: Long, output: Long) -> Unit,
        onUsageReported: (suspend (ProviderUsageSnapshot, attempt: Int) -> Unit)?,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean,
        recordTokenUsage: Boolean,
        onUsageFinalized: (suspend (attempt: Int?) -> Unit)?,
    ): Stream<String> = com.ai.assistance.operit.util.stream.stream {
        suspend fun runOffline(local: ModelConfigData, why: String) {
            val label = "${local.apiProviderTypeId}:${local.modelName}"
            AppLogger.i(TAG, "CHHOTU_BRAIN_MODE=OFFLINE reason=$why model=$label")
            offlineActive = true
            offlineProviderModel = label
            offlineOutputTokens = 0L
            var emittedText = false
            val replyText = StringBuilder()
            try {
                // runs in the separate ":llm" process, so a native crash there cannot kill the app
                ChhotuLocalLlmClient.generate(
                    context = appContext,
                    localConfig = local,
                    history = buildOfflineHistory(chatHistory),
                    onProviderModel = { pm -> if (pm.isNotBlank() && pm != "cancelled") offlineProviderModel = pm },
                ).collect { chunk ->
                    if (chunk.isNotBlank()) emittedText = true
                    if (replyText.length < 400) replyText.append(chunk)
                    offlineOutputTokens += 1
                    emit(chunk)
                }
                runCatching { onTokensUpdated(0L, 0L, offlineOutputTokens) }
                if (emittedText) {
                    lastOfflineOutcome = OfflineOutcome.OK
                    lastOfflineError = null
                    AppLogger.i(
                        TAG,
                        "CHHOTU_BRAIN_OFFLINE_REPLY_DONE tokens=$offlineOutputTokens text=" +
                            replyText.toString().replace(Regex("\\s+"), " ").trim().take(300)
                    )
                } else {
                    lastOfflineOutcome = OfflineOutcome.FAILED
                    lastOfflineError = "empty reply"
                    AppLogger.w(TAG, "CHHOTU_BRAIN_OFFLINE_FAILED empty reply")
                    emit(appContext.getString(R.string.chhotu_offline_failed, "empty reply"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChhotuLocalLlmCrashedException) {
                lastOfflineOutcome = OfflineOutcome.CRASHED
                lastOfflineError = e.message
                AppLogger.e(TAG, "CHHOTU_BRAIN_OFFLINE_CRASHED ${e.message}")
                emit((if (emittedText) "\n\n" else "") + appContext.getString(R.string.chhotu_offline_crashed))
            } catch (e: Throwable) {
                lastOfflineOutcome = OfflineOutcome.FAILED
                lastOfflineError = "${e.javaClass.simpleName}: ${e.message}"
                AppLogger.e(TAG, "CHHOTU_BRAIN_OFFLINE_FAILED ${e.javaClass.simpleName}: ${e.message}")
                emit(
                    (if (emittedText) "\n\n" else "") +
                        appContext.getString(R.string.chhotu_offline_failed, (e.message ?: e.javaClass.simpleName).take(160))
                )
            }
            runCatching { onUsageFinalized?.invoke(1) }
        }

        val unavailable = cloudUnavailableReason()
        if (unavailable != null) {
            val local = findLocalModelConfig(modelConfigManager)
            if (local != null) {
                runOffline(local, unavailable)
                return@stream
            }
            if (!isOnline(appContext) ||
                !ChatConfigReadiness.evaluate(primaryConfig, 0, emptySet()).isReady
            ) {
                AppLogger.w(TAG, "CHHOTU_BRAIN_MODE=NONE reason=$unavailable (no offline model)")
                lastOfflineOutcome = OfflineOutcome.FAILED
                lastOfflineError = "no offline model ($unavailable)"
                // answer with a clear message (shown + read aloud) instead of an error popup
                emit(appContext.getString(R.string.chhotu_offline_no_model))
                return@stream
            }
        }

        AppLogger.i(TAG, "CHHOTU_BRAIN_MODE=ONLINE model=${primary.providerModel}")
        offlineActive = false
        var emittedAny = false
        try {
            primary.sendMessage(
                context = context,
                chatHistory = chatHistory,
                modelParameters = modelParameters,
                enableThinking = enableThinking,
                stream = stream,
                availableTools = availableTools,
                preserveThinkInHistory = preserveThinkInHistory,
                onTokensUpdated = onTokensUpdated,
                onUsageReported = onUsageReported,
                onNonFatalError = onNonFatalError,
                enableRetry = enableRetry,
                recordTokenUsage = recordTokenUsage,
                onUsageFinalized = onUsageFinalized,
            ).collect { chunk ->
                emittedAny = true
                emit(chunk)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (emittedAny) throw e
            val local = findLocalModelConfig(modelConfigManager) ?: throw e
            AppLogger.w(TAG, "cloud request failed before any reply, switching to offline brain", e)
            runOffline(local, "cloud error: ${e.javaClass.simpleName}")
        }
    }
}
