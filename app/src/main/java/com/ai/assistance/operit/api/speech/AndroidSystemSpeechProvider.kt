package com.ai.assistance.operit.api.speech

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Chhotu Master: speech recognition through the phone's own Android SpeechRecognizer.
 *
 * Why: Operit only ships a Chinese/English sherpa-ncnn model, so Hindi speech could not be
 * understood offline. Android's recognizer supports hi-IN and works without internet once the
 * Hindi offline speech pack is installed (Settings > Google > Voice > Offline speech recognition).
 *
 * Results are emitted per utterance (partials grow, then one final), which matches how
 * SpeechInteractionManager accumulates segments.
 */
class AndroidSystemSpeechProvider(private val context: Context) : SpeechService {

    companion object {
        private const val TAG = "ChhotuAndroidStt"
        const val DEFAULT_LANGUAGE = "hi-IN"
        private const val RESTART_DELAY_MS = 150L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _state = MutableStateFlow(SpeechService.RecognitionState.UNINITIALIZED)
    override val recognitionStateFlow: StateFlow<SpeechService.RecognitionState> = _state.asStateFlow()

    override val currentState: SpeechService.RecognitionState
        get() = _state.value

    override val isRecognizing: Boolean
        get() =
            _state.value == SpeechService.RecognitionState.RECOGNIZING ||
                _state.value == SpeechService.RecognitionState.PREPARING

    private val _result = MutableStateFlow(SpeechService.RecognitionResult(""))
    override val recognitionResultFlow: StateFlow<SpeechService.RecognitionResult> = _result.asStateFlow()

    private val _error = MutableStateFlow(SpeechService.RecognitionError(0, ""))
    override val recognitionErrorFlow: StateFlow<SpeechService.RecognitionError> = _error.asStateFlow()

    private val _volume = MutableStateFlow(0f)
    override val volumeLevelFlow: StateFlow<Float> = _volume.asStateFlow()

    @Volatile private var active = false
    @Volatile private var continuous = false
    @Volatile private var partial = true
    @Volatile private var language = DEFAULT_LANGUAGE
    @Volatile private var stopRequested = false

    private val listener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = SpeechService.RecognitionState.RECOGNIZING
            }

            override fun onBeginningOfSpeech() {
                _state.value = SpeechService.RecognitionState.RECOGNIZING
            }

            override fun onRmsChanged(rmsdB: Float) {
                _volume.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _volume.value = 0f
                _state.value = SpeechService.RecognitionState.PROCESSING
            }

            override fun onError(error: Int) {
                _volume.value = 0f
                val recoverable =
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (active && continuous && !stopRequested && recoverable) {
                    // Keep listening in continuous voice mode; silence is not an error for the user.
                    mainHandler.postDelayed({ startListeningInternal() }, RESTART_DELAY_MS)
                    return
                }
                active = false
                if (recoverable) {
                    _state.value = SpeechService.RecognitionState.IDLE
                } else {
                    AppLogger.w(TAG, "recognizer error=$error (${describeError(error)})")
                    _error.value = SpeechService.RecognitionError(error, describeError(error))
                    _state.value = SpeechService.RecognitionState.ERROR
                }
            }

            override fun onResults(results: Bundle?) {
                val text = firstResult(results)
                _result.value = SpeechService.RecognitionResult(text = text, isFinal = true, confidence = 1f)
                if (active && continuous && !stopRequested) {
                    mainHandler.postDelayed({ startListeningInternal() }, RESTART_DELAY_MS)
                } else {
                    active = false
                    _state.value = SpeechService.RecognitionState.IDLE
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!partial) return
                val text = firstResult(partialResults)
                if (text.isNotBlank()) {
                    _result.value = SpeechService.RecognitionResult(text = text, isFinal = false, confidence = 0.5f)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

    private fun firstResult(bundle: Bundle?): String {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return list?.firstOrNull()?.trim().orEmpty()
    }

    private fun describeError(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
            SpeechRecognizer.ERROR_NETWORK -> "Network error (install the Hindi offline speech pack for offline use)"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognised"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
            else -> "Speech recognition error $error"
        }
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun buildIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !isOnline())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }

    private fun startListeningInternal() {
        if (!active) return
        val r = recognizer ?: return
        try {
            _state.value = SpeechService.RecognitionState.PREPARING
            r.startListening(buildIntent())
        } catch (e: Exception) {
            AppLogger.e(TAG, "startListening failed", e)
            active = false
            _error.value = SpeechService.RecognitionError(-2, e.message ?: "startListening failed")
            _state.value = SpeechService.RecognitionState.ERROR
        }
    }

    override suspend fun initialize(): Boolean {
        if (_isInitialized.value && recognizer != null) return true
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        if (!available) {
            AppLogger.w(TAG, "Android speech recognition is not available on this device")
            _error.value = SpeechService.RecognitionError(-1, "Android speech recognition not available")
            _state.value = SpeechService.RecognitionState.ERROR
            return false
        }
        withContext(Dispatchers.Main) {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                    it.setRecognitionListener(listener)
                }
            }
        }
        _isInitialized.value = true
        if (_state.value == SpeechService.RecognitionState.UNINITIALIZED ||
            _state.value == SpeechService.RecognitionState.ERROR
        ) {
            _state.value = SpeechService.RecognitionState.IDLE
        }
        return true
    }

    override suspend fun startRecognition(
        languageCode: String,
        continuousMode: Boolean,
        partialResults: Boolean,
        audioSource: Int,
    ): Boolean {
        if (!initialize()) return false
        if (active) return true
        language = languageCode.ifBlank { DEFAULT_LANGUAGE }
        continuous = continuousMode
        partial = partialResults
        stopRequested = false
        active = true
        _result.value = SpeechService.RecognitionResult("", isFinal = false)
        withContext(Dispatchers.Main) { startListeningInternal() }
        AppLogger.d(TAG, "startRecognition language=$language continuous=$continuousMode")
        return active
    }

    override suspend fun stopRecognition(): Boolean {
        stopRequested = true
        withContext(Dispatchers.Main) {
            try {
                recognizer?.stopListening()
            } catch (e: Exception) {
                AppLogger.w(TAG, "stopListening failed", e)
            }
        }
        return true
    }

    override suspend fun cancelRecognition() {
        stopRequested = true
        active = false
        withContext(Dispatchers.Main) {
            try {
                recognizer?.cancel()
            } catch (e: Exception) {
                AppLogger.w(TAG, "cancel failed", e)
            }
        }
        _volume.value = 0f
        _state.value = SpeechService.RecognitionState.IDLE
    }

    override fun shutdown() {
        active = false
        stopRequested = true
        val r = recognizer
        recognizer = null
        _isInitialized.value = false
        _state.value = SpeechService.RecognitionState.UNINITIALIZED
        if (r != null) {
            mainHandler.post {
                try {
                    r.destroy()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "destroy failed", e)
                }
            }
        }
    }

    override suspend fun getSupportedLanguages(): List<String> {
        return listOf("hi-IN", "en-IN", "en-US", "mr-IN")
    }

    override suspend fun recognize(audioData: FloatArray) {
        // The Android recognizer only listens to the live microphone; pre-recorded audio is not supported.
        _error.value = SpeechService.RecognitionError(-3, "Pre-recorded audio is not supported by Android system STT")
    }
}
