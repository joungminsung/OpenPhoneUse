package com.phoneuse.widget

import com.phoneuse.i18n.tr
import com.phoneuse.proactive.AskKinds
import com.phoneuse.proactive.AskRecord
import com.phoneuse.proactive.BriefingText
import com.phoneuse.routine.Routine
import com.phoneuse.routine.RoutineAction
import com.phoneuse.routine.Trigger
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.min

/** One chip of the ask-bar widget: [label] is shown, [prompt] is what a tap sends. */
data class AskChip(val label: String, val prompt: String)

/**
 * The ask bar's "what you'd likely ask now" chips. Pure and local: no model call per widget update.
 *
 * In order: the user's own typed asks that come back around this hour (AskLog, already without tainted
 * conversations and our own prompts), then an Ask routine due within the next [ROUTINE_WINDOW_MIN] minutes, then
 * time-of-day defaults. Duplicates (same first sentence, numbers ignored) are dropped.
 */
object AskChips {
    const val COUNT = 3
    const val LABEL_CHARS = 18
    const val ROUTINE_WINDOW_MIN = 90

    /** Asks from at most this many hours away from now's hour of day count as "around this time". */
    private const val HOUR_SPREAD = 2

    fun pick(now: ZonedDateTime, asks: List<AskRecord>, routines: List<Routine>, count: Int = COUNT): List<AskChip> {
        val out = ArrayList<AskChip>()
        val seen = HashSet<String>()
        fun add(chip: AskChip) {
            val key = AskKinds.normalize(chip.prompt)
            if (out.size < count && key.isNotEmpty() && seen.add(key)) out.add(chip)
        }
        habitual(now, asks).take(2).forEach(::add)
        dueRoutine(now, routines)?.let(::add)
        timeOfDay(now.hour).forEach(::add)
        return out
    }

    /**
     * Asks typed around this hour of day, most often first, then most recent. One typed at a similar hour counts;
     * one from any hour counts only when asked at least twice.
     */
    fun habitual(now: ZonedDateTime, asks: List<AskRecord>): List<AskChip> {
        val groups = asks.filter { it.text.isNotBlank() }.groupBy { AskKinds.normalize(it.text) }.filterKeys { it.isNotEmpty() }
        return groups.values.mapNotNull { group ->
            val near = group.count { hourDistance(Instant.ofEpochMilli(it.at).atZone(now.zone).hour, now.hour) <= HOUR_SPREAD }
            if (near == 0 && group.size < 2) return@mapNotNull null
            val latest = group.maxBy { it.at }
            Triple(near * 2 + group.size, latest.at, latest.text.trim())
        }.sortedWith(compareByDescending<Triple<Int, Long, String>> { it.first }.thenByDescending { it.second })
            .map { AskChip(label(it.third), it.third) }
    }

    /** An enabled Ask routine whose time trigger comes within the window: run its prompt early. */
    fun dueRoutine(now: ZonedDateTime, routines: List<Routine>): AskChip? = routines.asSequence()
        .filter { it.enabled && it.action is RoutineAction.Ask }
        .mapNotNull { r ->
            val t = r.trigger as? Trigger.Time ?: return@mapNotNull null
            if (t.days.isNotEmpty() && now.dayOfWeek !in t.days) return@mapNotNull null
            val minutes = (t.hour * 60 + t.minute) - (now.hour * 60 + now.minute)
            if (minutes !in 0..ROUTINE_WINDOW_MIN) return@mapNotNull null
            minutes to r
        }
        .minByOrNull { it.first }
        ?.second?.let { AskChip(label(it.title), (it.action as RoutineAction.Ask).prompt) }

    /** Three defaults for the hour; the user's own asks and routines come before them. */
    fun timeOfDay(hour: Int): List<AskChip> = when (hour) {
        in 5..10 -> listOf(
            AskChip(tr("Today at a glance", "오늘 한눈에"), BriefingText.TODAY_PROMPT),
            AskChip(tr("Weather", "오늘 날씨"), tr("What's the weather today? Do I need an umbrella?", "오늘 날씨 어때? 우산 필요해?")),
            AskChip(tr("Overnight messages", "밤사이 알림"), BriefingText.REPLY_PROMPT),
        )
        in 11..13 -> listOf(
            AskChip(tr("Lunch nearby", "근처 점심"), tr("Recommend a good lunch place near me", "근처 점심 맛집 추천해줘")),
            AskChip(tr("Rest of today", "남은 일정"), tr("What's left on my schedule today?", "오늘 남은 일정 알려줘")),
            AskChip(tr("Needs a reply", "답장 필요"), BriefingText.REPLY_PROMPT),
        )
        in 14..17 -> listOf(
            AskChip(tr("Rest of today", "남은 일정"), tr("What's left on my schedule today?", "오늘 남은 일정 알려줘")),
            AskChip(tr("Needs a reply", "답장 필요"), BriefingText.REPLY_PROMPT),
            AskChip(tr("Parcels", "택배 위치"), tr("Where are my parcels?", "택배 어디쯤 왔어?")),
        )
        in 18..21 -> listOf(
            AskChip(tr("Tomorrow", "내일 일정"), tr("What's on my schedule tomorrow?", "내일 일정 알려줘")),
            AskChip(tr("Dinner nearby", "저녁 메뉴"), tr("Recommend a dinner place near me", "근처 저녁 맛집 추천해줘")),
            AskChip(tr("Today's notifications", "오늘 알림 요약"), tr("Summarize today's notifications", "오늘 알림 요약해줘")),
        )
        else -> listOf(
            AskChip(tr("Alarm for 7", "7시 알람"), tr("Set an alarm for 7 AM tomorrow", "내일 아침 7시에 알람 맞춰줘")),
            AskChip(tr("Tomorrow", "내일 일정"), tr("What's on my schedule tomorrow?", "내일 일정 알려줘")),
            AskChip(tr("Do not disturb", "방해 금지"), tr("Turn on Do Not Disturb until 7 AM", "아침 7시까지 방해 금지 켜줘")),
        )
    }

    /** The prompt's first sentence, cut to [LABEL_CHARS] with an ellipsis. */
    fun label(text: String): String {
        val first = AskKinds.firstSentence(text).ifEmpty { text.trim() }
        return if (first.length <= LABEL_CHARS) first else first.take(LABEL_CHARS - 1).trimEnd() + "…"
    }

    /** Distance between two hours of the day on the 24-hour circle. */
    fun hourDistance(a: Int, b: Int): Int = abs(a - b).let { min(it, 24 - it) }
}
