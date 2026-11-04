package com.phoneuse.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runs Android's SpeechRecognizer without the system dialog and exposes what the mic UI draws as [state].
 * The recognised text goes to [onText] once per session. Main thread only; call [destroy] when the UI goes.
 */
class VoiceRecognizer(private val context: Context, private val onText: (String) -> Unit) {
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recognizerEngine: VoiceEngine? = null

    /** Bumped by every start and cancel, so callbacks from an older session are ignored. */
    private var session = 0

    /** Which recognizer this device offers; [VoiceEngine.Dialog] means the caller shows the system dialog. */
    val engine: VoiceEngine by lazy { detectEngine(context) }

    fun start(languageTag: String) {
        val engine = engine
        if (engine == VoiceEngine.Dialog) {
            emit(VoiceEvent.Failed(VoiceError.UNAVAILABLE))
            return
        }
        val id = ++session
        main.removeCallbacksAndMessages(null)
        val r = recognizerFor(engine) ?: run {
            emit(VoiceEvent.Start)
            emit(VoiceEvent.Failed(VoiceError.UNAVAILABLE))
            return
        }
        emit(VoiceEvent.Start)
        r.setRecognitionListener(Listener(id))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        try {
            r.startListening(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "speech recognition could not start", e)
            emit(VoiceEvent.Failed(VoiceError.UNAVAILABLE))
        }
    }

    /** Done: stop listening and keep what was said (the final text follows shortly). */
    fun stop() {
        if (!_state.value.listening) return
        emit(VoiceEvent.EndOfSpeech)
        runCatching { recognizer?.stopListening() }
        awaitFinal()
    }

    /** Some engines never answer a manual stop; after a while, use what was heard so far. */
    private fun awaitFinal() {
        val id = session
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ if (id == session && _state.value.phase == VoicePhase.PROCESSING) finish(emptyList()) }, FINAL_WAIT_MS)
    }

    /** Cancel: drop what was heard. */
    fun cancel() {
        session++
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        emit(VoiceEvent.Reset)
    }

    /** Shows [error] inline without running the recognizer (permission denied, no dialog either). */
    fun fail(error: VoiceError) {
        emit(VoiceEvent.Start)
        emit(VoiceEvent.Failed(error))
    }

    /** Clears an error message once it has been shown. */
    fun clearError() {
        if (_state.value.phase == VoicePhase.ERROR) emit(VoiceEvent.Reset)
    }

    fun destroy() {
        session++
        main.removeCallbacksAndMessages(null)
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        _state.value = VoiceState()
    }

    private fun recognizerFor(engine: VoiceEngine): SpeechRecognizer? {
        recognizer?.let { if (recognizerEngine == engine) return it }
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = try {
            when (engine) {
                is VoiceEngine.Service -> SpeechRecognizer.createSpeechRecognizer(context, ComponentName(engine.packageName, engine.className))
                VoiceEngine.OnDevice -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null
                VoiceEngine.Dialog -> null
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "speech recognizer could not be created", e)
            null
        }
        recognizerEngine = engine
        return recognizer
    }

    private fun finish(results: List<String>) {
        val text = finalText(_state.value, results)
        session++
        main.removeCallbacksAndMessages(null)
        if (text == null) {
            emit(VoiceEvent.Failed(VoiceError.NO_MATCH))
        } else {
            emit(VoiceEvent.Final(results))
            onText(text)
        }
    }

    private fun emit(event: VoiceEvent) {
        _state.value = _state.value.reduce(event)
    }

    private inner class Listener(private val id: Int) : RecognitionListener {
        private fun current(block: () -> Unit) {
            if (id == session) block()
        }

        override fun onReadyForSpeech(params: Bundle?) = current { emit(VoiceEvent.Ready) }
        override fun onBeginningOfSpeech() = current { emit(VoiceEvent.Ready) }
        override fun onRmsChanged(rmsdB: Float) = current { emit(VoiceEvent.Rms(rmsdB)) }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = current {
            emit(VoiceEvent.EndOfSpeech)
            awaitFinal()
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) = current {
            texts(partialResults).firstOrNull()?.let { emit(VoiceEvent.Partial(it)) }
        }

        override fun onResults(results: Bundle?) = current { finish(texts(results)) }

        override fun onError(error: Int) = current {
            // A manual stop that ends in "no match" still has whatever was heard.
            if (_state.value.partial.isNotBlank() && voiceErrorOf(error) == VoiceError.NO_MATCH) {
                finish(emptyList())
            } else {
                session++
                main.removeCallbacksAndMessages(null)
                emit(VoiceEvent.Failed(voiceErrorOf(error)))
            }
        }

        private fun texts(bundle: Bundle?): List<String> =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    }

    companion object {
        private const val TAG = "VoiceRecognizer"

        /** How long a manual stop waits for the final text before using the partial. */
        const val FINAL_WAIT_MS = 4_000L

        fun detectEngine(context: Context): VoiceEngine {
            val services = runCatching {
                context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                    .mapNotNull { it.serviceInfo?.let { s -> s.packageName to s.name } }
            }.getOrDefault(emptyList())
            val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
            return pickVoiceEngine(services, context.packageName, onDevice)
        }
    }
}
