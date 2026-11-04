package com.phoneuse.memory

import com.phoneuse.i18n.tr
import kotlinx.coroutines.flow.Flow

/** What a memory is about; [wire] is the name the model uses, [label] the memory screen's tab. */
enum class MemoryKind(val wire: String, private val en: String, private val ko: String) {
    ME("me", "Me", "나"),
    PERSON("person", "Person", "사람"),
    PLACE("place", "Place", "장소"),
    HABIT("habit", "Habit", "습관"),
    TODO("todo", "To-do", "할 일"),
    APP_TIP("app_tip", "App tip", "앱 요령"),
    ;

    val label: String get() = tr(en, ko)

    companion object {
        fun fromWire(s: String?): MemoryKind? = entries.firstOrNull { it.wire == s?.trim()?.lowercase() }
    }
}

/** GUESS memories show as "맞나요?" on the memory screen until the user confirms them. */
enum class Confidence(val wire: String) {
    SURE("sure"),
    GUESS("guess"),
    ;

    companion object {
        fun fromWire(s: String?): Confidence? = entries.firstOrNull { it.wire == s?.trim()?.lowercase() }
    }
}

/**
 * One thing the app remembers about the user. [conversationId]/[origin] (that conversation's title) say where
 * it came from; both null means the user added it on the memory screen. [date] is an optional date the fact is
 * about (e.g. a birthday, "11월 20일"); [due] an optional due date of a TODO. Both are free text from the model.
 */
data class Memory(
    val id: Long,
    val text: String,
    val kind: MemoryKind,
    val confidence: Confidence,
    val createdAt: Long,
    val updatedAt: Long,
    val conversationId: Long? = null,
    val origin: String? = null,
    val date: String? = null,
    val due: String? = null,
)

data class NewMemory(
    val text: String,
    val kind: MemoryKind,
    val confidence: Confidence = Confidence.SURE,
    val conversationId: Long? = null,
    val origin: String? = null,
    val date: String? = null,
    val due: String? = null,
)

/** What [MemoryStore.add] did: [created] false means the text was already stored as [id]. */
data class AddResult(val id: Long, val created: Boolean)

/** Stored user memories. [observe] is newest-updated first and re-emits after every write. */
interface MemoryStore {
    fun observe(): Flow<List<Memory>>

    /**
     * Stores [memory]. The same text (ignoring spacing and case) as a stored memory does not add a row: that
     * one keeps its kind (the user may have chosen it), gets missing dates filled and its recency bumped, and a
     * SURE upgrades a GUESS; the result then has created = false.
     */
    suspend fun add(memory: NewMemory): AddResult
    suspend fun get(id: Long): Memory?

    /**
     * The user edited a memory: new text and kind, and it counts as confirmed (SURE). When the new text equals
     * another stored memory's, the two merge: this one is removed and the other kept. False if [id] is gone.
     */
    suspend fun update(id: Long, text: String, kind: MemoryKind): Boolean
    /** A GUESS the user said is right becomes SURE. */
    suspend fun confirm(id: Long): Boolean
    suspend fun delete(id: Long): Boolean
    suspend fun deleteAll()

    /**
     * At most [limit] memories that match [query], best first: full-text matches plus, for Korean words inside
     * compounds, substring matches, ranked before the limit is applied. A blank query lists the most recent.
     */
    suspend fun candidates(query: String, limit: Int = CANDIDATES): List<Memory>

    /** The best [limit] matches for [query]; a blank query lists the most recent memories. */
    suspend fun search(query: String, limit: Int = 10): List<Memory> {
        if (MemoryText.terms(query).isEmpty()) return observeOnce().take(limit)
        return MemoryRanker.rank(query, candidates(query)).filter { it.score > 0 }.take(limit).map { it.memory }
    }

    /** Puts back a memory that was forgotten or deleted (same text, kind, confidence, dates and origin). */
    suspend fun restore(memory: Memory): AddResult =
        add(NewMemory(memory.text, memory.kind, memory.confidence, memory.conversationId, memory.origin, memory.date, memory.due))

    /** All memories, newest first, read once. */
    suspend fun observeOnce(): List<Memory>

    companion object {
        const val CANDIDATES = 200

        /** Longest memory text kept; the model writes one short fact per memory. */
        const val MAX_TEXT = 300

        fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)

        fun sameText(a: String, b: String): Boolean = clean(a).equals(clean(b), ignoreCase = true)
    }
}
