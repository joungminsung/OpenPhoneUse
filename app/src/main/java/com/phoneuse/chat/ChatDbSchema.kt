package com.phoneuse.chat

/**
 * Schema of the chat database as plain SQL, so creation and upgrades are testable without a device.
 * To add a version: bump [VERSION], add its statements to [steps] and, when they also suit a fresh install, to
 * [create] (as v3 does), so a new and an upgraded database end up with the same schema.
 */
object ChatDbSchema {
    const val VERSION = 7

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

    /** The current schema, for a fresh install: the same statements an upgrade runs, so both end up equal. */
    val create: List<String> = v2 + v3 + v4 + v5 + v6 + v7

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
    )

    /** The statements that take a database from [from] to [to], in order. */
    fun migrations(from: Int, to: Int): List<String> {
        require(from in 1..to && to <= VERSION) { "no migration from $from to $to" }
        return (from + 1..to).flatMap { steps[it] ?: error("missing migration to $it") }
    }
}
