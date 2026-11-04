package com.phoneuse.usage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class UsageDaysTest {
    @Test fun fillsSevenDaysOldestFirstWithZeros() {
        val today = LocalDate.of(2026, 11, 1)
        val days = UsageDays.fill(
            today, 7,
            listOf(
                Triple(today, RequestKind.CHAT, 3),
                Triple(today, RequestKind.SEARCH, 2),
                Triple(today.minusDays(6), RequestKind.DEVICE, 4),
                Triple(today.minusDays(9), RequestKind.DEVICE, 99),
            ),
        )
        assertEquals(7, days.size)
        assertEquals(today.minusDays(6), days.first().date)
        assertEquals(4, days.first().total)
        assertEquals(0, days[3].total)
        assertEquals(today, days.last().date)
        assertEquals(mapOf(RequestKind.CHAT to 3, RequestKind.SEARCH to 2), days.last().counts)
    }
}
