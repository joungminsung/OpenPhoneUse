package com.phoneuse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    private fun feedAll(parser: SseParser, vararg lines: String) = lines.mapNotNull { parser.feed(it) }

    @Test fun eventAndDataEmitOnBlankLine() {
        val events = feedAll(SseParser(), "event: a", "data: {\"x\":1}", "")
        assertEquals(listOf(SseEvent("a", "{\"x\":1}")), events)
    }

    @Test fun multipleDataLinesJoinedWithNewline() {
        val events = feedAll(SseParser(), "event: a", "data: one", "data: two", "")
        assertEquals(listOf(SseEvent("a", "one\ntwo")), events)
    }

    @Test fun commentsAreIgnored() {
        val p = SseParser()
        assertNull(p.feed(": keep-alive"))
        assertEquals(listOf(SseEvent("a", "d")), feedAll(p, "event: a", ": hi", "data: d", ""))
    }

    @Test fun nextEventLineFlushesPreviousEvent() {
        val p = SseParser()
        assertNull(p.feed("event: a"))
        assertNull(p.feed("data: 1"))
        assertEquals(SseEvent("a", "1"), p.feed("event: b"))
        assertNull(p.feed("data: 2"))
        assertEquals(SseEvent("b", "2"), p.feed(""))
    }

    @Test fun blankLineWithoutDataEmitsNothingAndResetClears() {
        val p = SseParser()
        assertNull(p.feed(""))
        p.feed("event: a")
        p.reset()
        assertNull(p.feed(""))
    }
}
