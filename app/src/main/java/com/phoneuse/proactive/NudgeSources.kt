package com.phoneuse.proactive

import com.phoneuse.memory.Confidence
import com.phoneuse.memory.Memory
import com.phoneuse.phone.SmsMessage
import com.phoneuse.phone.SmsParsers
import com.phoneuse.phone.StoredNotification
import com.phoneuse.routine.UpcomingEvent
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil

/** A payment read from a card or bank text: our parsed merchant and amount, at the time it arrived. */
data class PaymentRecord(val merchant: String, val amount: Long, val at: Long)

/**
 * A courier text: one of SmsParsers.PARCEL_STAGES. [key] tells two parcels apart when there is no tracking number:
 * the carrier plus the text's letters without the stage (the item and sender), see [NudgeReaders.parcelKey].
 */
data class ParcelRecord(val key: String, val carrier: String, val tracking: String?, val status: String, val at: Long)

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
) {
    companion object {
        /** The travel map's key of an event. */
        fun leaveKey(e: UpcomingEvent): String = "${e.startsAt}:${e.title.hashCode()}"
    }
}

/** Reads payments and parcels out of notifications and texts (both, de-duplicated: an SMS also posts a notification). */
object NudgeReaders {
    private const val SAME_PAYMENT_MS = 3 * 60_000L

    fun payments(notifications: List<StoredNotification>, sms: List<SmsMessage>): List<PaymentRecord> {
        val all = ArrayList<PaymentRecord>()
        for (n in notifications) {
            val p = SmsParsers.payment(n.text) ?: SmsParsers.payment("${n.title}\n${n.text}") ?: continue
            all += PaymentRecord(p.merchant, p.amount, n.postedAt)
        }
        for (m in sms) {
            if (!m.incoming) continue
            val p = SmsParsers.payment(m.body) ?: continue
            all += PaymentRecord(p.merchant, p.amount, m.at)
        }
        val out = ArrayList<PaymentRecord>()
        for (p in all.sortedBy { it.at }) {
            if (out.any { it.merchant == p.merchant && it.amount == p.amount && abs(it.at - p.at) <= SAME_PAYMENT_MS }) continue
            out += p
        }
        return out
    }

    fun parcels(notifications: List<StoredNotification>, sms: List<SmsMessage>): List<ParcelRecord> {
        val all = ArrayList<ParcelRecord>()
        for (n in notifications) {
            val p = SmsParsers.parcel(n.text) ?: continue
            all += ParcelRecord(parcelKey(p.carrier, p.status, n.text), p.carrier, p.tracking, p.status, n.postedAt)
        }
        for (m in sms) {
            if (!m.incoming) continue
            val p = SmsParsers.parcel(m.body) ?: continue
            all += ParcelRecord(parcelKey(p.carrier, p.status, m.body), p.carrier, p.tracking, p.status, m.at)
        }
        val out = ArrayList<ParcelRecord>()
        for (p in all.sortedBy { it.at }) {
            if (out.any { it.key == p.key && it.status == p.status && it.tracking == p.tracking && abs(it.at - p.at) <= SAME_PAYMENT_MS }) continue
            out += p
        }
        return out
    }

    /**
     * "CJ대한통운:…": the carrier and the text's letters with the stage, numbers, spacing and the SMS "[Web발신]" mark
     * taken out, so a text and its notification share it while two parcels of one courier do not.
     */
    fun parcelKey(carrier: String, status: String, text: String): String =
        carrier + ":" + text.replace("[Web발신]", "").replace(status, "").filter { it.isLetter() }.hashCode().toUInt().toString(16)
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
