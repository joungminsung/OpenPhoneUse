package com.phoneuse.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class NextRunTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    // 2026-11-06 is a Friday.
    private val fridayFivePm = ZonedDateTime.of(2026, 11, 6, 17, 0, 0, 0, seoul)

    private fun routine(trigger: Trigger, conditions: Conditions = Conditions(), enabled: Boolean = true) =
        Routine(id = 1, title = "t", trigger = trigger, conditions = conditions, action = RoutineAction.Notify("x"), enabled = enabled)

    @Test
    fun laterTodayOrTomorrow() {
        assertEquals(ZonedDateTime.of(2026, 11, 6, 23, 0, 0, 0, seoul), NextRun.compute(routine(Trigger.Time(23, 0)), fridayFivePm))
        assertEquals(ZonedDateTime.of(2026, 11, 7, 7, 30, 0, 0, seoul), NextRun.compute(routine(Trigger.Time(7, 30)), fridayFivePm))
        // Exactly now is not "next": it already fired.
        assertEquals(ZonedDateTime.of(2026, 11, 7, 17, 0, 0, 0, seoul), NextRun.compute(routine(Trigger.Time(17, 0)), fridayFivePm))
    }

    @Test
    fun weekdaysOnlySkipTheWeekend() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        assertEquals(ZonedDateTime.of(2026, 11, 9, 8, 0, 0, 0, seoul), NextRun.compute(routine(Trigger.Time(8, 0, weekdays)), fridayFivePm))
        // The routine's own conditions narrow the days too.
        assertEquals(
            ZonedDateTime.of(2026, 11, 9, 8, 0, 0, 0, seoul),
            NextRun.compute(routine(Trigger.Time(8, 0), Conditions(days = weekdays)), fridayFivePm),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 7, 9, 0, 0, 0, seoul),
            NextRun.compute(routine(Trigger.Time(9, 0, setOf(DayOfWeek.SATURDAY))), fridayFivePm),
        )
    }

    @Test
    fun noRunWhenDaysNeverMeet() {
        val r = routine(Trigger.Time(8, 0, setOf(DayOfWeek.MONDAY)), Conditions(days = setOf(DayOfWeek.TUESDAY)))
        assertNull(NextRun.compute(r, fridayFivePm))
    }

    @Test
    fun onlyEnabledTimeRoutines() {
        assertNull(NextRun.compute(routine(Trigger.Time(23, 0), enabled = false), fridayFivePm))
        assertNull(NextRun.compute(routine(Trigger.BeforeEvent(30)), fridayFivePm))
        assertNull(NextRun.compute(routine(Trigger.Device(DeviceKind.CHARGER_CONNECTED)), fridayFivePm))
    }

    @Test
    fun daylightSavingGapMovesForward() {
        val ny = ZoneId.of("America/New_York")
        // 2026-03-08 02:30 does not exist in New York; the run is at 03:30 that day.
        val before = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, ny)
        assertEquals(ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, ny), NextRun.compute(routine(Trigger.Time(2, 30)), before))
    }
}
