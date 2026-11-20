package com.phoneuse.assist

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.phoneuse.app.AppGraph
import com.phoneuse.app.FontSize
import com.phoneuse.app.ThemeMode
import com.phoneuse.ui.theme.PhoneUseTheme
import com.phoneuse.ui.theme.isDark
import com.phoneuse.ui.voice.VoiceSheet
import com.phoneuse.ui.voice.rememberVoiceInput

/**
 * Voice input for windows that cannot host it themselves: the assistant sheet's 🎤 (the sheet hides and starts
 * this) and the overlay dock's 🎤. Shows a compact in-app listening sheet (the same waveform as the chat
 * composer). The assistant sends recognized text to its conversation. A dock session returns it to [VoiceBridge].
 */
class AssistVoiceActivity : ComponentActivity() {
    /** Set once the session has an outcome, so it is delivered once. */
    private var done = false
    private var partial: String? = null
    private var externalUi = false
    private val bridgeId: Int? get() = intent.getIntExtra(EXTRA_BRIDGE_SESSION, -1).takeIf { it >= 0 }
    private val conversation: Long? get() = intent.getLongExtra(EXTRA_CONVERSATION, -1L).takeIf { it >= 0 }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        partial = savedInstanceState?.getString(STATE_PARTIAL)
        externalUi = savedInstanceState?.getBoolean(STATE_EXTERNAL) == true
        val id = bridgeId
        if (id != null && VoiceBridge.activeSession.value != id) {
            finish()
            return
        }
        // An in-process recognizer cannot resume across recreation. Recover its last words once.
        if (id != null && !externalUi && !partial.isNullOrBlank()) {
            interrupt()
            finish()
            return
        }
        val autoStart = if (id == null) savedInstanceState == null else !externalUi
        setContent {
            val mode by AppGraph.themeMode.collectAsState()
            val fontSize by AppGraph.fontSize.collectAsState()
            PhoneUseTheme(isDark(mode ?: ThemeMode.SYSTEM), (fontSize ?: FontSize.DEFAULT).scale) {
                val voice = rememberVoiceInput(
                    onText = ::onText,
                    onEnd = { interrupt(); finish() },
                    onPartial = ::checkpoint,
                    onInterrupted = { heard ->
                        checkpoint(heard)
                        if (!isChangingConfigurations && !done && id != null) {
                            interrupt()
                            finish()
                        }
                    },
                    onExternalUi = { visible ->
                        externalUi = visible
                        if (id != null) VoiceBridge.setExternalUi(id, visible)
                    },
                    onAudioActivity = { if (id != null) VoiceBridge.heardAudio(id) },
                    canStart = { !done && (id == null || VoiceBridge.activeSession.value == id) },
                )
                val dismiss = {
                    // Explicit cancellation discards even a partial the recognizer already delivered.
                    onText(null)
                    voice.cancel()
                }
                BackHandler(onBack = dismiss)
                if (id != null) {
                    val active by VoiceBridge.activeSession.collectAsState()
                    LaunchedEffect(active) {
                        if (active != id) finish()
                    }
                }
                // An outstanding permission or system recognition result is delivered to the recreated launcher.
                VoiceSheet(voice, autoStart = autoStart, onDismiss = dismiss)
            }
        }
    }

    private fun onText(text: String?) {
        if (done) return
        done = true
        val id = bridgeId
        finish()
        if (id != null) {
            VoiceBridge.deliver(id, text)
        } else if (!text.isNullOrBlank()) {
            val result = AssistSession.sender.submit(text, conversation) {}
            // No sheet to keep open here: say why and keep the words visible.
            if (result is SubmitResult.Blocked) {
                Toast.makeText(applicationContext, "${result.reason}: $text", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkpoint(text: String) {
        val id = bridgeId ?: return
        if (done || text.isBlank()) return
        partial = text
        VoiceBridge.update(id, text)
    }

    private fun interrupt() {
        if (done) return
        done = true
        bridgeId?.let(VoiceBridge::interrupt)
    }

    /** Home pressed while listening: the microphone must not stay open behind the launcher. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!externalUi) {
            interrupt()
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PARTIAL, partial)
        outState.putBoolean(STATE_EXTERNAL, externalUi)
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) interrupt()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONVERSATION = "conversation"
        const val EXTRA_BRIDGE_SESSION = "bridge_session"
        private const val STATE_PARTIAL = "partial"
        private const val STATE_EXTERNAL = "external"
    }
}
