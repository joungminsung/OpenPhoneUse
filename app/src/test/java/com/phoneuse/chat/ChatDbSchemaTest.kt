package com.phoneuse.chat

import com.phoneuse.memory.MemoryText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

class ChatDbSchemaTest {
    /** The v2 schema exactly as it shipped. */
    private val v2 = listOf(
        "CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, updated_at INTEGER NOT NULL)",
        "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL, " +
            "role TEXT NOT NULL, text TEXT NOT NULL, steps_json TEXT NOT NULL, created_at INTEGER NOT NULL, cards_json TEXT)",
        "CREATE INDEX idx_messages_conversation ON messages(conversation_id)",
    )

    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun Connection.exec(vararg sql: String) = createStatement().use { s -> sql.forEach { s.execute(it) } }

    private fun Connection.longs(sql: String, vararg args: Any): List<Long> = prepareStatement(sql).use { p ->
        args.forEachIndexed { i, a -> p.setObject(i + 1, a) }
        p.executeQuery().use { r -> buildList { while (r.next()) add(r.getLong(1)) } }
    }

    private fun Connection.schema(): List<String> =
        createStatement().use { s ->
            s.executeQuery("SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name").use { r ->
                buildList { while (r.next()) add("${r.getString(1)}:${r.getString(2)}") }
            }
        }

    @Test
    fun migratesV1ToV2KeepsMessages() {
        val sql = ChatDbSchema.migrations(1, 2)
        assertEquals(listOf("ALTER TABLE messages ADD COLUMN cards_json TEXT"), sql)
        // Nothing that would lose rows: no drops, deletes or table rebuilds.
        for (s in sql) {
            val u = s.uppercase()
            assertTrue(s, "DROP" !in u && "DELETE" !in u && "CREATE TABLE" !in u)
        }
    }

    @Test
    fun migratesV2ToV3() {
        db().use { c ->
            c.exec(*v2.toTypedArray())
            c.exec(
                "INSERT INTO conversations (id, title, updated_at) VALUES (1, '엄마 생신 선물', 10)",
                "INSERT INTO messages (conversation_id, role, text, steps_json, created_at, cards_json) " +
                    "VALUES (1, 'USER', '엄마 생신 선물 뭐가 좋을까?', '[]', 11, NULL)",
                "INSERT INTO messages (conversation_id, role, text, steps_json, created_at, cards_json) " +
                    "VALUES (1, 'ASSISTANT', '등산 스틱은 어때요', '[]', 12, '[]')",
            )
            val sql = ChatDbSchema.migrations(2, 3)
            for (s in sql) assertTrue(s, "DROP" !in s.uppercase() && "ALTER" !in s.uppercase())
            c.exec(*sql.toTypedArray())

            // Every v2 message survives and is searchable right away.
            assertEquals(2L, c.longs("SELECT COUNT(*) FROM messages").single())
            assertEquals(listOf(2L), c.longs("SELECT docid FROM messages_fts WHERE messages_fts MATCH ?", MemoryText.ftsQuery("스틱")!!))

            // New, edited and deleted messages keep the index in step.
            c.exec(
                "INSERT INTO messages (conversation_id, role, text, steps_json, created_at) VALUES (1, 'USER', '바람막이도 볼래', '[]', 13)",
                "UPDATE messages SET text = '양말 세트' WHERE id = 2",
            )
            assertEquals(listOf(3L), c.longs("SELECT docid FROM messages_fts WHERE messages_fts MATCH ?", "바람막이*"))
            assertEquals(emptyList<Long>(), c.longs("SELECT docid FROM messages_fts WHERE messages_fts MATCH ?", "스틱*"))
            c.exec("DELETE FROM messages WHERE conversation_id = 1")
            assertEquals(0L, c.longs("SELECT COUNT(*) FROM messages_fts").single())

            // Memories and their index.
            c.exec(
                "INSERT INTO memories (text, kind, confidence, created_at, updated_at) VALUES ('엄마가 10월부터 등산을 다니심', 'PERSON', 'SURE', 1, 1)",
            )
            assertEquals(listOf(1L), c.longs("SELECT docid FROM memories_fts WHERE memories_fts MATCH ?", MemoryText.ftsQuery("엄마가 좋아할 선물")!!))
            assertEquals(listOf(1L), c.longs(com.phoneuse.memory.MemorySql.MATCHING, MemoryText.ftsQuery("엄마 선물")!!, 10))
            assertEquals(listOf(1L), c.longs(com.phoneuse.memory.MemorySql.RECENT, 10))
            c.exec("UPDATE memories SET text = '아빠는 낚시' WHERE id = 1")
            assertEquals(listOf(1L), c.longs("SELECT docid FROM memories_fts WHERE memories_fts MATCH ?", "낚시*"))
            c.exec("DELETE FROM memories WHERE id = 1")
            assertEquals(0L, c.longs("SELECT COUNT(*) FROM memories_fts").single())

            c.exec("INSERT INTO app_tips (package, task, steps, created_at) VALUES ('com.app', 't', 's', 1)")
            assertEquals(1L, c.longs("SELECT COUNT(*) FROM app_tips WHERE package = 'com.app'").single())
        }
    }

