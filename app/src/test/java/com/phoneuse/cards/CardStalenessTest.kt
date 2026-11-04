package com.phoneuse.cards

import com.phoneuse.chat.ChatMessage
import com.phoneuse.chat.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardStalenessTest {
    private fun msg(id: Long, vararg cards: CardSpec) =
        ChatMessage(id, 1, MessageRole.ASSISTANT, "", emptyList(), id, cards.toList())

    @Test fun onlyTheNewestCardOfAKindTakesPromptButtons() {
        val oldPhotos = PhotosCard("p", listOf("content://a", "content://b"))
        val newPhotos = PhotosCard("p", listOf("content://c"))
        val draft = DraftCard("sms", "엄마", null, "곧 가요")
        val messages = listOf(msg(1, oldPhotos, draft), msg(2), msg(3, newPhotos))
        val superseded = CardStaleness.superseded(messages)
        assertFalse("an older photos card would act on the newer one", CardStaleness.promptsAllowed(superseded, 1, 0))
        assertTrue(CardStaleness.promptsAllowed(superseded, 1, 1))
        assertTrue(CardStaleness.promptsAllowed(superseded, 3, 0))
        assertEquals(setOf(CardStaleness.CardAt(1, 0)), superseded)
    }

    @Test fun twoCardsOfAKindInOneMessageKeepOnlyTheLast() {
        val superseded = CardStaleness.superseded(listOf(msg(1, PhotosCard("p", listOf("content://a")), PhotosCard("p", listOf("content://b")))))
        assertFalse(CardStaleness.promptsAllowed(superseded, 1, 0))
        assertTrue(CardStaleness.promptsAllowed(superseded, 1, 1))
    }
}
