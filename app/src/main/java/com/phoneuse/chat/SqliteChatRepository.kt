package com.phoneuse.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.phoneuse.cards.CardSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [ChatRepository] on SQLite (`chat.db`). Each write bumps [version], which makes every open flow re-query.
 * Deleting a conversation also deletes its attachment images from [attachmentsDir].
 */
class SqliteChatRepository(private val helper: ChatDb, private val attachmentsDir: File? = null) : ChatRepository {
    constructor(context: Context) : this(ChatDb.get(context), File(context.filesDir, AttachmentFiles.DIR))

    private val version = MutableStateFlow(0L)

    override fun conversations(): Flow<List<Conversation>> = version.map {
        helper.readableDatabase.query(
            "conversations", arrayOf("id", "title", "updated_at"), null, null, null, null, "updated_at DESC, id DESC",
        ).use { c ->
            buildList { while (c.moveToNext()) add(Conversation(c.getLong(0), c.getString(1), c.getLong(2))) }
        }
    }.flowOn(Dispatchers.IO)

    override fun messages(conversationId: Long): Flow<List<ChatMessage>> = version.map {
        helper.readableDatabase.query(
            "messages",
            arrayOf("id", "conversation_id", "role", "text", "steps_json", "created_at", "cards_json", "attachments_json"),
            "conversation_id = ?", arrayOf(conversationId.toString()), null, null, "id ASC",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val (cards, extras) = CardsColumn.decode(c.getString(6))
                    add(
                        ChatMessage(
                            id = c.getLong(0),
                            conversationId = c.getLong(1),
                            role = MessageRole.valueOf(c.getString(2)),
                            text = c.getString(3),
                            steps = StepsCodec.decode(c.getString(4)),
                            createdAt = c.getLong(5),
                            cards = cards,
                            extras = extras,
                            attachments = AttachmentsColumn.decode(c.getString(7)),
                        ),
                    )
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun createConversation(title: String): Long = write { db ->
        db.insertOrThrow("conversations", null, ContentValues().apply {
            put("title", title)
            put("updated_at", System.currentTimeMillis())
        })
    }

    override suspend fun addMessage(
        conversationId: Long,
        role: MessageRole,
        text: String,
        steps: List<StepLog>,
        cards: List<CardSpec>,
        extras: ReplyExtras,
        attachments: List<Attachment>,
        thirdParty: Boolean,
    ): Long =
        write { db ->
            // A message for a deleted (or unknown) conversation is dropped rather than left orphaned.
            val exists = db.rawQuery("SELECT 1 FROM conversations WHERE id = ?", arrayOf(conversationId.toString()))
                .use { it.moveToFirst() }
            if (!exists) return@write ChatRepository.NO_MESSAGE
            // The flag first, in the same transaction: third-party text is never stored in a clean conversation.
            if (thirdParty) {
                db.update("conversations", ContentValues().apply { put("third_party_seen", 1) }, "id = ?", arrayOf(conversationId.toString()))
            }
            val now = System.currentTimeMillis()
            val id = db.insertOrThrow("messages", null, ContentValues().apply {
                put("conversation_id", conversationId)
                put("role", role.name)
                put("text", text)
                put("steps_json", StepsCodec.encode(steps))
                put("created_at", now)
                CardsColumn.encode(cards, extras)?.let { put("cards_json", it) }
                AttachmentsColumn.encode(attachments)?.let { put("attachments_json", it) }
            })
            touch(db, conversationId, now)
            id
        }

    /** File names of every stored attachment image, for the startup sweep of unreferenced files. */
    suspend fun attachmentFiles(): Set<String> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT attachments_json FROM messages WHERE attachments_json IS NOT NULL", null).use { c ->
            buildSet {
                while (c.moveToNext()) {
                    for (a in AttachmentsColumn.decode(c.getString(0))) a.file?.let { add(File(it).name) }
                }
            }
        }
    }

    override suspend fun rename(conversationId: Long, title: String) {
        write { db ->
            db.update("conversations", ContentValues().apply { put("title", title) }, "id = ?", arrayOf(conversationId.toString()))
        }
    }

    override suspend fun delete(conversationId: Long) {
        // One transaction: the conversation and its messages go together; their files go once the rows are gone.
        val files = write { db ->
            val attached = db.rawQuery(
                "SELECT attachments_json FROM messages WHERE conversation_id = ? AND attachments_json IS NOT NULL",
                arrayOf(conversationId.toString()),
            ).use { c -> buildList { while (c.moveToNext()) addAll(AttachmentsColumn.decode(c.getString(0))) } }
            db.delete("messages", "conversation_id = ?", arrayOf(conversationId.toString()))
            db.delete("conversations", "id = ?", arrayOf(conversationId.toString()))
            attached
        }
        attachmentsDir?.let { dir -> withContext(Dispatchers.IO) { AttachmentFiles.delete(files, dir) } }
    }

    override suspend fun thirdPartySeen(conversationId: Long): Boolean = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT third_party_seen FROM conversations WHERE id = ?",
            arrayOf(conversationId.toString()),
        ).use { c -> c.moveToFirst() && c.getInt(0) != 0 }
    }

    override suspend fun markThirdPartySeen(conversationId: Long) {
        write { db ->
            db.update("conversations", ContentValues().apply { put("third_party_seen", 1) }, "id = ?", arrayOf(conversationId.toString()))
        }
    }

    private suspend fun <T> write(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        val result = try {
            block(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
        version.update { it + 1 }
        result
    }

    private fun touch(db: SQLiteDatabase, conversationId: Long, at: Long) {
        db.update("conversations", ContentValues().apply { put("updated_at", at) }, "id = ?", arrayOf(conversationId.toString()))
    }
}
