package com.phoneuse.agent

import kotlin.math.abs
import kotlin.math.hypot

/** A fitted display-space gesture. The risk check and the dispatcher use the same contact positions. */
sealed class TwoFingerGesture {
    abstract val starts: List<Pair<Int, Int>>
    abstract val travelPx: Double

    /** Positive angles turn clockwise on screen. The contacts start on opposite sides of the center. */
    data class Rotation(val cx: Int, val cy: Int, val radius: Int, val degrees: Int) : TwoFingerGesture() {
        override val starts get() = listOf(cx - radius to cy, cx + radius to cy)
        override val travelPx get() = radius * Math.toRadians(abs(degrees).toDouble())
    }

    /** The two center points are the midpoints between the fingers, which stay a fixed distance apart. */
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val halfSpacing: Int) : TwoFingerGesture() {
        override val starts get() = listOf(x1 - halfSpacing to y1, x1 + halfSpacing to y1)
        override val travelPx get() = hypot(x2.toDouble() - x1, y2.toDouble() - y1)
    }
}
