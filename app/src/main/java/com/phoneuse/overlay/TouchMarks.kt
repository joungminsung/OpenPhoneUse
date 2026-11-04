package com.phoneuse.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** One touch of the agent, in screen pixels: a tap or long press at (x, y), or a swipe to (x2, y2). */
data class TouchMark(val id: Long, val x: Int, val y: Int, val x2: Int?, val y2: Int?, val long: Boolean)

private val TouchCore = Color(0xFFFFFFFF)
private val TouchRing = Color(0xFFE4572E)

/**
 * Where the agent touches, drawn on the border layer (which never takes touches): a ripple for a tap, a ring that
 * fills for a long press, a fading trail with a moving dot for a swipe. The window covers the whole screen from
 * (0, 0), so screen pixels are canvas pixels.
 */
@Composable
fun TouchMarks(marks: List<TouchMark>) {
    for (mark in marks) {
        key(mark.id) { Mark(mark) }
    }
}

@Composable
private fun Mark(mark: TouchMark) {
    val swipe = mark.x2 != null && mark.y2 != null
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(if (mark.long) 900 else if (swipe) 650 else 650, easing = FastOutSlowInEasing))
    }
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        val start = Offset(mark.x.toFloat(), mark.y.toFloat())
        val fade = 1f - p
        if (swipe) {
            val end = Offset(mark.x2!!.toFloat(), mark.y2!!.toFloat())
            val head = start + (end - start) * minOf(1f, p * 1.4f)
            drawLine(TouchRing.copy(alpha = 0.55f * fade), start, head, strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(TouchCore.copy(alpha = 0.9f * fade), 11.dp.toPx(), head)
            drawCircle(TouchRing.copy(alpha = fade), 11.dp.toPx(), head, style = Stroke(3.dp.toPx()))
            return@Canvas
        }
        // The finger: a soft dot that shrinks away, with a ring that spreads out.
        drawCircle(TouchCore.copy(alpha = 0.85f * fade), (14 - 6 * p).dp.toPx(), start)
        drawCircle(TouchRing.copy(alpha = 0.9f * fade), (14 + 26 * p).dp.toPx(), start, style = Stroke(4.dp.toPx()))
        if (mark.long) {
            // A long press fills its ring while it is held.
            drawCircle(TouchRing.copy(alpha = 0.25f * fade), (14 + 26 * p).dp.toPx(), start)
        }
    }
}