    @Test
    fun migratesV3ToV4KeepsMessages() {
        db().use { c ->
            c.exec(*v2.toTypedArray())
            c.exec(*ChatDbSchema.migrations(2, 3).toTypedArray())
            c.exec(
                "INSERT INTO conversations (id, title, updated_at) VALUES (1, '화분', 10)",
                "INSERT INTO messages (conversation_id, role, text, steps_json, created_at) VALUES (1, 'USER', '잎이 노래져', '[]', 11)",
            )
            val sql = ChatDbSchema.migrations(3, 4)
            assertEquals(listOf("ALTER TABLE messages ADD COLUMN attachments_json TEXT"), sql)
            c.exec(*sql.toTypedArray())
            assertEquals(1L, c.longs("SELECT COUNT(*) FROM messages WHERE attachments_json IS NULL").single())
            c.exec(
                "INSERT INTO messages (conversation_id, role, text, steps_json, created_at, attachments_json) " +
                    "VALUES (1, 'USER', '이 사진', '[]', 12, '[]')",
            )
            assertEquals(listOf(2L), c.longs("SELECT id FROM messages WHERE attachments_json IS NOT NULL"))
        }
    }

    @Test
    fun migratesV4ToV5KeepsMessagesAndAddsNotifications() {
        db().use { c ->
            c.exec(*v2.toTypedArray())
            c.exec(*ChatDbSchema.migrations(2, 4).toTypedArray())
            c.exec(
                "INSERT INTO conversations (id, title, updated_at) VALUES (1, '택배', 10)",
                "INSERT INTO messages (conversation_id, role, text, steps_json, created_at) VALUES (1, 'USER', '이어폰 어디쯤 왔어?', '[]', 11)",
            )
            val sql = ChatDbSchema.migrations(4, 5)
            for (s in sql) assertTrue(s, "DROP" !in s.uppercase() && "DELETE" !in s.uppercase() && "ALTER" !in s.uppercase())
            c.exec(*sql.toTypedArray())
            assertEquals(1L, c.longs("SELECT COUNT(*) FROM messages").single())
            c.exec(
                "INSERT INTO notifications (key, package, app_label, title, text, posted_at) " +
                    "VALUES ('0|com.kakao.talk|1|null|10', 'com.kakao.talk', '카카오톡', '엄마', '저녁 먹고 오니?', 100)",
            )
            assertEquals(listOf(0L), c.longs("SELECT is_group FROM notifications"))
        }
    }

    @Test
    fun migratesV5ToV6AddsTheThirdPartyFlagAndRemovedAt() {
        db().use { c ->
            c.exec(*v2.toTypedArray())
            c.exec(*ChatDbSchema.migrations(2, 5).toTypedArray())
            c.exec(
                "INSERT INTO conversations (id, title, updated_at) VALUES (1, '알림', 10)",
                "INSERT INTO notifications (key, package, app_label, title, text, posted_at) VALUES ('k', 'p', '앱', 't', 'x', 100)",
            )
            c.exec(*ChatDbSchema.migrations(5, 6).toTypedArray())
            // Existing conversations start clean; existing notifications are still in the shade.
            assertEquals(listOf(0L), c.longs("SELECT third_party_seen FROM conversations"))
            assertEquals(listOf(1L), c.longs("SELECT COUNT(*) FROM notifications WHERE removed_at IS NULL"))
            c.exec("UPDATE conversations SET third_party_seen = 1 WHERE id = 1")
            assertEquals(listOf(1L), c.longs("SELECT third_party_seen FROM conversations"))
        }
    }

