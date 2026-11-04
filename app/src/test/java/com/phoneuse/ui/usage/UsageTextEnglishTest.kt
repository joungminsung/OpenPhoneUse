package com.phoneuse.ui.usage

import com.phoneuse.i18n.Lang
import com.phoneuse.usage.DayUsage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class UsageTextEnglishTest {
    private var saved = true

    @Before fun english() {
        saved = Lang.korean
        Lang.korean = false
    }

    @After fun restore() {
        Lang.korean = saved
    }

    @Test fun dayLabelsInEnglish() {
        val sunday = DayUsage(LocalDate.of(2026, 11, 1), emptyMap())
        assertEquals("Sun", UsageText.dayLabel(sunday, isToday = false))
        assertEquals("Today", UsageText.dayLabel(sunday, isToday = true))
    }
}
