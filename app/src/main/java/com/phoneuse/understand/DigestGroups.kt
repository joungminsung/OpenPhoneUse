package com.phoneuse.understand

import com.phoneuse.i18n.tr
import com.phoneuse.phone.StoredNotification

/** The groups of the board "Digest", in its order; [id] is the stable name, [tone] the digest card's group tone. */
enum class DigestBucket(val id: String, val tone: String) {
    REPLY("reply", "urgent"),
    MONEY("money", "money"),
    ARRIVAL("arrival", "info"),
    PENDING("pending", "muted"),
    OTHER("other", "muted"),
    ;

    fun label(): String = when (this) {
        REPLY -> tr("Needs a reply", "답장 필요")
        MONEY -> tr("Money", "돈")
        ARRIVAL -> tr("Parcels", "택배")
        PENDING -> tr("Being sorted", "정리 중")
        OTHER -> tr("Everything else", "나머지")
    }

    companion object {
        fun of(id: String?): DigestBucket? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Sorts notifications by what the analysis understood of them ([NoticeFact]); nothing here reads their text. A
 * notification not analysed yet waits in [DigestBucket.PENDING]; with analysis off nothing is grouped at all.
 */
object DigestGroups {
    /** A person wrote and waits for an answer, and the notification is still in the shade. */
    fun needsReply(n: StoredNotification, f: NoticeFact?): Boolean =
        n.removedAt == null && f != null && f.j.sender == Sender.PERSON && f.j.needsReply

    /**
     * Non-empty groups in board order, each in the given (newest-first) order. A message already gone from the shade
     * needs no reply and goes to the rest. With [analysisOn] false everything is one [DigestBucket.OTHER] list.
     */
    fun group(list: List<StoredNotification>, facts: Map<String, NoticeFact>, analysisOn: Boolean): Map<DigestBucket, List<StoredNotification>> {
        if (!analysisOn) return if (list.isEmpty()) emptyMap() else mapOf(DigestBucket.OTHER to list)
        val by = list.groupBy { n ->
            val f = facts[n.key]
            when {
                f == null -> DigestBucket.PENDING
                needsReply(n, f) -> DigestBucket.REPLY
                f.j.kind.isMoney -> DigestBucket.MONEY
                f.j.kind == NoticeKind.PARCEL -> DigestBucket.ARRIVAL
                else -> DigestBucket.OTHER
            }
        }
        return DigestBucket.entries.mapNotNull { b -> by[b]?.let { b to it } }.toMap(LinkedHashMap())
    }

    /** Spending among [items] in the currency most of them use, each payment once however many notices it had; null when none. */
    fun spendTotal(items: List<StoredNotification>, facts: Map<String, NoticeFact>): Pair<Long, String>? {
        val spends = items.mapNotNull { facts[it.key] }.filter { it.j.kind == NoticeKind.SPEND && it.j.amountMinor != null && it.j.currency != null }
        if (spends.isEmpty()) return null
        val currency = spends.groupingBy { it.j.currency!! }.eachCount().maxBy { it.value }.key
        val total = spends.filter { it.j.currency == currency }.distinctBy { it.moneyEvent ?: -it.hashCode().toLong() }.sumOf { it.j.amountMinor!! }
        return total to currency
    }

    /** The one line above the digest saying why it may be incomplete, most important first; null when nothing to say. */
    fun notice(analysisOn: Boolean, hasModel: Boolean, limitReached: Boolean, pending: Int): String? = when {
        !analysisOn -> tr("Notification analysis is off", "알림 분석이 꺼져 있어요")
        !hasModel -> tr("Connect an AI model to sort your notifications", "AI 모델을 연결하면 알림을 정리해 드려요")
        limitReached -> tr("Today's analysis limit is reached", "오늘 분석 한도에 도달했어요")
        pending > 0 -> tr("Sorting $pending", "${pending}개 정리 중")
        else -> null
    }
}
