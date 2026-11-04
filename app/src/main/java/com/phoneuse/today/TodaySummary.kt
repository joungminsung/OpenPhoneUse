package com.phoneuse.today

import com.phoneuse.i18n.Lang
import com.phoneuse.i18n.tr
import com.phoneuse.phone.DigestBucket
import com.phoneuse.phone.DigestClassifier
import com.phoneuse.phone.StoredNotification
import com.phoneuse.proactive.ParcelRecord
import com.phoneuse.proactive.PaymentRecord
import com.phoneuse.routine.UpcomingEvent
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** What the home "오늘 챙길 것" tiles are read from (the Android side fills it; pure so the summary is testable). */
data class TodaySources(
    val notifications: List<StoredNotification> = emptyList(),
    val parcels: List<ParcelRecord> = emptyList(),
    val payments: List<PaymentRecord> = emptyList(),
    val events: List<UpcomingEvent> = emptyList(),
    val nextAlarm: Long? = null,
    /**
     * Whether [payments] cover this month and last (card texts readable). Without, they are the notification
     * history's last week only, and the spending tile says so.
     */
    val monthCovered: Boolean = true,
)

/** One tile of boards Main and UnfoldedHome. Text is our parsed fields; event titles are shown, never sent anywhere. */
sealed interface TodayTile {
    /** Parcels on their way today: the newest as "CJ대한통운 · 배송 출발" and its stage (SmsParsers.PARCEL_STAGES). */
    data class Parcels(val count: Int, val line: String, val stage: String) : TodayTile

    /** The next alarm within a day: "내일 아침" and "07:00". */
    data class Alarm(val label: String, val time: String) : TodayTile

    /** Today's notifications still in the shade, and the 1:1 messages among them that need a reply. */
    data class Notifications(val unread: Int, val reply: Int) : TodayTile

    /** The next (at most two) timed events left today; [time] is "19:00". */
    data class Events(val items: List<Item>) : TodayTile {
        data class Item(val time: String, val title: String, val startsAt: Long)
    }

    /**
     * Delivery orders (or all spending when there are none) over the span [label] names: this month with the change
     * since last month, or the last seven days from notifications ([diff] null) when the texts cannot be read.
     */
    data class Spending(val label: String, val total: Long, val diff: Long?) : TodayTile
}

/**
 * Board Main "오늘 챙길 것": tiles from parcels (the text parsers), the next alarm, unread notifications and
 * replies, the next two events, and this month's delivery or spending against last month. A tile without data is
 * left out; with none at all the home stays plain.
 */
object TodaySummary {
    /** Food delivery apps and their payment names. */
    private val DELIVERY = Regex("배달의민족|배민|우아한형제들|쿠팡이츠|요기요|땡겨요|위메프오")
    private const val DAY_MS = 24 * 60 * 60_000L

    const val PARCEL_PROMPT_KO = "택배 어디쯤 왔어?"
    const val PARCEL_PROMPT_EN = "Where is my parcel?"
    val PARCEL_PROMPT: String get() = tr(PARCEL_PROMPT_EN, PARCEL_PROMPT_KO)

    /** A parcel stage (SmsParsers.PARCEL_STAGES, kept Korean in the data) as the user reads it. */
    fun stageLabel(stage: String): String = if (Lang.korean) stage else when (stage) {
        "접수" -> "Received"
        "이동 중" -> "In transit"
        "배송 출발" -> "Out for delivery"
        "도착" -> "Delivered"
        else -> stage
    }

    fun build(now: ZonedDateTime, s: TodaySources): List<TodayTile> = listOfNotNull(
        parcels(now, s),
        alarm(now, s),
        notifications(now, s),
        events(now, s),
        spending(now, s),
    )

    private fun parcels(now: ZonedDateTime, s: TodaySources): TodayTile.Parcels? {
        val today = s.parcels.filter { day(it.at, now) == now.toLocalDate() }.sortedByDescending { it.at }
        if (today.isEmpty()) return null
        val newest = today.first()
        return TodayTile.Parcels(today.distinctBy { it.tracking ?: it.key }.size, "${newest.carrier} · ${stageLabel(newest.status)}", newest.status)
    }

