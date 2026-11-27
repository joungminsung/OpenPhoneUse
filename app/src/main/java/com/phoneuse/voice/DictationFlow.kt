package com.phoneuse.voice

/**
 * One tap of the in-app mic, kept open until the user has been quiet for the chosen wait. A recognition service
 * ends an utterance after a pause of its own choosing, often a second or two. So every ending inside the wait
 * starts another utterance, and the words of all of them are joined into one text by [DictationWindow], each
 * said once.
 *
 * With [segmented] the request asked the service to keep one utterance open through pauses and to return each
 * phrase as it is finished ([segment]). Then the pause is measured here alone, and nothing has to be started
 * again between phrases. A service that ignores the request ends its utterances as before, and is handled as
 * before.
 *
 * The mic waits [waitMs] for the first words and [pauseMs] after the last ones. With a [pauseMs] of zero the
 * service's own end of speech ends the session, which is what a screen that sends without a tap wants. A sound
 * with no words in it, such as a cough, does not end the wait for the first words. However much is said, the
 * session ends [maxMs] after the tap.
 *
 * Done ([stop]) and Cancel ([close]) never wait out the rest of the pause.
 *
 * Plain JVM code. [Host] stands for the recognizer, the timer and the mic UI. Main thread only.
 */
internal class DictationFlow(
    waitMs: Long,
    private val host: Host,
    private val pauseMs: Long = waitMs,
    private val maxMs: Long = MAX_SESSION_MS,
    private val segmented: Boolean = false,
) {
    interface Host {
        fun now(): Long

        /** Starts utterance [turn] on the service, whose callbacks carry [turn] back. False when it could not start. */
        fun start(turn: Int): Boolean

        /** Asks the service to finish the utterance in flight. Its final result or error follows. */
        fun stop()

        /** Drops the utterance in flight. */
        fun cancel()

        /** Runs [action] after [delayMs], in place of any action still waiting. */
        fun after(delayMs: Long, action: () -> Unit)
        fun clearTimer()

        // What the mic UI is told: one session, however many utterances it took.
        fun ready()
        fun beginningOfSpeech()

        /** Everything heard in this session so far. */
        fun partial(text: String)

        /** The wait ran out. The final text follows. */
        fun endOfSpeech()

        /** The session's text, empty when nothing was heard. Sent once, and nothing after it. */
        fun results(text: String)

        /** The session failed with nothing to keep. Sent once, and nothing after it. */
        fun error(code: Int)
    }

    private val window = DictationWindow(waitMs, pauseMs, host.now())

    /** When the session ends whatever is being said. Set by the first [listen]. */
    private var limitAt = Long.MAX_VALUE

    /** The utterance whose callbacks count. Callbacks of older ones are ignored. */
    private var turn = 0

    /** The service still owes [turn] its final result or error. */
    private var open = false
    private var startedAt = 0L

    /** The service has heard speech begin in [turn]: words, or a sound it may yet find no words in. */
    private var speaking = false

    /** The service has reported the end of the speech it heard in [turn]. A plain utterance is over then. */
    private var ended = false

    /** Speech began again in [turn] before the words of the speech before it came back as a finished phrase. */
    private var resumed = false
    private var stopping = false
    private var closed = false

    /** Opens the mic, or opens it again for the next utterance. */
    fun listen() {
        if (closed) return
        val id = ++turn
        open = true
        speaking = false
        ended = false
        resumed = false
        startedAt = host.now()
        if (id == 1) {
            limitAt = startedAt + maxMs
            host.after(maxMs, ::expire)
        }
        if (!host.start(id)) error(id, ERROR_CLIENT)
    }

    /**
     * Done: end with what was heard. While nothing is being said, which includes the whole wait, that is at
     * once. With words in flight it is as soon as the service returns them.
     */
    fun stop() {
        if (stopping || closed) return
        stopping = true
        host.clearTimer()
        if (open && speaking) {
            // A plain utterance returns its words by itself after its end of speech. One that was asked to listen
            // on is still listening then, and has to be told to finish.
            if (!ended || segmented) host.stop()
        } else {
            deliver()
        }
    }

    /** Cancel, or the mic UI went away: drop everything at once. Nothing is reported after this. */
    fun close() {
        if (closed) return
        closed = true
        stopping = true
        host.clearTimer()
        if (open) {
            open = false
            host.cancel()
        }
    }

    /** True while callbacks of utterance [id] still count. */
    fun hears(id: Int): Boolean = !closed && open && id == turn

    fun ready(id: Int) {
        if (!hears(id) || stopping) return
        val now = host.now()
        window.ready(now)
        if (!speaking && !ended) host.after(remaining(now), ::expire)
        host.ready()
    }

    fun beginningOfSpeech(id: Int) {
        if (!hears(id) || stopping) return
        if (ended) {
            // A plain utterance is over after its end of speech. One that was asked to listen on goes on.
            if (!segmented) return
            ended = false
            resumed = true
            window.resumed()
        }
        speaking = true
        waitForTheLimitOnly()
        host.beginningOfSpeech()
    }

    fun partial(id: Int, words: String) {
        if (!hears(id) || words.isBlank()) return
        if (!ended && !stopping) {
            speaking = true
            waitForTheLimitOnly()
        }
        host.partial(window.partial(words, host.now()))
    }

    fun endOfSpeech(id: Int) {
        if (!hears(id) || ended) return
        ended = true
        resumed = false
        val now = host.now()
        window.endOfSpeech(now)
        if (stopping) return
        val pause = minOf(pauseMs, untilLimit(now))
        host.after(
            when {
                // Words are in flight, so only the pause after them is left to wait.
                window.hasUnconfirmedWords -> pause
                // A sound, and no words yet. The final decides: with words the service never sent as partial text
                // the pause applies, and with none it was a cough, which must not end the wait for the first words.
                speaking -> maxOf(pause, remaining(now))
                else -> remaining(now)
            },
            ::expire,
        )
    }

    /** A finished phrase of utterance [id], which stays open: the service was asked to listen on. */
    fun segment(id: Int, words: String) {
        if (!hears(id) || words.isBlank()) return
        val now = host.now()
        window.phrase(words, now)
        // After speech began again these are the words before it, and the speech in flight goes on.
        if (resumed) {
            resumed = false
        } else {
            speaking = false
            ended = false
        }
        host.partial(window.text)
        when {
            speaking -> Unit
            // Done, or the session's limit, was waiting for these words.
            stopping -> deliver()
            else -> host.after(remaining(now), ::expire)
        }
    }

    /** The service closed an utterance it had kept open: a final with nothing in it. */
    fun endOfSegments(id: Int) {
        if (hears(id)) complete(null)
    }

    fun results(id: Int, words: String?) {
        if (hears(id)) complete(words?.takeIf { it.isNotBlank() })
    }

    fun error(id: Int, code: Int) {
        if (!hears(id)) return
        val kind = voiceErrorOf(code)
        when {
            kind == VoiceError.NO_MATCH -> complete(null)
            // The service was not ready for another utterance yet. Ask again while the wait lasts.
            kind == VoiceError.BUSY && turn > 1 -> complete(null)
            // Words the service never confirmed must not end up in the text: a screen that sends without a tap
            // would send a phrase cut off by the failure. With nothing confirmed either, it is a plain failure.
            window.hasUnconfirmedWords && !window.hasConfirmedWords -> fail(code)
            // A later utterance failing must not lose the earlier ones, or turn a quiet ending into an error.
            turn > 1 || window.hasConfirmedWords -> {
                open = false
                window.dropUnconfirmed()
                deliver()
            }
            else -> fail(code)
        }
    }

    private fun fail(code: Int) {
        open = false
        close()
        host.error(code)
    }

    /** What is left of the wait, and never more than what is left of the session. */
    private fun remaining(now: Long): Long = minOf(window.remaining(now), untilLimit(now))

    private fun untilLimit(now: Long): Long = (limitAt - now).coerceAtLeast(0L)

    /** Words are being heard, so the wait does not run. The session's limit still does. */
    private fun waitForTheLimitOnly() = host.after(untilLimit(host.now()), ::expire)

    /** The service returned utterance [turn], with [words] or with nothing. */
    private fun complete(words: String?) {
        val now = host.now()
        open = false
        host.clearTimer()
        window.complete(words, now)
        val remaining = remaining(now)
        // Opening the mic again for the last moment of the wait would only start it and stop it.
        if (stopping || remaining <= RESTART_GAP_MS) {
            deliver()
            return
        }
        if (window.text.isNotEmpty()) host.partial(window.text)
        // A service that returns at once every time must not be restarted in a tight loop.
        val gap = (RESTART_GAP_MS - (now - startedAt)).coerceAtLeast(0L)
        host.after(minOf(remaining, gap), ::continueListening)
    }

    private fun continueListening() {
        val remaining = remaining(host.now())
        if (remaining == 0L) {
            expire()
            return
        }
        host.after(remaining, ::expire)
        listen()
    }

    /** The user has been quiet for the whole wait, or the session has reached its limit. */
    private fun expire() {
        if (stopping || closed) return
        // The pause ran out after an end of speech. A service that was asked to listen on has had the whole pause
        // to return those words, so nothing more is waited for. At the session's limit the words in flight are.
        if (segmented && ended && host.now() < limitAt) speaking = false
        host.endOfSpeech()
        stop()
    }

    private fun deliver() {
        val text = window.text
        close()
        host.results(text)
    }

    companion object {
        /** The least time between two starts of the service. */
        const val RESTART_GAP_MS = 250L

        /** The longest one tap keeps the mic open, however much is said. The same as VoiceBridge.MAX_WAIT_MS. */
        const val MAX_SESSION_MS = 60_000L

        /** SpeechRecognizer.ERROR_CLIENT, kept literal so this stays plain JVM code. */
        private const val ERROR_CLIENT = 5
    }
}
