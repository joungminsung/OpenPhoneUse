package com.phoneuse.chat

import com.phoneuse.memory.MemoryText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager
import java.time.ZoneId

class ConversationSearchTest {
    @Test fun queryFindsNewestMatchPerConversationAndSkipsCurrent() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { s ->
                ChatDbSchema.create.forEach { s.execute(it) }
                s.execute("INSERT INTO conversations (id, title, updated_at) VALUES (1, '엄마 생신 선물', 0), (2, '주말 등산', 0), (3, '지금 대화', 0)")
                val rows = listOf(
                    Triple(1, "등산 스틱은 어때요", 100L),
                    Triple(1, "스틱 최저가 찾아줘", 200L),
                    Triple(2, "북한산 등산 코스", 150L),
                    Triple(3, "스틱 얘기 다시", 300L),
                    Triple(2, "점심은 김밥", 400L),
                )
                for ((conv, text, at) in rows) {
                    s.execute("INSERT INTO messages (conversation_id, role, text, steps_json, created_at) VALUES ($conv, 'USER', '$text', '[]', $at)")
                }
            }
            val query = MemoryText.ftsQuery("등산 스틱")!!
            val found = c.prepareStatement(ConversationSearchSql.QUERY).use { p ->
                p.setString(1, query)
                // Bound as text, the way SQLiteDatabase.rawQuery binds its selection args.
                p.setString(2, "3")
                p.executeQuery().use { r ->
                    buildList { while (r.next()) add(ConversationSearchSql.Row(r.getLong(1), r.getString(2), r.getString(3), r.getLong(4))) }
                }
            }
            val hits = ConversationSearchSql.hits(found, MemoryText.terms("등산 스틱"), limit = 10, zone = ZoneId.of("UTC"))
            // One hit per conversation, its newest matching message; the current conversation is left out.
            assertEquals(listOf(1L, 2L), hits.map { it.conversationId })
            assertEquals("엄마 생신 선물", hits[0].title)
            assertTrue(hits[0].snippet, "스틱 최저가" in hits[0].snippet)
            assertEquals("1970-01-01 00:00", hits[0].at)
        }
    }

    @Test fun snippetCentersOnTheFirstMatch() {
        val text = "가".repeat(200) + " 등산 스틱 " + "나".repeat(200)
        val s = Snippets.around(text, listOf("스틱"), max = 40)
        assertTrue(s, "스틱" in s)
        assertTrue(s, s.startsWith("…") && s.endsWith("…"))
        assertTrue(s.length <= 42)
        assertEquals("짧은 글", Snippets.around("짧은 글", listOf("없음"), max = 40))
    }
}
