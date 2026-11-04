package com.phoneuse.phone

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class OverlapsTest {
    private fun t(h: Int, m: Int = 0, day: Int = 3) = LocalDateTime.of(2026, 11, day, h, m)

    private fun ev(id: Long, s: LocalDateTime, e: LocalDateTime, allDay: Boolean = false) =
        CalendarEvent(id, "e$id", s, e, null, allDay)

    @Test
    fun findsOnlyEventsThatShareTime() {
        val new = ev(-1, t(14), t(15))
        val existing = listOf(
            ev(1, t(13), t(14)), // ends as the new one starts: no overlap
            ev(2, t(14, 30), t(16)), // overlaps
            ev(3, t(15), t(16)), // starts as the new one ends: no overlap
            ev(4, t(13), t(17)), // covers it
            ev(5, t(14, 10), t(14, 20)), // inside it
        )
        assertEquals(listOf(4L, 5L, 2L), Overlaps.find(new, existing).map { it.id })
    }

    @Test
    fun sortedByStartAndCapped() {
        val new = ev(-1, t(9), t(18))
        val existing = (0 until 30).map { ev(it.toLong(), t(17).minusMinutes(it.toLong() * 10), t(17)) }
        val found = Overlaps.find(new, existing)
        assertEquals(Overlaps.MAX, found.size)
        assertEquals(found.sortedBy { it.start }, found)
    }

    @Test
    fun allDayEventsDoNotClashWithTimedOnes() {
        val birthday = ev(1, t(0), t(0, day = 4), allDay = true)
        assertEquals(emptyList<CalendarEvent>(), Overlaps.find(ev(-1, t(14), t(15)), listOf(birthday)))
        // A new all-day event (a day off) does show that day's meetings.
        val dayOff = ev(-1, t(0), t(0, day = 4), allDay = true)
        assertEquals(listOf(2L), Overlaps.find(dayOff, listOf(ev(2, t(10), t(11)), ev(3, t(10, day = 4), t(11, day = 4)))).map { it.id })
    }

    @Test
    fun zeroLengthEventsCountAsAMinute() {
        val point = ev(-1, t(14), t(14))
        assertEquals(listOf(1L), Overlaps.find(point, listOf(ev(1, t(14), t(15)), ev(2, t(13), t(14)))).map { it.id })
        assertEquals(listOf(7L), Overlaps.find(ev(-1, t(13), t(15)), listOf(ev(7, t(14), t(14)))).map { it.id })
    }
}
