package com.phoneuse.ui.cards

import com.phoneuse.ui.fold.PaneCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class CardStateStoreTest {
    private class State(var selected: Int = 0)

    private fun store(max: Int = CardStateStore.MAX_CARDS) = CardStateStore(max) { State() }

    @Test fun theSameKeyGivesTheSameStateToEveryCopy() {
        val s = store()
        val inline = s.get(PaneCard.keyOf(10, 0))
        inline.selected = 2
        // The result pane (or flex mode) draws the same card: it sees the selection made in the chat.
        val pane = s.get(PaneCard.keyOf(10, 0))
        assertSame(inline, pane)
        assertEquals(2, pane.selected)
        assertNotSame(inline, s.get(PaneCard.keyOf(10, 1)))
    }

    @Test fun keepsOnlyTheMostRecentlyUsedCards() {
        val s = store(max = 3)
        val first = s.get("a")
        s.get("b")
        s.get("c")
        s.get("a") // used again: now the newest
        s.get("d") // evicts "b", the least recently used
        assertEquals(3, s.size)
        assertSame(first, s.get("a"))
        // Three newer cards push "a" out.
        s.get("e")
        s.get("f")
        s.get("g")
        assertEquals(3, s.size)
        assertNotSame(first, s.get("a"))
    }

    @Test fun capIsTwentyCards() {
        val s = store()
        repeat(30) { s.get("card:$it:0") }
        assertEquals(20, s.size)
    }
}
