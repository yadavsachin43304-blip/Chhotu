package com.ai.assistance.operit.core.chhotu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ai.assistance.operit.api.chat.llmprovider.AIServiceFactory
import com.ai.assistance.operit.api.chat.llmprovider.ChhotuOfflineFallbackService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.data.preferences.AgreementPreferences
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.data.preferences.initAndroidPermissionPreferences
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Chhotu Master self-test, used by the CI emulator test (and handy for debugging on a phone).
 *
 * Trigger: adb shell am broadcast -a com.sachin.chhotu.SELFTEST -n <pkg>/com.ai.assistance.operit.core.chhotu.ChhotuSelfTestReceiver
 * Protected by android.permission.DUMP, so only adb shell / the system can trigger it, never other apps.
 *
 * It checks:
 *  1. Offline brain: a Gemini config with NO key is wrapped by ChhotuOfflineFallbackService, which must
 *     route the turn to the on-device model and return text. Log tag ChhotuSelfTest.
 *  2. Hindi TTS: the system TextToSpeech engine synthesises a Hindi sentence to a WAV file.
 */
class ChhotuSelfTestReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ChhotuSelfTest"
        const val ACTION = "com.sachin.chhotu.SELFTEST"
        const val ACTION_PREPARE = "com.sachin.chhotu.SELFTEST_PREPARE"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (intent.action == ACTION_PREPARE) {
            prepareForTest(appContext)
            return
        }
        if (intent.action != ACTION) return
        val runLlm = intent.getBooleanExtra("llm", true)
        val runTts = intent.getBooleanExtra("tts", true)
        val crashProbe = intent.getBooleanExtra("crash_probe", false)
        AppLogger.i(TAG, "CHHOTU_SELFTEST_START llm=$runLlm tts=$runTts crash_probe=$crashProbe")
        val pending = goAsync()
        scope.launch {
            try {
                if (runTts) testTts(appContext)
                if (runLlm) testOfflineBrain(appContext, crashProbe)
            } catch (e: Throwable) {
                AppLogger.e(TAG, "CHHOTU_SELFTEST_CRASH ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                AppLogger.i(TAG, "CHHOTU_SELFTEST_END")
                pending.finish()
            }
        }
    }

    /**
     * Test-only: marks the first-run agreement as accepted and picks the "standard" permission
     * level, so the emulator test lands on the chat screen without tapping through onboarding.
     */
    private fun prepareForTest(context: Context) {
        val pending = goAsync()
        scope.launch {
            try {
                AgreementPreferences(context).acceptCurrentAgreement()
                initAndroidPermissionPreferences(context)
                androidPermissionPreferences.savePreferredPermissionLevel(AndroidPermissionLevel.STANDARD)
                AppLogger.i(TAG, "CHHOTU_SELFTEST_PREPARED level=${androidPermissionPreferences.getPreferredPermissionLevel()}")
            } catch (e: Throwable) {
                AppLogger.e(TAG, "CHHOTU_SELFTEST_PREPARE_FAIL ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun testOfflineBrain(context: Context, crashProbe: Boolean = false) {
        if (crashProbe) {
            testCrashSafety(context)
            return
        }
        try {
            val modelConfigManager = ModelConfigManager(context)
            val local = ChhotuOfflineFallbackService.findLocalModelConfig(modelConfigManager)
            AppLogger.i(TAG, "CHHOTU_SELFTEST_LOCAL_MODEL ${local?.apiProviderTypeId}:${local?.modelName}")
            val cloudConfig = ModelConfigData(
                id = "chhotu_selftest_cloud",
                name = "selftest cloud (no key)",
                apiKey = "",
                apiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models",
                modelName = "gemini-3.5-flash-lite",
                apiProviderType = ApiProviderType.GOOGLE,
                apiProviderTypeId = ApiProviderType.GOOGLE.name,
            )
            val primary = AIServiceFactory.createService(cloudConfig, modelConfigManager, context)
            val brain = ChhotuOfflineFallbackService(context, primary, cloudConfig, modelConfigManager)
            val history = listOf(
                PromptTurn(kind = PromptTurnKind.USER, content = "Namaste Chhotu! Ek line mein batao, tum kaun ho?")
            )
            val reply = StringBuilder()
            val startedAt = System.currentTimeMillis()
            withTimeout(600_000L) {
                brain.sendMessage(context = context, chatHistory = history, stream = true)
                    .collect { chunk -> reply.append(chunk) }
            }
            val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
            val text = reply.toString().replace("\n", " ").trim()
            val outcome = brain.lastOfflineOutcome
            AppLogger.i(TAG, "CHHOTU_SELFTEST_LLM_OUTCOME $outcome error=${brain.lastOfflineError}")
            if (outcome != ChhotuOfflineFallbackService.OfflineOutcome.OK) {
                AppLogger.e(TAG, "CHHOTU_SELFTEST_LLM_FAIL outcome=$outcome error=${brain.lastOfflineError} shown=${text.take(200)}")
            } else if (text.isNotBlank()) {
                AppLogger.i(TAG, "CHHOTU_SELFTEST_LLM_OK provider=${brain.providerModel} seconds=$seconds reply=${text.take(300)}")
            } else {
                AppLogger.e(TAG, "CHHOTU_SELFTEST_LLM_FAIL empty reply provider=${brain.providerModel}")
            }
            brain.release()
        } catch (e: Throwable) {
            AppLogger.e(TAG, "CHHOTU_SELFTEST_LLM_FAIL ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /**
     * Crash-safety probe: asks the ":llm" process to SIGABRT itself mid-request (exactly what a
     * llama.cpp / MNN native abort does) and checks that this main process survives and gets the
     * friendly "offline brain stopped" message instead of dying.
     */
    private suspend fun testCrashSafety(context: Context) {
        try {
            val local = ChhotuOfflineFallbackService.findLocalModelConfig(ModelConfigManager(context))
            if (local == null) {
                AppLogger.e(TAG, "CHHOTU_SELFTEST_CRASHSAFE_FAIL no local model")
                return
            }
            val mainPid = android.os.Process.myPid()
            var crashed = false
            val text = StringBuilder()
            try {
                withTimeout(120_000L) {
                    ChhotuLocalLlmClient.generate(
                        context = context,
                        localConfig = local,
                        history = listOf(PromptTurn(kind = PromptTurnKind.USER, content = "Ek lambi kahani sunao.")),
                        crashProbe = true,
                    ).collect { text.append(it) }
                }
            } catch (e: ChhotuLocalLlmCrashedException) {
                crashed = true
                AppLogger.i(TAG, "crash probe: llm process died as intended: ${e.message}")
            }
            if (crashed && android.os.Process.myPid() == mainPid) {
                AppLogger.i(TAG, "CHHOTU_SELFTEST_CRASHSAFE_OK main_pid=$mainPid survived the :llm SIGABRT")
            } else {
                AppLogger.e(TAG, "CHHOTU_SELFTEST_CRASHSAFE_FAIL crashed=$crashed text=${text.take(200)}")
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "CHHOTU_SELFTEST_CRASHSAFE_FAIL ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun testTts(context: Context) {
        val initLatch = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        var tts: TextToSpeech? = null
        try {
            tts = TextToSpeech(context) { status ->
                initStatus = status
                initLatch.countDown()
            }
            if (!initLatch.await(30, TimeUnit.SECONDS) || initStatus != TextToSpeech.SUCCESS) {
                AppLogger.e(TAG, "CHHOTU_SELFTEST_TTS_FAIL init status=$initStatus engines=${tts.engines.map { it.name }}")
                return
            }
            val engine = tts.defaultEngine
            val hindi = Locale.forLanguageTag("hi-IN")
            val availability = tts.isLanguageAvailable(hindi)
            val setResult = tts.setLanguage(hindi)
            val hindiOk = setResult >= TextToSpeech.LANG_AVAILABLE
            if (!hindiOk) {
                tts.setLanguage(Locale.US)
            }
            AppLogger.i(TAG, "CHHOTU_SELFTEST_TTS_ENGINE engine=$engine hindiAvailability=$availability hindiSet=$setResult")
            val out = File(context.cacheDir, "chhotu_selftest_tts.wav")
            if (out.exists()) out.delete()
            val doneLatch = CountDownLatch(1)
            var done = false
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    done = true
                    doneLatch.countDown()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    doneLatch.countDown()
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    AppLogger.e(TAG, "TTS onError code=$errorCode")
                    doneLatch.countDown()
                }
            })
            val text = if (hindiOk) "नमस्ते, मैं छोटू हूँ। आपकी क्या मदद करूँ?" else "Namaste, main Chhotu hoon."
            val result = tts.synthesizeToFile(text, Bundle(), out, "chhotu_selftest")
            doneLatch.await(60, TimeUnit.SECONDS)
            val bytes = if (out.exists()) out.length() else 0L
            if (result == TextToSpeech.SUCCESS && done && bytes > 1000) {
                AppLogger.i(TAG, "CHHOTU_SELFTEST_TTS_OK hindi=$hindiOk engine=$engine bytes=$bytes")
            } else {
                AppLogger.e(TAG, "CHHOTU_SELFTEST_TTS_FAIL result=$result done=$done bytes=$bytes hindi=$hindiOk engine=$engine")
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "CHHOTU_SELFTEST_TTS_FAIL ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            try {
                tts?.shutdown()
            } catch (_: Throwable) {
            }
        }
    }
}
