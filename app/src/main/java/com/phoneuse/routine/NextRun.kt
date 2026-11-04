package com.phoneuse.routine

import java.time.LocalTime
import java.time.ZonedDateTime

/** When a time routine runs next. Pure: only [Trigger.Time] routines have a next run. */
object NextRun {
    private const val DAYS_AHEAD = 8L

    /**
     * The first time after [now] (strictly) at the trigger's hour and minute on a day both the trigger's days and
     * the conditions' days allow. Null for a disabled routine, another trigger, or days that never meet. A time
     * that does not exist that day (DST gap) moves forward, as [ZonedDateTime] does.
     */
    fun compute(routine: Routine, now: ZonedDateTime): ZonedDateTime? {
        if (!routine.enabled) return null
        val t = routine.trigger as? Trigger.Time ?: return null
        val clock = LocalTime.of(t.hour, t.minute)
        for (offset in 0..DAYS_AHEAD) {
            val day = now.toLocalDate().plusDays(offset)
            val dow = day.dayOfWeek
            if (t.days.isNotEmpty() && dow !in t.days) continue
            if (routine.conditions.days?.let { dow !in it } == true) continue
            val at = ZonedDateTime.of(day, clock, now.zone)
            if (at.isAfter(now)) return at
        }
        return null
    }
}
