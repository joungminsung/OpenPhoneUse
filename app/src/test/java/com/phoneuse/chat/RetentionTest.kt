package com.phoneuse.chat

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_000 * day

    @Test fun prunesOlderConversations() = runTest {
        var clock = now - 100 * day
        val repo = InMemoryChatRepository { clock }
        val old = repo.createConversation("오래된 대화")
        repo.addMessage(old, MessageRole.USER, "안녕")
        clock = now - 91 * day
        val stale = repo.createConversation("91일 전")
        clock = now - 89 * day
        val recent = repo.createConversation("89일 전")
        // A reply today keeps an old conversation: age counts from its last message.
        clock = now - 120 * day
        val revived = repo.createConversation("다시 이어간 대화")
        clock = now - day
        repo.addMessage(revived, MessageRole.USER, "이어서")

        val removed = Retention.prune(repo, days = 90, now = now)

        assertEquals(setOf(old, stale), removed.toSet())
        assertEquals(setOf(recent, revived), repo.conversations().first().map { it.id }.toSet())
        assertTrue(repo.messages(old).first().isEmpty())
    }

    @Test fun foreverKeepsEverything() = runTest {
        val repo = InMemoryChatRepository { 0L }
        repo.createConversation("아주 오래된 대화")
        assertTrue(Retention.prune(repo, days = 0, now = now).isEmpty())
        assertEquals(1, repo.conversations().first().size)
    }

    @Test fun keepsTheConversationInUse() = runTest {
        val repo = InMemoryChatRepository { 0L }
        val running = repo.createConversation("실행 중")
        val idle = repo.createConversation("그냥 오래된 대화")
        assertEquals(listOf(idle), Retention.prune(repo, days = 30, now = now, keep = setOf(running)))
    }

    @Test fun cutoffIsDaysBeforeNowAndNoneForForever() {
        assertEquals(now - 30 * day, Retention.cutoff(30, now))
        assertEquals(null, Retention.cutoff(0, now))
    }
}
