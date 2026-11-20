package com.phoneuse.service

import com.phoneuse.agent.TwoFingerGesture
import kotlin.math.abs

/**
 * Keeps injected touches out of the system gesture strips: a start near a side edge is taken as 뒤로가기,
 * near the bottom as 홈, near the top as the notification shade.
 */
internal object EdgeGuard {
    data class Stroke(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

    class Edges(val width: Int, val height: Int, val side: Int, val top: Int, val bottom: Int) {
        private val right get() = width - 1 - side
        private val lastY get() = height - 1 - bottom
        private val shortSide get() = minOf(width, height)
        private fun onScreen(x: Int, y: Int) = x in 0 until width && y in 0 until height

        fun rotate(x: Int, y: Int, degrees: Int, spreadPx: Int? = null): TwoFingerGesture.Rotation? {
            if (!onScreen(x, y) || degrees == 0 || degrees !in -180..180 || (spreadPx != null && spreadPx <= 0)) return null
            val maxRadius = maxOf(1, shortSide / 6)
            val requestedRadius = spreadPx?.let { (it / 2).coerceIn(1, maxRadius) } ?: maxRadius
            // Keep the requested pivot wherever possible. Near an edge first shorten the radius,
            // then move the pivot just enough to leave useful separation between the fingers.
            val minRadius = minOf(requestedRadius, maxOf(1, shortSide / 20))
            if (right - side < 2 * minRadius || lastY - top < 2 * minRadius) return null
            val cx = x.coerceIn(side + minRadius, right - minRadius)
            val cy = y.coerceIn(top + minRadius, lastY - minRadius)
            val radius = minOf(requestedRadius, cx - side, right - cx, cy - top, lastY - cy)
            return TwoFingerGesture.Rotation(cx, cy, radius, degrees)
        }

        fun twoFingerSwipe(x1: Int, y1: Int, x2: Int, y2: Int): TwoFingerGesture.Swipe? {
            if (!onScreen(x1, y1) || !onScreen(x2, y2) || (x1 == x2 && y1 == y2)) return null
            val dx = x2 - x1
            val dy = y2 - y1
            val spacing = maxOf(1, shortSide / 20)
            // Translate the pair as a unit so neither finger is clipped or travels a different distance.
            val left = side + spacing
            val lastX = right - spacing
            if (abs(dx) > lastX - left || abs(dy) > lastY - top) return null
            val sx = x1.coerceIn(left - minOf(0, dx), lastX - maxOf(0, dx))
            val sy = y1.coerceIn(top - minOf(0, dy), lastY - maxOf(0, dy))
            return TwoFingerGesture.Swipe(sx, sy, sx + dx, sy + dy, spacing)
        }
    }

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
