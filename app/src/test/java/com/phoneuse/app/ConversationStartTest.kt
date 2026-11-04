package com.phoneuse.app

import com.phoneuse.chat.InMemoryChatRepository
import com.phoneuse.chat.MessageRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStartTest {
    @Test fun storesInAnExistingConversation() = runBlocking {
        val repo = InMemoryChatRepository()
        val id = repo.createConversation("a")
        val stored = ConversationStart.storeUserMessage(repo, id, "new", "hi", emptyList(), thirdParty = false)
        assertEquals(id, stored.conversationId)
        assertFalse(stored.movedToNew)
        assertEquals(listOf("hi"), repo.messages(id).first().map { it.text })
    }

    /** The conversation was deleted (retention, another screen): the message goes to a new one, not nowhere. */
    @Test fun aDeletedConversationMovesTheRunToANewOne() = runBlocking {
        val repo = InMemoryChatRepository()
        val gone = repo.createConversation("old")
        repo.delete(gone)
        val stored = ConversationStart.storeUserMessage(repo, gone, "보내줘", "보내줘", emptyList(), thirdParty = true)
        assertTrue(stored.movedToNew)
        assertNotEquals(gone, stored.conversationId)
        assertEquals(listOf("보내줘"), repo.messages(stored.conversationId).first().map { it.text })
        assertEquals("보내줘", repo.conversations().first().single().title)
        assertTrue("the third-party flag goes along", repo.thirdPartySeen(stored.conversationId))
    }

    @Test fun anUnansweredQuestionFromAnEarlierProcessIsMarkedInterrupted() = runBlocking {
        var clock = 1_000L
        val repo = InMemoryChatRepository { clock }
        val dangling = repo.createConversation("a")
        repo.addMessage(dangling, MessageRole.USER, "문자 보내줘")
        val answered = repo.createConversation("b")
        repo.addMessage(answered, MessageRole.USER, "q")
        repo.addMessage(answered, MessageRole.ASSISTANT, "a")
        val startedAt = 2_000L
        clock = 2_500L
        // A run of this process has just stored its question: never marked.
        val running = repo.createConversation("c")
        repo.addMessage(running, MessageRole.USER, "지금 하는 중")

        val marked = ConversationStart.markInterrupted(repo, startedAt, "끊겼어요")

        assertEquals(listOf(dangling), marked)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), repo.messages(dangling).first().map { it.role })
        assertEquals("끊겼어요", repo.messages(dangling).first().last().text)
        assertTrue(repo.thirdPartySeen(dangling))
        assertEquals(1, repo.messages(running).first().size)
        assertEquals(2, repo.messages(answered).first().size)
    }
}
