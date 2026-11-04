package com.phoneuse.app

import com.phoneuse.risk.RiskCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatUserGateTest {
    @Test
    fun cardsCarryTheAskingRunsConversation() = runTest {
        var conversation: Long? = 5
        val gate = ChatUserGate { conversation }
        val asked = async { gate.ask("어느 쪽?", listOf("A")) }
        advanceUntilIdle()
        val card = gate.card.value!!
        assertEquals(5L, card.conversationId)
        assertTrue(card.belongsTo(5))
        assertFalse("another chat neither shows nor answers it", card.belongsTo(6))
        assertFalse("nor does the home screen", card.belongsTo(null))
        gate.answer(card.id, "A")
        assertEquals("A", asked.await())
        conversation = null
        assertTrue("unknown conversation: shown anywhere", ChatCard.HandOff("x", 1).belongsTo(9))
    }

    @Test
    fun confirmTrueOnAllow() = runTest {
        val gate = ChatUserGate()
        val allowed = async { gate.confirm(RiskCategory.MONEY, "결제 1만원") }
        advanceUntilIdle()
        val card = gate.card.value as ChatCard.Confirm
        assertEquals(RiskCategory.MONEY, card.category)
        assertEquals("결제 1만원", card.summary)
        gate.answer(card.id, "허용")
        assertTrue(allowed.await())
        assertNull(gate.card.value)

        val denied = async { gate.confirm(RiskCategory.DELETE, "삭제") }
        advanceUntilIdle()
        gate.answer(gate.card.value!!.id, "거부")
        assertFalse(denied.await())
    }

    @Test
    fun staleAnswerIgnored() = runTest {
        val gate = ChatUserGate()
        val first = async { gate.ask("q1", listOf("a", "b")) }
        advanceUntilIdle()
        val firstId = gate.card.value!!.id
        gate.answer(firstId, "a")
        assertEquals("a", first.await())

        val second = async { gate.ask("q2", listOf("c")) }
        advanceUntilIdle()
        val card = gate.card.value as ChatCard.Ask
        assertEquals("q2", card.question)
        gate.answer(firstId, "b") // a late tap on the old card
        gate.answer(card.id + 1, "x") // an id that was never issued
        advanceUntilIdle()
        assertTrue(second.isActive)
        assertEquals(card, gate.card.value)
        gate.answer(card.id, "c")
        assertEquals("c", second.await())
    }

    @Test
    fun cancelClearsCard() = runTest {
        val gate = ChatUserGate()
        val handOff = async { gate.handToUser("로그인해 주세요") }
        advanceUntilIdle()
        val card = gate.card.value as ChatCard.HandOff
        assertEquals("로그인해 주세요", card.reason)
        handOff.cancel()
        advanceUntilIdle()
        assertNull(gate.card.value)
        gate.answer(card.id, "완료") // answering a cancelled card is a no-op
        assertNull(gate.card.value)
    }
}
