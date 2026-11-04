package com.phoneuse.routine

import com.phoneuse.i18n.tr
import java.time.DayOfWeek

/** The builder's "언제" chips (board RoutineBuilder). */
enum class TriggerKind(private val en: String, private val ko: String) {
    TIME("Time", "시간"),
    PLACE("Place", "장소"),
    NOTIFICATION("On notification", "알림이 오면"),
    DEVICE("Charger, earphones, car", "충전·이어폰·차"),
    BEFORE_EVENT("Before event", "일정 전");

    val label: String get() = tr(en, ko)
}

/** The builder's "할 일" kinds. */
enum class ActionKind(private val en: String, private val ko: String) {
    ASK("Ask", "부탁하기"),
    NOTIFY("Notify only", "알림만"),
    OPEN_APP("Open app", "앱 열기");

    val label: String get() = tr(en, ko)
}

/**
 * What the routine builder edits: every trigger's fields at once, so switching chips keeps what was typed, and
 * a place may still be unpicked. [toRoutine] checks it with the same rules as routine JSON. Editing a stored
 * routine keeps its id, state and the conditions the builder has no control for.
 */
data class RoutineDraft(
    val title: String = "",
    val trigger: TriggerKind = TriggerKind.TIME,
    val hour: Int = 8,
    val minute: Int = 0,
    val timeDays: Set<DayOfWeek> = emptySet(),
    val placeName: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val radiusM: Int = Routine.DEFAULT_RADIUS_M,
    val edge: PlaceEdge = PlaceEdge.ENTER,
    val notifPackage: String = "",
    val notifKeyword: String = "",
    val deviceKind: DeviceKind = DeviceKind.CHARGER_CONNECTED,
    val deviceDetail: String = "",
    val eventMinutes: Int = 60,
    val weekdaysOnly: Boolean = false,
    val onceADay: Boolean = false,
    val onlyUnlocked: Boolean = false,
    val action: ActionKind = ActionKind.ASK,
    val prompt: String = "",
    val needsDevice: Boolean = false,
    val steps: List<RoutineStep> = emptyList(),
    val notifyText: String = "",
    val appPackage: String = "",
    val appLabel: String = "",
    val sendWithoutConfirm: Boolean = false,
    val sendTo: String = "",
    val sendBody: String = "",
    val sentence: String? = null,
    val base: Routine? = null,
) {
    val hasPlace: Boolean get() = lat != null && lng != null

    fun toRoutine(): RoutineParse {
        if (trigger == TriggerKind.PLACE && !hasPlace) return RoutineParse.Error(tr("Pick a place", "장소를 골라 주세요"))
        val skipConfirm = sendWithoutConfirm && action == ActionKind.ASK
        if (skipConfirm && (sendTo.isBlank() || sendBody.isBlank())) {
            return RoutineParse.Error(tr("To send without confirming, enter a recipient and a message", "확인 없이 보내려면 받는 사람과 보낼 내용을 적어 주세요"))
        }
        val t: Trigger = when (trigger) {
            TriggerKind.TIME -> Trigger.Time(hour, minute, timeDays)
            TriggerKind.PLACE -> Trigger.Place(placeName.trim().ifEmpty { tr("Place", "장소") }, lat!!, lng!!, radiusM, edge)
            TriggerKind.NOTIFICATION -> Trigger.Notification(notifPackage.trim().ifEmpty { null }, notifKeyword.trim().ifEmpty { null })
            TriggerKind.DEVICE -> Trigger.Device(deviceKind, deviceDetail.trim().ifEmpty { null })
            TriggerKind.BEFORE_EVENT -> Trigger.BeforeEvent(eventMinutes)
        }
        val a: RoutineAction = when (action) {
            ActionKind.ASK -> RoutineAction.Ask(prompt.trim(), needsDevice, steps)
            ActionKind.NOTIFY -> RoutineAction.Notify(notifyText.trim())
            ActionKind.OPEN_APP -> RoutineAction.OpenApp(appPackage.trim(), appLabel.trim().ifEmpty { null })
        }
        val old = base?.conditions ?: Conditions()
        val conditions = old.copy(
            days = if (weekdaysOnly) WEEKDAYS else old.days?.takeIf { it != WEEKDAYS },
            onlyWhenUnlocked = onlyUnlocked,
            cooldownMin = when {
                onceADay -> Conditions.ONCE_A_DAY
                old.cooldownMin >= Conditions.ONCE_A_DAY -> Conditions.DEFAULT_COOLDOWN_MIN
                else -> old.cooldownMin
            },
        )
        val draft = (base ?: Routine(title = "", trigger = t, action = a)).copy(
            title = title.trim().ifEmpty { defaultTitle(t, a) },
            trigger = t,
            conditions = conditions,
            action = a,
            sendWithoutConfirm = skipConfirm,
            sendTo = sendTo.trim().takeIf { skipConfirm },
            sendBody = sendBody.trim().takeIf { skipConfirm },
            sentence = sentence ?: base?.sentence,
        )
        // The same checks as routine JSON (radius, package names, lengths…), then the stored fields back.
        return when (val checked = RoutineJson.parse(RoutineJson.encode(draft), fromModel = false)) {
            is RoutineParse.Ok -> RoutineParse.Ok(checked.routine)
            is RoutineParse.Error -> checked
        }
    }

    companion object {
        val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

        fun from(r: Routine): RoutineDraft {
            var d = RoutineDraft(
                title = r.title,
                weekdaysOnly = r.conditions.days == WEEKDAYS,
                onceADay = r.conditions.cooldownMin >= Conditions.ONCE_A_DAY,
                onlyUnlocked = r.conditions.onlyWhenUnlocked,
                sendWithoutConfirm = r.sendWithoutConfirm,
                sendTo = r.sendTo.orEmpty(),
                sendBody = r.sendBody.orEmpty(),
                sentence = r.sentence,
                base = r,
            )
            d = when (val t = r.trigger) {
                is Trigger.Time -> d.copy(trigger = TriggerKind.TIME, hour = t.hour, minute = t.minute, timeDays = t.days)
                is Trigger.Place -> d.copy(trigger = TriggerKind.PLACE, placeName = t.name, lat = t.lat, lng = t.lng, radiusM = t.radiusM, edge = t.on)
                is Trigger.Notification -> d.copy(trigger = TriggerKind.NOTIFICATION, notifPackage = t.packageName.orEmpty(), notifKeyword = t.keyword.orEmpty())
                is Trigger.Device -> d.copy(trigger = TriggerKind.DEVICE, deviceKind = t.kind, deviceDetail = t.detail.orEmpty())
                is Trigger.BeforeEvent -> d.copy(trigger = TriggerKind.BEFORE_EVENT, eventMinutes = t.minutes)
            }
            return when (val a = r.action) {
                is RoutineAction.Ask -> d.copy(action = ActionKind.ASK, prompt = a.prompt, needsDevice = a.needsDevice, steps = a.steps)
                is RoutineAction.Notify -> d.copy(action = ActionKind.NOTIFY, notifyText = a.text)
                is RoutineAction.OpenApp -> d.copy(action = ActionKind.OPEN_APP, appPackage = a.packageName, appLabel = a.label.orEmpty())
            }
        }

        private fun defaultTitle(t: Trigger, a: RoutineAction): String = when (a) {
            is RoutineAction.Notify -> a.text
            is RoutineAction.OpenApp -> tr("Open ${a.label ?: "app"}", "${a.label ?: "앱"} 열기")
            is RoutineAction.Ask -> a.steps.firstOrNull()?.text ?: a.prompt
        }.ifBlank { RoutineText.whenText(t) }.take(Routine.MAX_TITLE)
    }
}