    private fun alarm(now: ZonedDateTime, s: TodaySources): TodayTile.Alarm? {
        val at = s.nextAlarm ?: return null
        val nowMs = now.toInstant().toEpochMilli()
        if (at <= nowMs || at - nowMs > DAY_MS) return null
        val t = Instant.ofEpochMilli(at).atZone(now.zone)
        val sameDay = t.toLocalDate() == now.toLocalDate()
        val day = if (sameDay) "오늘" else "내일"
        val period = when (t.hour) {
            in 0..4 -> "새벽"
            in 5..10 -> "아침"
            in 11..16 -> "낮"
            in 17..20 -> "저녁"
            else -> "밤"
        }
        val label = tr(
            when (t.hour) {
                in 0..4 -> if (sameDay) "Early this morning" else "Tomorrow, early morning"
                in 5..10 -> if (sameDay) "This morning" else "Tomorrow morning"
                in 11..16 -> if (sameDay) "This afternoon" else "Tomorrow afternoon"
                in 17..20 -> if (sameDay) "This evening" else "Tomorrow evening"
                else -> if (sameDay) "Tonight" else "Tomorrow night"
            },
            "$day $period",
        )
        return TodayTile.Alarm(label, "%02d:%02d".format(t.hour, t.minute))
    }

    private fun notifications(now: ZonedDateTime, s: TodaySources): TodayTile.Notifications? {
        val today = s.notifications.filter { day(it.postedAt, now) == now.toLocalDate() }
        val unread = today.count { it.removedAt == null }
        val reply = DigestClassifier.group(today)[DigestBucket.REPLY]?.size ?: 0
        if (unread == 0 && reply == 0) return null
        return TodayTile.Notifications(unread, reply)
    }

    private fun events(now: ZonedDateTime, s: TodaySources): TodayTile.Events? {
        val nowMs = now.toInstant().toEpochMilli()
        val left = s.events.filter { !it.allDay && it.startsAt >= nowMs && day(it.startsAt, now) == now.toLocalDate() }
            .sortedBy { it.startsAt }.take(2)
        if (left.isEmpty()) return null
        return TodayTile.Events(
            left.map {
                val t = Instant.ofEpochMilli(it.startsAt).atZone(now.zone)
                TodayTile.Events.Item("%02d:%02d".format(t.hour, t.minute), it.title.trim().take(30), it.startsAt)
            },
        )
    }

    /**
     * This month so far against the same span of last month (1st up to the same day and time, shortened when last
     * month is shorter). Delivery orders when there are any in either month, else all payments. No diff when last
     * month has no payment at all (often just no history on the phone).
     */
    private fun spending(now: ZonedDateTime, s: TodaySources): TodayTile.Spending? {
        if (!s.monthCovered) return lastWeek(now, s)
        val thisStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.zone)
        val lastStart = thisStart.minusMonths(1)
        val lastEnd = lastStart.plus(ChronoUnit.MILLIS.between(thisStart, now), ChronoUnit.MILLIS).let { if (it > thisStart) thisStart else it }
        fun inRange(p: PaymentRecord, from: ZonedDateTime, to: ZonedDateTime) = p.at >= ms(from) && p.at < ms(to)
        val thisMonth = s.payments.filter { p -> p.at >= ms(thisStart) && p.at <= ms(now) }
        val lastMonth = s.payments.filter { inRange(it, lastStart, lastEnd) }
        val delivery = (thisMonth + lastMonth).any { DELIVERY.containsMatchIn(it.merchant) }
        val pick: (List<PaymentRecord>) -> List<PaymentRecord> = { list -> if (delivery) list.filter { DELIVERY.containsMatchIn(it.merchant) } else list }
        val current = pick(thisMonth)
        if (current.isEmpty()) return null
        val previous = pick(lastMonth)
        val total = current.sumOf { it.amount }
        return TodayTile.Spending(
            if (delivery) tr("Delivery this month", "이번 달 배달") else tr("Spending this month", "이번 달 지출"),
            total,
            if (lastMonth.isEmpty()) null else total - previous.sumOf { it.amount },
        )
    }

    /** Only a week of notifications to go on: "최근 7일 지출 (알림 기준)", no comparison. */
    private fun lastWeek(now: ZonedDateTime, s: TodaySources): TodayTile.Spending? {
        val nowMs = ms(now)
        val week = s.payments.filter { nowMs - it.at in 0 until 7 * DAY_MS }
        val delivery = week.filter { DELIVERY.containsMatchIn(it.merchant) }
        val current = delivery.ifEmpty { week }
        if (current.isEmpty()) return null
        return TodayTile.Spending(if (delivery.isNotEmpty()) tr("Delivery, last 7 days (from notifications)", "최근 7일 배달 (알림 기준)") else tr("Spending, last 7 days (from notifications)", "최근 7일 지출 (알림 기준)"), current.sumOf { it.amount }, null)
    }

    private fun day(at: Long, now: ZonedDateTime) = Instant.ofEpochMilli(at).atZone(now.zone).toLocalDate()

    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    /** "₩186,400" and "+₩32,100" / "−₩5,000". */
    fun won(amount: Long): String = "₩" + "%,d".format(java.util.Locale.US, amount)

    fun signedWon(diff: Long): String = (if (diff >= 0) "+" else "−") + won(kotlin.math.abs(diff))
}
