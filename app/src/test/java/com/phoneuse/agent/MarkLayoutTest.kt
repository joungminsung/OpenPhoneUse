package com.phoneuse.agent

import com.phoneuse.screen.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkLayoutTest {
    private val measure: (String) -> Float = { it.length * 10f }

    @Test fun tagsSitAtTheTopLeftOfTheirElementScaledToTheImage() {
        val tags = MarkLayout.place(listOf(ScreenMark(7, Bounds(200, 400, 600, 800))), 0.5f, 540, 1200, measure)
        val t = tags.single()
        assertEquals("7", t.label)
        assertEquals(100f, t.box.left)
        assertEquals(200f, t.box.top)
        assertEquals(t.box.left, t.tag.left)
        assertEquals(t.box.top, t.tag.top)
    }

    @Test fun aTagThatWouldCoverAnotherMovesToAFreeCorner() {
        val marks = listOf(ScreenMark(1, Bounds(0, 0, 1080, 300)), ScreenMark(2, Bounds(0, 0, 500, 100)))
        val tags = MarkLayout.place(marks, 1f, 1080, 2400, measure)
        assertFalse(tags[0].tag.overlaps(tags[1].tag))
    }

    @Test fun tagsStayInsideTheImageAndEmptyElementsGetNone() {
        val marks = listOf(ScreenMark(1, Bounds(1070, 2390, 1200, 2600)), ScreenMark(2, Bounds(5, 5, 5, 50)))
        val tags = MarkLayout.place(marks, 1f, 1080, 2400, measure)
        assertEquals(listOf(1), tags.map { it.index })
        val t = tags.single().tag
        assertTrue(t.left >= 0 && t.top >= 0 && t.right <= 1080 && t.bottom <= 2400)
    }
}
