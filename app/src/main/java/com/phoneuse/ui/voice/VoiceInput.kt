package com.phoneuse.ui.voice

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import com.phoneuse.app.AppGraph
import com.phoneuse.app.voiceLanguageNow
import com.phoneuse.i18n.tr
import com.phoneuse.voice.VoiceEngine
import com.phoneuse.voice.VoiceError
import com.phoneuse.voice.VoicePhase
import com.phoneuse.voice.VoiceRecognizer
import com.phoneuse.voice.VoiceState
import com.phoneuse.voice.speechLanguageTag
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** How long an inline voice error stays before the input comes back. */
private const val ERROR_SHOW_MS = 2_600L

/**
 * One in-app voice input: the mic button calls [toggle], the listening UI draws [state] and offers [done] and
 * [cancel]. Made by [rememberVoiceInput].
 */
@Stable
class VoiceInput internal constructor(
    private val recognizer: VoiceRecognizer,
    stateHolder: State<VoiceState>,
    private val begin: () -> Unit,
    private val ended: () -> Unit,
    /** Drops a start still waiting for the dictation language; true when there was one. */
    private val cancelPending: () -> Boolean = { false },
) {
    val state: VoiceState by stateHolder

    /** The mic: starts listening, or finishes when already listening (tap the mic again to stop). */
    fun toggle() {
        when (state.phase) {
            VoicePhase.IDLE, VoicePhase.ERROR -> if (cancelPending()) ended() else begin()
            VoicePhase.STARTING, VoicePhase.LISTENING -> recognizer.stop()
            VoicePhase.PROCESSING -> Unit
        }
    }

    /** Done: keep what was said. */
    fun done() {
        if (cancelPending()) ended() else recognizer.stop()
    }

    /** Cancel (X): drop what was heard and go back to the input. */
    fun cancel() {
        val wasPending = cancelPending()
        val wasActive = state.active
        recognizer.cancel()
        if (wasActive || wasPending) ended()
    }
}

/**
 * Speech input that runs inside the app (no Google dialog): asks for the microphone when needed, ticks on
 * start and stop, clears error messages after a moment, and falls back to the system speech dialog only on a
 * device without any usable recognizer. [onText] gets the recognised text; [onEnd] is told when a session
 * ends without text (cancelled, failed, dialog closed).
 */
@Composable
fun rememberVoiceInput(onText: (String) -> Unit, onEnd: () -> Unit = {}): VoiceInput {
    val context = LocalContext.current
    val view = LocalView.current
    val latestText by rememberUpdatedState(onText)
    val latestEnd by rememberUpdatedState(onEnd)
    val recognizer = remember(context) { VoiceRecognizer(context) { latestText(it) } }
    DisposableEffect(recognizer) { onDispose { recognizer.destroy() } }
    val state = recognizer.state.collectAsState()

    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (text != null) latestText(text) else latestEnd()
    }
    val scope = rememberCoroutineScope()
    // The start that is still resolving the dictation language; a stop or cancel meanwhile drops it.
    val pending = remember { arrayOfNulls<Job>(1) }
    val cancelPending = {
        val job = pending[0]
        pending[0] = null
        job?.cancel()
        job != null
    }
    DisposableEffect(recognizer) { onDispose { pending[0]?.cancel(); pending[0] = null } }
    val listen = {
        pending[0]?.cancel()
        pending[0] = scope.launch {
            val language = speechLanguageTag(AppGraph.voiceLanguageNow(), Locale.getDefault())
            pending[0] = null
            if (recognizer.engine == VoiceEngine.Dialog) {
                try {
                    dialog.launch(dialogIntent(language))
                } catch (e: ActivityNotFoundException) {
                    recognizer.fail(VoiceError.UNAVAILABLE)
                }
            } else {
                recognizer.start(language)
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else recognizer.fail(VoiceError.PERMISSION)
    }
    val begin = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // The system dialog records in its own app; only the in-app recognizer needs our microphone permission.
        if (granted || recognizer.engine == VoiceEngine.Dialog) listen() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // A tick when the mic opens and when it closes, however it closed.
    val listening = state.value.listening
    val seen = remember { booleanArrayOf(false) }
    LaunchedEffect(listening) {
        if (seen[0]) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        seen[0] = true
    }
    val error = state.value.error
    LaunchedEffect(error) {
        if (error != null) {
            delay(ERROR_SHOW_MS)
            recognizer.clearError()
            latestEnd()
        }
    }
    return remember(recognizer) { VoiceInput(recognizer, state, begin, { latestEnd() }, cancelPending) }
}

private fun dialogIntent(language: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
    .putExtra(RecognizerIntent.EXTRA_PROMPT, tr("Speak now", "말해 주세요"))
