package com.phoneuse.understand

/**
 * The understanding tables of chat.db (v8): one fact per notification with its analysis status, the payments and
 * parcels notices add up to, and the daily call count. Plain blocking calls: run them off the main thread.
 */
class UnderstandingStore(private val sql: SqlPort, private val clock: () -> Long = System::currentTimeMillis) {

    /**
     * A notification was stored. A new one waits [FIRST_WAIT_MS] so a payment's several notices arrive together. One
     * whose content [changed] under the same key (a new message in the same chat) waits [UPDATE_QUIET_MS] after its
     * latest change and is analysed again from scratch; a repost with the same content keeps its fact.
     */
    fun noteArrival(key: String, postedAt: Long, changed: Boolean) {
        val now = clock()
        val exists = sql.query("SELECT 1 FROM notification_facts WHERE key = ?", listOf(key)) { 1 }.isNotEmpty()
        when {
            !exists -> sql.exec(
                "INSERT INTO notification_facts (key, posted_at, status, ready_at) VALUES (?, ?, '$PENDING', ?)",
                listOf(key, postedAt, now + FIRST_WAIT_MS),
            )
            changed -> sql.exec(
                "UPDATE notification_facts SET posted_at = ?, status = '$PENDING', ready_at = ?, attempts = 0, kind = NULL, " +
                    "amount = NULL, currency = NULL, merchant = NULL, instrument = NULL, sender = NULL, needs_reply = NULL, " +
                    "summary = NULL, money_event = NULL, parcel_id = NULL, model = NULL, analyzed_at = NULL WHERE key = ?",
                listOf(postedAt, now + UPDATE_QUIET_MS, key),
            )
        }
    }

    /** Keys ready for analysis at [now], oldest notification first, at most [limit]. */
    fun pendingBatch(now: Long, limit: Int = BATCH): List<String> = sql.query(
        "SELECT key FROM notification_facts WHERE status = '$PENDING' AND ready_at <= ? ORDER BY posted_at, key LIMIT ?",
        listOf(now, limit),
    ) { it.string(0)!! }

    /** When the next pending notification becomes ready, or null when none waits. */
    fun nextReadyAt(): Long? =
        sql.query("SELECT MIN(ready_at) FROM notification_facts WHERE status = '$PENDING'") { it.long(0) }.firstOrNull()

    fun pendingCount(): Int =
        sql.query("SELECT COUNT(*) FROM notification_facts WHERE status = '$PENDING'") { it.long(0) ?: 0 }.single().toInt()

    /** A failed try: after [MAX_ATTEMPTS] the keys are given up, otherwise they wait [RETRY_MS]. */
    fun fail(keys: List<String>, now: Long) {
        if (keys.isEmpty()) return
        sql.transaction {
            for (k in keys) {
                sql.exec(
                    "UPDATE notification_facts SET attempts = attempts + 1, ready_at = ?, " +
                        "status = CASE WHEN attempts + 1 >= $MAX_ATTEMPTS THEN '$FAILED' ELSE status END " +
                        "WHERE key = ? AND status = '$PENDING'",
                    listOf(now + RETRY_MS, k),
                )
            }
        }
    }

    /** Not a failure (a usage limit, no network): the keys simply wait until [until]. */
    fun postpone(keys: List<String>, until: Long) {
        if (keys.isEmpty()) return
        sql.transaction {
            for (k in keys) sql.exec("UPDATE notification_facts SET ready_at = ? WHERE key = ? AND status = '$PENDING'", listOf(until, k))
        }
    }

    /** Facts whose notification is gone (pruned or removed). */
    fun dropOrphans() = sql.exec("DELETE FROM notification_facts WHERE key NOT IN (SELECT key FROM notifications)")

    fun callsOn(day: String): Int =
        sql.query("SELECT n FROM understand_calls WHERE day = ?", listOf(day)) { it.long(0) ?: 0 }.firstOrNull()?.toInt() ?: 0

    fun lastCallAt(): Long? = sql.query("SELECT MAX(last_at) FROM understand_calls") { it.long(0) }.firstOrNull()

