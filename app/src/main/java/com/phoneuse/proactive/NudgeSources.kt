package com.phoneuse.proactive

import com.phoneuse.memory.Confidence
import com.phoneuse.memory.Memory
import com.phoneuse.phone.StoredNotification
import com.phoneuse.routine.UpcomingEvent
import com.phoneuse.understand.NoticeFact
import com.phoneuse.understand.ParcelRecord
import com.phoneuse.understand.PaymentRecord
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import kotlin.math.ceil

/** The first message the user typed in a new conversation (what they asked for), and when. */
data class AskRecord(val text: String, val at: Long)

/** How long getting to an event's place takes, estimated from the straight-line distance. */
data class TravelEstimate(val minutes: Int, val lat: Double, val lng: Double, val meters: Double)

/**
 * Everything the nudges look at, read once per check by the Android side. Pure data so the engine is testable.
 * [notifications] are the last day's (removedAt set once one left the shade); [payments] cover about two
 * months; [events] the next day and a half; [memories] only confirmed ones; [travel] is keyed by [leaveKey].
 */
data class NudgeSources(
    val notifications: List<StoredNotification> = emptyList(),
    val payments: List<PaymentRecord> = emptyList(),
    val parcels: List<ParcelRecord> = emptyList(),
    val events: List<UpcomingEvent> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val asks: List<AskRecord> = emptyList(),
    val travel: Map<String, TravelEstimate> = emptyMap(),
    val nextAlarm: Long? = null,
    /** What the analysis understood of [notifications], by key; one not analysed yet is missing. */
    val facts: Map<String, NoticeFact> = emptyMap(),
) {
    companion object {
        /** The travel map's key of an event. */
        fun leaveKey(e: UpcomingEvent): String = "${e.startsAt}:${e.title.hashCode()}"
    }
}

/** Dates the model wrote into memories ("11월 20일", "2026-11-15", "내일"). */
object MemoryDates {
    private val ISO = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val KOREAN = Regex("""(\d{1,2})\s*월\s*(\d{1,2})\s*일""")
    private val SLASH = Regex("""^\s*(\d{1,2})\s*[/.]\s*(\d{1,2})\s*\.?\s*$""")
    private val LUNAR = Regex("음력")

    /** A yearly date (birthday, anniversary) from the memory's date, else its text. Lunar dates are not read. */
    fun monthDay(m: Memory): MonthDay? {
        if (LUNAR.containsMatchIn(m.date.orEmpty()) || (m.date == null && LUNAR.containsMatchIn(m.text))) return null
        m.date?.let { d -> monthDay(d, slash = true)?.let { return it } }
        return monthDay(m.text, slash = false)
    }

    private fun monthDay(s: String, slash: Boolean): MonthDay? {
        val g = (ISO.find(s)?.groupValues?.drop(2) ?: KOREAN.find(s)?.groupValues?.drop(1) ?: (if (slash) SLASH.find(s)?.groupValues?.drop(1) else null))
            ?: return null
        return runCatching { MonthDay.of(g[0].toInt(), g[1].toInt()) }.getOrNull()
    }

    /** A to-do's due date: ISO, "M월 d일" (on or after the day it was said), or 오늘/내일/모레 from that day. */
    fun due(m: Memory, zone: ZoneId): LocalDate? {
        val said = java.time.Instant.ofEpochMilli(m.createdAt).atZone(zone).toLocalDate()
        val s = m.due ?: return null
        ISO.find(s)?.let { g -> return runCatching { LocalDate.of(g.groupValues[1].toInt(), g.groupValues[2].toInt(), g.groupValues[3].toInt()) }.getOrNull() }
        KOREAN.find(s)?.let { g ->
            val md = runCatching { MonthDay.of(g.groupValues[1].toInt(), g.groupValues[2].toInt()) }.getOrNull() ?: return null
            return nextOn(md, said)
        }
        return when {
            "모레" in s -> said.plusDays(2)
            "내일" in s -> said.plusDays(1)
            "오늘" in s -> said
            else -> null
        }
    }

    /** The first [md] on or after [from] (Feb 29 falls on Feb 28 in other years). */
    fun nextOn(md: MonthDay, from: LocalDate): LocalDate {
        val thisYear = md.atYear(from.year)
        return if (thisYear < from) md.atYear(from.year + 1) else thisYear
    }

    /** Memories the nudges may use: confirmed only, so a guess from someone else's text never reaches the user. */
    fun usable(memories: List<Memory>): List<Memory> = memories.filter { it.confidence == Confidence.SURE }
}

/** "같은 종류 부탁": the first sentence without spacing, punctuation or numbers, plus the time of day. */
object AskKinds {
    private val SENTENCE_END = Regex("[.?!\\n。？！]")
    private val NUMBER = Regex("\\d+")
    private val NOT_WORD = Regex("[^\\p{L}#]")

    fun firstSentence(text: String): String = text.split(SENTENCE_END).firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    fun normalize(text: String): String = firstSentence(text).lowercase().replace(NUMBER, "#").replace(NOT_WORD, "")

    /** 아침 5–11시, 낮 11–17시, 저녁 17–22시, 밤. */
    fun band(hour: Int): String = when (hour) {
        in 5..10 -> "아침"
        in 11..16 -> "낮"
        in 17..21 -> "저녁"
        else -> "밤"
    }
}

/** Travel time from the straight-line distance: walking up to 1.5 km, else a city trip (transit or car). */
object Travel {
    private const val WALK_MAX_M = 1500.0
    private const val WALK_M_PER_MIN = 75.0
    private const val TRIP_BASE_MIN = 15.0
    private const val TRIP_MIN_PER_KM = 2.4
    private const val MAX_MIN = 180

    fun minutes(meters: Double): Int = when {
        meters <= 0 -> 0
        meters <= WALK_MAX_M -> ceil(meters / WALK_M_PER_MIN).toInt()
        else -> ceil(TRIP_BASE_MIN + meters / 1000.0 * TRIP_MIN_PER_KM).toInt()
    }.coerceAtMost(MAX_MIN)
}
