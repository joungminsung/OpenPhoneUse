package com.phoneuse.understand

import com.phoneuse.phone.StoredNotification
import kotlin.math.abs

sealed interface EventTarget {
    data class Existing(val id: Long) : EventTarget
    data class New(val slot: Int) : EventTarget
}

sealed interface ParcelTarget {
    data class Existing(val id: Long) : ParcelTarget
    data class New(val slot: Int) : ParcelTarget
}

/** One analysed notification and the payment or parcel it belongs to. */
data class FactRow(val key: String, val j: NoticeJudgment, val moneyEvent: EventTarget?, val parcel: ParcelTarget?)

data class NewMoneyEvent(
    val slot: Int,
    val kind: NoticeKind,
    val amountMinor: Long,
    val currency: String,
    val merchant: String?,
    val instrument: String?,
    val at: Long,
    val refundOf: Long?,
    /** Taken off at once: a cancellation that arrived in the same batch. */
    val cancelledMinor: Long = 0,
)

/** One more notice of a stored payment; [merchant] and [instrument] only fill what the event lacks. */
data class MoneyJoin(val id: Long, val at: Long, val merchant: String?, val instrument: String?)

data class Cancellation(val eventId: Long, val amountMinor: Long)

data class ParcelUpdate(val target: ParcelTarget, val info: ParcelInfo, val at: Long)

/** What one analysed batch changes in the store. */
data class MergePlan(
    val facts: List<FactRow>,
    val newEvents: List<NewMoneyEvent>,
    val joins: List<MoneyJoin>,
    val cancels: List<Cancellation>,
    val parcels: List<ParcelUpdate>,
)

/**
 * Turns a batch's judgments into store changes. The model says which notices report the same payment or parcel
 * ([NoticeJudgment.sameAs]); a link is kept only when it is consistent: the same kind, amount and currency within
 * [UnderstandingStore.JOIN_WINDOW_MS] for money, matching tracking numbers for parcels. A refund cancels its original
 * only when that is a spend of at least its amount within [UnderstandingStore.REFUND_WINDOW_MS].
 */
