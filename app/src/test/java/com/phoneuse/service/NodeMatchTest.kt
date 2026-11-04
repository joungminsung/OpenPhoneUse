package com.phoneuse.service

import com.phoneuse.screen.Bounds
import com.phoneuse.screen.UiNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeMatchTest {
    private val button = UiNode(
        1, listOf(0, 2), "android.widget.Button", "보내기", null, null, "com.app:id/send", Bounds(800, 2000, 1000, 2100),
    )

    @Test fun pathNodeThatMovedIsStillTheSameElement() {
        // The keyboard pushed it up: same class, id and text.
        assertTrue(NodeMatch.atPath(button, "android.widget.Button", "com.app:id/send", "보내기", null, Bounds(800, 1200, 1000, 1300)))
        assertTrue(NodeMatch.atPath(button, "android.widget.Button", null, null, null, button.bounds))
    }

    @Test fun pathNodeWithOtherTextOrClassIsNot() {
        assertFalse(NodeMatch.atPath(button, "android.widget.Button", "com.app:id/send", "삭제", null, Bounds(0, 0, 10, 10)))
        assertFalse(NodeMatch.atPath(button, "android.widget.TextView", "com.app:id/send", "보내기", null, button.bounds))
        val anonymous = button.copy(text = null, viewId = null)
        assertFalse(NodeMatch.atPath(anonymous, "android.widget.Button", null, null, null, Bounds(800, 1200, 1000, 1300)))
    }

    @Test fun positionMatchNeedsTheSameClassAndSize() {
        assertTrue(NodeMatch.byPosition(button, "android.widget.Button", Bounds(802, 2001, 1002, 2101)))
        // A list row that shares the centre is a different, larger element.
        assertFalse(NodeMatch.byPosition(button, "android.widget.Button", Bounds(0, 1900, 1800, 2200)))
        assertFalse(NodeMatch.byPosition(button, "android.widget.LinearLayout", button.bounds))
    }
}
