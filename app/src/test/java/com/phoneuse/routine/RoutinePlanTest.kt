package com.phoneuse.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutinePlanTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val now = ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, seoul)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    private fun r(id: Long, trigger: Trigger, enabled: Boolean = true) =
        Routine(id = id, title = "t$id", trigger = trigger, action = RoutineAction.Notify("x"), enabled = enabled)

    @Test
    fun dueTimesSinceTheLastCheck() {
        val routines = listOf(r(1, Trigger.Time(8, 0)), r(2, Trigger.Time(7, 0)), r(3, Trigger.Time(9, 0)), r(4, Trigger.Time(7, 55), enabled = false))
        val due = RoutinePlan.dueTimes(routines, lastCheck = now.minusMinutes(90), now = now)
        assertEquals(listOf(1L, 2L), due.map { it.routineId })
        assertFalse(due[0].late)
        // 07:00 came an hour late (phone was off): skipped rather than run now.
        assertTrue(due[1].late)
        // Nothing twice: after this check, 08:00 is no longer due.
        assertTrue(RoutinePlan.dueTimes(routines, lastCheck = now, now = now.plusMinutes(1)).isEmpty())
    }

    @Test
    fun eventFiresOnlyForLocatedTimedEventsAhead() {
        val routines = listOf(r(5, Trigger.BeforeEvent(60)), r(6, Trigger.BeforeEvent(30), enabled = false))
        val events = listOf(
            UpcomingEvent("치과", ms(now.plusHours(3)), "강남역 치과"),
            UpcomingEvent("회의", ms(now.plusHours(2)), null),
            UpcomingEvent("휴가", ms(now.plusHours(4)), "제주", allDay = true),
            UpcomingEvent("곧", ms(now.plusMinutes(30)), "역삼"), // 60 minutes before is already past
            UpcomingEvent("모레", ms(now.plusDays(2)), "부산"), // beyond the horizon
        )
        val fires = RoutinePlan.eventFires(routines, events, ms(now))
        assertEquals(listOf(EventFire(5, ms(now.plusHours(2)), "치과", "강남역 치과", ms(now.plusHours(3)))), fires)
        assertTrue(RoutinePlan.needsCalendar(routines))
        assertFalse(RoutinePlan.needsCalendar(listOf(r(1, Trigger.Time(8, 0)))))
    }

    @Test
    fun nextWakeIsTheEarliestPendingThing() {
        val routines = listOf(r(1, Trigger.Time(23, 0)), r(2, Trigger.Device(DeviceKind.CHARGER_CONNECTED)))
        assertEquals(ms(now.withHour(23)), RoutinePlan.nextWake(routines, emptyList(), emptyList(), null, now)?.toEpochMilli())
        val reminder = Reminder(1, ms(now.plusMinutes(5)), "약")
        assertEquals(reminder.at, RoutinePlan.nextWake(routines, listOf(reminder), emptyList(), null, now)?.toEpochMilli())
        val fire = EventFire(3, ms(now.plusMinutes(2)), "a", "b", 0)
        assertEquals(fire.at, RoutinePlan.nextWake(routines, listOf(reminder), listOf(fire), null, now)?.toEpochMilli())
        assertEquals(1L, RoutinePlan.nextWake(emptyList(), emptyList(), emptyList(), 1L, now)?.toEpochMilli())
        assertNull(RoutinePlan.nextWake(emptyList(), emptyList(), emptyList(), null, now))
        // While place routines are paused, a retry wake comes even with nothing else pending.
        assertEquals(5L, RoutinePlan.nextWake(emptyList(), emptyList(), emptyList(), null, now, placesRetryAt = 5L)?.toEpochMilli())
    }

    @Test
    fun pausedPlacesRetryOnlyOnceLocationIsBack() {
        assertTrue(RoutinePlan.retryPlaces(paused = true, locationOn = true))
        assertFalse(RoutinePlan.retryPlaces(paused = true, locationOn = false))
        assertFalse(RoutinePlan.retryPlaces(paused = false, locationOn = true))
        assertEquals(1_000L + RoutinePlan.PLACES_RETRY_MS, RoutinePlan.placesRetryAt(paused = true, now = 1_000L))
        assertNull(RoutinePlan.placesRetryAt(paused = false, now = 1_000L))
    }

    @Test
    fun calendarIsReadAfterMidnight() {
        assertEquals(ZonedDateTime.of(2026, 11, 3, 0, 5, 0, 0, seoul), RoutinePlan.nextCalendarRefresh(now))
        assertEquals(ZonedDateTime.of(2026, 11, 2, 0, 5, 0, 0, seoul), RoutinePlan.nextCalendarRefresh(now.withHour(0).withMinute(1)))
    }

    @Test
    fun drainingSkipsDisabledDeletedAndExpiredRuns() {
        val queue = listOf(
            DeferredRun(1, RoutineEvent.TimeDue(1), 0), // waited too long
            DeferredRun(2, RoutineEvent.TimeDue(2), 40 * 60_000L), // switched off while waiting
            DeferredRun(3, RoutineEvent.TimeDue(3), 41 * 60_000L), // deleted
            DeferredRun(4, RoutineEvent.TimeDue(4), 42 * 60_000L),
            DeferredRun(5, RoutineEvent.TimeDue(5), 43 * 60_000L),
        )
        val enabled = mapOf(1L to true, 2L to false, 4L to true, 5L to true)
        val step = RoutinePlan.drainStep(queue, now = 45 * 60_000L) { enabled[it] }
        assertEquals(4L, step.next?.routineId)
        assertEquals(listOf(1L), step.expired.map { it.routineId })
        assertEquals(listOf(5L), step.rest.map { it.routineId })
        // The rest goes on the next step, then nothing is left.
        val second = RoutinePlan.drainStep(step.rest, 45 * 60_000L) { enabled[it] }
        assertEquals(5L, second.next?.routineId)
        assertNull(RoutinePlan.drainStep(second.rest, 45 * 60_000L) { enabled[it] }.next)
        // A routine queued again replaces its earlier run instead of running twice.
        val again = RoutinePlan.enqueue(queue, DeferredRun(4, RoutineEvent.Manual, 50))
        assertEquals(listOf(1L, 2L, 3L, 5L, 4L), again.map { it.routineId })
        assertEquals(RoutineEvent.Manual, again.last().event)
    }
}
