package com.phoneuse.chat

import com.phoneuse.cards.CardSpec
import com.phoneuse.cards.SourceLink
import kotlinx.serialization.Serializable

enum class MessageRole { USER, ASSISTANT }

/** One action line the agent reported while producing an assistant message. */
data class StepLog(val text: String)

/** A memory as a reply shows it: its id (to open or undo it) and its text at the time. */
@Serializable
data class MemoryRef(val id: Long, val text: String)

/**
 * A memory the forget tool removed, with everything needed to put it back from the reply's
 * "잊었어요 · … [되돌리기]" line. [kind] and [confidence] are the enum names.
 */
@Serializable
data class ForgottenMemory(
    val id: Long,
    val text: String,
    val kind: String,
    val confidence: String,
    val date: String? = null,
    val due: String? = null,
    val conversationId: Long? = null,
    val origin: String? = null,
)

/**
 * What an assistant reply carries besides text, steps and cards: how many web searches it ran ("웹 n곳 확인"),
 * the web [sources] its text cites, a one-line [notice] from the app (e.g. fast mode was turned off), the
 * memories the prompt used for it (purple chips above the answer), the ones it saved ("기억했어요 · … [취소]"),
 * the ones it forgot ("잊었어요 · … [되돌리기]") and whether it is the ChatGPT plan's usage limit ("Manage usage").
 */
data class ReplyExtras(
    val webSearches: Int = 0,
    val sources: List<SourceLink> = emptyList(),
    val notice: String? = null,
    val usedMemories: List<MemoryRef> = emptyList(),
    val remembered: List<MemoryRef> = emptyList(),
    val forgotten: List<ForgottenMemory> = emptyList(),
    val planUsageLimit: Boolean = false,
) {
    val isEmpty: Boolean
        get() = webSearches <= 0 && sources.isEmpty() && notice.isNullOrBlank() && usedMemories.isEmpty() &&
            remembered.isEmpty() && forgotten.isEmpty() && !planUsageLimit

    val usedMemoryIds: List<Long> get() = usedMemories.map { it.id }

    companion object {
        val NONE = ReplyExtras()
    }
}

data class ChatMessage(
    val id: Long,
    val conversationId: Long,
    val role: MessageRole,
    val text: String,
    val steps: List<StepLog>,
    val createdAt: Long,
    /** Result cards shown with an assistant message, in the order they were produced. */
    val cards: List<CardSpec> = emptyList(),
    val extras: ReplyExtras = ReplyExtras.NONE,
    /** What the user attached to a user message (photos, file text). */
    val attachments: List<Attachment> = emptyList(),
) {
    /** Web citations of an assistant message. */
    val sources: List<SourceLink> get() = extras.sources
}

data class Conversation(val id: Long, val title: String, val updatedAt: Long)
