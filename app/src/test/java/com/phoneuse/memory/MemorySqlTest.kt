package com.phoneuse.memory

import com.phoneuse.chat.ChatDbSchema
import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** [MemorySql] on real SQLite, every argument bound as text the way SQLiteDatabase.rawQuery binds it. */
class MemorySqlTest {
    private fun Connection.ids(sql: String, vararg args: String): List<Long> = prepareStatement(sql).use { p ->
        args.forEachIndexed { i, a -> p.setString(i + 1, a) }
        p.executeQuery().use { r -> buildList { while (r.next()) add(r.getLong(1)) } }
    }

    @Test fun ftsMissesCompoundsAndSubstringFindsThem() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { s ->
                ChatDbSchema.create.forEach { s.execute(it) }
                val rows = listOf("엄마 생일선물은 등산 스틱" to 10, "선물 예산은 10만 원" to 20, "회사는 강남역 100%" to 30)
                rows.forEachIndexed { i, (text, at) ->
                    s.execute(
                        "INSERT INTO memories (id, text, kind, confidence, created_at, updated_at) " +
                            "VALUES (${i + 1}, '$text', 'ME', 'SURE', $at, $at)",
                    )
                }
            }
            // FTS prefix terms find "선물" only at a word start.
            assertEquals(listOf(2L), c.ids(MemorySql.MATCHING, MemoryText.ftsQuery("선물")!!, MemorySql.FETCH.toString()))
            // Substring LIKE also finds it inside "생일선물"; newest first.
            val terms = MemoryText.terms("선물")
            assertEquals(listOf(2L, 1L), c.ids(MemorySql.substring(terms.size), *terms.map(MemorySql::likeArg).toTypedArray(), "10"))
            // Wildcards in a term are literal.
            assertEquals(emptyList<Long>(), c.ids(MemorySql.substring(1), MemorySql.likeArg("_"), "10"))
            assertEquals(listOf(3L, 2L, 1L), c.ids(MemorySql.RECENT, "5"))
        }
    }
}
