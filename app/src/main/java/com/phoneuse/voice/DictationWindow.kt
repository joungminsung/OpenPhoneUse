package com.phoneuse.voice

/**
 * One mic tap can span several short utterances returned by an Android recognition service. A service that is
 * asked to listen on through pauses can also return several phrases inside one utterance. The mic waits [waitMs]
 * for the first words, and [pauseMs] after the last ones.
 *
 * The text is the utterances that have ended, then the finished phrases of the utterance in flight, then the
 * phrase in flight.
 */
internal class DictationWindow(private val waitMs: Long, private val pauseMs: Long = waitMs, startedAt: Long) {
    private var deadline = startedAt + waitMs
    private var firstReady = true
    private var confirmed = ""

    /** The finished phrases of the utterance in flight. */
    private var phrases = ""

    /** The phrase in flight: the service's latest partial text. */
    private var partial = ""
    private var lastWordsAt: Long? = null
    private var endOfSpeechAt: Long? = null

    /** More speech began after the end of the speech whose text is in [partial], and that text has not come back. */
    private var stale = false

    /** The service returns its finished phrases itself. */
    private var returnsPhrases = false

    /** The last phrase that was kept by guesswork, because the service started its partial text over. */
    private var kept = ""

    fun ready(now: Long) {
        if (firstReady) deadline = now + waitMs
        firstReady = false
    }

    fun remaining(now: Long): Long = (deadline - now).coerceAtLeast(0L)

    fun partial(words: String, now: Long): String {
        val text = words.trim()
        if (text.isEmpty()) return this.text
        if (stale) {
            stale = false
            // A service that listens on without returning its phrases starts its partial text over after a pause,
            // with a space in front. The phrase before it is kept. One that repeats the earlier words goes on
            // replacing them.
            if (words.first().isWhitespace() || !text.startsWith(partial, ignoreCase = true)) {
                kept = partial
                phrases = joinPhrases(phrases, partial)
                partial = ""
                lastWordsAt = null
            }
        }
        if (text != partial) {
            partial = text
            lastWordsAt = endOfSpeechAt ?: now
        }
        return this.text
    }

    fun endOfSpeech(now: Long) {
        endOfSpeechAt = now
    }

    /** More speech began after an end of speech, in an utterance the service keeps open. */
    fun resumed() {
        endOfSpeechAt = null
        stale = partial.isNotEmpty() && !returnsPhrases
    }

    /** A finished phrase of an utterance that stays open. */
    fun phrase(words: String, now: Long) {
        val text = words.trim()
        if (text.isEmpty()) return
        returnsPhrases = true
        if (kept.isNotEmpty()) {
            // The phrase that was kept by guesswork comes back from the service after all. Its own text stands,
            // and the partial text in flight belongs to the speech after it.
            if (phrases.endsWith(kept)) phrases = phrases.removeSuffix(kept).trimEnd()
            kept = ""
            phrases = joinPhrases(phrases, text)
            deadline = (endOfSpeechAt ?: now) + pauseMs
            return
        }
        deadline = lastSpeech(text, now) + pauseMs
        phrases = joinPhrases(phrases, text)
        clearInFlight()
    }

    // A final ends the utterance. An empty retry must never buy another full wait.
    fun complete(words: String?, now: Long) {
        val final = words?.trim().orEmpty()
        val last = final.ifEmpty { partial }
        if (last.isNotEmpty()) deadline = lastSpeech(last, now) + pauseMs
        confirmed = joinPhrases(
            confirmed,
            when {
                // An empty ending keeps what was heard.
                final.isEmpty() -> joinPhrases(phrases, partial)
                // A final is the whole utterance, so it stands for the phrases it repeats.
                final.startsWith(phrases, ignoreCase = true) -> final
                // One that holds only the last words is added after them: repeated words can be seen, lost ones not.
                else -> joinPhrases(phrases, final)
            },
        )
        phrases = ""
        kept = ""
        clearInFlight()
        firstReady = false
    }

    val text: String get() = joinPhrases(joinPhrases(confirmed, phrases), partial)
    val hasConfirmedWords: Boolean get() = confirmed.isNotEmpty() || phrases.isNotEmpty()

    /** Words of the phrase in flight that the service has not confirmed with a final or a finished phrase. */
    val hasUnconfirmedWords: Boolean get() = partial.isNotEmpty()

    /** The utterance in flight failed: the words of its phrase in flight are not part of the text. */
    fun dropUnconfirmed() = clearInFlight()

    /**
     * When [text] was last heard. A matching final or phrase confirms when we last heard the words. A revised or
     * final-only one can contain speech long after the last partial, so the end callback is used.
     */
    private fun lastSpeech(text: String, now: Long): Long = (if (text == partial) lastWordsAt else null) ?: endOfSpeechAt ?: now

    private fun clearInFlight() {
        partial = ""
        lastWordsAt = null
        endOfSpeechAt = null
        stale = false
    }
}

/**
 * Two phrases of one dictation as one text. A space goes between them, except where either side of the join is
 * Chinese or Japanese writing, which does not put spaces between words.
 */
internal fun joinPhrases(first: String, second: String): String = when {
    second.isEmpty() -> first
    first.isEmpty() -> second
    writtenWithoutSpaces(first.codePointBefore(first.length)) || writtenWithoutSpaces(second.codePointAt(0)) -> first + second
    else -> "$first $second"
}

private fun writtenWithoutSpaces(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
    // Their punctuation: the ideographic full stop and comma, and the fullwidth forms.
    else -> codePoint in 0x3000..0x303F || codePoint in 0xFF00..0xFFEF
}
