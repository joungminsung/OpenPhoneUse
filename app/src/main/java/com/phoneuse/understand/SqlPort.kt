package com.phoneuse.understand

import android.database.sqlite.SQLiteDatabase
import com.phoneuse.chat.ChatDb

/** The few SQL calls [UnderstandingStore] needs, so the same store runs on the phone and on plain SQLite in tests. */
interface SqlPort {
    fun exec(sql: String, args: List<Any?> = emptyList())

    /** Runs an INSERT and returns the new row id. */
    fun insert(sql: String, args: List<Any?> = emptyList()): Long

    fun <T> query(sql: String, args: List<Any?> = emptyList(), row: (SqlRow) -> T): List<T>

    fun <T> transaction(block: () -> T): T
}

/** One result row; columns by 0-based index, null for SQL NULL. */
interface SqlRow {
    fun long(i: Int): Long?
    fun string(i: Int): String?
}

/** [SqlPort] on chat.db. Call it off the main thread. */
class ChatDbSqlPort(private val db: ChatDb) : SqlPort {
    override fun exec(sql: String, args: List<Any?>) = db.writable.execSQL(sql, args.map(::bind).toTypedArray())

    override fun insert(sql: String, args: List<Any?>): Long {
        val w = db.writable
        w.execSQL(sql, args.map(::bind).toTypedArray())
        return w.rawQuery("SELECT last_insert_rowid()", null).use { c -> c.moveToFirst(); c.getLong(0) }
    }

    override fun <T> query(sql: String, args: List<Any?>, row: (SqlRow) -> T): List<T> =
        db.readable.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            val r = object : SqlRow {
                override fun long(i: Int): Long? = if (c.isNull(i)) null else c.getLong(i)
                override fun string(i: Int): String? = if (c.isNull(i)) null else c.getString(i)
            }
            buildList { while (c.moveToNext()) add(row(r)) }
        }

    override fun <T> transaction(block: () -> T): T {
        val w: SQLiteDatabase = db.writable
        w.beginTransaction()
        try {
            val v = block()
            w.setTransactionSuccessful()
            return v
        } finally {
            w.endTransaction()
        }
    }

    private fun bind(a: Any?): Any? = when (a) {
        is Boolean -> if (a) 1L else 0L
        is Int -> a.toLong()
        else -> a
    }
}
