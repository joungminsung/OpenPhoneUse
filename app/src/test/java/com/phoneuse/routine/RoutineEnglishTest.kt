package com.phoneuse.routine

import com.phoneuse.i18n.Lang
import com.phoneuse.proactive.AskLog
import com.phoneuse.proactive.BriefingText
import java.time.DayOfWeek
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class RoutineEnglishTest {
    private var before = true

    @Before fun english() {
        before = Lang.korean
        Lang.korean = false
    }

    @After fun restore() {
        Lang.korean = before
    }

    @Test fun whenTextInEnglish() {
        assertEquals("07:30 · Weekdays", RoutineText.whenText(Trigger.Time(7, 30, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))))
        assertEquals("When charging starts", RoutineText.whenText(Trigger.Device(DeviceKind.CHARGER_CONNECTED, null)))
        assertEquals("1 h 30 min", RoutineText.minutes(90))
    }

    @Test fun templatesAreEnglish() {
        val first = RoutineTemplates.all.first()
        assertEquals("Morning briefing", first.title)
        assertEquals("Morning briefing", first.draft.title)
    }

    @Test fun englishRoutineAskIsOurs() {
        assertTrue(AskLog.ours(BriefingText.TODAY_PROMPT_EN))
        assertTrue(AskLog.ours("${BriefingText.ROUTINE_PROMPT_EN} at 08:00: check the weather"))
    }
}
