package com.phoneuse.chat

import com.phoneuse.cards.CardSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/** Stored conversations. Flows re-emit after every write; [conversations] is newest-updated first. */
interface ChatRepository {
    fun conversations(): Flow<List<Conversation>>
    fun messages(conversationId: Long): Flow<List<ChatMessage>>
    suspend fun createConversation(title: String): Long
    /**
     * Returns the new message id, or [NO_MESSAGE] (nothing stored) when the conversation does not exist (e.g.
     * deleted). [thirdParty]: the message carries (or came from a run that read) text the user did not write; the
     * conversation's [thirdPartySeen] flag is then written in the same write, before the message.
     */
    suspend fun addMessage(
        conversationId: Long,
        role: MessageRole,
        text: String,
        steps: List<StepLog> = emptyList(),
        cards: List<CardSpec> = emptyList(),
        extras: ReplyExtras = ReplyExtras.NONE,
        attachments: List<Attachment> = emptyList(),
        thirdParty: Boolean = false,
    ): Long

    suspend fun rename(conversationId: Long, title: String)
    /** Removes the conversation together with all of its messages and their attachment files. */
    suspend fun delete(conversationId: Long)

    /**
     * Whether a run of this conversation ever read text the user did not write: its history may carry
     * that text, so later runs start history-tainted. False for an unknown conversation.
     */
    suspend fun thirdPartySeen(conversationId: Long): Boolean

    /** Records [thirdPartySeen] for the conversation; it is never cleared. */
    suspend fun markThirdPartySeen(conversationId: Long)

    companion object {
        const val NO_MESSAGE = -1L
    }
}

/** steps_json: a JSON array of step texts. A value that does not parse reads as no steps. */
object StepsCodec {
    private val serializer = ListSerializer(String.serializer())

    fun encode(steps: List<StepLog>): String = Json.encodeToString(serializer, steps.map { it.text })

    fun decode(raw: String?): List<StepLog> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            Json.decodeFromString(serializer, raw).map { StepLog(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
