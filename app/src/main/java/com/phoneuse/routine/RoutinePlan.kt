package com.phoneuse.routine

import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** A calendar event as BeforeEvent planning needs it; times in epoch ms. */
data class UpcomingEvent(val title: String, val startsAt: Long, val location: String?, val allDay: Boolean = false)

/** [routineId] runs at [at], [minutes] before the event starting at [startsAt]. */
data class EventFire(val routineId: Long, val at: Long, val title: String, val location: String, val startsAt: Long)

/**
 * The scheduler's arithmetic, pure. One exact alarm is set for [nextWake]; when it rings, everything due since
 * the previous check is handled ([dueTimes], due reminders and event fires) and the next one is set.
 */
object RoutinePlan {
    /** A time run that comes more than this late (phone off, alarm delayed) is skipped rather than run at a wrong time. */
    const val LATE_MAX_MS = 30 * 60_000L

    /** How far ahead BeforeEvent routines look at the calendar. */
    const val EVENT_HORIZON_MS = 36 * 60 * 60_000L

    /** The calendar is read again once a day at this time (and on every calendar change). */
    val CALENDAR_REFRESH: LocalTime = LocalTime.of(0, 5)

    data class Due(val routineId: Long, val at: ZonedDateTime, val late: Boolean)

    /** Time routines whose next run after [lastCheck] is not after [now]. */
    fun dueTimes(routines: List<Routine>, lastCheck: ZonedDateTime, now: ZonedDateTime): List<Due> =
        routines.mapNotNull { r ->
            val next = NextRun.compute(r, lastCheck) ?: return@mapNotNull null
            if (next.isAfter(now)) return@mapNotNull null
            Due(r.id, next, now.toInstant().toEpochMilli() - next.toInstant().toEpochMilli() > LATE_MAX_MS)
        }

    /**
     * For each enabled BeforeEvent routine, the events with a location (not all-day) whose "minutes before"
     * point is still ahead of [now] and within [EVENT_HORIZON_MS].
     */
    fun eventFires(routines: List<Routine>, events: List<UpcomingEvent>, now: Long): List<EventFire> = buildList {
        for (r in routines) {
            if (!r.enabled) continue
            val t = r.trigger as? Trigger.BeforeEvent ?: continue
            for (e in events) {
                val place = e.location?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                if (e.allDay) continue
                val at = e.startsAt - t.minutes * 60_000L
                if (at <= now || at > now + EVENT_HORIZON_MS) continue
                add(EventFire(r.id, at, e.title, place, e.startsAt))
            }
        }
    }.sortedBy { it.at }

    /** Whether any routine needs the calendar read. */
    fun needsCalendar(routines: List<Routine>): Boolean = routines.any { it.enabled && it.trigger is Trigger.BeforeEvent }

    /** The next daily calendar read after [now]. */
    fun nextCalendarRefresh(now: ZonedDateTime): ZonedDateTime {
        val today = now.with(CALENDAR_REFRESH).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** How often paused place routines check whether location is back (besides the location-change broadcast). */
    const val PLACES_RETRY_MS = 15 * 60_000L

    /**
     * Whether paused place routines should register their geofences again now: only while paused and once location
     * is on again (registering with location off just fails with GEOFENCE_NOT_AVAILABLE).
     */
    fun retryPlaces(paused: Boolean, locationOn: Boolean): Boolean = paused && locationOn

    /** When to look again while place routines are paused; null when they are not. */
    fun placesRetryAt(paused: Boolean, now: Long): Long? = if (paused) now + PLACES_RETRY_MS else null

    /** The earliest of the next time runs, reminders, event fires and the calendar read; null when nothing is pending. */
    fun nextWake(
        routines: List<Routine>,
        reminders: List<Reminder>,
        fires: List<EventFire>,
        calendarRefreshAt: Long?,
        now: ZonedDateTime,
        placesRetryAt: Long? = null,
    ): Instant? {
        val times = buildList {
            routines.forEach { r -> NextRun.compute(r, now)?.let { add(it.toInstant().toEpochMilli()) } }
            reminders.forEach { add(it.at) }
            fires.forEach { add(it.at) }
            calendarRefreshAt?.let { add(it) }
            placesRetryAt?.let { add(it) }
        }
        return times.minOrNull()?.let { Instant.ofEpochMilli(it) }
    }

    /** One step of draining the deferred queue (see [drainStep]). */
    data class Drain(val next: DeferredRun?, val expired: List<DeferredRun>, val rest: List<DeferredRun>)

    /**
     * The next queued run to try: the oldest one still due whose routine is still there and switched on.
     * [enabled] is null for a deleted routine. Runs of deleted or disabled routines are dropped; [Drain.expired]
     * waited too long (the user is told). [Drain.rest] is the queue after this step.
     */
    fun drainStep(queue: List<DeferredRun>, now: Long, enabled: (Long) -> Boolean?): Drain {
        val expired = ArrayList<DeferredRun>()
        val rest = ArrayList<DeferredRun>()
        var next: DeferredRun? = null
        for (d in queue) {
            val on = enabled(d.routineId) ?: continue
            if (!on) continue
            when {
                !RoutineEngine.stillDue(d.queuedAt, now) -> expired += d
                next == null -> next = d
                else -> rest += d
            }
        }
        return Drain(next, expired, rest)
    }

    /** [queue] with [run] at the end, replacing an earlier run of the same routine. */
    fun enqueue(queue: List<DeferredRun>, run: DeferredRun): List<DeferredRun> = queue.filter { it.routineId != run.routineId } + run
}
