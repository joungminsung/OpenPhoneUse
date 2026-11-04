package com.phoneuse.app

import com.phoneuse.phone.DigestBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DailySummaryTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 2, h, m, 0, 0, seoul)

    @Test fun nextTimeIsTodayOrTomorrow() {
        assertEquals(at(21, 0), DailySummary.nextAt(at(9, 0), 21 * 60))
        assertEquals(at(21, 0).plusDays(1), DailySummary.nextAt(at(21, 0), 21 * 60))
        assertEquals(at(7, 30).plusDays(1), DailySummary.nextAt(at(22, 15), 7 * 60 + 30))
        assertNull(DailySummary.nextAt(at(9, 0), -1))
    }

    @Test fun textCountsGroupsInBoardOrder() {
        val text = DailySummary.text(mapOf(DigestBucket.OTHER to listOf(1, 2, 3), DigestBucket.REPLY to listOf(1)))
        assertEquals("답장 필요 1 · 나머지 3", text)
        assertNull(DailySummary.text(emptyMap()))
    }

    @Test fun quietHoursCrossMidnight() {
        val q = QuietHours(on = true, startMin = 23 * 60, endMin = 7 * 60)
        assertTrue(q.contains(23 * 60))
        assertTrue(q.contains(3 * 60))
        assertFalse(q.contains(7 * 60))
        assertFalse(q.contains(12 * 60))
        assertFalse(q.copy(on = false).contains(3 * 60))
        val day = QuietHours(on = true, startMin = 13 * 60, endMin = 14 * 60)
        assertTrue(day.contains(13 * 60 + 30))
        assertFalse(day.contains(14 * 60))
    }
}
