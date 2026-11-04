package com.phoneuse.assist

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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
 * composer, no Google dialog) and sends the recognised text like a typed sheet message.
 * With [EXTRA_TO_BRIDGE] (the overlay dock's 🎤) the text goes to [VoiceBridge] instead of being sent.
 */
class AssistVoiceActivity : ComponentActivity() {
    /** Set once the session has an outcome, so it is delivered once. */
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val mode by AppGraph.themeMode.collectAsState()
            val fontSize by AppGraph.fontSize.collectAsState()
            PhoneUseTheme(isDark(mode ?: ThemeMode.SYSTEM), (fontSize ?: FontSize.DEFAULT).scale) {
                val voice = rememberVoiceInput(onText = ::onText, onEnd = { onText(null) })
                BackHandler { voice.cancel(); onText(null) }
                // Listening starts at once, as the system dialog did; a recreated activity keeps waiting instead.
                VoiceSheet(voice, autoStart = savedInstanceState == null, onDismiss = { voice.cancel(); onText(null) })
            }
        }
    }

    private fun onText(text: String?) {
        if (done) return
        done = true
        val conversation = intent.getLongExtra(EXTRA_CONVERSATION, -1L).takeIf { it >= 0 }
        finish()
        if (toBridge) {
            VoiceBridge.deliver(text?.takeIf { it.isNotBlank() })
        } else if (!text.isNullOrBlank()) {
            val result = AssistSession.sender.submit(text, conversation) {}
            // No sheet to keep open here: say why and keep the words visible.
            if (result is SubmitResult.Blocked) {
                Toast.makeText(applicationContext, "${result.reason}: $text", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val toBridge: Boolean get() = intent.getBooleanExtra(EXTRA_TO_BRIDGE, false)

    /** Home pressed while listening: the microphone must not stay open behind the launcher. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        onText(null)
    }

    override fun onDestroy() {
        // Gone without a result (cancelled, killed): the waiting dock must not stay in listening mode.
        if (toBridge && isFinishing) VoiceBridge.deliver(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONVERSATION = "conversation"
        const val EXTRA_TO_BRIDGE = "to_bridge"
    }
}
