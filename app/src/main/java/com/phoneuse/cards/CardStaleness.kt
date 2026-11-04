package com.phoneuse.cards

import com.phoneuse.chat.ChatMessage

/**
 * A card button's prompt names its card only by kind and position ("this event", "photos 1, 3", "item 2"), and the
 * model reads it against the newest card of that kind. So a prompt button works only on the newest card of its
 * kind in the conversation; an older card of the same kind would act on the wrong one.
 */
object CardStaleness {
    /** A card by its message id and position in that message. */
    data class CardAt(val messageId: Long, val index: Int)

    /** The cards that a later card of the same kind (in a later message, or later in the same one) supersedes. */
    fun superseded(messages: List<ChatMessage>): Set<CardAt> {
        val seen = HashSet<Class<out CardSpec>>()
        val out = HashSet<CardAt>()
        for (m in messages.asReversed()) {
            for (i in m.cards.indices.reversed()) {
                if (!seen.add(m.cards[i].javaClass)) out += CardAt(m.id, i)
            }
        }
        return out
    }

    /** Whether prompt buttons of card [index] in message [messageId] should act (the newest card of its kind). */
    fun promptsAllowed(superseded: Set<CardAt>, messageId: Long, index: Int): Boolean = CardAt(messageId, index) !in superseded
}
