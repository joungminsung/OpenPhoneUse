package com.phoneuse.widget

import com.phoneuse.i18n.tr
import com.phoneuse.routine.NextRun
import com.phoneuse.routine.Routine
import com.phoneuse.routine.RoutineText
import java.time.ZonedDateTime

/** One routine on the widget: [whenText] is its trigger, [next] the next scheduled run ("Today 07:00") or null. */
data class RoutineRow(val id: Long, val title: String, val whenText: String, val next: String?, val nextAt: Long?, val enabled: Boolean)

/** The routines widget's rows: enabled first, then by next run (time routines), then by title. Pure. */
object RoutineRows {
    fun rows(routines: List<Routine>, now: ZonedDateTime): List<RoutineRow> = routines.map { r ->
        val at = NextRun.compute(r, now)
        RoutineRow(r.id, r.title, RoutineText.whenText(r.trigger), at?.let { label(it, now) }, at?.toInstant()?.toEpochMilli(), r.enabled)
    }.sortedWith(
        compareByDescending<RoutineRow> { it.enabled }
            .thenBy { it.nextAt ?: Long.MAX_VALUE }
            .thenBy { it.title.lowercase() },
    )

    /** "Today 07:00", "Tomorrow 07:00", or "Mon 07:00" for later in the week. */
    fun label(at: ZonedDateTime, now: ZonedDateTime): String {
        val clock = RoutineText.clock(at.hour, at.minute)
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        return when (days) {
            0L -> tr("Today $clock", "오늘 $clock")
            1L -> tr("Tomorrow $clock", "내일 $clock")
            else -> "${RoutineText.dayLabel(at.dayOfWeek)} $clock"
        }
    }
}
