package com.phoneuse.overlay

import com.phoneuse.agent.ServiceUnavailableException
import com.phoneuse.risk.RiskCategory

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayUserGateTest {
    private class FakeSink : CardSink {
        override val state = MutableStateFlow(AgentUiState(phase = AgentPhase.THINKING))
        override var onCardResult: (Long, String) -> Unit = { _, _ -> }
        override var isClosed = false
        override var onClosed: () -> Unit = {}

        fun close() {
            isClosed = true
            onClosed()
        }
        val blocking = mutableListOf<Boolean>()
        override fun setBlocking(enabled: Boolean) {
            blocking += enabled
        }
    }

    @Test
    fun confirmOnlyAcceptsTheCurrentCardId() = runTest {
        val sink = FakeSink()
        val gate = OverlayUserGate(sink)
        val result = async { gate.confirm(RiskCategory.MONEY, "결제") }
        advanceUntilIdle()
        val card = sink.state.value.card as DockCard.Confirm
        assertEquals(AgentPhase.WAITING_USER, sink.state.value.phase)

        sink.onCardResult(card.id + 1, "실행") // stale tap
        advanceUntilIdle()
        assertTrue(result.isActive)

        sink.onCardResult(card.id, "취소")
        assertFalse(result.await())
        assertNull(sink.state.value.card)
        assertEquals(AgentPhase.THINKING, sink.state.value.phase)
    }

    @Test
    fun staleTapFromAnsweredCardDoesNotCompleteNextRequest() = runTest {
        val sink = FakeSink()
        val gate = OverlayUserGate(sink)
        val first = async { gate.ask("q1", listOf("a", "b")) }
        advanceUntilIdle()
        val firstId = sink.state.value.card!!.id
        sink.onCardResult(firstId, "a")
        assertEquals("a", first.await())

        val second = async { gate.ask("q2", listOf("c")) }
        advanceUntilIdle()
        sink.onCardResult(firstId, "b") // double tap on the old card
        advanceUntilIdle()
        assertTrue(second.isActive)
        sink.onCardResult(sink.state.value.card!!.id, "c")
        assertEquals("c", second.await())
    }

    @Test
    fun closingTheOverlayFailsAPendingCard() = runTest {
        val sink = FakeSink()
        val gate = OverlayUserGate(sink)
        val result = async { runCatching { gate.handToUser("로그인") } }
        advanceUntilIdle()
        assertTrue(result.isActive)
        sink.close()
        assertTrue(result.await().exceptionOrNull() is ServiceUnavailableException)
        assertNull(sink.state.value.card)
        assertEquals(listOf(false, true), sink.blocking)
    }

    @Test
    fun requestsAfterCloseFailImmediately() = runTest {
        val sink = FakeSink()
        val gate = OverlayUserGate(sink)
        sink.close()
        val r = runCatching { gate.confirm(RiskCategory.MONEY, "결제") }
        assertTrue(r.exceptionOrNull() is ServiceUnavailableException)
        assertNull(sink.state.value.card)
    }

    @Test
    fun handOffDisablesBlockingUntilDone() = runTest {
        val sink = FakeSink()
        val gate = OverlayUserGate(sink)
        val job = async { gate.handToUser("로그인") }
        advanceUntilIdle()
        assertEquals(listOf(false), sink.blocking)
        sink.onCardResult(sink.state.value.card!!.id, "완료")
        job.await()
        assertEquals(listOf(false, true), sink.blocking)
    }
}
