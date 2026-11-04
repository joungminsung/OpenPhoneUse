package com.phoneuse.app

import com.phoneuse.chat.Attachment
import com.phoneuse.chat.ChatRepository
import com.phoneuse.chat.MessageRole
import kotlinx.coroutines.flow.first

/** Where a run's user message landed. */
data class StoredUserMessage(val conversationId: Long, val messageId: Long, val movedToNew: Boolean)

object ConversationStart {
    /**
     * Stores the user message of a run in [conversationId]. When that conversation no longer exists (deleted by
     * the user or by retention, or a stale id from a notification or a restored screen), the message goes to a new
     * conversation titled [newTitle] instead, so neither it nor the run's reply is silently dropped.
     */
    suspend fun storeUserMessage(
        chats: ChatRepository,
        conversationId: Long,
        newTitle: String,
        text: String,
        attachments: List<Attachment>,
        thirdParty: Boolean,
    ): StoredUserMessage {
        val stored = chats.addMessage(conversationId, MessageRole.USER, text, attachments = attachments, thirdParty = thirdParty)
        if (stored != ChatRepository.NO_MESSAGE) return StoredUserMessage(conversationId, stored, movedToNew = false)
        val fresh = chats.createConversation(newTitle)
        val again = chats.addMessage(fresh, MessageRole.USER, text, attachments = attachments, thirdParty = thirdParty)
        return StoredUserMessage(fresh, again, movedToNew = true)
    }

    /**
     * After a process death mid-run (crash, OOM kill, update) a conversation ends with the user's message and no
     * reply. Each such conversation whose last message is older than [startedAt] (so never one a run of this process
     * is answering) gets an assistant note [text], so the chat shows what happened and the next run's history does
     * not carry two user turns in a row (the model might redo the unfinished action). The run may have read text
     * the user did not write before it died, so the conversation is flagged third-party. Returns the ids marked.
     */
    suspend fun markInterrupted(chats: ChatRepository, startedAt: Long, text: String): List<Long> {
        val marked = ArrayList<Long>()
        for (c in chats.conversations().first()) {
            val last = chats.messages(c.id).first().lastOrNull() ?: continue
            if (last.role != MessageRole.USER || last.createdAt >= startedAt) continue
            if (chats.addMessage(c.id, MessageRole.ASSISTANT, text, thirdParty = true) != ChatRepository.NO_MESSAGE) marked += c.id
        }
        return marked
    }
}
