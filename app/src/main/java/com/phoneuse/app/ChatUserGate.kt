package com.phoneuse.app

import com.phoneuse.agent.UserGate
import com.phoneuse.risk.RiskCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A question card shown in the chat screen; the same shapes as the overlay's DockCard. */
sealed interface ChatCard {
    val id: Long

    /** The conversation of the run that asks; the chat shows and answers the card only there. Null: unknown. */
    val conversationId: Long?

    data class Confirm(
        val category: RiskCategory,
        val summary: String,
        override val id: Long,
        override val conversationId: Long? = null,
    ) : ChatCard

    data class Ask(
        val question: String,
        val options: List<String>,
        override val id: Long,
        override val conversationId: Long? = null,
    ) : ChatCard

    data class HandOff(val reason: String, override val id: Long, override val conversationId: Long? = null) : ChatCard

    /** Whether this card belongs to the chat showing [shown] (a card of unknown conversation shows anywhere). */
    fun belongsTo(shown: Long?): Boolean = conversationId == null || conversationId == shown
}

/**
 * Shows a card in the chat screen and suspends until [answer] is called with its id. One request at a time.
 * Answers for any other id are ignored; cancelling the waiting request removes the card.
 */
class ChatUserGate(
    /** The asking run's conversation, stamped on each card. */
    private val conversationId: () -> Long? = { null },
) : UserGate {
    private val mutex = Mutex()
    private val _card = MutableStateFlow<ChatCard?>(null)
    val card: StateFlow<ChatCard?> = _card.asStateFlow()

    private class Pending(val id: Long, val answer: CompletableDeferred<String>)

    @Volatile
    private var pending: Pending? = null
    private var nextId = 0L

    fun answer(id: Long, text: String) {
        pending?.takeIf { it.id == id }?.answer?.complete(text)
    }

    override suspend fun confirm(category: RiskCategory, summary: String): Boolean =
        request { ChatCard.Confirm(category, summary, it, conversationId()) } == CONFIRM_YES

    override suspend fun ask(question: String, options: List<String>): String =
        request { ChatCard.Ask(question, options, it, conversationId()) }

    override suspend fun handToUser(reason: String) {
        request { ChatCard.HandOff(reason, it, conversationId()) }
    }

    private suspend fun request(makeCard: (Long) -> ChatCard): String = mutex.withLock {
        val request = Pending(++nextId, CompletableDeferred())
        pending = request
        _card.value = makeCard(request.id)
        try {
            request.answer.await()
        } finally {
            if (pending?.id == request.id) pending = null
            _card.update { if (it?.id == request.id) null else it }
        }
    }

    companion object {
        const val CONFIRM_YES = "허용"
        const val CONFIRM_NO = "거부"
        const val HAND_OFF_DONE = "완료"
    }
}
