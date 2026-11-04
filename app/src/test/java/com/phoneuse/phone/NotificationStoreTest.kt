package com.phoneuse.phone

import com.phoneuse.chat.ChatDbSchema
import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** [NotificationSql] on real SQLite with the chat.db schema. */
class NotificationStoreTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    private fun Connection.run(sql: String, vararg args: Any?) = prepareStatement(sql).use { p ->
        args.forEachIndexed { i, a -> p.setObject(i + 1, a) }
        p.execute()
    }

    private fun Connection.strings(sql: String, vararg args: Any?): List<String> = prepareStatement(sql).use { p ->
        args.forEachIndexed { i, a -> p.setObject(i + 1, a) }
        p.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
    }

    private fun Connection.add(key: String, at: Long, title: String = "t") =
        run(NotificationSql.UPSERT, key, "com.kakao.talk", "카카오톡", title, "text", at, 0)

    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { c ->
        c.createStatement().use { s -> ChatDbSchema.create.forEach { s.execute(it) } }
    }

    @Test fun prunesOlderThan7Days() {
        db().use { c ->
            c.add("old", now - 8 * day)
            c.add("edge", now - 7 * day - 1)
            c.add("week", now - 7 * day + 60_000)
            c.add("fresh", now - 60_000)
            c.run(NotificationSql.PRUNE, NotificationStore.cutoff(now))
            assertEquals(listOf("fresh", "week"), c.strings(NotificationSql.SINCE, 0L, 100))
        }
    }

    @Test fun recentIsNewestFirstWithinTheWindowAndKeysUpsert() {
        db().use { c ->
            c.add("a", now - 30 * 60 * 60 * 1000L)
            c.add("b", now - 2 * 60 * 60 * 1000L)
            c.add("c", now - 60_000)
            // The same notification updated (a new message in the chat) replaces its row.
            c.add("b", now - 30_000, title = "updated")
            assertEquals(listOf("b", "c"), c.strings(NotificationSql.SINCE, NotificationStore.since(now, 24), 100))
            assertEquals(listOf("updated"), c.strings("SELECT title FROM notifications WHERE key = 'b'"))
            c.run(NotificationSql.deleteKeys(2), "b", "zz")
            assertEquals(listOf("c", "a"), c.strings(NotificationSql.SINCE, 0L, 100))
        }
    }

    @Test fun windowIsClampedToTheRetention() {
        assertEquals(now - 7 * day, NotificationStore.since(now, 10_000))
        assertEquals(now - 60 * 60 * 1000L, NotificationStore.since(now, 0))
    }

    @Test fun removedNotificationsAreMarkedNotDeleted() {
        db().use { c ->
            c.add("pay", now - 60_000)
            c.add("msg", now - 30_000)
            c.run(NotificationSql.markRemoved(2), now, "pay", "nope")
            assertEquals(listOf("msg", "pay"), c.strings(NotificationSql.SINCE, 0L, 100))
            assertEquals(listOf("pay"), c.strings("SELECT key FROM notifications WHERE removed_at IS NOT NULL"))
            // The first removal counts; a re-post under the same key is back in the shade.
            c.run(NotificationSql.markRemoved(1), now + 5, "pay")
            assertEquals(listOf(now.toString()), c.strings("SELECT removed_at FROM notifications WHERE key = 'pay'"))
            c.add("pay", now)
            assertEquals(listOf("2"), c.strings("SELECT COUNT(*) FROM notifications WHERE removed_at IS NULL"))
            // Still pruned after 7 days.
            c.run(NotificationSql.PRUNE, NotificationStore.cutoff(now + 8 * day))
            assertEquals(emptyList<String>(), c.strings(NotificationSql.SINCE, 0L, 100))
        }
    }
}
