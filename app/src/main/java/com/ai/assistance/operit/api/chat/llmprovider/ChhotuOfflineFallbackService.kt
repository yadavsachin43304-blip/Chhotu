package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
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

    @Volatile private var active: AIService = primary
    @Volatile private var localService: AIService? = null
    @Volatile private var localServiceModel: String? = null
    private val localLock = Any()

    override val inputTokenCount: Long
        get() = active.inputTokenCount

    override val cachedInputTokenCount: Long
        get() = active.cachedInputTokenCount

    override val outputTokenCount: Long
        get() = active.outputTokenCount

    override val providerModel: String
        get() = active.providerModel

    override fun resetTokenCounts() {
        primary.resetTokenCounts()
        localService?.resetTokenCounts()
    }

    override fun cancelStreaming() {
        primary.cancelStreaming()
        localService?.cancelStreaming()
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
        synchronized(localLock) {
            localService?.release()
            localService = null
            localServiceModel = null
        }
    }

    private fun cloudUnavailableReason(): String? {
        if (!isOnline(appContext)) return "no internet"
        val readiness = ChatConfigReadiness.evaluate(primaryConfig, 0, emptySet())
        if (!readiness.isReady) return "cloud model not configured (${readiness.issue})"
        return null
    }

    private suspend fun obtainLocalService(): AIService? {
        val localConfig = findLocalModelConfig(modelConfigManager) ?: return null
        val key = "${localConfig.apiProviderTypeId}:${localConfig.modelName}"
        synchronized(localLock) {
            val existing = localService
            if (existing != null && localServiceModel == key) return existing
            existing?.release()
            val created = AIServiceFactory.createService(localConfig, modelConfigManager, appContext)
            localService = created
            localServiceModel = key
            AppLogger.i(TAG, "offline brain ready: $key")
            return created
        }
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
        suspend fun runOffline(local: AIService, why: String) {
            AppLogger.i(TAG, "CHHOTU_BRAIN_MODE=OFFLINE reason=$why model=${local.providerModel}")
            active = local
            local.sendMessage(
                context = context,
                chatHistory = buildOfflineHistory(chatHistory),
                modelParameters = modelParameters,
                enableThinking = false,
                stream = stream,
                availableTools = null,
                preserveThinkInHistory = false,
                onTokensUpdated = onTokensUpdated,
                onUsageReported = onUsageReported,
                onNonFatalError = onNonFatalError,
                enableRetry = false,
                recordTokenUsage = recordTokenUsage,
                onUsageFinalized = onUsageFinalized,
            ).collect { chunk -> emit(chunk) }
            AppLogger.i(TAG, "CHHOTU_BRAIN_OFFLINE_REPLY_DONE")
        }

        val unavailable = cloudUnavailableReason()
        if (unavailable != null) {
            val local = obtainLocalService()
            if (local != null) {
                runOffline(local, unavailable)
                return@stream
            }
            if (!isOnline(appContext) ||
                !ChatConfigReadiness.evaluate(primaryConfig, 0, emptySet()).isReady
            ) {
                AppLogger.w(TAG, "CHHOTU_BRAIN_MODE=NONE reason=$unavailable (no offline model)")
                throw IOException(appContext.getString(R.string.chhotu_offline_no_model))
            }
        }

        AppLogger.i(TAG, "CHHOTU_BRAIN_MODE=ONLINE model=${primary.providerModel}")
        active = primary
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
            val local = obtainLocalService() ?: throw e
            AppLogger.w(TAG, "cloud request failed before any reply, switching to offline brain", e)
            runOffline(local, "cloud error: ${e.javaClass.simpleName}")
        }
    }
}
