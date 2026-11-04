package com.phoneuse.app

import com.phoneuse.agent.AgentEvent
import com.phoneuse.overlay.AgentPhase
import com.phoneuse.overlay.AgentUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentEventReducerTest {
    private val start = AgentUiState(AgentPhase.IDLE, listOf("시작"), null)

    @Test fun thinkingSetsPhase() {
        assertEquals(AgentPhase.THINKING, reduce(start, AgentEvent.Thinking).phase)
    }

    @Test fun actingAddsLine() {
        val s = reduce(start, AgentEvent.Acting("탭: 설정"))
        assertEquals(AgentPhase.ACTING, s.phase)
        assertEquals(listOf("시작", "탭: 설정"), s.lines)
    }

    @Test fun logAddsLineKeepingPhase() {
        val s = reduce(start.copy(phase = AgentPhase.ACTING), AgentEvent.Log("hi"))
        assertEquals(AgentPhase.ACTING, s.phase)
        assertEquals(listOf("시작", "hi"), s.lines)
    }

    @Test fun usageLeavesStateUnchanged() {
        assertEquals(start, reduce(start, AgentEvent.Usage(1, 2, 3, 4)))
    }

    @Test fun finishedAndFailedGoIdleWithLine() {
        val f = reduce(start, AgentEvent.Finished("끝"))
        assertEquals(AgentPhase.IDLE, f.phase)
        assertEquals("완료: 끝", f.lines.last())
        val x = reduce(start, AgentEvent.Failed("앗"))
        assertEquals(AgentPhase.IDLE, x.phase)
        assertEquals("실패: 앗", x.lines.last())
    }

    @Test fun keepsOnlyLastThreeLines() {
        val s = withLine(withLine(withLine(start, "a"), "b"), "c")
        assertEquals(listOf("a", "b", "c"), s.lines)
    }

    @Test fun terminalEventClearsCard() {
        val s = reduce(start.copy(card = com.phoneuse.overlay.DockCard.HandOff("x", 1)), AgentEvent.Finished("k"))
        assertEquals(null, s.card)
    }

    @Test fun cancelledGoesIdleWithStoppedLine() {
        val s = reduce(start.copy(phase = AgentPhase.ACTING), AgentEvent.Cancelled)
        assertEquals(AgentPhase.IDLE, s.phase)
        assertEquals("중단됨", s.lines.last())
    }
}
