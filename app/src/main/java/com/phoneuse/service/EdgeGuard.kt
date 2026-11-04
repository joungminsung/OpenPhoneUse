package com.phoneuse.service

/**
 * Keeps an injected swipe from starting in a system gesture strip: a start near a side edge is taken as 뒤로가기,
 * near the bottom as 홈, near the top as the notification shade. The whole stroke shifts inward so its direction
 * and most of its length survive; the end only has to stay on screen.
 */
internal object EdgeGuard {
    data class Stroke(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

    /** [side], [top] and [bottom] are the strip widths in px. */
    fun keepOff(s: Stroke, width: Int, height: Int, side: Int, top: Int, bottom: Int): Stroke {
        val x1 = s.x1.coerceIn(side, maxOf(side, width - 1 - side))
        val y1 = s.y1.coerceIn(top, maxOf(top, height - 1 - bottom))
        val dx = x1 - s.x1
        val dy = y1 - s.y1
        return Stroke(
            x1, y1,
            (s.x2 + dx).coerceIn(0, maxOf(0, width - 1)),
            (s.y2 + dy).coerceIn(0, maxOf(0, height - 1)),
        )
    }

    /**
     * The two fingers of a pinch around (x, y), side by side: from close together to far apart when [zoomIn], the
     * other way otherwise. The center stays off the gesture strips, and moves inward so both fingers stay between the side strips.
     */
    fun pinch(x: Int, y: Int, zoomIn: Boolean, width: Int, height: Int, side: Int, top: Int, bottom: Int): Pair<Stroke, Stroke> {
        val far = minOf((width * PINCH_FAR).toInt(), maxOf(0, (width - 1 - 2 * side) / 2))
        val near = minOf((width * PINCH_NEAR).toInt(), far)
        // Near an edge the center moves inward, so the fingers still travel their full distance.
        val cx = x.coerceIn(side + far, maxOf(side + far, width - 1 - side - far))
        val cy = y.coerceIn(top, maxOf(top, height - 1 - bottom))
        val (from, to) = if (zoomIn) near to far else far to near
        return Stroke(cx - from, cy, cx - to, cy) to Stroke(cx + from, cy, cx + to, cy)
    }

    /** Each finger's distance from the center at the close and far ends of a pinch, as a share of the width. */
    private const val PINCH_NEAR = 0.05
    private const val PINCH_FAR = 0.3
}
