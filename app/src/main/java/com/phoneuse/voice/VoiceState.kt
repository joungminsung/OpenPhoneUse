package com.phoneuse.voice

import com.phoneuse.app.VoiceLanguage
import com.phoneuse.i18n.tr
import java.util.Locale

/**
 * In-app speech input, the pure part: what the mic UI shows ([VoiceState]), how recognizer callbacks change it
 * ([reduce]), which recognizer to use ([pickVoiceEngine]) and in which language ([speechLanguageTag]).
 * [VoiceRecognizer] feeds Android's SpeechRecognizer callbacks through here; the UI only draws the state.
 */
enum class VoicePhase {
    IDLE,

    /** Asked the recognizer to start; the mic is not open yet. */
    STARTING,
    LISTENING,

    /** Speech ended (or the user tapped done); waiting for the final text. */
    PROCESSING,

    /** A short inline message ([VoiceState.error]); the UI clears it after a moment. */
    ERROR,
}

/** Why listening ended without text, grouped the way the inline message words it. */
enum class VoiceError {
    NO_MATCH, NETWORK, BUSY, PERMISSION, LANGUAGE, AUDIO, UNAVAILABLE;

    /** The one-line inline message. */
    val message: String
        get() = when (this) {
            NO_MATCH -> tr("Didn't catch that. Try again", "잘 못 들었어요. 다시 말해 주세요")
            NETWORK -> tr("No connection for voice input", "음성 인식에 연결할 수 없어요")
            BUSY -> tr("Voice input is busy. Try again in a moment", "음성 인식이 사용 중이에요. 잠시 후 다시 해 주세요")
            PERMISSION -> tr("Allow the microphone to talk to me", "말하려면 마이크 권한을 허용해 주세요")
            LANGUAGE -> tr("This language isn't available for voice input", "이 언어는 음성 인식을 쓸 수 없어요")
            AUDIO -> tr("Couldn't use the microphone", "마이크를 쓸 수 없어요")
            UNAVAILABLE -> tr("Voice input isn't available on this device", "이 기기에서 음성 입력을 쓸 수 없어요")
        }
}

data class VoiceState(
    val phase: VoicePhase = VoicePhase.IDLE,
    /** What has been heard so far (partial results), shown live. */
    val partial: String = "",
    /** Input loudness, 0..1, for the waveform. */
    val level: Float = 0f,
    val error: VoiceError? = null,
) {
    /** True while the mic UI replaces the normal input (listening, finishing, or showing an error). */
    val active: Boolean get() = phase != VoicePhase.IDLE
    val listening: Boolean get() = phase == VoicePhase.STARTING || phase == VoicePhase.LISTENING
}

sealed interface VoiceEvent {
    data object Start : VoiceEvent
    data object Ready : VoiceEvent
    data class Rms(val db: Float) : VoiceEvent
    data class Partial(val text: String) : VoiceEvent

    /** Silence after speech, or the user tapped done. */
    data object EndOfSpeech : VoiceEvent

    /** The recognizer's final candidates, best first. */
    data class Final(val results: List<String>) : VoiceEvent
    data class Failed(val error: VoiceError) : VoiceEvent

    /** The user cancelled, or the error message has been shown long enough. */
    data object Reset : VoiceEvent
}

fun VoiceState.reduce(event: VoiceEvent): VoiceState = when (event) {
    VoiceEvent.Start -> VoiceState(phase = VoicePhase.STARTING)
    VoiceEvent.Ready -> if (phase == VoicePhase.STARTING) copy(phase = VoicePhase.LISTENING) else this
    is VoiceEvent.Rms -> if (listening) copy(level = rmsLevel(event.db)) else this
    is VoiceEvent.Partial -> if (listening || phase == VoicePhase.PROCESSING) {
        // Some engines send an empty partial between words; keep what was shown.
        if (event.text.isBlank()) this else copy(partial = event.text.trim())
    } else {
        this
    }
    VoiceEvent.EndOfSpeech -> if (listening) copy(phase = VoicePhase.PROCESSING, level = 0f) else this
    is VoiceEvent.Final -> VoiceState()
    is VoiceEvent.Failed -> if (active) VoiceState(phase = VoicePhase.ERROR, error = event.error) else this
    VoiceEvent.Reset -> VoiceState()
}

