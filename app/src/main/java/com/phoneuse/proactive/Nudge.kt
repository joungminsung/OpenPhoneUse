package com.phoneuse.proactive

import com.phoneuse.cards.CardAction
import com.phoneuse.i18n.tr
import kotlinx.serialization.Serializable
import java.time.LocalTime

/**
 * The kinds of "알아서 챙기기", one per switch on the board NudgeSettings, in its order. [label] is the switch and
 * the notification's header ("OpenPhoneUse · 아침 브리핑"); [sub] the switch's second line.
 */
@Serializable
enum class NudgeKind(private val labelEn: String, private val labelKo: String, private val subEn: String, private val subKo: String) {
    BRIEFING("Morning briefing", "아침 브리핑", "Weather · schedule · parcels · overnight notifications", "날씨 · 일정 · 택배 · 밤사이 알림"),
    LEAVE(
        "Time to leave", "출발 알림",
        "For events with a place, 10 minutes before you need to go · places are looked up on OpenStreetMap",
        "장소가 있는 일정, 나갈 시간 10분 전 · 장소는 OpenStreetMap에서 찾아요",
    ),
    REPLY("Unanswered reply", "답장 잊음", "When a 1:1 message has sat for over 3 hours", "1:1 메시지가 3시간 넘게 그대로일 때"),
    ANNIVERSARY("Anniversaries", "기념일", "Birthdays and anniversaries you saved, 3 days before and the morning of", "기억해 둔 생일·기념일 3일 전, 당일 아침"),
    DELIVERY("Parcels · payments", "택배 · 결제", "Arrivals right away, payments as a daily total at 9 PM", "도착은 바로, 결제는 밤 9시에 하루 합계"),
    UNUSUAL_PAYMENT("Unusual payment", "평소와 다른 결제", "Right away when it is over 3x the usual at the same place", "같은 곳 평균의 3배가 넘으면 바로"),
    TODO("To-do deadline", "할 일 기한", "Things you said, like \"I have to do ~ by tomorrow\"", "“내일까지 ~해야 해”라고 말한 것"),
    ROUTINE("Repeated request → routine suggestion", "반복하는 부탁 → 루틴 제안", "When you make the same request more than 3 times a week", "같은 부탁을 일주일에 3번 넘게 하면");

    val label: String get() = tr(labelEn, labelKo)
    val sub: String get() = tr(subEn, subKo)
}

/**
 * A nudge button. [prompt] starts a card-originated run (never the user's authorization to send); [lat]/[lng]
 * open a map app; a button with neither just closes the nudge ("나중에"). Prompts never quote third-party text.
 */
@Serializable
data class NudgeAction(
    val label: String,
    val prompt: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val place: String? = null,
) {
    /** What the button does as a card action; null for a plain "나중에". */
    fun cardAction(): CardAction? = when {
        prompt != null -> CardAction.Prompt(prompt)
        lat != null && lng != null -> CardAction.Navigate(lat, lng, place.orEmpty())
        place != null -> CardAction.OpenUrl("https://www.google.com/maps/search/?api=1&query=" + java.net.URLEncoder.encode(place, "UTF-8"))
        else -> null
    }

    companion object {
        fun prompt(label: String, text: String) = NudgeAction(label, prompt = text)

        fun later() = NudgeAction(tr("Later", "나중에"))
    }
}

/**
 * Something the app tells the user before being asked: a notification on the "알아서 챙기기" channel (with at most
 * two of [actions] as buttons) and a card at the top of the home screen. It is shown from [dueAt] until
 * [expiresAt]; [dedupeKey] is never shown twice. [id] is the dedupe key.
 */
@Serializable
data class Nudge(
    val kind: NudgeKind,
    val title: String,
    val body: String,
    val actions: List<NudgeAction>,
    val dueAt: Long,
    val expiresAt: Long,
    val dedupeKey: String,
) {
    val id: String get() = dedupeKey

    companion object {
        /** Notification buttons. */
        const val MAX_BUTTONS = 2
    }
}

/** What became of a sent nudge: tapped ([OPENED]), swiped away, closed or left to expire ([IGNORED]). */
@Serializable
enum class NudgeOutcome { PENDING, OPENED, IGNORED }

/** A nudge as the log keeps it. */
@Serializable
data class SentNudge(
    val nudge: Nudge,
    val sentAt: Long,
    val outcome: NudgeOutcome = NudgeOutcome.PENDING,
    val resolvedAt: Long? = null,
) {
    val kind: NudgeKind get() = nudge.kind
    val dedupeKey: String get() = nudge.dedupeKey
}

/**
 * The board NudgeSettings: which kinds are on, the briefing time, quiet hours and the daily cap. The quiet hours
 * are the app-wide settings "방해하지 않는 시간" (SettingsStore.quietHours), applied with [withQuiet].
 */
data class NudgeSettings(
    val enabled: Set<NudgeKind> = NudgeKind.entries.toSet(),
    val briefingAt: LocalTime = DEFAULT_BRIEFING,
    val quietStart: LocalTime = DEFAULT_QUIET_START,
    val quietEnd: LocalTime = DEFAULT_QUIET_END,
    val maxPerDay: Int = DEFAULT_MAX_PER_DAY,
) {
    /** With settings "방해하지 않는 시간" (minutes of the day; off means no quiet hours). */
    fun withQuiet(on: Boolean, startMin: Int, endMin: Int): NudgeSettings =
        if (!on) copy(quietStart = LocalTime.MIDNIGHT, quietEnd = LocalTime.MIDNIGHT)
        else copy(quietStart = LocalTime.of(startMin / 60, startMin % 60), quietEnd = LocalTime.of(endMin / 60, endMin % 60))

    /** Inside the quiet hours (they may wrap past midnight; equal ends mean none). */
    fun isQuiet(t: LocalTime): Boolean = when {
        quietStart == quietEnd -> false
        quietStart < quietEnd -> t >= quietStart && t < quietEnd
        else -> t >= quietStart || t < quietEnd
    }

    companion object {
        val DEFAULT_BRIEFING: LocalTime = LocalTime.of(7, 30)
        val DEFAULT_QUIET_START: LocalTime = LocalTime.of(23, 0)
        val DEFAULT_QUIET_END: LocalTime = LocalTime.of(7, 0)
        const val DEFAULT_MAX_PER_DAY = 6
    }
}
