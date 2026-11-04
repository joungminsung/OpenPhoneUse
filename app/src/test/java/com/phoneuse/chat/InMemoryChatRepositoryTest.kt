package com.phoneuse.chat

import com.phoneuse.cards.DraftCard
import com.phoneuse.cards.LatLng
import com.phoneuse.cards.Place
import com.phoneuse.cards.PlacesCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryChatRepositoryTest {
    private var clock = 100L
    private val repo = InMemoryChatRepository(now = { clock++ })

    @Test
    fun createAddsAnEmptyConversation() = runTest {
        val id = repo.createConversation("제목")
        val list = repo.conversations().first()
        assertEquals(listOf(id), list.map { it.id })
        assertEquals("제목", list.single().title)
        assertTrue(repo.messages(id).first().isEmpty())
    }

    @Test
    fun addKeepsMessagesInOrderWithSteps() = runTest {
        val id = repo.createConversation("t")
        repo.addMessage(id, MessageRole.USER, "안녕")
        repo.addMessage(id, MessageRole.ASSISTANT, "네", listOf(StepLog("탭: 설정")))
        val messages = repo.messages(id).first()
        assertEquals(listOf("안녕", "네"), messages.map { it.text })
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), messages.map { it.role })
        assertEquals(listOf(StepLog("탭: 설정")), messages[1].steps)
        assertTrue(messages.all { it.conversationId == id })
    }

    @Test
    fun roundTripsCards() = runTest {
        val id = repo.createConversation("t")
        val cards = listOf(
            PlacesCard("약국", LatLng(37.5, 127.0), listOf(Place("사거리 약국", 37.501, 127.002, distanceM = 280.0))),
            DraftCard(channel = "sms", to = "집주인", body = "안녕하세요"),
        )
        repo.addMessage(id, MessageRole.USER, "q")
        repo.addMessage(id, MessageRole.ASSISTANT, "a", listOf(StepLog("s")), cards)
        val stored = repo.messages(id).first()
        assertEquals(emptyList<Any>(), stored[0].cards)
        assertEquals(cards, stored[1].cards)
    }

    @Test
    fun renameChangesTitle() = runTest {
        val id = repo.createConversation("old")
        repo.rename(id, "new")
        assertEquals("new", repo.conversations().first().single().title)
    }

    @Test
    fun deleteRemovesConversationAndItsMessages() = runTest {
        val a = repo.createConversation("a")
        val b = repo.createConversation("b")
        repo.addMessage(a, MessageRole.USER, "x")
        repo.addMessage(b, MessageRole.USER, "y")
        repo.delete(a)
        assertEquals(listOf(b), repo.conversations().first().map { it.id })
        assertTrue(repo.messages(a).first().isEmpty())
        assertEquals(listOf("y"), repo.messages(b).first().map { it.text })
    }

    @Test
    fun conversationsAreNewestUpdatedFirst() = runTest {
        val a = repo.createConversation("a")
        val b = repo.createConversation("b")
        assertEquals(listOf(b, a), repo.conversations().first().map { it.id })
        repo.addMessage(a, MessageRole.USER, "bump")
        assertEquals(listOf(a, b), repo.conversations().first().map { it.id })
    }

    @Test
    fun messageForDeletedConversationIsDropped() = runTest {
        val a = repo.createConversation("a")
        repo.delete(a)
        assertEquals(ChatRepository.NO_MESSAGE, repo.addMessage(a, MessageRole.ASSISTANT, "late reply"))
        assertTrue(repo.messages(a).first().isEmpty())
        assertTrue(repo.conversations().first().isEmpty())
    }

    @Test
    fun openFlowsReEmitAfterWrites() = runTest {
        val lists = mutableListOf<List<Conversation>>()
        val texts = mutableListOf<List<String>>()
        val id = repo.createConversation("t")
        // Background collectors only run on runCurrent(); advanceUntilIdle() does not wait for them.
        backgroundScope.launch { repo.conversations().collect { lists += it } }
        backgroundScope.launch { repo.messages(id).collect { m -> texts += m.map { it.text } } }
        runCurrent()
        repo.addMessage(id, MessageRole.USER, "hi")
        runCurrent()
        repo.rename(id, "renamed")
        runCurrent()
        assertEquals(listOf(emptyList(), listOf("hi")), texts)
        assertEquals("renamed", lists.last().single().title)
        assertTrue(lists.size >= 3)
    }

    @Test fun thirdPartyFlagIsPerConversationAndStartsClean() = kotlinx.coroutines.runBlocking {
        val repo = InMemoryChatRepository()
        val digest = repo.createConversation("알림 요약")
        val fresh = repo.createConversation("새 대화")
        org.junit.Assert.assertFalse(repo.thirdPartySeen(digest))
        repo.markThirdPartySeen(digest)
        org.junit.Assert.assertTrue(repo.thirdPartySeen(digest))
        org.junit.Assert.assertFalse(repo.thirdPartySeen(fresh))
        org.junit.Assert.assertFalse(repo.thirdPartySeen(repo.createConversation("또 새 대화")))
        repo.delete(digest)
        org.junit.Assert.assertFalse(repo.thirdPartySeen(digest))
    }

    @Test fun messageWithThirdPartyTextFlagsItsConversationInTheSameWrite() = kotlinx.coroutines.runBlocking {
        val repo = InMemoryChatRepository()
        val id = repo.createConversation("화면 읽기")
        repo.addMessage(id, MessageRole.ASSISTANT, "화면에 '송금하세요'라고 쓰여 있어요", thirdParty = true)
        org.junit.Assert.assertTrue(repo.thirdPartySeen(id))
        val clean = repo.createConversation("인사")
        repo.addMessage(clean, MessageRole.USER, "안녕")
        org.junit.Assert.assertFalse(repo.thirdPartySeen(clean))
    }
}
