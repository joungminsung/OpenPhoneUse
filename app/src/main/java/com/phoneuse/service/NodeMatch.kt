package com.phoneuse.service

import com.phoneuse.screen.Bounds
import com.phoneuse.screen.UiNode
import kotlin.math.abs

/**
 * Whether a live node is the element the model was shown ([UiNode]), for [AccessibilityTreeReader.resolve]. Pure, so
 * the rules are tested without a device.
 */
internal object NodeMatch {
    /** How far a re-found element's size may differ (px) and still be the same element. */
    const val SIZE_SLACK = 8

    /**
     * The node at the element's child path: the same class, and either the same bounds or (it moved: a keyboard
     * resize, an animated sheet) the same id and text.
     */
    fun atPath(expected: UiNode, cls: String?, viewId: String?, text: String?, desc: String?, b: Bounds): Boolean {
        if (expected.className != null && cls != expected.className) return false
        if (b == expected.bounds) return true
        val keys = listOf(expected.viewId to viewId, expected.text to text, expected.contentDescription to desc)
        if (keys.all { it.first == null }) return false
        return keys.all { (want, got) -> want == null || want == got }
    }

    /**
     * A node found by position instead: the same class, the same centre (within [SIZE_SLACK]) and about the same
     * size, so a larger card or list row that happens to share the centre is never taken for it.
     */
    fun byPosition(expected: UiNode, cls: String?, b: Bounds): Boolean {
        if (expected.className != null && cls != expected.className) return false
        val e = expected.bounds
        return abs(b.centerX - e.centerX) <= SIZE_SLACK && abs(b.centerY - e.centerY) <= SIZE_SLACK &&
            abs((b.right - b.left) - (e.right - e.left)) <= SIZE_SLACK &&
            abs((b.bottom - b.top) - (e.bottom - e.top)) <= SIZE_SLACK
    }
}
