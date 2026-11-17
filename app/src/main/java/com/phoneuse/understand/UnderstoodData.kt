package com.phoneuse.understand

import android.content.Context
import com.phoneuse.chat.ChatDb
import java.time.Instant
import java.time.ZonedDateTime

/** A payment as the user spent it: all its notices counted once, less what was cancelled. */
data class PaymentRecord(val id: Long, val merchant: String?, val amountMinor: Long, val currency: String, val at: Long)

/** A refund whose original payment was not found; shown apart, never taken off the spending. */
data class RefundRecord(val amountMinor: Long, val currency: String, val at: Long)

/** What the analysis stored for one notification: its judgment, and the payment or parcel it belongs to. */
data class NoticeFact(val j: NoticeJudgment, val moneyEvent: Long?, val parcelId: Long?)

/** A parcel and the furthest stage its notices reported; [at] is its latest notice. */
data class ParcelRecord(
    val id: Long,
    val carrier: String?,
    val tracking: String?,
    val itemHint: String?,
    val stage: ParcelStage,
    val at: Long,
    val deliveredAt: Long?,
)

/** What the screens, nudges and widgets read of the understood notifications. Blocking calls: run them off the main thread. */
class UnderstoodData(private val sql: SqlPort) {
    /** Spending since [since], oldest first: spend events with their cancellations taken off; fully cancelled ones are left out. */
    fun payments(since: Long): List<PaymentRecord> = sql.query(
        "SELECT id, merchant, amount - cancelled_amount, currency, first_at FROM money_events " +
            "WHERE kind = 'spend' AND first_at >= ? AND amount > cancelled_amount ORDER BY first_at, id",
        listOf(since),
    ) { r -> PaymentRecord(r.long(0)!!, r.string(1), r.long(2)!!, r.string(3)!!, r.long(4)!!) }

    /** Refunds since [since] that cancelled no stored payment. */
    fun refunds(since: Long): List<RefundRecord> = sql.query(
        "SELECT amount, currency, first_at FROM money_events WHERE kind = 'refund' AND first_at >= ? ORDER BY first_at",
        listOf(since),
    ) { r -> RefundRecord(r.long(0)!!, r.string(1)!!, r.long(2)!!) }

    /** Parcels heard of in the last [RECENT_PARCEL_MS], newest first. */
    fun parcels(now: Long): List<ParcelRecord> = sql.query(
        "SELECT id, carrier, tracking, item_hint, stage, updated_at, delivered_at FROM parcels WHERE updated_at >= ? ORDER BY updated_at DESC",
        listOf(now - RECENT_PARCEL_MS),
    ) { r ->
        ParcelStage.of(r.string(4))?.let { ParcelRecord(r.long(0)!!, r.string(1), r.string(2), r.string(3), it, r.long(5)!!, r.long(6)) }
    }.filterNotNull()

    /** The judgments of the analysed notifications among [keys]. */
    fun facts(keys: Collection<String>): Map<String, NoticeFact> {
        if (keys.isEmpty()) return emptyMap()
        return keys.distinct().chunked(MAX_ARGS).flatMap { chunk ->
            sql.query(
                "SELECT key, kind, amount, currency, merchant, instrument, sender, needs_reply, summary, money_event, parcel_id FROM notification_facts " +
                    "WHERE status = 'done' AND key IN (${chunk.joinToString(",") { "?" }})",
                chunk,
            ) { r ->
                val kind = NoticeKind.of(r.string(1)) ?: return@query null
                val sender = Sender.of(r.string(6)) ?: Sender.SYSTEM
                val j = NoticeJudgment(0, kind, r.long(2), r.string(3), r.string(4), r.string(5), null, null, null, sender, r.long(7) == 1L, r.string(8).orEmpty())
                r.string(0)!! to NoticeFact(j, r.long(9), r.long(10))
            }.filterNotNull()
        }.toMap()
    }

    fun pendingCount(): Int =
        sql.query("SELECT COUNT(*) FROM notification_facts WHERE status = 'pending'") { it.long(0) ?: 0 }.single().toInt()

    /** When the stored money history starts, or null when there is none. */
    fun coverageStart(): Long? = sql.query("SELECT MIN(first_at) FROM money_events") { it.long(0) }.firstOrNull()

    companion object {
        fun of(context: Context): UnderstoodData = UnderstoodData(ChatDbSqlPort(ChatDb.get(context.applicationContext)))

        private const val RECENT_PARCEL_MS = 7 * 24 * 60 * 60_000L
        private const val MAX_ARGS = 500

        /**
         * Parcels that are today's news, newest first: out for delivery or waiting for pickup with a notice today,
         * or delivered today. The Today tile and the morning briefing count the same ones.
         */
        fun parcelsToday(parcels: List<ParcelRecord>, now: ZonedDateTime): List<ParcelRecord> {
            val today = now.toLocalDate()
            fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(now.zone).toLocalDate()
            return parcels.filter { p ->
                when (p.stage) {
                    ParcelStage.OUT_FOR_DELIVERY, ParcelStage.PICKUP_READY -> day(p.at) == today
                    ParcelStage.DELIVERED -> p.deliveredAt?.let(::day) == today
                    else -> false
                }
            }.sortedByDescending { it.at }
        }
    }
}
