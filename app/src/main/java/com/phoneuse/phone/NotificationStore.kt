package com.phoneuse.phone

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.phoneuse.chat.ChatDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * One posted notification as the listener keeps it: only these fields, never extras, pictures or actions.
 * [group] is true for a group conversation (MessagingStyle), which needs no personal reply. [removedAt] is when it
 * left the shade (read, answered, swiped or cancelled); such a row leaves only the 답장 필요 group.
 */
data class StoredNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val group: Boolean = false,
    val removedAt: Long? = null,
)

/** The SQL of [NotificationStore] (table `notifications`, chat.db v5); exercised on plain SQLite in tests. */
object NotificationSql {
    const val COLUMNS = "key, package, app_label, title, text, posted_at, is_group"

    /** Args: the [COLUMNS] in order. A notification posted again under the same key replaces its row. */
    const val UPSERT = "INSERT OR REPLACE INTO notifications ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?)"

    /** Read columns: [COLUMNS] and removed_at (v6). */
    const val READ_COLUMNS = "$COLUMNS, removed_at"

    /** Args: since (epoch ms), limit. Newest first. */
    const val SINCE = "SELECT $READ_COLUMNS FROM notifications WHERE posted_at >= ? ORDER BY posted_at DESC, key LIMIT ?"

    /** Args: cutoff (epoch ms). */
    const val PRUNE = "DELETE FROM notifications WHERE posted_at < ?"

    /** The full wipe (settings "모든 대화와 기억 삭제"). */
    const val CLEAR = "DELETE FROM notifications"

    /** Args: removed at (epoch ms), then [count] keys. The first removal counts; a re-post (UPSERT) clears it. */
    fun markRemoved(count: Int): String {
        require(count > 0)
        return "UPDATE notifications SET removed_at = ? WHERE removed_at IS NULL AND key IN (${List(count) { "?" }.joinToString(", ")})"
    }

    /** Args: [count] keys. */
    fun deleteKeys(count: Int): String {
        require(count > 0)
        return "DELETE FROM notifications WHERE key IN (${List(count) { "?" }.joinToString(", ")})"
    }
}

/**
 * The notification history in chat.db, kept for [RETENTION_MS] (pruned as rows are added). It stays on the
 * phone; the model only sees what a notifications_recent call returns in that turn.
 */
class NotificationStore(private val db: ChatDb, private val clock: () -> Long = System::currentTimeMillis) {

    private val _changes = MutableStateFlow(0L)

    /** Bumped on every write, so screens can reload. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    @Volatile private var lastPrune = 0L

    suspend fun add(n: StoredNotification) = withContext(Dispatchers.IO) {
        val w = db.writable
        w.insertWithOnConflict(
            "notifications", null,
            ContentValues().apply {
                put("key", n.key)
                put("package", n.packageName)
                put("app_label", n.appLabel.take(MAX_LABEL))
                put("title", n.title.take(MAX_TITLE))
                put("text", n.text.take(MAX_TEXT))
                put("posted_at", n.postedAt)
                put("is_group", if (n.group) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        val now = clock()
        if (now - lastPrune > PRUNE_EVERY_MS) {
            lastPrune = now
            w.execSQL(NotificationSql.PRUNE, arrayOf<Any>(cutoff(now)))
        }
        _changes.update { it + 1 }
    }

    /** Notifications posted in the last [hours] (clamped to the retention), newest first. */
    suspend fun recent(hours: Int = 24): List<StoredNotification> = withContext(Dispatchers.IO) {
        db.readable.rawQuery(NotificationSql.SINCE, arrayOf(since(clock(), hours).toString(), MAX_ROWS.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(StoredNotification(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getInt(6) != 0, if (c.isNull(7)) null else c.getLong(7)))
                }
            }
        }
    }

    suspend fun remove(keys: Collection<String>) = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext
        for (chunk in keys.distinct().chunked(MAX_ARGS)) db.writable.execSQL(NotificationSql.deleteKeys(chunk.size), chunk.toTypedArray())
        _changes.update { it + 1 }
    }

    /** Marks [keys] as gone from the shade; the rows stay for the digest's other groups until the retention ends. */
    suspend fun markRemoved(keys: Collection<String>) = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext
        val at = clock()
        for (chunk in keys.distinct().chunked(MAX_ARGS)) {
            db.writable.execSQL(NotificationSql.markRemoved(chunk.size), (listOf<Any>(at) + chunk).toTypedArray())
        }
        _changes.update { it + 1 }
    }

    /**
     * Drops rows past the retention now. Also done while adding (at most every [PRUNE_EVERY_MS]), at app start and
     * when the listener connects or disconnects, so the 7 days hold after access is revoked too.
     */
    suspend fun prune() = withContext(Dispatchers.IO) {
        db.writable.execSQL(NotificationSql.PRUNE, arrayOf<Any>(cutoff(clock())))
        _changes.update { it + 1 }
    }

    /** Deletes the whole history. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        db.writable.execSQL(NotificationSql.CLEAR)
        _changes.update { it + 1 }
    }

    companion object {
        @Volatile private var instance: NotificationStore? = null

        /** The one store of the process, shared by the listener, the agent's tools and the digest screen. */
        fun get(context: Context): NotificationStore =
            instance ?: synchronized(this) { instance ?: NotificationStore(ChatDb.get(context)).also { instance = it } }

        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val HOUR_MS = 60L * 60 * 1000
        private const val PRUNE_EVERY_MS = HOUR_MS
        private const val MAX_ROWS = 500
        private const val MAX_ARGS = 500
        const val MAX_LABEL = 60
        const val MAX_TITLE = 120
        const val MAX_TEXT = 600

        /** Rows posted before this are past the retention. */
        fun cutoff(now: Long): Long = now - RETENTION_MS

        /** Start of a window of [hours] (at least 1, at most the retention). */
        fun since(now: Long, hours: Int): Long = maxOf(now - hours.coerceAtLeast(1) * HOUR_MS, cutoff(now))
    }
}
