package com.phoneuse.voice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Runs one [DictationFlow] on Android's [recognizer]: the mic stays open across the service's own endings for
 * [waitSeconds] before the first words and [pauseSeconds] after the last ones. [segmented] says that [request]
 * asks the service to listen on through pauses. [listener] hears it as a single session. The recognizer is not
 * destroyed here, so the next session can use it again.
 */
internal class DictationSession(
    private val recognizer: SpeechRecognizer,
    private val request: Intent,
    waitSeconds: Int,
    pauseSeconds: Int,
    segmented: Boolean,
    private val listener: RecognitionListener,
) {
    private val timer = Handler(Looper.getMainLooper())
    private val flow = DictationFlow(waitSeconds * 1_000L, Host(), pauseSeconds * 1_000L, segmented = segmented)

    fun listen() = flow.listen()

    /** Done: keep what was said. */
    fun stop() = flow.stop()

    /** Cancel: drop the session. [listener] hears nothing more. */
    fun close() = flow.close()

    private inner class Host : DictationFlow.Host {
        override fun now(): Long = SystemClock.uptimeMillis()

        override fun start(turn: Int): Boolean {
            recognizer.setRecognitionListener(Utterance(turn))
            return try {
                recognizer.startListening(request)
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "speech recognition could not start", e)
                false
            }
        }

        override fun stop() {
            runCatching { recognizer.stopListening() }
        }

        override fun cancel() {
            runCatching { recognizer.cancel() }
        }

        override fun after(delayMs: Long, action: () -> Unit) {
            timer.removeCallbacksAndMessages(null)
            timer.postDelayed({ action() }, delayMs)
        }

        override fun clearTimer() = timer.removeCallbacksAndMessages(null)

        override fun ready() = listener.onReadyForSpeech(null)
        override fun beginningOfSpeech() = listener.onBeginningOfSpeech()
        override fun partial(text: String) = listener.onPartialResults(recognition(text))
        override fun endOfSpeech() = listener.onEndOfSpeech()
        override fun results(text: String) = listener.onResults(recognition(text))
        override fun error(code: Int) = listener.onError(code)
    }

    /** One utterance's callbacks, tagged so that a late one from an earlier utterance is ignored. */
    private inner class Utterance(private val turn: Int) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = flow.ready(turn)
        override fun onBeginningOfSpeech() = flow.beginningOfSpeech(turn)
        override fun onRmsChanged(rmsdB: Float) {
            if (flow.hears(turn)) listener.onRmsChanged(rmsdB)
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = flow.endOfSpeech(turn)
        override fun onError(error: Int) = flow.error(turn, error)
        override fun onResults(results: Bundle?) = flow.results(turn, best(results))
        override fun onPartialResults(partialResults: Bundle?) {
            best(partialResults)?.let { flow.partial(turn, it) }
        }
        override fun onSegmentResults(segmentResults: Bundle) {
            best(segmentResults)?.let { flow.segment(turn, it) }
        }
        override fun onEndOfSegmentedSession() = flow.endOfSegments(turn)
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }

    private fun recognition(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private companion object {
        const val TAG = "DictationSession"
    }
}
