package com.phoneuse.app

import com.phoneuse.agent.FakeUserGate
import com.phoneuse.agent.UserGate
import com.phoneuse.risk.RiskCategory
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedUserGateTest {
    private object Never : UserGate {
        override suspend fun confirm(category: RiskCategory, summary: String): Boolean = awaitCancellation()
        override suspend fun ask(question: String, options: List<String>): String = awaitCancellation()
        override suspend fun handToUser(reason: String) = awaitCancellation()
    }

    @Test
    fun unansweredQuestionsEndAsRefusals() = runTest {
        val waiting = mutableListOf<String>()
        val gate = TimedUserGate(Never, 60_000) { waiting += it }
        assertFalse(gate.confirm(RiskCategory.SEND, "민수에게 보내기"))
        assertEquals(TimedUserGate.NO_ANSWER, gate.ask("어느 쪽?", listOf("a", "b")))
        gate.handToUser("로그인")
        assertEquals(listOf("민수에게 보내기", "어느 쪽?", "로그인"), waiting)
    }

    @Test
    fun answersPassThrough() = runTest {
        val inner = FakeUserGate().apply { askResult = "a" }
        val gate = TimedUserGate(inner, 60_000)
        assertTrue(gate.confirm(RiskCategory.SEND, "보내기"))
        assertEquals("a", gate.ask("q", listOf("a", "b")))
        assertEquals(listOf(RiskCategory.SEND to "보내기"), inner.confirms)
    }
}
