package com.phoneuse.phone

import com.phoneuse.i18n.tr
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** The clock app's alarms; Android's is [AndroidAlarmSource]. */
interface AlarmSource {
    /** The next alarm clock alarm (AlarmManager.nextAlarmClock), or null when none is set. */
    fun next(): ZonedDateTime?

    /** Sets an alarm without showing the clock app (AlarmClock.ACTION_SET_ALARM, EXTRA_SKIP_UI); false when no app took it. */
    fun set(hour: Int, minute: Int, label: String): Boolean
}

/**
 * next_alarm and set_alarm. Setting asks first on a confirmation card, then checks that the clock app really set it:
 * a blocked background launch or a clock app that ignores the intent does not throw, so the next alarm is read
 * again ([wait] between reads).
 */
class AlarmTool(
    private val source: AlarmSource,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
) {
    fun next(): PhoneReply {
        val at = source.next()?.withZoneSameInstant(now().zone) ?: return PhoneReply.Ok("예약된 알람 없음")
        val today = now().toLocalDate()
        val day = when (at.toLocalDate()) {
            today -> "오늘 "
            today.plusDays(1) -> "내일 "
            else -> ""
        }
        val weekday = at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
        return PhoneReply.Ok("${hm(at.hour, at.minute)} ($day${at.monthValue}/${at.dayOfMonth} $weekday)")
    }

    /** [confirm] shows the confirmation card with its text and answers whether the user allowed it. */
    suspend fun set(hour: Int, minute: Int, label: String, confirm: suspend (String) -> Boolean): PhoneReply {
        if (hour !in 0..23 || minute !in 0..59) return PhoneReply.Failed("hour must be 0-23 and minute 0-59")
        val name = label.replace(Regex("\\s+"), " ").trim().take(MAX_LABEL)
        val time = hm(hour, minute)
        if (!confirm(tr("Set alarm: $time", "알람 설정: $time") + if (name.isEmpty()) "" else " · $name")) return PhoneReply.Cancelled
        if (!source.set(hour, minute, name)) return PhoneReply.Failed("알람을 맞출 시계 앱이 없어요")
        repeat(VERIFY_TRIES) {
            wait(VERIFY_INTERVAL_MS)
            val next = source.next()?.withZoneSameInstant(now().zone)
            if (next != null && next.hour == hour && next.minute == minute) {
                return PhoneReply.Ok("OK: $time 알람을 맞췄어요" + if (name.isEmpty()) "" else " ($name)")
            }
        }
        // Not seen: the launch may have been blocked, or an earlier alarm comes first. Never retry (no duplicates).
        return PhoneReply.Ok(
            "확인 못 함: 시계 앱에 $time 알람을 요청했지만 맞춰졌는지 확인하지 못했어요. 다시 맞추지 말고, " +
                "사용자에게 시계 앱에서 확인해 달라고 하세요 (더 이른 알람이 있으면 확인이 안 될 수 있어요).",
        )
    }

    private fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)

    companion object {
        const val MAX_LABEL = 40
        private const val VERIFY_TRIES = 6
        private const val VERIFY_INTERVAL_MS = 500L
    }
}
