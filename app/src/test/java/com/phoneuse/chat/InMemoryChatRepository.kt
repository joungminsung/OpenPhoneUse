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

/** Keeps everything in memory; for tests. */
class InMemoryChatRepository(private val now: () -> Long = { System.currentTimeMillis() }) : ChatRepository {
    private data class Data(val conversations: List<Conversation> = emptyList(), val messages: List<ChatMessage> = emptyList())

    private val data = MutableStateFlow(Data())
    private val nextConversationId = AtomicLong(1)
    private val nextMessageId = AtomicLong(1)

    override fun conversations(): Flow<List<Conversation>> =
        data.map { d -> d.conversations.sortedWith(compareByDescending<Conversation> { it.updatedAt }.thenByDescending { it.id }) }
            .distinctUntilChanged()

    override fun messages(conversationId: Long): Flow<List<ChatMessage>> =
        data.map { d -> d.messages.filter { it.conversationId == conversationId }.sortedBy { it.id } }
            .distinctUntilChanged()

    override suspend fun createConversation(title: String): Long {
        val id = nextConversationId.getAndIncrement()
        val at = now()
        data.update { it.copy(conversations = it.conversations + Conversation(id, title, at)) }
        return id
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
    ): Long {
        if (thirdParty) markThirdPartySeen(conversationId)
        val id = nextMessageId.getAndIncrement()
        val at = now()
        var stored = false
        data.update { d ->
            stored = d.conversations.any { it.id == conversationId }
            if (!stored) return@update d
            d.copy(
                conversations = d.touch(conversationId, at),
                messages = d.messages + ChatMessage(id, conversationId, role, text, steps, at, cards, extras, attachments),
            )
        }
        return if (stored) id else ChatRepository.NO_MESSAGE
    }

    override suspend fun rename(conversationId: Long, title: String) {
        data.update { d -> d.copy(conversations = d.conversations.map { if (it.id == conversationId) it.copy(title = title) else it }) }
    }

    private val thirdParty = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    override suspend fun thirdPartySeen(conversationId: Long) = conversationId in thirdParty

    override suspend fun markThirdPartySeen(conversationId: Long) {
        if (data.value.conversations.any { it.id == conversationId }) thirdParty += conversationId
    }

    override suspend fun delete(conversationId: Long) {
        thirdParty -= conversationId
        data.update { d ->
            d.copy(
                conversations = d.conversations.filter { it.id != conversationId },
                messages = d.messages.filter { it.conversationId != conversationId },
            )
        }
    }

    private fun Data.touch(conversationId: Long, at: Long) =
        conversations.map { if (it.id == conversationId) it.copy(updatedAt = at) else it }
}
