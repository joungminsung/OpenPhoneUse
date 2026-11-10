package com.phoneuse.phone

import com.phoneuse.i18n.tr
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** The clock app's alarms and timers; Android's is [AndroidAlarmSource]. */
interface AlarmSource {
    /** The next alarm clock alarm (AlarmManager.nextAlarmClock), or null when none is set. */
    fun next(): ZonedDateTime?

    /**
     * Sets an alarm without showing the clock app (AlarmClock.ACTION_SET_ALARM, EXTRA_SKIP_UI), repeating on [days]
     * when there are any; false when no app took it.
     */
    fun set(hour: Int, minute: Int, label: String, days: List<DayOfWeek>): Boolean

    /** Starts a countdown timer without showing the clock app (AlarmClock.ACTION_SET_TIMER); false when no app took it. */
    fun timer(seconds: Int, label: String): Boolean

    /** Whether this app is on screen, which is when Android surely lets it reach the clock app. */
    fun inFront(): Boolean = false
}

/**
 * next_alarm, set_alarm and set_timer. Setting asks first ([confirm] is the confirmation card, or the user's own
 * words), then checks that the clock app really set the alarm: a blocked background launch or a clock app that
 * ignores the intent does not throw, so the next alarm is read again ([wait] between reads). A timer cannot be
 * read back: it counts as started only when the request went out from the foreground, and otherwise the reply
 * says it was only requested.
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

    /** [days] makes it a repeating alarm; [confirm] gets the card's summary and answers whether to go ahead. */
    suspend fun set(
        hour: Int,
        minute: Int,
        label: String,
        days: List<DayOfWeek> = emptyList(),
        confirm: suspend (String) -> Boolean,
    ): PhoneReply {
        if (hour !in 0..23 || minute !in 0..59) return PhoneReply.Failed("hour must be 0-23 and minute 0-59")
        val name = clean(label)
        val onDays = days.distinct().sorted()
        val time = hm(hour, minute)
        if (!confirm(alarmSummary(time, onDays, name))) return PhoneReply.Cancelled
        // When this alarm rings first: only then can the phone's "next alarm" show it.
        val first = firstRing(hour, minute, onDays)
        // An alarm already due at that very minute looks the same afterwards, so it cannot prove the new one.
        val already = source.next()?.let { sameMinute(it, first) } == true
        if (!source.set(hour, minute, name, onDays)) return PhoneReply.Failed("알람을 맞출 시계 앱이 없어요")
        val what = listOfNotNull(onDays.takeIf { it.isNotEmpty() }?.let { daysText(it, korean = true) }, name.ifEmpty { null })
            .joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
        if (already) {
            return PhoneReply.Ok(
                "요청함: 시계 앱에 $time 알람을 맞춰 달라고 했어요$what. 같은 시각에 울릴 알람이 이미 있어서 새로 맞춰졌는지는 " +
                    "여기서 구분할 수 없어요. 다시 맞추지 말고, 요청했다는 것과 같은 시각의 알람이 이미 있었다는 것을 사용자에게 그대로 알려 주세요.",
            )
        }
        var next: ZonedDateTime? = null
        repeat(VERIFY_TRIES) {
            wait(VERIFY_INTERVAL_MS)
            next = source.next()?.withZoneSameInstant(now().zone)
            if (next?.let { sameMinute(it, first) } == true) return PhoneReply.Ok("OK: $time 알람을 맞췄어요$what")
        }
        // Not seen. Never retry: the alarm may be there, and a second request makes a duplicate.
        val earlier = next?.takeIf { it.isBefore(first) }
        if (earlier != null) {
            return PhoneReply.Ok(
                "요청함: 시계 앱에 $time 알람을 맞춰 달라고 했어요$what. 더 이른 알람(${hm(earlier.hour, earlier.minute)})이 먼저 울리기 때문에 " +
                    "맞춰졌는지는 여기서 확인할 수 없어요. 다시 맞추지 말고, 요청했다는 것과 확인은 못 했다는 것을 사용자에게 그대로 알려 주세요.",
            )
        }
        return PhoneReply.Ok(
            "확인 못 함: 시계 앱에 $time 알람을 요청했지만 맞춰졌는지 확인하지 못했어요. 다시 맞추지 말고, " +
                "사용자에게 시계 앱에서 확인해 달라고 하세요.",
        )
    }

    /** Starts a countdown of [seconds]; [confirm] as in [set]. */
    suspend fun timer(seconds: Int, label: String, confirm: suspend (String) -> Boolean): PhoneReply {
        if (seconds !in 1..MAX_TIMER_SECONDS) return PhoneReply.Failed("seconds must be 1-$MAX_TIMER_SECONDS")
        val name = clean(label)
        if (!confirm(timerSummary(seconds, name))) return PhoneReply.Cancelled
        // Read before the request: the clock app may come to the front after it.
        val inFront = source.inFront()
        if (!source.timer(seconds, name)) return PhoneReply.Failed("타이머를 시작할 시계 앱이 없어요")
        val length = duration(seconds, korean = true)
        val named = if (name.isEmpty()) "" else " ($name)"
        if (inFront) return PhoneReply.Ok("OK: $length 타이머를 시작했어요$named")
        return PhoneReply.Ok(
            "요청함: 시계 앱에 $length 타이머를 시작해 달라고 했어요$named. 이 앱이 화면에 없을 때 보낸 요청이라 시작됐는지는 여기서 확인할 수 없어요. " +
                "다시 시작하지 말고, 요청했다는 것과 확인은 못 했다는 것을 사용자에게 그대로 알려 주세요.",
        )
    }

    private fun clean(label: String) = label.replace(Regex("\\s+"), " ").trim().take(MAX_LABEL)

    private fun sameMinute(a: ZonedDateTime, b: ZonedDateTime) = a.toEpochSecond() / 60 == b.toEpochSecond() / 60

    private fun firstRing(hour: Int, minute: Int, days: List<DayOfWeek>): ZonedDateTime {
        val current = now()
        var at = current.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!at.isAfter(current)) at = at.plusDays(1)
        if (days.isNotEmpty()) while (at.dayOfWeek !in days) at = at.plusDays(1)
        return at
    }

    companion object {
        const val MAX_LABEL = 40
        const val MAX_TIMER_SECONDS = 86_400
        private const val VERIFY_TRIES = 6
        private const val VERIFY_INTERVAL_MS = 500L
        private val WEEKDAYS = DayOfWeek.values().take(5)
        private val WEEKEND = listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        /** Starts the card summary of an alarm (see confirmCopy): the time, then the repeat days and the label, one line each. */
        val ALARM_PREFIX get() = "" + tr("Set alarm: ", "알람 설정: ")

        /** Starts the card summary of a timer: the length, then the label on its own line. */
        val TIMER_PREFIX get() = "" + tr("Start timer: ", "타이머 시작: ")

        fun alarmSummary(time: String, days: List<DayOfWeek>, label: String) =
            "$ALARM_PREFIX$time\n${if (days.isEmpty()) "" else tr(daysText(days, korean = false), daysText(days, korean = true))}\n$label"

        fun timerSummary(seconds: Int, label: String) =
            "$TIMER_PREFIX${tr(duration(seconds, korean = false), duration(seconds, korean = true))}\n$label"

        private fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)

        /** "매일", "평일", "주말" or "매주 월, 수". */
        fun daysText(days: List<DayOfWeek>, korean: Boolean): String = when {
            days.size == 7 -> if (korean) "매일" else "Every day"
            days == WEEKDAYS -> if (korean) "평일" else "Weekdays"
            days == WEEKEND -> if (korean) "주말" else "Weekends"
            else -> {
                val names = days.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, if (korean) Locale.KOREAN else Locale.ENGLISH) }
                if (korean) "매주 $names" else "Every $names"
            }
        }

        /** "1시간 5분 30초" / "1 h 5 min 30 s", without the parts that are zero. */
        fun duration(seconds: Int, korean: Boolean): String {
            val parts = listOf(seconds / 3600 to (if (korean) "시간" else " h"), seconds % 3600 / 60 to (if (korean) "분" else " min"), seconds % 60 to (if (korean) "초" else " s"))
            return parts.filter { it.first > 0 }.joinToString(" ") { "${it.first}${it.second}" }
        }
    }
}
