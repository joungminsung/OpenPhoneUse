package com.phoneuse.chat

/**
 * Schema of the chat database as plain SQL, so creation and upgrades are testable without a device.
 * To add a version: bump [VERSION], add its statements to [steps] and, when they also suit a fresh install, to
 * [create] (as v3 does), so a new and an upgraded database end up with the same schema.
 */
object ChatDbSchema {
    const val VERSION = 8

    /** Version 2 as created: conversations and messages, with cards_json. */
    private val v2: List<String> = listOf(
        "CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, " +
            "updated_at INTEGER NOT NULL)",
        "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL, " +
            "role TEXT NOT NULL, text TEXT NOT NULL, steps_json TEXT NOT NULL, created_at INTEGER NOT NULL, " +
            "cards_json TEXT)",
        "CREATE INDEX idx_messages_conversation ON messages(conversation_id)",
    )

    /**
     * v3: user memories, app tips, and full-text indexes. The FTS4 tables hold only text, keyed by the row id
     * of their source table (docid), and triggers keep them in step. The default tokenizer keeps Hangul inside
     * words, so lookups use prefix terms (see MemoryText.ftsQuery). Existing messages are indexed once here.
     */
    private val v3: List<String> = listOf(
        "CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, kind TEXT NOT NULL, " +
            "confidence TEXT NOT NULL, conversation_id INTEGER, origin TEXT, date TEXT, due TEXT, " +
            "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        "CREATE INDEX idx_memories_updated ON memories(updated_at)",
        "CREATE VIRTUAL TABLE memories_fts USING fts4(text)",
        "CREATE TRIGGER memories_fts_insert AFTER INSERT ON memories BEGIN " +
            "INSERT INTO memories_fts(docid, text) VALUES (new.id, new.text); END",
        "CREATE TRIGGER memories_fts_update AFTER UPDATE OF text ON memories BEGIN " +
            "UPDATE memories_fts SET text = new.text WHERE docid = old.id; END",
        "CREATE TRIGGER memories_fts_delete AFTER DELETE ON memories BEGIN " +
            "DELETE FROM memories_fts WHERE docid = old.id; END",
        "CREATE VIRTUAL TABLE messages_fts USING fts4(text)",
        "CREATE TRIGGER messages_fts_insert AFTER INSERT ON messages BEGIN " +
            "INSERT INTO messages_fts(docid, text) VALUES (new.id, new.text); END",
        "CREATE TRIGGER messages_fts_update AFTER UPDATE OF text ON messages BEGIN " +
            "UPDATE messages_fts SET text = new.text WHERE docid = old.id; END",
        "CREATE TRIGGER messages_fts_delete AFTER DELETE ON messages BEGIN " +
            "DELETE FROM messages_fts WHERE docid = old.id; END",
        "INSERT INTO messages_fts(docid, text) SELECT id, text FROM messages",
        "CREATE TABLE app_tips (id INTEGER PRIMARY KEY AUTOINCREMENT, package TEXT NOT NULL, task TEXT NOT NULL, " +
            "steps TEXT NOT NULL, created_at INTEGER NOT NULL)",
        "CREATE INDEX idx_app_tips_package ON app_tips(package)",
    )

    /** v4: what the user attached to a message ([AttachmentsColumn]; NULL = none). */
    private val v4: List<String> = listOf("ALTER TABLE messages ADD COLUMN attachments_json TEXT")

    /**
     * v5: the notification history (NotificationStore): only key, app, title, text and time, kept 7 days.
     * The key is the system's notification key, so an updated notification replaces its row.
     */
    private val v5: List<String> = listOf(
        "CREATE TABLE notifications (key TEXT PRIMARY KEY, package TEXT NOT NULL, app_label TEXT NOT NULL, " +
            "title TEXT NOT NULL, text TEXT NOT NULL, posted_at INTEGER NOT NULL, is_group INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX idx_notifications_posted ON notifications(posted_at)",
    )

