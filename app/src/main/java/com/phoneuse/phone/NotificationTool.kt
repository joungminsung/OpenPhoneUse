package com.phoneuse.phone

import com.phoneuse.i18n.tr
import com.phoneuse.cards.PermissionCard
import com.phoneuse.understand.DigestBucket
import com.phoneuse.understand.DigestGroups
import com.phoneuse.understand.Money
import com.phoneuse.understand.NoticeFact
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZonedDateTime

/** What a notification or text tool answers; [text] is the tool output. */
sealed interface MessagingReply {
    val text: String

    data class Ok(override val text: String) : MessagingReply

    /**
     * The app lacks an access; the chat shows a [권한 허용] card. [permissions] are runtime permissions; a special
     * access that only a Settings screen grants (notification access) has none and names that page in [settings]
     * (PermissionCard.settings).
     */
    data class PermissionNeeded(val label: String, val permissions: List<String>, val settings: String? = null) : MessagingReply {
        override val text get() = "권한 필요: $label"
    }

    data class Failed(val reason: String) : MessagingReply {
        override val text get() = "ERROR: $reason"
    }

    /** The user declined the confirmation card. */
    data object Cancelled : MessagingReply {
        override val text = "CANCELLED by user"
    }
}

/** The notification history and the listener's reach; Android's is [AndroidNotificationSource]. */
interface NotificationSource {
    /** True while the user has given OpenPhoneUse notification access (Settings › 알림 읽기). */
    fun accessEnabled(): Boolean

    /** Kept notifications of the last [hours], newest first. */
    suspend fun recent(hours: Int): List<StoredNotification>

    /** Removes [keys] from the history and, while the listener is connected, from the notification shade. */
    suspend fun dismiss(keys: List<String>)

    /** What the analysis understood of [keys]; a key not analysed yet is missing. */
    suspend fun facts(keys: Collection<String>): Map<String, NoticeFact> = emptyMap()

    /** Settings "알림 내용 분석". */
    suspend fun analysisOn(): Boolean = true
}

/**
 * notifications_recent and dismiss_notifications. Grouping and judgments are what the background analysis understood
 * ([DigestGroups]). All of it is third-party text (the run is marked so by the executor).
 */
class NotificationTool(private val source: NotificationSource, private val now: () -> ZonedDateTime = ZonedDateTime::now) {
    suspend fun recent(hours: Int): MessagingReply {
        if (!source.accessEnabled()) return ACCESS_NEEDED
        val h = hours.coerceIn(1, MAX_HOURS)
        val list = source.recent(h)
        if (list.isEmpty()) return MessagingReply.Ok("최근 ${h}시간 동안 받은 알림이 없어요.")
        val clock = now()
        val facts = source.facts(list.map { it.key })
        val groups = DigestGroups.group(list, facts, source.analysisOn())
        return MessagingReply.Ok(
            buildJsonObject {
                put("hours", h)
                put("total", groups.values.sumOf { it.size })
                put(
                    "groups",
                    buildJsonArray {
                        for ((bucket, items) in groups) {
                            add(
                                buildJsonObject {
                                    put("label", bucket.label())
                                    put("id", bucket.id)
                                    put("tone", bucket.tone)
                                    put("count", items.size)
                                    // The rest (ads, updates, codes) goes as counts per app and keys only, no text.
                                    if (bucket == DigestBucket.OTHER) {
                                        put("by_app", buildJsonObject { items.groupingBy { it.appLabel }.eachCount().forEach { (app, n) -> put(app, n) } })
                                    }
                                    put("items", buildJsonArray { items.take(MAX_PER_GROUP).forEach { add(item(it, facts[it.key], clock, withText = bucket != DigestBucket.OTHER)) } })
                                },
                            )
                        }
                    },
                )
            }.toString(),
        )
    }

    /** DELETE-confirmed; [confirm] gets the summary for the card. Keys not in the history are ignored. */
    suspend fun dismiss(keys: List<String>, confirm: suspend (String) -> Boolean): MessagingReply {
        if (!source.accessEnabled()) return ACCESS_NEEDED
        val known = source.recent(MAX_HOURS).associateBy { it.key }
        val targets = keys.distinct().mapNotNull { known[it] }
        if (targets.isEmpty()) return MessagingReply.Failed("그 알림을 찾지 못했어요. notifications_recent의 key를 쓰세요.")
        // App and time only: a notification's own title is third-party text and must not word the DELETE card.
        val clock = now()
        val shown = targets.take(MAX_SHOWN).joinToString(", ") { "${it.appLabel.take(MAX_SHOWN_CHARS)} ${clockText(it.postedAt, clock)}" }
        val extra = targets.size - MAX_SHOWN
        val more = if (extra > 0) tr(" (+$extra more)", " (외 ${extra}개)") else ""
        if (!confirm(tr("Clear ${targets.size} notifications: $shown$more", "알림 ${targets.size}개 지우기: $shown$more"))) return MessagingReply.Cancelled
        source.dismiss(targets.map { it.key })
        return MessagingReply.Ok("OK: 알림 ${targets.size}개를 지웠어요.")
    }

    private fun item(n: StoredNotification, f: NoticeFact?, clock: ZonedDateTime, withText: Boolean): JsonObject = buildJsonObject {
        put("key", n.key)
        put("app", n.appLabel)
        put("at", clockText(n.postedAt, clock))
        if (!withText) return@buildJsonObject
        // Rows stored before masking existed are masked here too: codes never reach the model.
        val both = n.title + "\n" + n.text
        put("title", SmsParsers.maskCodes(n.title, both))
        put("text", SmsParsers.maskCodes(n.text, both))
        if (f == null) {
            put("analysis", "pending")
            return@buildJsonObject
        }
        val j = f.j
        put("judgment", buildJsonObject {
            put("kind", j.kind.wire)
            j.amountMinor?.let { a ->
                val currency = j.currency ?: "KRW"
                put("amount", java.math.BigDecimal.valueOf(a, Money.minorDigits(currency)).toPlainString())
                put("currency", currency)
            }
            j.merchant?.let { put("merchant", it) }
            put("sender", j.sender.wire)
            put("needs_reply", j.needsReply)
            if (j.summary.isNotEmpty()) put("summary", j.summary)
        })
    }

    companion object {
        val ACCESS_NEEDED get() = MessagingReply.PermissionNeeded(tr("Read notifications", "알림 읽기"), emptyList(), PermissionCard.SETTINGS_NOTIFICATION_LISTENER)
        const val MAX_HOURS = 7 * 24
        /** Items per group the tool returns; the digest screen shows the same number. */
        const val MAX_PER_GROUP = 30
        private const val MAX_SHOWN = 5
        private const val MAX_SHOWN_CHARS = 40

        /** "19:12" today, "11/01 19:12" another day. */
        fun clockText(at: Long, now: ZonedDateTime): String {
            val t = Instant.ofEpochMilli(at).atZone(now.zone)
            val time = "%02d:%02d".format(t.hour, t.minute)
            return if (t.toLocalDate() == now.toLocalDate()) time else "%02d/%02d %s".format(t.monthValue, t.dayOfMonth, time)
        }
    }
}
