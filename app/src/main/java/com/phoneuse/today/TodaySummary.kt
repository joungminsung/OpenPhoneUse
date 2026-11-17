package com.phoneuse.today

import com.phoneuse.i18n.Lang
import com.phoneuse.i18n.tr
import com.phoneuse.phone.StoredNotification
import com.phoneuse.proactive.ProactiveEngine
import com.phoneuse.understand.DigestGroups
import com.phoneuse.understand.Money
import com.phoneuse.understand.NoticeFact
import com.phoneuse.understand.ParcelRecord
import com.phoneuse.understand.ParcelStage
import com.phoneuse.understand.PaymentRecord
import com.phoneuse.understand.RefundRecord
import com.phoneuse.understand.UnderstoodData
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
    /** Refunds whose payment was not found: shown apart, never taken off the spending. */
    val refunds: List<RefundRecord> = emptyList(),
    /** Notifications still waiting for analysis. */
    val pending: Int = 0,
    /** When the payment history starts (null: none yet); a month that starts later is said to be partial. */
    val coverageStart: Long? = null,
    /** What the analysis understood of [notifications], by key. */
    val facts: Map<String, NoticeFact> = emptyMap(),
)

/** One tile of boards Main and UnfoldedHome. Text is our parsed fields; event titles are shown, never sent anywhere. */
sealed interface TodayTile {
    /** Parcels that are today's news: the newest as "CJ대한통운 · 배송 출발" and its stage. */
    data class Parcels(val count: Int, val line: String, val stage: ParcelStage) : TodayTile

    /** The next alarm within a day: "내일 아침" and "07:00". */
    data class Alarm(val label: String, val time: String) : TodayTile

    /** Today's notifications still in the shade, and the 1:1 messages among them that need a reply. */
    data class Notifications(val unread: Int, val reply: Int) : TodayTile

    /** The next (at most two) timed events left today; [time] is "19:00". */
    data class Events(val items: List<Item>) : TodayTile {
        data class Item(val time: String, val title: String, val startsAt: Long)
    }

    /**
     * This month's spending in [currency] (minor units), the change since the same span of last month ([diff] null
     * without last month's history), refunds shown apart, notifications still being sorted, and [since] when the
     * history starts inside this month.
     */
    data class Spending(
        val label: String,
        val total: Long,
        val currency: String,
        val diff: Long?,
        val refunds: Long = 0,
        val pending: Int = 0,
        val since: Long? = null,
    ) : TodayTile
}

/**
 * Board Main "오늘 챙길 것": tiles from parcels (the text parsers), the next alarm, unread notifications and
 * replies, the next two events, and this month's delivery or spending against last month. A tile without data is
 * left out; with none at all the home stays plain.
 */
object TodaySummary {
    private const val DAY_MS = 24 * 60 * 60_000L

    const val PARCEL_PROMPT_KO = "택배 어디쯤 왔어?"
    const val PARCEL_PROMPT_EN = "Where is my parcel?"
    val PARCEL_PROMPT: String get() = tr(PARCEL_PROMPT_EN, PARCEL_PROMPT_KO)

    fun build(now: ZonedDateTime, s: TodaySources): List<TodayTile> = listOfNotNull(
        parcels(now, s),
        alarm(now, s),
        notifications(now, s),
        events(now, s),
        spending(now, s),
    )

    private fun parcels(now: ZonedDateTime, s: TodaySources): TodayTile.Parcels? {
        val today = UnderstoodData.parcelsToday(s.parcels, now)
        if (today.isEmpty()) return null
        val newest = today.first()
        return TodayTile.Parcels(today.size, "${ProactiveEngine.parcelName(newest)} · ${newest.stage.label()}", newest.stage)
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
        val reply = today.count { DigestGroups.needsReply(it, s.facts[it.key]) }
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
     * month is shorter), in the currency most payments use. No diff when last month has no payment or the history
     * starts this month ([TodayTile.Spending.since]).
     */
    private fun spending(now: ZonedDateTime, s: TodaySources): TodayTile.Spending? {
        val thisStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.zone)
        val lastStart = thisStart.minusMonths(1)
        val lastEnd = lastStart.plus(ChronoUnit.MILLIS.between(thisStart, now), ChronoUnit.MILLIS).let { if (it > thisStart) thisStart else it }
        val thisMonthAll = s.payments.filter { p -> p.at >= ms(thisStart) && p.at <= ms(now) }
        val currency = ProactiveEngine.mainCurrency(thisMonthAll)
        val thisMonth = thisMonthAll.filter { it.currency == currency }
        val refunds = s.refunds.filter { it.currency == currency && it.at >= ms(thisStart) && it.at <= ms(now) }.sumOf { it.amountMinor }
        if (thisMonth.isEmpty() && refunds == 0L) return null
        val lastMonth = s.payments.filter { it.currency == currency && it.at >= ms(lastStart) && it.at < ms(lastEnd) }
        val since = s.coverageStart?.takeIf { it > ms(thisStart) }
        val total = thisMonth.sumOf { it.amountMinor }
        return TodayTile.Spending(
            tr("Spending this month", "이번 달 지출"),
            total,
            currency,
            // Last month is compared only when the history covers all of it (from its 1st).
            if (since != null || lastMonth.isEmpty() || (s.coverageStart ?: Long.MAX_VALUE) > ms(lastStart)) null
            else total - lastMonth.sumOf { it.amountMinor },
            refunds,
            s.pending,
            since,
        )
    }

    private fun day(at: Long, now: ZonedDateTime) = Instant.ofEpochMilli(at).atZone(now.zone).toLocalDate()

    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    /** "+₩32,100" / "−₩5,000". */
    fun signed(diff: Long, currency: String): String = (if (diff >= 0) "+" else "−") + Money.format(kotlin.math.abs(diff), currency)
}
