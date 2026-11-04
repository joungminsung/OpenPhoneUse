package com.phoneuse.widget

import com.phoneuse.proactive.AskRecord
import com.phoneuse.routine.Routine
import com.phoneuse.routine.RoutineAction
import com.phoneuse.routine.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class AskChipsTest {
    private val zone = ZoneId.of("Asia/Seoul")

    // A Monday, 08:10.
    private val now = ZonedDateTime.of(2026, 11, 2, 8, 10, 0, 0, zone)

    private fun at(daysAgo: Long, hour: Int, minute: Int = 0) = now.minusDays(daysAgo).withHour(hour).withMinute(minute).toInstant().toEpochMilli()

    private fun timeRoutine(id: Long, title: String, hour: Int, minute: Int, days: Set<DayOfWeek> = emptySet(), enabled: Boolean = true) =
        Routine(id = id, title = title, trigger = Trigger.Time(hour, minute, days), action = RoutineAction.Ask("$title 해줘"), enabled = enabled)

    @Test
    fun anAskTypedAroundThisHourComesFirst() {
        val asks = listOf(AskRecord("출근길 지하철 막히는지 알려줘", at(1, 8)), AskRecord("저녁 메뉴 추천", at(1, 19)))
        val chips = AskChips.pick(now, asks, emptyList())
        assertEquals("출근길 지하철 막히는지 알려줘", chips.first().prompt)
        assertTrue(chips.none { it.prompt == "저녁 메뉴 추천" })
        assertEquals(AskChips.COUNT, chips.size)
    }

    @Test
    fun anAskFromAnotherHourCountsOnlyWhenRepeated() {
        val once = listOf(AskRecord("환율 알려줘", at(1, 15)))
        assertTrue(AskChips.habitual(now, once).isEmpty())
        val twice = once + AskRecord("환율 알려줘!", at(2, 16))
        // The newest wording is the one offered.
        assertEquals(listOf("환율 알려줘"), AskChips.habitual(now, twice).map { it.prompt })
    }

    @Test
    fun moreFrequentAsksRankHigher() {
        val asks = listOf(
            AskRecord("날씨 알려줘", at(1, 8)),
            AskRecord("뉴스 요약해줘", at(1, 7)),
            AskRecord("뉴스 요약해줘", at(2, 8)),
        )
        assertEquals(listOf("뉴스 요약해줘", "날씨 알려줘"), AskChips.habitual(now, asks).map { it.prompt })
    }

    @Test
    fun hourDistanceWrapsAroundMidnight() {
        assertEquals(2, AskChips.hourDistance(23, 1))
        assertEquals(0, AskChips.hourDistance(8, 8))
        assertEquals(12, AskChips.hourDistance(0, 12))
    }

    @Test
    fun aRoutineDueSoonBecomesAChip() {
        val routines = listOf(
            timeRoutine(1, "아침 브리핑", 9, 0),
            timeRoutine(2, "저녁 정리", 21, 0),
            timeRoutine(3, "꺼진 루틴", 8, 30, enabled = false),
        )
        val chip = AskChips.dueRoutine(now, routines)
        assertEquals("아침 브리핑 해줘", chip?.prompt)
        assertEquals("아침 브리핑", chip?.label)
    }

    @Test
    fun aRoutineOnAnotherDayOrAlreadyPastIsNotDue() {
        assertNull(AskChips.dueRoutine(now, listOf(timeRoutine(1, "주말 청소", 9, 0, setOf(DayOfWeek.SATURDAY)))))
        assertNull(AskChips.dueRoutine(now, listOf(timeRoutine(2, "새벽 알림", 7, 0))))
    }

    @Test
    fun duplicatesAreDroppedAndDefaultsFillTheRest() {
        val asks = listOf(AskRecord("오늘 날씨, 일정, 택배, 밤사이 알림 한눈에 정리해줘", at(1, 8)))
        val chips = AskChips.pick(now, asks, emptyList())
        assertEquals(3, chips.size)
        assertEquals(3, chips.map { it.prompt }.toSet().size)
    }

    @Test
    fun everyHourHasThreeDefaults() {
        for (h in 0..23) assertEquals("hour $h", 3, AskChips.timeOfDay(h).size)
    }

    @Test
    fun longLabelsAreCut() {
        val label = AskChips.label("다음 주 부산 출장 KTX 왕복 기차표 제일 싼 시간대로 찾아줘")
        assertEquals(AskChips.LABEL_CHARS, label.length)
        assertTrue(label.endsWith("…"))
        assertEquals("택배 어디야", AskChips.label("택배 어디야? 빨리 알려줘"))
    }
}
