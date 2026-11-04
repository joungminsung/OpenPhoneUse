package com.phoneuse.proactive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The nudges sent in the last [KEEP_MS] and what became of them, in one JSON file (written to a temp file and
 * renamed). The engine reads it for dedupe, the daily cap and the back-off; the home screen shows the pending ones.
 * Nudge text is our own wording plus parsed fields (never message text), so the file holds no third-party text
 * beyond a sender name or a place the user's calendar already holds.
 */
class NudgeLog(private val file: File) {
    private val lock = Any()
    private val _entries = MutableStateFlow<List<SentNudge>>(emptyList())
    @Volatile private var loaded = false

    /** Oldest first. Empty until [load] (ProactiveSystem loads it off the main thread at start). */
    val entries: StateFlow<List<SentNudge>> = _entries.asStateFlow()

    /** Reads the file once; every other call does this first too, so an early caller never sees an empty log. */
    fun load() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            _entries.value = read()
            loaded = true
        }
    }

    fun all(): List<SentNudge> {
        load()
        return _entries.value
    }

    fun add(nudge: Nudge, at: Long) = change { list ->
        list.filterNot { it.dedupeKey == nudge.dedupeKey } + SentNudge(nudge, at)
    }

    /** A pending nudge was opened or ignored; null when it is unknown or already settled. */
    fun resolve(id: String, outcome: NudgeOutcome, at: Long): SentNudge? {
        var settled: SentNudge? = null
        change { list ->
            list.map {
                if (it.dedupeKey == id && it.outcome == NudgeOutcome.PENDING) {
                    it.copy(outcome = outcome, resolvedAt = at).also { s -> settled = s }
                } else {
                    it
                }
            }
        }
        return settled
    }

    /** Pending nudges past their expiry count as ignored; entries older than [KEEP_MS] go. Returns the expired ones. */
    fun expire(now: Long): List<SentNudge> {
        val expired = ArrayList<SentNudge>()
        change { list ->
            list.filter { now - it.sentAt < KEEP_MS }.map {
                if (it.outcome == NudgeOutcome.PENDING && now >= it.nudge.expiresAt) {
                    it.copy(outcome = NudgeOutcome.IGNORED, resolvedAt = it.nudge.expiresAt).also { e -> expired += e }
                } else {
                    it
                }
            }
        }
        return expired
    }

    fun get(id: String): SentNudge? = all().lastOrNull { it.dedupeKey == id }

    /** Settings "삭제" of everything: the log goes too. */
    fun clear() = change { emptyList() }

    private fun change(transform: (List<SentNudge>) -> List<SentNudge>) {
        load()
        synchronized(lock) {
            val next = transform(_entries.value)
            if (next == _entries.value) return
            _entries.value = next
            save(next)
        }
    }

    private fun read(): List<SentNudge> = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<SentNudge>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(serializer, list))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            // A full disk loses the log; nudges may then repeat once, which is harmless.
        }
    }

    companion object {
        const val FILE_NAME = "nudges.json"
        const val KEEP_MS = 30L * 24 * 60 * 60_000
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(SentNudge.serializer())

        /** The home cards: pending nudges that have not expired, newest first. */
        fun active(entries: List<SentNudge>, now: Long): List<SentNudge> =
            entries.filter { it.outcome == NudgeOutcome.PENDING && now < it.nudge.expiresAt }.sortedByDescending { it.sentAt }

        /** Board NudgeSettings' "이번 주 n번 챙겼고": nudges sent in the last seven days. */
        fun sentThisWeek(entries: List<SentNudge>, now: Long): Int = entries.count { now - it.sentAt < 7L * 24 * 60 * 60_000 }
    }
}