/**
 * The text a session ends with: the best final candidate, or what was last heard when the engine returns
 * nothing (some do after a manual stop). Null when there is nothing to use.
 */
fun finalText(state: VoiceState, results: List<String>): String? =
    results.firstOrNull { it.isNotBlank() }?.trim() ?: state.partial.takeIf { it.isNotBlank() }

/** SpeechRecognizer's onRmsChanged gives roughly -2..10 dB; mapped to 0..1 for drawing. */
fun rmsLevel(db: Float): Float = ((db + 2f) / 12f).coerceIn(0f, 1f)

/**
 * Maps SpeechRecognizer.ERROR_* codes (values as in the platform; kept literal so this stays plain JVM code).
 * Errors after the user's own cancel never get here: [VoiceRecognizer] drops them.
 */
fun voiceErrorOf(code: Int): VoiceError = when (code) {
    6, 7 -> VoiceError.NO_MATCH // SPEECH_TIMEOUT, NO_MATCH
    1, 2, 4, 11 -> VoiceError.NETWORK // NETWORK_TIMEOUT, NETWORK, SERVER, SERVER_DISCONNECTED
    8, 10 -> VoiceError.BUSY // RECOGNIZER_BUSY, TOO_MANY_REQUESTS
    9 -> VoiceError.PERMISSION // INSUFFICIENT_PERMISSIONS
    12, 13 -> VoiceError.LANGUAGE // LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE
    3 -> VoiceError.AUDIO // AUDIO
    else -> VoiceError.UNAVAILABLE // CLIENT, CANNOT_CHECK_SUPPORT, CANNOT_LISTEN_TO_DOWNLOAD_EVENTS, unknown
}

/**
 * The recognition language: Settings "음성으로 말하기" (the same choice the old system dialog used), or the
 * device language when it is set to follow the device.
 */
fun speechLanguageTag(setting: VoiceLanguage, device: Locale): String = setting.tag ?: device.toLanguageTag()

/** Which recognizer runs the in-app mic. */
sealed interface VoiceEngine {
    /** A RecognitionService named explicitly (normally Google's), run without its dialog. */
    data class Service(val packageName: String, val className: String) : VoiceEngine

    /** The platform's on-device recognizer (API 31+). */
    data object OnDevice : VoiceEngine

    /** No usable in-app recognizer: the old system speech dialog. */
    data object Dialog : VoiceEngine
}

/** Google's recognizers, best first: the Google app, then Speech Services by Google. */
val PREFERRED_RECOGNIZERS = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")

/**
 * Picks the recognizer. Phone Use's own RecognitionService is only a stub for the assistant declaration, and
 * once Phone Use is the default assistant the system's "default" recognizer *is* that stub. So the default is
 * never used: a real service is named explicitly (Google's first), then the on-device recognizer, and only
 * without either the old dialog.
 *
 * @param services installed RecognitionService components as (package, class).
 */
fun pickVoiceEngine(services: List<Pair<String, String>>, ownPackage: String, onDeviceAvailable: Boolean): VoiceEngine {
    val others = services.filter { it.first != ownPackage }
    val best = PREFERRED_RECOGNIZERS.firstNotNullOfOrNull { pkg -> others.firstOrNull { it.first == pkg } }
        ?: others.firstOrNull()
    return when {
        best != null -> VoiceEngine.Service(best.first, best.second)
        onDeviceAvailable -> VoiceEngine.OnDevice
        else -> VoiceEngine.Dialog
    }
}