    @Test
    fun migratesV6ToV7MarksEveryExistingConversationThirdParty() {
        db().use { c ->
            c.exec(*v2.toTypedArray())
            c.exec(*ChatDbSchema.migrations(2, 6).toTypedArray())
            c.exec(
                "INSERT INTO conversations (id, title, updated_at) VALUES (1, '예전 대화', 10)",
                "INSERT INTO conversations (id, title, updated_at) VALUES (2, '다른 대화', 11)",
            )
            c.exec(*ChatDbSchema.migrations(6, 7).toTypedArray())
            // Pre-v7 history may carry screen or notification text: every old conversation is tainted.
            assertEquals(listOf(1L, 1L), c.longs("SELECT third_party_seen FROM conversations ORDER BY id"))
            // A conversation made after the upgrade starts clean.
            c.exec("INSERT INTO conversations (id, title, updated_at) VALUES (3, '새 대화', 12)")
            assertEquals(listOf(0L), c.longs("SELECT third_party_seen FROM conversations WHERE id = 3"))
        }
    }

    @Test
    fun compactingAfterAWipeLeavesNoIndexedText() {
        // Deleted text must not linger in FTS segments or free pages after "모든 데이터 지우기".
        db().use { c ->
            c.exec(*ChatDbSchema.create.toTypedArray())
            c.exec(
                "INSERT INTO conversations (id, title, updated_at) VALUES (1, 't', 1)",
                "INSERT INTO messages (conversation_id, role, text, steps_json, created_at) VALUES (1, 'USER', '비밀 계좌 1234', '[]', 1)",
                "INSERT INTO memories (text, kind, confidence, created_at, updated_at) VALUES ('비밀 메모', 'me', 'SURE', 1, 1)",
            )
            c.exec("DELETE FROM messages", "DELETE FROM conversations", "DELETE FROM memories")
            // One statement at a time, as execSQL does (VACUUM needs no other statement open).
            ChatDbSchema.compact.forEach { c.exec(it) }
            assertEquals(listOf(0L), c.longs("SELECT COUNT(*) FROM messages_fts"))
            assertEquals(listOf(0L), c.longs("SELECT COUNT(*) FROM memories_fts"))
            assertTrue(ChatDbSchema.compact.last() == "VACUUM")
        }
    }

    @Test
    fun freshSchemaMatchesMigratedOne() {
        assertEquals(7, ChatDbSchema.VERSION)
        val messages = ChatDbSchema.create.single { it.startsWith("CREATE TABLE messages") }
        assertTrue(messages, "cards_json TEXT" in messages)
        assertTrue(messages, "steps_json TEXT NOT NULL" in messages)
        assertTrue(ChatDbSchema.create.contains("ALTER TABLE messages ADD COLUMN attachments_json TEXT"))
        val fresh = db().use { c ->
            c.exec(*ChatDbSchema.create.toTypedArray())
            c.schema()
        }
        val migrated = db().use { c ->
            c.exec(*v2.toTypedArray())
            c.exec(*ChatDbSchema.migrations(2, 7).toTypedArray())
            c.schema()
        }
        assertEquals(fresh, migrated)
        assertTrue(fresh.containsAll(listOf("table:memories", "table:memories_fts", "table:messages_fts", "table:app_tips", "table:notifications")))
    }

    @Test
    fun noStepsWhenAlreadyCurrentAndUnknownVersionsFail() {
        assertEquals(emptyList<String>(), ChatDbSchema.migrations(7, 7))
        assertTrue(runCatching { ChatDbSchema.migrations(1, 99) }.isFailure)
        assertTrue(runCatching { ChatDbSchema.migrations(7, 1) }.isFailure)
    }
}
