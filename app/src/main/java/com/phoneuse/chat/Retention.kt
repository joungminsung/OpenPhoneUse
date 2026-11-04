package com.phoneuse.chat

import kotlinx.coroutines.flow.first

/**
 * Settings "대화 기록 보관": a conversation whose last message is older than the chosen days is deleted, with its
 * messages and attachment files. Run once a day (AppGraph) and when the setting changes. 0 days = forever.
 */
object Retention {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Conversations last updated before this are removed; null keeps everything. */
    fun cutoff(days: Int, now: Long): Long? = if (days <= 0) null else now - days * DAY_MS

    /** The conversations [prune] would delete now, without deleting them (Settings asks before shortening). */
    suspend fun expired(repo: ChatRepository, days: Int, now: Long, keep: Set<Long> = emptySet()): List<Long> {
        val before = cutoff(days, now) ?: return emptyList()
        return repo.conversations().first().filter { it.updatedAt < before && it.id !in keep }.map { it.id }
    }

    /** Deletes the expired conversations except those in [keep] (the one a run is using); returns their ids. */
    suspend fun prune(repo: ChatRepository, days: Int, now: Long, keep: Set<Long> = emptySet()): List<Long> {
        val expired = expired(repo, days, now, keep)
        for (id in expired) repo.delete(id)
        return expired
    }
}
