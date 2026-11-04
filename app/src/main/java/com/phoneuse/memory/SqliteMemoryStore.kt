package com.phoneuse.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.phoneuse.chat.ChatDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** The SQL of [SqliteMemoryStore]; the FTS lookups are exercised on plain SQLite in the schema tests. */
object MemorySql {
    const val COLUMNS = "id, text, kind, confidence, created_at, updated_at, conversation_id, origin, date, due"

    /** Args: FTS MATCH expression, limit. */
    const val MATCHING =
        "SELECT $COLUMNS FROM memories WHERE id IN (SELECT docid FROM memories_fts WHERE memories_fts MATCH ?) " +
            "ORDER BY updated_at DESC, id DESC LIMIT ?"

    /** Args: limit. */
    const val RECENT = "SELECT $COLUMNS FROM memories ORDER BY updated_at DESC, id DESC LIMIT ?"

    /**
     * Substring matches for [termCount] terms, for Korean words inside compounds that the prefix-only FTS
     * misses ("생일선물" for "선물"). Args: one [likeArg] per term, then the limit.
     */
    fun substring(termCount: Int): String {
        require(termCount > 0)
        return "SELECT $COLUMNS FROM memories WHERE " +
            List(termCount) { "text LIKE ? ESCAPE '\\'" }.joinToString(" OR ") +
            " ORDER BY updated_at DESC, id DESC LIMIT ?"
    }

    /** `%term%` with LIKE's wildcards escaped (terms are letters and digits, so this is belt and braces). */
    fun likeArg(term: String): String =
        "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /** How many rows each lookup may bring before ranking. */
    const val FETCH = 1000
}

/**
 * [MemoryStore] and [AppTipStore] on chat.db. Memories are few and short; [observe] reads them all, while the
 * prompt path ([candidates]) only reads full-text matches plus the most recent ones.
 */
class SqliteMemoryStore(private val db: ChatDb) : MemoryStore, AppTipStore {
    constructor(context: Context) : this(ChatDb.get(context))

    private val version = MutableStateFlow(0L)

    override fun observe(): Flow<List<Memory>> = version.map { read(MemorySql.RECENT, Int.MAX_VALUE.toString()) }.flowOn(Dispatchers.IO)

    override suspend fun observeOnce(): List<Memory> = withContext(Dispatchers.IO) { read(MemorySql.RECENT, Int.MAX_VALUE.toString()) }

    override suspend fun add(memory: NewMemory): AddResult {
        val text = MemoryStore.clean(memory.text)
        require(text.isNotEmpty()) { "empty memory" }
        MemorySecrets.check(memory.text)
        return write { w ->
            val now = System.currentTimeMillis()
            val same = w.rawQuery(
                "SELECT id, confidence, date, due FROM memories WHERE lower(text) = lower(?) LIMIT 1", arrayOf(text),
            ).use { c -> if (c.moveToFirst()) Triple(c.getLong(0), c.getString(1), c.getString(2) to c.getString(3)) else null }
            if (same != null) {
                // The stored kind stays (the user may have chosen it); only missing dates are filled.
                val (id, confidence, dates) = same
                w.update("memories", ContentValues().apply {
                    put("confidence", if (memory.confidence == Confidence.SURE) Confidence.SURE.name else confidence)
                    put("date", dates.first ?: memory.date)
                    put("due", dates.second ?: memory.due)
                    put("updated_at", now)
                }, "id = ?", arrayOf(id.toString()))
                AddResult(id, created = false)
            } else {
                val id = w.insertOrThrow("memories", null, ContentValues().apply {
                    put("text", text)
                    put("kind", memory.kind.name)
                    put("confidence", memory.confidence.name)
                    memory.conversationId?.let { put("conversation_id", it) }
                    memory.origin?.let { put("origin", it) }
                    memory.date?.let { put("date", it) }
                    memory.due?.let { put("due", it) }
                    put("created_at", now)
                    put("updated_at", now)
                })
                AddResult(id, created = true)
            }
        }
    }

    override suspend fun get(id: Long): Memory? = withContext(Dispatchers.IO) {
        db.readable.rawQuery("SELECT ${MemorySql.COLUMNS} FROM memories WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) memory(c) else null
        }
    }

