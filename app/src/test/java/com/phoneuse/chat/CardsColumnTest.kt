package com.phoneuse.chat

import com.phoneuse.cards.CardJson
import com.phoneuse.cards.ResultItem
import com.phoneuse.cards.ResultsCard
import com.phoneuse.cards.SourceLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardsColumnTest {
    private val card = ResultsCard("전시", listOf(ResultItem("시립 미술관")))
    private val extras = ReplyExtras(3, listOf(SourceLink("A", "https://a.example")), "빠른 모드를 껐어요")

    @Test fun roundTripsCardsAndExtras() {
        val raw = CardsColumn.encode(listOf(card), extras)!!
        assertEquals(listOf(card) to extras, CardsColumn.decode(raw))
        // Older readers that only know cards still see just the cards.
        assertEquals(listOf(card), CardJson.decodeList(raw))
    }

    @Test fun extrasWithoutCards() {
        val raw = CardsColumn.encode(emptyList(), extras)!!
        assertEquals(emptyList<Any>() to extras, CardsColumn.decode(raw))
    }

    @Test fun nothingStoresNullAndOldValuesReadWithoutExtras() {
        assertNull(CardsColumn.encode(emptyList(), ReplyExtras.NONE))
        val old = CardJson.encodeList(listOf(card))
        assertEquals(listOf(card) to ReplyExtras.NONE, CardsColumn.decode(old))
        assertTrue(CardsColumn.decode("garbage reply_extras").second.isEmpty)
    }

    @Test fun roundTripsUsedAndRememberedMemories() {
        val withMemory = ReplyExtras(
            usedMemories = listOf(MemoryRef(4, "엄마 · 요즘 등산 시작"), MemoryRef(9, "선물 예산 10만 원 안쪽")),
            remembered = listOf(MemoryRef(12, "엄마 생신 11.20")),
        )
        assertTrue(!withMemory.isEmpty)
        assertEquals(listOf(4L, 9L), withMemory.usedMemoryIds)
        val raw = CardsColumn.encode(listOf(card), withMemory)!!
        assertEquals(listOf(card) to withMemory, CardsColumn.decode(raw))
        // Search counts and sources still come back next to them.
        val all = extras.copy(usedMemories = withMemory.usedMemories, remembered = withMemory.remembered)
        assertEquals(emptyList<Any>() to all, CardsColumn.decode(CardsColumn.encode(emptyList(), all)))
    }

    @Test fun roundTripsThePlanUsageLimitOnAReplyWithNothingElse() {
        val limit = ReplyExtras(planUsageLimit = true)
        assertTrue(!limit.isEmpty)
        assertEquals(emptyList<Any>() to limit, CardsColumn.decode(CardsColumn.encode(emptyList(), limit)!!))
    }

    @Test fun roundTripsForgottenMemories() {
        val f = ForgottenMemory(12, "엄마 생신 11.20", kind = "PERSON", confidence = "SURE", date = "11월 20일", conversationId = 3, origin = "선물")
        val withForgot = ReplyExtras(forgotten = listOf(f))
        assertTrue(!withForgot.isEmpty)
        assertEquals(emptyList<Any>() to withForgot, CardsColumn.decode(CardsColumn.encode(emptyList(), withForgot)!!))
    }
}
