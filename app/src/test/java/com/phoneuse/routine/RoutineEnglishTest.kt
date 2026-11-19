package com.phoneuse.routine

import com.phoneuse.i18n.Lang
import com.phoneuse.i18n.Region
import com.phoneuse.proactive.AskLog
import com.phoneuse.proactive.BriefingText
import java.time.DayOfWeek
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class RoutineEnglishTest {
    private var before = true
    private var region = Region.country

    @Before fun english() {
        before = Lang.korean
        Lang.korean = false
        region = Region.country
    }

    @After fun restore() {
        Lang.korean = before
        Region.country = region
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

    @Test fun placeSourceLabelsTranslateStoredValues() {
        assertEquals("From your memories", PlaceResolver.label("기억에서"))
        assertEquals("Map search", PlaceResolver.label("지도 검색"))
        assertEquals("Set by you", PlaceResolver.label("직접 지정"))
        assertEquals("Set by you", PlaceResolver.label("Set by you"))
        assertEquals("somewhere", PlaceResolver.label("somewhere"))
        assertNull(PlaceResolver.label(null))
    }

    @Test fun placeSourceLabelsInKorean() {
        Lang.korean = true
        assertEquals("직접 지정", PlaceResolver.label("Set by you"))
        assertEquals("기억에서", PlaceResolver.label("기억에서"))
    }

    @Test fun parcelTemplateFollowsRegionNotLanguage() {
        fun parcel() = RoutineTemplates.all.single { it.draft.trigger == TriggerKind.NOTIFICATION }
        Region.country = "KR"
        assertEquals("택배", parcel().draft.notifKeyword)
        Region.country = "US"
        assertEquals("deliver", parcel().draft.notifKeyword)
        assertEquals("When a 'deliver' notification arrives · courier, expected arrival, what to do", parcel().description)
    }
}
