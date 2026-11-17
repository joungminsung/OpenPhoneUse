package com.phoneuse.usage

import com.phoneuse.i18n.Lang
import com.phoneuse.ui.usage.UsageText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class UsageDaysTest {
    @After fun restoreLanguage() { Lang.korean = true }

    @Test fun fillsSevenDaysOldestFirstWithZeros() {
        val today = LocalDate.of(2026, 11, 1)
        val days = UsageDays.fill(
            today, 7,
            listOf(
                UsageRow(today, RequestKind.CHAT, 3),
                UsageRow(today, RequestKind.SEARCH, 2),
                UsageRow(today.minusDays(6), RequestKind.DEVICE, 4),
                UsageRow(today.minusDays(9), RequestKind.DEVICE, 99),
            ),
        )
        assertEquals(7, days.size)
        assertEquals(today.minusDays(6), days.first().date)
        assertEquals(4, days.first().total)
        assertEquals(0, days[3].total)
        assertEquals(today, days.last().date)
        assertEquals(mapOf(RequestKind.CHAT to 3, RequestKind.SEARCH to 2), days.last().counts)
    }

    @Test fun addsTokensPerDay() {
        val today = LocalDate.of(2026, 11, 17)
        val days = UsageDays.fill(
            today, 2,
            listOf(
                UsageRow(today, RequestKind.UNDERSTAND, 2, inputTokens = 5000, outputTokens = 900),
                UsageRow(today, RequestKind.CHAT, 1, inputTokens = 1000, outputTokens = 100),
                UsageRow(today.minusDays(1), RequestKind.UNDERSTAND, 1, inputTokens = 2500, outputTokens = 400),
            ),
        )
        assertEquals(6000, days.last().inputTokens)
        assertEquals(1000, days.last().outputTokens)
        assertEquals(2500, days.first().inputTokens)
    }

    @Test fun everyKindHasAName() {
        Lang.korean = false
        RequestKind.entries.forEach { UsageText.KIND_NAMES.getValue(it) }
        assertEquals("Notification analysis", UsageText.KIND_NAMES.getValue(RequestKind.UNDERSTAND))
        Lang.korean = true
        assertEquals("알림 분석", UsageText.KIND_NAMES.getValue(RequestKind.UNDERSTAND))
    }
}
