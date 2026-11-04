package com.phoneuse.app

import com.phoneuse.agent.FakeUserGate
import com.phoneuse.risk.RiskCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingUserGateTest {
    @Test
    fun routesToChatUntilOverlayActive() = runTest {
        val overlay = FakeUserGate().apply { askResult = "overlay" }
        val chat = FakeUserGate().apply { askResult = "chat" }
        var active = false
        val gate = RoutingUserGate(overlay, chat) { active }

        assertEquals("chat", gate.ask("q1", emptyList()))
        gate.confirm(RiskCategory.SEND, "s1")
        gate.handToUser("r1")
        assertEquals(listOf("q1"), chat.asks.map { it.first })
        assertEquals(listOf("s1"), chat.confirms.map { it.second })
        assertEquals(listOf("r1"), chat.handoffs)
        assertTrue(overlay.asks.isEmpty() && overlay.confirms.isEmpty() && overlay.handoffs.isEmpty())

        active = true
        assertEquals("overlay", gate.ask("q2", emptyList()))
        gate.confirm(RiskCategory.MONEY, "s2")
        gate.handToUser("r2")
        assertEquals(listOf("q2"), overlay.asks.map { it.first })
        assertEquals(listOf("s2"), overlay.confirms.map { it.second })
        assertEquals(listOf("r2"), overlay.handoffs)
        assertEquals(1, chat.asks.size)
    }
}