    fun countCall(day: String, at: Long) = sql.exec(
        "INSERT INTO understand_calls (day, n, last_at) VALUES (?, 1, ?) " +
            "ON CONFLICT(day) DO UPDATE SET n = n + 1, last_at = excluded.last_at",
        listOf(day, at),
    )

    /**
     * What the model may tie new notices to: money events of the last [JOIN_WINDOW_MS], spends of the last
     * [REFUND_WINDOW_MS] (newest [REFUND_CANDIDATES]) for refunds, and parcels not yet delivered or delivered within a
     * day. Parcel ids are offset by [PARCEL_REF_BASE] so they never clash with money event ids in the prompt.
     */
    fun recentEvents(now: Long): List<RecentEvent> {
        val money = sql.query(
            "SELECT id, kind, amount, currency, merchant, instrument, last_at FROM money_events WHERE last_at >= ? " +
                "UNION SELECT * FROM (SELECT id, kind, amount, currency, merchant, instrument, last_at FROM money_events " +
                "WHERE kind = 'spend' AND last_at >= ? ORDER BY last_at DESC LIMIT $REFUND_CANDIDATES) ORDER BY 7 DESC",
            listOf(now - JOIN_WINDOW_MS, now - REFUND_WINDOW_MS),
        ) { r ->
            RecentEvent(r.long(0)!!, NoticeKind.of(r.string(1)) ?: NoticeKind.OTHER, r.long(2), r.string(3), r.string(4), r.string(5), r.long(6)!!, null, null)
        }
        val parcels = sql.query(
            "SELECT id, carrier, tracking, stage, updated_at FROM parcels WHERE delivered_at IS NULL OR delivered_at >= ? " +
                "ORDER BY updated_at DESC LIMIT $PARCEL_CANDIDATES",
            listOf(now - DAY_MS),
        ) { r ->
            RecentEvent(PARCEL_REF_BASE + r.long(0)!!, NoticeKind.PARCEL, null, null, r.string(1), null, r.long(4)!!, r.string(2), ParcelStage.of(r.string(3)))
        }
        return money + parcels
    }

    /** Drops what is past its retention, and facts whose notification is gone. */
    fun prune(now: Long) = sql.transaction {
        dropOrphans()
        sql.exec("DELETE FROM money_events WHERE last_at < ?", listOf(now - MONEY_KEEP_MS))
        sql.exec("DELETE FROM parcels WHERE delivered_at IS NOT NULL AND delivered_at < ?", listOf(now - DELIVERED_KEEP_MS))
        sql.exec("DELETE FROM parcels WHERE delivered_at IS NULL AND updated_at < ?", listOf(now - STALE_PARCEL_MS))
    }

    /** Deletes everything understood (Settings "모든 데이터 지우기"). */
    fun clear() = sql.transaction {
        for (t in listOf("notification_facts", "money_events", "parcels", "understand_calls")) sql.exec("DELETE FROM $t")
    }

    companion object {
        const val PENDING = "pending"
        const val DONE = "done"
        const val FAILED = "failed"
        const val SKIPPED = "skipped"

        const val FIRST_WAIT_MS = 20_000L
        const val UPDATE_QUIET_MS = 2 * 60_000L
        const val RETRY_MS = 60 * 60_000L
        const val MAX_ATTEMPTS = 3
        const val BATCH = 20

        private const val DAY_MS = 24 * 60 * 60_000L
        const val JOIN_WINDOW_MS = 30 * 60_000L
        const val REFUND_WINDOW_MS = 30 * DAY_MS
        private const val REFUND_CANDIDATES = 20
        private const val PARCEL_CANDIDATES = 20
        const val MONEY_KEEP_MS = 90 * DAY_MS
        const val DELIVERED_KEEP_MS = 14 * DAY_MS
        private const val STALE_PARCEL_MS = 30 * DAY_MS

        /** Added to a parcel's id where it shares the prompt's event ids with money events. */
        const val PARCEL_REF_BASE = 1_000_000_000L
    }
}