object EventMerger {
    fun plan(batch: List<StoredNotification>, judgments: Map<Int, NoticeJudgment>, recent: List<RecentEvent>): MergePlan {
        val at = batch.map { it.postedAt }
        val money = judgments.values.filter { it.kind.isMoney && it.amountMinor != null && it.currency != null }
        val parcels = judgments.values.filter { it.kind == NoticeKind.PARCEL && it.parcel != null }
        val moneyTarget = HashMap<Int, EventTarget>()
        val parcelTarget = HashMap<Int, ParcelTarget>()
        val newEvents = ArrayList<NewMoneyEvent>()
        val joins = ArrayList<MoneyJoin>()
        val cancels = ArrayList<Cancellation>()
        var slot = 0

        // ---- money -----------------------------------------------------------------------------
        fun sameMoney(a: NoticeJudgment, b: NoticeJudgment) =
            a.kind == b.kind && a.amountMinor == b.amountMinor && a.currency == b.currency && abs(at[a.index] - at[b.index]) <= UnderstandingStore.JOIN_WINDOW_MS
        val recentMoney = recent.filter { it.id < UnderstandingStore.PARCEL_REF_BASE }.associateBy { it.id }
        fun joinsEvent(j: NoticeJudgment, id: Long): Boolean {
            val e = recentMoney[id] ?: return false
            return e.kind == j.kind && e.amountMinor == j.amountMinor && e.currency == j.currency && abs(e.at - at[j.index]) <= UnderstandingStore.JOIN_WINDOW_MS
        }
        fun cancelsEvent(j: NoticeJudgment, id: Long): Boolean {
            val e = recentMoney[id] ?: return false
            return e.kind == NoticeKind.SPEND && e.currency == j.currency && (e.amountMinor ?: 0) >= (j.amountMinor ?: Long.MAX_VALUE) &&
                at[j.index] - e.at in 0..UnderstandingStore.REFUND_WINDOW_MS
        }
        /** A spend of this batch that [j] (a refund) may cancel: earlier, same currency, at least its amount. */
        fun cancelsItem(j: NoticeJudgment, k: Int): Boolean {
            val o = judgments[k] ?: return false
            return o.kind == NoticeKind.SPEND && o.currency == j.currency && (o.amountMinor ?: 0) >= (j.amountMinor ?: Long.MAX_VALUE) &&
                at[j.index] - at[k] in 0..UnderstandingStore.REFUND_WINDOW_MS
        }
        // Refunds last, so a cancellation of a spend in this same batch finds that spend's event.
        val groups = components(money) { a, b -> (a.sameAs as? Ref.Batch)?.index == b.index && sameMoney(a, b) }
            .sortedBy { g -> if (g.first().kind == NoticeKind.REFUND) 1 else 0 }
        for (group in groups) {
            val first = group.minBy { at[it.index] }
            val existing = group.firstNotNullOfOrNull { j -> (j.sameAs as? Ref.Event)?.id?.takeIf { joinsEvent(j, it) } }
            val sameBatch = if (existing == null && first.kind == NoticeKind.REFUND) {
                group.firstNotNullOfOrNull { j -> (j.cancels as? Ref.Batch)?.index?.takeIf { cancelsItem(j, it) }?.let { moneyTarget[it] } }
            } else {
                null
            }
            if (sameBatch != null) {
                when (sameBatch) {
                    is EventTarget.Existing -> cancels += Cancellation(sameBatch.id, first.amountMinor!!)
                    is EventTarget.New -> {
                        val i = newEvents.indexOfFirst { it.slot == sameBatch.slot }
                        val e = newEvents[i]
                        newEvents[i] = e.copy(cancelledMinor = minOf(e.amountMinor, e.cancelledMinor + first.amountMinor!!))
                    }
                }
                group.forEach { moneyTarget[it.index] = sameBatch }
                continue
            }
            val cancelled = if (existing == null && first.kind == NoticeKind.REFUND) {
                group.firstNotNullOfOrNull { j -> (j.cancels as? Ref.Event)?.id?.takeIf { cancelsEvent(j, it) } }
            } else {
                null
            }
            when {
                existing != null -> group.forEach { j ->
                    moneyTarget[j.index] = EventTarget.Existing(existing)
                    joins += MoneyJoin(existing, at[j.index], j.merchant, j.instrument)
                }
                cancelled != null -> {
                    cancels += Cancellation(cancelled, first.amountMinor!!)
                    group.forEach { moneyTarget[it.index] = EventTarget.Existing(cancelled) }
                }
                else -> {
                    val s = slot++
                    newEvents += NewMoneyEvent(
                        s, first.kind, first.amountMinor!!, first.currency!!,
                        group.firstNotNullOfOrNull { it.merchant }, group.firstNotNullOfOrNull { it.instrument }, at[first.index], null,
                    )
                    group.forEach { moneyTarget[it.index] = EventTarget.New(s) }
                }
            }
        }

        // ---- parcels ---------------------------------------------------------------------------
        fun compatible(a: String?, b: String?) = a == null || b == null || a == b
        val recentParcels = recent.filter { it.id >= UnderstandingStore.PARCEL_REF_BASE }
        val updates = ArrayList<ParcelUpdate>()
        for (group in components(parcels) { a, b ->
            val ta = a.parcel!!.tracking
            val tb = b.parcel!!.tracking
            (ta != null && ta == tb) || ((a.sameAs as? Ref.Batch)?.index == b.index && compatible(ta, tb))
        }) {
            val tracking = group.firstNotNullOfOrNull { it.parcel!!.tracking }
            val byRef = group.firstNotNullOfOrNull { j ->
                val id = (j.sameAs as? Ref.Event)?.id ?: return@firstNotNullOfOrNull null
                recentParcels.firstOrNull { it.id == id && compatible(it.tracking, tracking) }
            }
            val byTracking = tracking?.let { t -> recentParcels.firstOrNull { it.tracking == t } }
            val target = (byRef ?: byTracking)?.let { ParcelTarget.Existing(it.id - UnderstandingStore.PARCEL_REF_BASE) } ?: ParcelTarget.New(slot++)
            group.sortedBy { at[it.index] }.forEach { j ->
                parcelTarget[j.index] = target
                updates += ParcelUpdate(target, j.parcel!!, at[j.index])
            }
        }

        val facts = judgments.values.sortedBy { it.index }.map { j -> FactRow(batch[j.index].key, j, moneyTarget[j.index], parcelTarget[j.index]) }
        return MergePlan(facts, newEvents, joins, cancels, updates)
    }

    /** Groups [items] into connected components, where [linked] (checked both ways) is an edge. */
    private fun components(items: List<NoticeJudgment>, linked: (NoticeJudgment, NoticeJudgment) -> Boolean): List<List<NoticeJudgment>> {
        val parent = IntArray(items.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (i in items.indices) for (k in items.indices) {
            if (i != k && (linked(items[i], items[k]) || linked(items[k], items[i]))) parent[find(i)] = find(k)
        }
        return items.indices.groupBy { find(it) }.values.map { ix -> ix.map { items[it] } }
    }
}
