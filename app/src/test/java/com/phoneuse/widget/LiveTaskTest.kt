package com.phoneuse.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTaskTest {
    @Test
    fun aRunningTaskShowsTitleNewestStepAndProgress() {
        val view = LiveTask.map(
            running = true, task = "쿠팡에서 이어폰 찾아서 장바구니에 담아줘\n둘째 줄", steps = listOf("쿠팡 열기", "검색: 이어폰"),
            streaming = "", awaitingConfirm = false, lastReply = null, lastConversation = 3,
        ) as LiveView.Running
        assertEquals("쿠팡에서 이어폰 찾아서 장바구니에 담아줘", view.title)
        assertEquals("검색: 이어폰", view.step)
        assertEquals(2, view.steps)
        assertTrue(view.progress > 0f && view.progress < 1f)
    }

    @Test
    fun withoutStepsTheStreamingReplyIsTheStep() {
        val view = LiveTask.map(true, "날씨", emptyList(), "**맑음**, 18도", false, null, null) as LiveView.Running
        assertEquals("맑음, 18도", view.step)
    }

    @Test
    fun aConfirmCardMeansItNeedsTheUser() {
        val view = LiveTask.map(true, "문자 보내줘", listOf("메시지 작성"), "", true, null, null) as LiveView.Running
        assertTrue(view.needsYou)
    }

    @Test
    fun idleShowsTheLastReplysFirstRealLine() {
        val view = LiveTask.map(false, "날씨 알려줘", emptyList(), "", false, "## 오늘 날씨\n- 맑음", 7)
        assertEquals(LiveView.Idle("날씨 알려줘", "오늘 날씨", 7), view)
    }

    @Test
    fun nothingToShowIsEmpty() {
        assertEquals(LiveView.Empty, LiveTask.map(false, "", emptyList(), "", false, null, null))
        assertEquals(LiveView.Empty, LiveTask.map(false, "x", emptyList(), "", false, "  ", 4))
    }

    @Test
    fun progressGrowsWithStepsAndNeverFinishes() {
        var last = 0f
        for (n in 0..60) {
            val p = LiveTask.progress(n)
            assertTrue("step $n", p >= last)
            assertTrue(p < 1f)
            last = p
        }
        assertTrue(LiveTask.progress(1) > LiveTask.progress(0))
    }

    @Test
    fun firstLineDropsMarkdown() {
        assertEquals("가격 비교", LiveTask.firstLine("\n> **가격 비교**\n| a | b |"))
        assertEquals("첫 항목", LiveTask.firstLine("1. 첫 항목\n2. 둘"))
        assertEquals("", LiveTask.firstLine("---\n|---|"))
    }

    @Test
    fun clipAddsAnEllipsis() {
        assertEquals("abcd…", LiveTask.clip("abcdefgh", 5))
        assertEquals("abc", LiveTask.clip("abc", 5))
    }
}
