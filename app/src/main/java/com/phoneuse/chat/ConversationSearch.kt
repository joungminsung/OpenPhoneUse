package com.phoneuse.chat

import android.content.Context
import com.phoneuse.memory.MemoryText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A past conversation that mentions what was searched for; [at] is the matching message's local time. */
data class ConversationHit(val conversationId: Long, val title: String, val snippet: String, val at: String)

/** Full-text search over stored messages (search_conversations). */
fun interface ConversationSearch {
    /** At most [limit] conversations, newest match first, one hit each; [excludeConversation] is left out. */
    suspend fun search(query: String, limit: Int, excludeConversation: Long?): List<ConversationHit>
}

/** The SQL behind [SqliteConversationSearch], and turning its rows into hits; testable on plain SQLite. */
object ConversationSearchSql {
    /** Args: the FTS MATCH expression, the conversation id to skip (-1 for none). */
    const val QUERY =
        "SELECT m.conversation_id, c.title, m.text, m.created_at FROM messages_fts f " +
            "JOIN messages m ON m.id = f.docid JOIN conversations c ON c.id = m.conversation_id " +
            "WHERE messages_fts MATCH ? AND m.conversation_id != ? ORDER BY m.created_at DESC LIMIT 200"

    data class Row(val conversationId: Long, val title: String, val text: String, val createdAt: Long)

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun hits(rows: List<Row>, terms: List<String>, limit: Int, zone: ZoneId = ZoneId.systemDefault()): List<ConversationHit> =
        rows.distinctBy { it.conversationId }.take(limit).map {
            ConversationHit(
                it.conversationId,
                it.title,
                Snippets.around(it.text, terms),
                TIME.format(Instant.ofEpochMilli(it.createdAt).atZone(zone)),
            )
        }
}

object Snippets {
    /** About [max] characters of [text] around the first of [terms] it contains, "…" marking cut ends. */
    fun around(text: String, terms: List<String>, max: Int = 80): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.length <= max) return t
        val lower = t.lowercase()
        val at = terms.mapNotNull { term -> lower.indexOf(term).takeIf { it >= 0 } }.minOrNull() ?: 0
        val start = (at - max / 3).coerceIn(0, t.length - max)
        val end = start + max
        return (if (start > 0) "…" else "") + t.substring(start, end).trim() + (if (end < t.length) "…" else "")
    }
}

/** [ConversationSearch] on chat.db's `messages_fts`. */
class SqliteConversationSearch(private val db: ChatDb) : ConversationSearch {
    constructor(context: Context) : this(ChatDb.get(context))

    override suspend fun search(query: String, limit: Int, excludeConversation: Long?): List<ConversationHit> {
        val match = MemoryText.ftsQuery(query) ?: return emptyList()
        val rows = withContext(Dispatchers.IO) {
            db.readable.rawQuery(ConversationSearchSql.QUERY, arrayOf(match, (excludeConversation ?: -1L).toString())).use { c ->
                buildList {
                    while (c.moveToNext()) add(ConversationSearchSql.Row(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
                }
            }
        }
        return ConversationSearchSql.hits(rows, MemoryText.terms(query), limit)
    }
}
