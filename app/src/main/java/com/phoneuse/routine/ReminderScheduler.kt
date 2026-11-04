package com.phoneuse.routine

import com.phoneuse.i18n.tr
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * One-shot app notifications (remind_at, the route card's "18:05에 알림"). Not an alarm: a notification on
 * the "알림" channel. On Android, [AlarmReminderScheduler] hands them to the routine scheduler (RoutineSystem),
 * which keeps them across reboots.
 */
interface ReminderScheduler {
    /** False when the app may not post notifications (permission off). */
    fun canNotify(): Boolean

    /** What the [권한 허용] card asks for; empty below Android 13, where only the settings page can turn them on. */
    fun notifyPermissions(): List<String> = PERMISSIONS

    /** Schedules [text] at [at]. True when exact; false when the system only allows an inexact (maybe late) one. */
    fun oneShot(at: Instant, text: String): Boolean

    companion object {
        const val PERMISSION_NEEDED = "권한 필요: 알림"
        val PERMISSIONS = listOf("android.permission.POST_NOTIFICATIONS")
    }
}

/** remind_at's time argument. */
object ReminderTime {
    private val CLOCK = Regex("""([01]?\d|2[0-3]):([0-5]\d)""")

    /**
     * "HH:mm" is its next occurrence after [now] (today, or tomorrow once past); otherwise an ISO local or
     * offset date-time, read in [now]'s zone. Null when it is neither.
     */
    fun parse(input: String, now: ZonedDateTime): ZonedDateTime? {
        val t = input.trim()
        CLOCK.matchEntire(t)?.let { m ->
            val today = now.with(LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()))
            return if (today.isAfter(now)) today else today.plusDays(1)
        }
        return try {
            OffsetDateTime.parse(t).atZoneSameInstant(now.zone)
        } catch (e: DateTimeParseException) {
            try {
                LocalDateTime.parse(t).atZone(now.zone)
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }

    /** "18:05", "내일 08:00" or "11월 5일 08:00", relative to [now]. */
    fun describe(at: ZonedDateTime, now: ZonedDateTime): String {
        val local = at.withZoneSameInstant(now.zone)
        val clock = "%02d:%02d".format(local.hour, local.minute)
        val day: LocalDate = local.toLocalDate()
        return when (day) {
            now.toLocalDate() -> clock
            now.toLocalDate().plusDays(1) -> tr("Tomorrow $clock", "내일 $clock")
            else -> tr(
                "${day.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH))} $clock",
                "${day.monthValue}월 ${day.dayOfMonth}일 $clock",
            )
        }
    }
}
