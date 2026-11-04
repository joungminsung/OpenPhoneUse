package com.phoneuse.overlay

import com.phoneuse.risk.RiskCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentUiStateTest {
    @Test
    fun koreanNamesCoverEveryCategory() {
        val names = RiskCategory.entries.associateWith { it.koreanName() }
        assertEquals(
            mapOf(
                RiskCategory.MONEY to "결제",
                RiskCategory.SECURITY to "보안",
                RiskCategory.DELETE to "삭제",
                RiskCategory.SEND to "전송",
                RiskCategory.CHANGE to "변경",
            ),
            names,
        )
    }

    @Test
    fun dockTextAnswersAnOpenAskCard() {
        val answers = mutableListOf<Pair<Long, String>>()
        val steers = mutableListOf<String>()
        routeDockText(DockCard.Ask("어느 쪽?", emptyList(), 7), "왼쪽", { id, t -> answers += id to t }, { steers += it })
        assertEquals(listOf(7L to "왼쪽"), answers)
        assertEquals(emptyList<String>(), steers)

        routeDockText(DockCard.Confirm(RiskCategory.MONEY, "결제", 8), "그만", { id, t -> answers += id to t }, { steers += it })
        routeDockText(null, "더 빨리", { id, t -> answers += id to t }, { steers += it })
        assertEquals(1, answers.size)
        assertEquals(listOf("그만", "더 빨리"), steers)
    }

    @Test
    fun dockInputAnswersAskCardWithItsIdEvenWithoutOptions() {
        val answers = mutableListOf<Pair<Long, String>>()
        val steers = mutableListOf<String>()
        // A free-form question has no option buttons: the typed text is the only way to answer it.
        val taken = submitDockText(DockCard.Ask("받는 사람 이름은?", emptyList(), 3), "  엄마 ", { id, t -> answers += id to t }, { steers += it })
        assertEquals(true, taken)
        assertEquals(listOf(3L to "엄마"), answers)
        assertEquals(emptyList<String>(), steers)
    }

    @Test
    fun dockInputSteersOtherwiseAndIgnoresBlank() {
        val answers = mutableListOf<Pair<Long, String>>()
        val steers = mutableListOf<String>()
        assertEquals(false, submitDockText(null, "   ", { id, t -> answers += id to t }, { steers += it }))
        assertEquals(true, submitDockText(DockCard.HandOff("로그인", 4), "됐어", { id, t -> answers += id to t }, { steers += it }))
        assertEquals(true, submitDockText(null, " 더 빨리", { id, t -> answers += id to t }, { steers += it }))
        assertEquals(emptyList<Pair<Long, String>>(), answers)
        assertEquals(listOf("됐어", "더 빨리"), steers)
    }
}
