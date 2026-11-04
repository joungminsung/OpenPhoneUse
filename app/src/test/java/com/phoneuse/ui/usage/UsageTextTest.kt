package com.phoneuse.ui.usage

import com.phoneuse.usage.DayUsage
import com.phoneuse.usage.RequestKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class UsageTextTest {
    @Test fun dayLabels() {
        val sunday = DayUsage(LocalDate.of(2026, 11, 1), emptyMap())
        assertEquals("일", UsageText.dayLabel(sunday, isToday = false))
        assertEquals("오늘", UsageText.dayLabel(sunday, isToday = true))
    }

    @Test fun shares() {
        val shares = UsageText.shares(mapOf(RequestKind.DEVICE to 1, RequestKind.CHAT to 1, RequestKind.SEARCH to 1))
        assertEquals(100, shares.values.sum())
        assertEquals(emptyMap<RequestKind, Int>(), UsageText.shares(emptyMap()))
    }
}
