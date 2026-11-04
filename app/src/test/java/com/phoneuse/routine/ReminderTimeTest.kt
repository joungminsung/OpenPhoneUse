package com.phoneuse.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTimeTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val fivePm = ZonedDateTime.of(2026, 11, 1, 17, 0, 0, 0, seoul)

    @Test
    fun clockTimeIsTheNextOccurrence() {
        assertEquals(ZonedDateTime.of(2026, 11, 1, 18, 5, 0, 0, seoul), ReminderTime.parse("18:05", fivePm))
        assertEquals(ZonedDateTime.of(2026, 11, 1, 9, 5, 0, 0, seoul), ReminderTime.parse(" 9:05 ", fivePm.withHour(8)))
        // Already past today: tomorrow.
        assertEquals(ZonedDateTime.of(2026, 11, 2, 16, 0, 0, 0, seoul), ReminderTime.parse("16:00", fivePm))
        assertEquals(ZonedDateTime.of(2026, 11, 2, 17, 0, 0, 0, seoul), ReminderTime.parse("17:00", fivePm))
    }

    @Test
    fun isoDateTimes() {
        assertEquals(ZonedDateTime.of(2026, 11, 3, 8, 30, 0, 0, seoul), ReminderTime.parse("2026-11-03T08:30", fivePm))
        assertEquals(ZonedDateTime.of(2026, 11, 3, 8, 30, 0, 0, seoul), ReminderTime.parse("2026-11-03T08:30:00+09:00", fivePm))
        assertEquals(ZonedDateTime.of(2026, 11, 3, 17, 30, 0, 0, seoul), ReminderTime.parse("2026-11-03T08:30:00Z", fivePm))
    }

    @Test
    fun rejectsGarbage() {
        for (bad in listOf("", "25:00", "18:60", "내일 아침", "2026-13-01T00:00")) assertNull(bad, ReminderTime.parse(bad, fivePm))
    }

    @Test
    fun describesWhen() {
        assertEquals("18:05", ReminderTime.describe(ZonedDateTime.of(2026, 11, 1, 18, 5, 0, 0, seoul), fivePm))
        assertEquals("내일 08:00", ReminderTime.describe(ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, seoul), fivePm))
        assertEquals("11월 5일 08:00", ReminderTime.describe(ZonedDateTime.of(2026, 11, 5, 8, 0, 0, 0, seoul), fivePm))
    }
}