    /**
     * v6: whether a conversation ever read third-party text (ChatRepository.thirdPartySeen), and when a
     * notification left the shade (NULL = still there; such rows leave only the 답장 필요 group).
     */
    private val v6: List<String> = listOf(
        "ALTER TABLE conversations ADD COLUMN third_party_seen INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE notifications ADD COLUMN removed_at INTEGER",
    )

    /**
     * v7: conversations from before the flag existed may carry screen, notification or web text in their
     * history, so every existing one counts as having read third-party text. A fresh install has none.
     */
    private val v7: List<String> = listOf("UPDATE conversations SET third_party_seen = 1")

    /**
     * v8: what the model understood of each notification (NotificationUnderstanding), the payments and parcels those
     * notices add up to, and the daily count of analysis calls. Facts follow their notification's 7 days; money events
     * keep no notification text and last 90 days.
     */
    private val v8: List<String> = listOf(
        "CREATE TABLE notification_facts (key TEXT PRIMARY KEY, posted_at INTEGER NOT NULL, status TEXT NOT NULL, " +
            "ready_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, kind TEXT, amount INTEGER, currency TEXT, " +
            "merchant TEXT, instrument TEXT, sender TEXT, needs_reply INTEGER, summary TEXT, money_event INTEGER, " +
            "parcel_id INTEGER, model TEXT, analyzed_at INTEGER)",
        "CREATE INDEX idx_facts_status ON notification_facts(status, ready_at)",
        "CREATE TABLE money_events (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, amount INTEGER NOT NULL, " +
            "currency TEXT NOT NULL, merchant TEXT, instrument TEXT, first_at INTEGER NOT NULL, last_at INTEGER NOT NULL, " +
            "source_count INTEGER NOT NULL, cancelled_amount INTEGER NOT NULL DEFAULT 0, refund_of INTEGER)",
        "CREATE INDEX idx_money_events_first ON money_events(first_at)",
        "CREATE TABLE parcels (id INTEGER PRIMARY KEY AUTOINCREMENT, carrier TEXT, tracking TEXT, item_hint TEXT, " +
            "stage TEXT NOT NULL, first_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, delivered_at INTEGER)",
        "CREATE TABLE understand_calls (day TEXT PRIMARY KEY, n INTEGER NOT NULL, last_at INTEGER NOT NULL)",
    )

    /** On upgrade only: notifications from the last 48 hours wait for analysis, older ones are skipped. */
    private val v8Seed: List<String> = listOf(
        "INSERT INTO notification_facts (key, posted_at, status, ready_at) SELECT key, posted_at, " +
            "CASE WHEN posted_at >= CAST(strftime('%s','now') AS INTEGER) * 1000 - 172800000 THEN 'pending' ELSE 'skipped' END, 0 " +
            "FROM notifications",
    )

    /** The current schema, for a fresh install: the same statements an upgrade runs, so both end up equal. */
    val create: List<String> = v2 + v3 + v4 + v5 + v6 + v7 + v8

    /**
     * After deleting everything (Settings "모든 데이터 지우기"): merges the full-text segments so deleted text
     * leaves them, then rebuilds the file so freed pages holding old text are gone. Run outside a transaction.
     */
    val compact: List<String> = listOf(
        "INSERT INTO messages_fts(messages_fts) VALUES('optimize')",
        "INSERT INTO memories_fts(memories_fts) VALUES('optimize')",
        "VACUUM",
    )

    /** Statements that upgrade version N-1 to N, keyed by N. */
    private val steps: Map<Int, List<String>> = mapOf(
        // v2: result cards stored with the assistant message (NULL = none).
        2 to listOf("ALTER TABLE messages ADD COLUMN cards_json TEXT"),
        3 to v3,
        4 to v4,
        5 to v5,
        6 to v6,
        7 to v7,
        8 to v8 + v8Seed,
    )

    /** The statements that take a database from [from] to [to], in order. */
    fun migrations(from: Int, to: Int): List<String> {
        require(from in 1..to && to <= VERSION) { "no migration from $from to $to" }
        return (from + 1..to).flatMap { steps[it] ?: error("missing migration to $it") }
    }
}
