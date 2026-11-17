package com.phoneuse.understand

import com.phoneuse.chat.ChatDbSchema
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** [SqlPort] on plain SQLite through JDBC, so the store's SQL runs in unit tests as it does on the phone. */
class JdbcSqlPort(val connection: Connection) : SqlPort {
    override fun exec(sql: String, args: List<Any?>) {
        connection.prepareStatement(sql).use { p ->
            args.forEachIndexed { i, a -> p.setObject(i + 1, a) }
            p.execute()
        }
    }

    override fun insert(sql: String, args: List<Any?>): Long {
        exec(sql, args)
        return connection.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { r -> r.next(); r.getLong(1) } }
    }

    override fun <T> query(sql: String, args: List<Any?>, row: (SqlRow) -> T): List<T> =
        connection.prepareStatement(sql).use { p ->
            args.forEachIndexed { i, a -> p.setObject(i + 1, a) }
            p.executeQuery().use { r ->
                val w = Row(r)
                buildList { while (r.next()) add(row(w)) }
            }
        }

    override fun <T> transaction(block: () -> T): T {
        val auto = connection.autoCommit
        connection.autoCommit = false
        try {
            val v = block()
            connection.commit()
            return v
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = auto
        }
    }

    private class Row(private val r: ResultSet) : SqlRow {
        override fun long(i: Int): Long? = r.getLong(i + 1).takeUnless { r.wasNull() }
        override fun string(i: Int): String? = r.getString(i + 1)
    }

    companion object {
        /** A fresh in-memory chat.db at the current schema. */
        fun fresh(): JdbcSqlPort = JdbcSqlPort(DriverManager.getConnection("jdbc:sqlite::memory:")).also { db ->
            db.connection.createStatement().use { s -> ChatDbSchema.create.forEach { s.execute(it) } }
        }
    }
}