/** A starting point on the routine screen; place templates still need the place picked. */
data class RoutineTemplate(val title: String, val description: String, val draft: RoutineDraft)

/** The eight templates of the routine screen. */
object RoutineTemplates {
    val all: List<RoutineTemplate> get() = listOf(
        RoutineTemplate(
            tr("Morning briefing", "아침 브리핑"), tr("Daily 07:30 · weather, today's schedule, important overnight notifications", "매일 07:30 · 날씨, 오늘 일정, 밤사이 중요한 알림"),
            RoutineDraft(
                title = tr("Morning briefing", "아침 브리핑"), trigger = TriggerKind.TIME, hour = 7, minute = 30,
                prompt = tr("Give me a short briefing: today's weather, today's schedule and the important notifications that came overnight.", "오늘 날씨, 오늘 일정, 밤사이 온 중요한 알림을 짧게 브리핑해줘."),
                steps = listOf(RoutineStep(tr("Weather", "날씨")), RoutineStep(tr("Today's schedule", "오늘 일정")), RoutineStep(tr("Important notifications", "중요한 알림"))),
            ),
        ),
        RoutineTemplate(
            tr("Commute music", "퇴근길 음악"), tr("When I leave work · my usual playlist in the music app", "회사를 떠나면 · 음악 앱에서 늘 듣던 재생목록"),
            RoutineDraft(
                title = tr("Commute music", "퇴근길 음악"), trigger = TriggerKind.PLACE, placeName = tr("Work", "회사"), edge = PlaceEdge.EXIT, weekdaysOnly = true, onceADay = true,
                prompt = tr("Open the music app and play the playlist I listen to most.", "음악 앱을 열고 내가 자주 듣는 재생목록을 틀어줘."), needsDevice = true,
                steps = listOf(RoutineStep(tr("Open the music app", "음악 앱 열기"), true), RoutineStep(tr("Play the playlist", "재생목록 틀기"), true)),
            ),
        ),
        RoutineTemplate(
            tr("Silent mode at work", "회사 도착 시 무음"), tr("When I arrive at work · silent mode, today's meetings", "회사에 도착하면 · 무음, 오늘 회의 알려주기"),
            RoutineDraft(
                title = tr("Work mode", "일 모드"), trigger = TriggerKind.PLACE, placeName = tr("Work", "회사"), edge = PlaceEdge.ENTER, weekdaysOnly = true, onceADay = true,
                prompt = tr("Switch the phone to silent (Do Not Disturb) and tell me when today's meetings are.", "폰을 무음(방해 금지)으로 바꾸고, 오늘 회의 시간을 알려줘."), needsDevice = true,
                steps = listOf(RoutineStep(tr("Turn on silent mode", "무음 켜기"), true), RoutineStep(tr("Tell me today's meeting times", "오늘 회의 시간 알려주기"))),
            ),
        ),
        RoutineTemplate(
            tr("Plan tomorrow when charging", "충전 시작하면 내일 일정 정리"), tr("When charging starts (once a day) · summary of tomorrow's schedule", "충전을 시작하면 (하루 한 번) · 내일 일정 요약"),
            RoutineDraft(
                title = tr("Prep for tomorrow", "내일 준비"), trigger = TriggerKind.DEVICE, deviceKind = DeviceKind.CHARGER_CONNECTED, onceADay = true,
                prompt = tr("Lay out tomorrow's schedule in time order and tell me if there is anything to prepare.", "내일 일정을 시간순으로 정리하고, 준비할 것이 있으면 알려줘."),
                steps = listOf(RoutineStep(tr("Organize tomorrow's schedule", "내일 일정 정리"))),
            ),
        ),
        RoutineTemplate(
            tr("Playlist when earphones connect", "이어폰 연결하면 플레이리스트"), tr("When earphones are plugged in or connected · play my playlist", "이어폰을 꽂거나 연결하면 · 재생목록 틀기"),
            RoutineDraft(
                title = tr("Earphone music", "이어폰 음악"), trigger = TriggerKind.DEVICE, deviceKind = DeviceKind.HEADSET_PLUGGED,
                prompt = tr("Open the music app and play my playlist.", "음악 앱을 열고 내 재생목록을 틀어줘."), needsDevice = true,
                steps = listOf(RoutineStep(tr("Play the playlist", "재생목록 틀기"), true)),
            ),
        ),
        RoutineTemplate(
            tr("Sort parcel messages", "택배 문자 오면 정리"), tr("When a '택배' notification arrives · courier, expected arrival, what to do", "'택배' 알림이 오면 · 택배사, 도착 예정, 할 일"),
            RoutineDraft(
                title = tr("Parcel summary", "택배 정리"), trigger = TriggerKind.NOTIFICATION, notifKeyword = "택배", onceADay = false,
                prompt = tr("Sort out this parcel notification: courier, expected arrival, and what I need to do.", "이 택배 알림을 정리해줘: 택배사, 도착 예정, 내가 할 일."),
                steps = listOf(RoutineStep(tr("Summarize parcel info", "택배 정보 정리"))),
            ),
        ),
        RoutineTemplate(
            tr("Leave-now alert 1 hour before", "일정 1시간 전 출발 알림"), tr("1 hour before an event with a location · route and departure time", "장소 있는 일정 1시간 전 · 가는 길과 출발 시간"),
            RoutineDraft(
                title = tr("Leave-now alert", "출발 알림"), trigger = TriggerKind.BEFORE_EVENT, eventMinutes = 60,
                prompt = tr("Tell me how to get to this event's location and when I should leave.", "이 일정 장소까지 가는 방법과 언제 출발하면 되는지 알려줘."),
                steps = listOf(RoutineStep(tr("Find the route", "가는 길 찾기")), RoutineStep(tr("Tell me when to leave", "출발 시간 알려주기"))),
            ),
        ),
        RoutineTemplate(
            tr("11 PM notification summary", "밤 11시 알림 요약"), tr("Daily 23:00 · summary of today's notifications", "매일 23:00 · 오늘 받은 알림 요약"),
            RoutineDraft(
                title = tr("Notification summary", "알림 요약"), trigger = TriggerKind.TIME, hour = 23, minute = 0,
                prompt = tr("Summarize the notifications I got today. Put the ones that need a reply first.", "오늘 받은 알림을 요약해줘. 답장이 필요한 것을 먼저."),
                steps = listOf(RoutineStep(tr("Only the ones needing a reply, on top", "답장 필요한 것만 위로")), RoutineStep(tr("Summarize the rest", "나머지 요약"))),
            ),
        ),
    )
}
