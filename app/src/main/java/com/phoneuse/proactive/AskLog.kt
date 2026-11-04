package com.phoneuse.proactive

import com.phoneuse.today.TodaySummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A message the user typed in the composer to start a new conversation ([conversationId]), and when. */
@Serializable
data class TypedAsk(val text: String, val at: Long, val conversationId: Long)

/**
 * The repeated-ask nudge's only source: first messages the user typed themselves (Source.APP), recorded by
 * the chat screen when the run's conversation appears. Card buttons, nudges, side-button suggestions and routines
 * never get here, so the "루틴으로 만들기" prompt can only quote the user's own words. One JSON file, last
 * [KEEP_MS], cleared with everything else.
 */
class AskLog(private val file: File) {
    private val lock = Any()
    @Volatile private var entries: List<TypedAsk>? = null

    fun all(): List<TypedAsk> = synchronized(lock) { loaded() }

    fun add(text: String, at: Long, conversationId: Long) = synchronized(lock) {
        val t = text.trim().take(MAX_CHARS)
        if (t.isEmpty()) return@synchronized
        val next = (loaded().filter { at - it.at < KEEP_MS } + TypedAsk(t, at, conversationId)).takeLast(MAX_ENTRIES)
        entries = next
        save(next)
    }

    /** Drops what deleted (or expired) conversations left here. */
    fun forget(conversationIds: Set<Long>) = synchronized(lock) {
        if (conversationIds.isEmpty()) return@synchronized
        val next = loaded().filter { it.conversationId !in conversationIds }
        if (next.size == loaded().size) return@synchronized
        entries = next
        save(next)
    }

    fun clear() = synchronized(lock) {
        entries = emptyList()
        file.delete()
    }

    private fun loaded(): List<TypedAsk> = entries ?: load().also { entries = it }

    private fun load(): List<TypedAsk> = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<TypedAsk>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(serializer, list))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            // A full disk loses the asks; the suggestion just waits for new ones.
        }
    }

    companion object {
        const val FILE_NAME = "asks.json"
        const val KEEP_MS = 8L * 24 * 60 * 60_000
        private const val MAX_CHARS = 200
        private const val MAX_ENTRIES = 300
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(TypedAsk.serializer())

        /** Our own prompts the user can send unchanged (a tile puts one in the composer); never a "repeated ask". */
        private val OURS = setOf(
            TodaySummary.PARCEL_PROMPT_KO, TodaySummary.PARCEL_PROMPT_EN, ProactiveEngine.PARCEL_PROMPT_KO, ProactiveEngine.PARCEL_PROMPT_EN,
            BriefingText.TODAY_PROMPT_KO, BriefingText.TODAY_PROMPT_EN, BriefingText.REPLY_PROMPT_KO, BriefingText.REPLY_PROMPT_EN,
        ).mapTo(HashSet()) { AskKinds.normalize(it) }

        /**
         * The asks the routine suggestion may count: the last week's, outside conversations that later read
         * third-party text ([tainted], ChatRepository.thirdPartySeen), and not one of our own prompts.
         */
        fun usable(asks: List<TypedAsk>, tainted: Set<Long>, now: Long): List<AskRecord> = asks.filter {
            now - it.at in 0 until 7L * 24 * 60 * 60_000 && it.conversationId !in tainted && !ours(it.text)
        }.map { AskRecord(it.text, it.at) }

        fun ours(text: String): Boolean = AskKinds.normalize(text).let { it in OURS || it.startsWith("루틴만들어줘") || it.startsWith("makearoutine") }
    }
}
