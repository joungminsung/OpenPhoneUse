package com.phoneuse.voice

import android.annotation.SuppressLint
import android.content.Intent
import android.speech.RecognizerIntent

/**
 * Asks the recognition service for a segmented session (Android 13): one utterance kept open through pauses, with
 * each phrase returned through RecognitionListener.onSegmentResults as it is finished. The type of the session is
 * the name of another extra, and that extra has to be set as well. Here it is the minimum length, set to the
 * session limit. It is a minimum, not a limit: it asks the service not to end the utterance sooner. The app ends
 * the session itself. [DictationFlow] does that when the pause runs out, or at [DictationFlow.MAX_SESSION_MS].
 *
 * A service may ignore the request. It then ends its utterances as usual, and they are joined as before.
 */
// The key is a String constant, inlined at compile time. The request is only made on Android 13 and later.
@SuppressLint("InlinedApi")
internal fun Intent.withSegmentedSession(): Intent = apply {
    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, DictationFlow.MAX_SESSION_MS.toInt())
}
