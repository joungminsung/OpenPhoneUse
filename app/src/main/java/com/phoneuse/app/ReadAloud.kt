package com.phoneuse.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * "소리 내어 읽기": replies read with the system text-to-speech, one at a time (a new one replaces the last).
 * The engine starts on first use; text asked for before it is ready is spoken once it is.
 */
object ReadAloud {
    private const val TAG = "ReadAloud"
    private const val UTTERANCE = "phoneuse-reply"

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, Locale>? = null

    /** Each reading's chunks share a number; only the last chunk's end clears [speaking]. */
    private var reading = 0

    @Volatile
    private var lastChunk: String? = null

    private val _speaking = MutableStateFlow(false)

    /** True while a reply is being read; the chat's menu offers "읽기 멈추기" then. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Reads [text] (Markdown marks dropped) in [language] (a BCP-47 tag; null = the device language). */
    @Synchronized
    fun speak(context: Context, text: String, language: String? = null) {
        val plain = plain(text)
        if (plain.isBlank()) return
        val locale = language?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        val engine = tts
        if (engine == null) {
            pending = plain to locale
            tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
            return
        }
        if (!ready) {
            pending = plain to locale
            return
        }
        say(engine, plain, locale)
    }

    @Synchronized
    fun stop() {
        pending = null
        lastChunk = null
        tts?.stop()
        _speaking.value = false
    }

    @Synchronized
    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "text-to-speech unavailable ($status)")
            engine.shutdown()
            tts = null
            pending = null
            return
        }
        ready = true
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastChunk) _speaking.value = false
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == lastChunk) _speaking.value = false
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == lastChunk) _speaking.value = false
            }
        })
        pending?.let { (text, locale) -> say(engine, text, locale) }
        pending = null
    }

    private fun say(engine: TextToSpeech, text: String, locale: Locale) {
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        // Long answers go in pieces the engine accepts; the first flushes whatever was being read.
        val max = TextToSpeech.getMaxSpeechInputLength() - 1
        val parts = text.chunked(max)
        val id = ++reading
        lastChunk = "$UTTERANCE-$id-${parts.lastIndex}"
        parts.forEachIndexed { i, part ->
            engine.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$UTTERANCE-$id-$i")
        }
    }

    /** The reply as it should sound: no Markdown marks, links read by their text. */
    fun plain(markdown: String): String =
        markdown
            .replace(Regex("""```[\s\S]*?```"""), " ")
            .replace(Regex("""!?\[([^\]]*)]\((?:[^()]|\([^()]*\))*\)"""), "$1")
            .replace(Regex("""(?m)^\s{0,3}(#{1,6}\s+|>\s?|[-*+]\s+|\d+\.\s+)"""), "")
            .replace(Regex("""[*_`~|]"""), "")
            .replace(Regex("""[ \t]+"""), " ")
            .trim()
}
