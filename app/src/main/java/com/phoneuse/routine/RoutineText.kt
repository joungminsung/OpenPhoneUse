package com.phoneuse.routine

import com.phoneuse.i18n.tr
import java.time.DayOfWeek

/** The routine screen's and preview card's wording, kept pure so it is testable. */
object RoutineText {
    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private val SHORT = mapOf(
        DayOfWeek.MONDAY to "월", DayOfWeek.TUESDAY to "화", DayOfWeek.WEDNESDAY to "수", DayOfWeek.THURSDAY to "목",
        DayOfWeek.FRIDAY to "금", DayOfWeek.SATURDAY to "토", DayOfWeek.SUNDAY to "일",
    )
    private val SHORT_EN = mapOf(
        DayOfWeek.MONDAY to "Mon", DayOfWeek.TUESDAY to "Tue", DayOfWeek.WEDNESDAY to "Wed", DayOfWeek.THURSDAY to "Thu",
        DayOfWeek.FRIDAY to "Fri", DayOfWeek.SATURDAY to "Sat", DayOfWeek.SUNDAY to "Sun",
    )
    private val FULL_EN = mapOf(
        DayOfWeek.MONDAY to "Monday", DayOfWeek.TUESDAY to "Tuesday", DayOfWeek.WEDNESDAY to "Wednesday", DayOfWeek.THURSDAY to "Thursday",
        DayOfWeek.FRIDAY to "Friday", DayOfWeek.SATURDAY to "Saturday", DayOfWeek.SUNDAY to "Sunday",
    )

    fun dayLabel(d: DayOfWeek): String = tr(SHORT_EN.getValue(d), SHORT.getValue(d))

    /** "매일", "평일", "주말", "월요일", "월·수·금". */
    fun days(days: Set<DayOfWeek>): String = when {
        days.isEmpty() || days.size == 7 -> tr("Every day", "매일")
        days == WEEKDAYS -> tr("Weekdays", "평일")
        days == WEEKEND -> tr("Weekends", "주말")
        days.size == 1 -> tr(FULL_EN.getValue(days.first()) + "s", SHORT.getValue(days.first()) + "요일")
        else -> days.sorted().joinToString(tr(", ", "·")) { dayLabel(it) }
    }

    fun clock(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

    /** The "when" line: "23:00 · 매일", "회사에 도착하면", "충전 시작하면", "일정 1시간 전". [appName] names a package. */
    fun whenText(t: Trigger, appName: (String) -> String? = { null }): String = when (t) {
        is Trigger.Time -> "${clock(t.hour, t.minute)} · ${days(t.days)}"
        is Trigger.Place -> if (t.on == PlaceEdge.ENTER) tr("When I arrive at ${t.name}", "${t.name}에 도착하면") else tr("When I leave ${t.name}", "${t.name}${objectParticle(t.name)} 떠나면")
        is Trigger.Notification -> {
            val app = t.packageName?.let { appName(it) ?: it }
            when {
                app != null && t.keyword != null -> tr("$app notification · '${t.keyword}'", "$app 알림 · '${t.keyword}'")
                app != null -> tr("When a $app notification arrives", "$app 알림이 오면")
                else -> tr("When a '${t.keyword}' notification arrives", "'${t.keyword}' 알림이 오면")
            }
        }
        is Trigger.Device -> when (t.kind) {
            DeviceKind.CHARGER_CONNECTED -> tr("When charging starts", "충전 시작하면")
            DeviceKind.BLUETOOTH_CONNECTED -> if (t.detail != null) tr("When '${t.detail}' connects", "'${t.detail}' 연결되면") else tr("When a Bluetooth device connects", "블루투스 기기 연결되면")
            DeviceKind.HEADSET_PLUGGED -> tr("When earphones connect", "이어폰 연결하면")
            DeviceKind.WIFI_CONNECTED -> if (t.detail != null) tr("When Wi-Fi '${t.detail}' connects", "Wi-Fi '${t.detail}' 연결되면") else tr("When Wi-Fi connects", "Wi-Fi 연결되면")
        }
        is Trigger.BeforeEvent -> tr("${minutes(t.minutes)} before an event with a location", "장소 있는 일정 ${minutes(t.minutes)} 전")
    }

    /** "30분", "1시간", "1시간 30분". */
    fun minutes(m: Int): String = when {
        m < 60 -> tr("$m min", "${m}분")
        m % 60 == 0 -> tr(if (m / 60 == 1) "1 hour" else "${m / 60} hours", "${m / 60}시간")
        else -> tr("${m / 60} h ${m % 60} min", "${m / 60}시간 ${m % 60}분")
    }

    /** Chips for the conditions; the default cooldown is not shown. */
    fun conditionChips(c: Conditions): List<String> = buildList {
        c.days?.let { add(if (it == WEEKDAYS) tr("Weekdays only", "평일만") else if (it == WEEKEND) tr("Weekends only", "주말만") else days(it)) }
        if (c.timeFrom != null && c.timeTo != null) add("${RoutineJson.clockText(c.timeFrom)}–${RoutineJson.clockText(c.timeTo)}")
        if (c.onlyWhenUnlocked) add(tr("While unlocked", "잠금 풀려 있을 때"))
        when {
            c.cooldownMin >= Conditions.ONCE_A_DAY -> add(tr("Once a day", "하루 한 번"))
            c.cooldownMin == 0 -> add(tr("Every time", "매번"))
            c.cooldownMin != Conditions.DEFAULT_COOLDOWN_MIN -> add(tr("Once every ${minutes(c.cooldownMin)}", "${minutes(c.cooldownMin)}에 한 번"))
        }
    }

    /** The step list: the action's steps, or one line describing it. */
    fun steps(a: RoutineAction, appName: (String) -> String? = { null }): List<RoutineStep> = when (a) {
        is RoutineAction.Ask -> a.steps.ifEmpty { listOf(RoutineStep(short(a.prompt), a.needsDevice)) }
        is RoutineAction.Notify -> listOf(RoutineStep(tr("Notify: ${short(a.text)}", "알림: ${short(a.text)}")))
        is RoutineAction.OpenApp -> {
            val app = a.label ?: appName(a.packageName) ?: a.packageName
            listOf(RoutineStep(tr("Open $app", "$app 열기"), device = true))
        }
    }

    private fun short(s: String): String {
        val line = s.replace(Regex("\\s+"), " ").trim()
        return if (line.length <= SHORT_CHARS) line else line.take(SHORT_CHARS - 1).trimEnd() + "…"
    }

    /** 을/를 after [word] (Hangul batchim); 를 for anything else. */
    fun objectParticle(word: String): String {
        val last = word.trim().lastOrNull() ?: return "를"
        if (last !in '가'..'힣') return "를"
        return if ((last - '가') % 28 != 0) "을" else "를"
    }

    private const val SHORT_CHARS = 40
}