    override suspend fun update(id: Long, text: String, kind: MemoryKind): Boolean {
        val clean = MemoryStore.clean(text)
        if (clean.isEmpty()) return false
        MemorySecrets.check(text)
        return write { w ->
            val exists = w.rawQuery("SELECT 1 FROM memories WHERE id = ?", arrayOf(id.toString())).use { it.moveToFirst() }
            if (!exists) return@write false
            val other = w.rawQuery(
                "SELECT id FROM memories WHERE lower(text) = lower(?) AND id != ? LIMIT 1", arrayOf(clean, id.toString()),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            // An edit is the user's word: it confirms the memory. Editing into another memory's text merges them.
            val values = ContentValues().apply {
                put("kind", kind.name)
                put("confidence", Confidence.SURE.name)
                put("updated_at", System.currentTimeMillis())
            }
            if (other != null) {
                w.delete("memories", "id = ?", arrayOf(id.toString()))
                w.update("memories", values, "id = ?", arrayOf(other.toString()))
            } else {
                values.put("text", clean)
                w.update("memories", values, "id = ?", arrayOf(id.toString()))
            }
            true
        }
    }

    override suspend fun confirm(id: Long): Boolean = write { w ->
        w.update("memories", ContentValues().apply { put("confidence", Confidence.SURE.name) }, "id = ?", arrayOf(id.toString())) > 0
    }

    override suspend fun delete(id: Long): Boolean = write { w -> w.delete("memories", "id = ?", arrayOf(id.toString())) > 0 }

    override suspend fun candidates(query: String, limit: Int): List<Memory> = withContext(Dispatchers.IO) {
        val match = MemoryText.ftsQuery(query) ?: return@withContext read(MemorySql.RECENT, limit.toString())
        val terms = MemoryText.terms(query).take(MemoryText.MAX_TERMS)
        val found = read(MemorySql.MATCHING, match, MemorySql.FETCH.toString()).toMutableList()
        // Prefix-only FTS misses words inside Korean compounds; look for substrings when it found few.
        if (found.size < limit) {
            found += read(MemorySql.substring(terms.size), *terms.map(MemorySql::likeArg).toTypedArray(), MemorySql.FETCH.toString())
        }
        // Ranked before the limit, so an older memory matching more terms beats newer single-term ones.
        MemoryRanker.rank(query, found.distinctBy { it.id }).filter { it.score > 0 }.take(limit).map { it.memory }
    }

    // ---- app tips ---------------------------------------------------------------------------------

    override suspend fun add(packageName: String, task: String, steps: String): Long = write { w ->
        val t = MemoryStore.clean(task).take(AppTipStore.MAX_TASK)
        w.delete("app_tips", "package = ? AND lower(task) = lower(?)", arrayOf(packageName, t))
        val id = w.insertOrThrow("app_tips", null, ContentValues().apply {
            put("package", packageName)
            put("task", t)
            put("steps", steps.take(AppTipStore.MAX_STEPS))
            put("created_at", System.currentTimeMillis())
        })
        w.execSQL(
            "DELETE FROM app_tips WHERE package = ? AND id NOT IN " +
                "(SELECT id FROM app_tips WHERE package = ? ORDER BY created_at DESC, id DESC LIMIT ${AppTipStore.PER_APP})",
            arrayOf(packageName, packageName),
        )
        id
    }

    override suspend fun top(packageName: String, query: String, n: Int): List<AppTip> = withContext(Dispatchers.IO) {
        val all = db.readable.rawQuery(
            "SELECT id, package, task, steps, created_at FROM app_tips WHERE package = ?", arrayOf(packageName),
        ).use { c -> buildList { while (c.moveToNext()) add(AppTip(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))) } }
        AppTips.rank(query, all, n)
    }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        db.readable.rawQuery("SELECT COUNT(*) FROM app_tips", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** Clears memories only; app tips have [deleteAllTips]. */
    override suspend fun deleteAll() {
        write { w -> w.delete("memories", null, null) }
    }

    override suspend fun deleteAllTips() {
        write { w -> w.delete("app_tips", null, null) }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun read(sql: String, vararg args: String): List<Memory> =
        db.readable.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(memory(c)) } }

    private fun memory(c: Cursor) = Memory(
        id = c.getLong(0),
        text = c.getString(1),
        kind = MemoryKind.entries.firstOrNull { it.name == c.getString(2) } ?: MemoryKind.ME,
        confidence = Confidence.entries.firstOrNull { it.name == c.getString(3) } ?: Confidence.SURE,
        createdAt = c.getLong(4),
        updatedAt = c.getLong(5),
        conversationId = if (c.isNull(6)) null else c.getLong(6),
        origin = c.getString(7),
        date = c.getString(8),
        due = c.getString(9),
    )

    private suspend fun <T> write(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        val w = db.writable
        w.beginTransaction()
        val result = try {
            block(w).also { w.setTransactionSuccessful() }
        } finally {
            w.endTransaction()
        }
        version.update { it + 1 }
        result
    }
}
