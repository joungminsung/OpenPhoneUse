package com.phoneuse.service

import com.phoneuse.service.EdgeGuard.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeGuardTest {
    private fun keep(s: Stroke) = EdgeGuard.keepOff(s, width = 1080, height = 2400, side = 100, top = 120, bottom = 150)

    @Test fun aSwipeInTheMiddleIsUntouched() {
        val s = Stroke(540, 1600, 540, 600)
        assertEquals(s, keep(s))
    }

    @Test fun aStartAtTheRightEdgeMovesInwardKeepingDirection() {
        // A carousel swipe from the very edge would be read as 뒤로가기.
        assertEquals(Stroke(979, 1200, 99, 1200), keep(Stroke(1075, 1200, 195, 1200)))
    }

    @Test fun aStartAtTheLeftEdgeMovesInwardAndTheEndStaysOnScreen() {
        assertEquals(Stroke(100, 1200, 1079, 1200), keep(Stroke(5, 1200, 1000, 1200)))
    }

    @Test fun aShortEdgeSwipeKeepsItsDirection() {
        val r = keep(Stroke(10, 1200, 60, 1200))
        assertEquals(100, r.x1)
        assertEquals(150, r.x2)
    }

    @Test fun bottomAndTopStartsMoveOutOfTheHomeAndShadeStrips() {
        assertEquals(Stroke(540, 2249, 540, 1049), keep(Stroke(540, 2390, 540, 1190)))
        assertEquals(Stroke(540, 120, 540, 1120), keep(Stroke(540, 20, 540, 1020)))
    }

    private fun pinch(x: Int, y: Int, zoomIn: Boolean) =
        EdgeGuard.pinch(x, y, zoomIn, width = 1080, height = 2400, side = 100, top = 120, bottom = 150)

    @Test fun aZoomInSpreadsTwoFingersFromTheCenter() {
        // 5% and 30% of the width: 54 px and 324 px each side.
        assertEquals(Stroke(486, 1200, 216, 1200) to Stroke(594, 1200, 864, 1200), pinch(540, 1200, zoomIn = true))
    }

    @Test fun aZoomOutClosesThem() {
        assertEquals(Stroke(216, 1200, 486, 1200) to Stroke(864, 1200, 594, 1200), pinch(540, 1200, zoomIn = false))
    }

    @Test fun aPinchNearAnEdgeMovesInwardAndKeepsItsReach() {
        // Center 100 + 324 = 424 keeps the left finger at the strip; the bottom start leaves the home strip.
        assertEquals(Stroke(370, 2249, 100, 2249) to Stroke(478, 2249, 748, 2249), pinch(150, 2390, zoomIn = true))
    }

    private val portrait = EdgeGuard.Edges(1080, 2400, 100, 120, 150)
    private val landscape = EdgeGuard.Edges(2400, 1080, 100, 120, 150)

    @Test fun twistsShareACenterAndRadiusInBothDirections() {
        for (angle in listOf(-90, 90, 180)) {
            val twist = portrait.rotate(540, 1200, angle)!!
            assertEquals(540 to 1200, twist.cx to twist.cy)
            assertEquals(angle, twist.degrees)
            assertEquals(listOf(540 - twist.radius to 1200, 540 + twist.radius to 1200), twist.starts)
            assertEquals(Math.toRadians(kotlin.math.abs(angle).toDouble()) * twist.radius, twist.travelPx, 0.001)
        }
    }

    @Test fun aTurnNearAnyEdgeFitsBothArcsInEitherOrientation() {
        for (edges in listOf(portrait, landscape)) {
            for (x in listOf(0, edges.width - 1)) for (y in listOf(0, edges.height - 1)) {
                val g = edges.rotate(x, y, 180)!!
                assertTrue(g.radius > 0)
                assertTrue(g.cx - g.radius >= edges.side && g.cx + g.radius < edges.width - edges.side)
                assertTrue(g.cy - g.radius >= edges.top && g.cy + g.radius < edges.height - edges.bottom)
            }
        }
    }

    @Test fun rotationSpreadFitsSmallTargetsAndNeverExceedsTheDefault() {
        val large = portrait.rotate(540, 1200, 90)!!
        assertEquals(180, large.radius)
        assertEquals(large, portrait.rotate(540, 1200, 90, Int.MAX_VALUE))
        assertEquals(listOf(521 to 1200, 559 to 1200), portrait.rotate(540, 1200, 90, 39)!!.starts)
        assertEquals(listOf(539 to 1200, 541 to 1200), portrait.rotate(540, 1200, 90, 1)!!.starts)
        assertNull(portrait.rotate(540, 1200, 90, 0))
        assertNull(portrait.rotate(540, 1200, 90, -10))
    }

    @Test fun aSmallSpreadKeepsThePivotNearTheRequestedEdge() {
        val g = portrait.rotate(108, 130, 90, 16)!!
        assertEquals(108 to 130, g.cx to g.cy)
        assertEquals(listOf(100 to 130, 116 to 130), g.starts)
        val moved = portrait.rotate(1, 1, -90, 16)!!
        assertEquals(108 to 128, moved.cx to moved.cy)
        assertEquals(listOf(100 to 128, 116 to 128), moved.starts)
    }

    @Test fun parallelSwipePreservesTheRequestedDisplacementNearEdges() {
        for (edges in listOf(portrait, landscape)) {
            val swipe = edges.twoFingerSwipe(0, 0, 240, 380)!!
            assertEquals(240, swipe.x2 - swipe.x1)
            assertEquals(380, swipe.y2 - swipe.y1)
            assertEquals(listOf(swipe.x1 - swipe.halfSpacing to swipe.y1, swipe.x1 + swipe.halfSpacing to swipe.y1), swipe.starts)
            assertTrue(swipe.halfSpacing > 0)
            assertTrue(swipe.x1 - swipe.halfSpacing >= edges.side && swipe.x2 + swipe.halfSpacing < edges.width - edges.side)
            assertTrue(swipe.y1 >= edges.top && swipe.y2 < edges.height - edges.bottom)
            assertNotNull(edges.twoFingerSwipe(300, 400, 200, 300))
        }
    }

    @Test fun impossibleOrMalformedTwoFingerGesturesHaveNoPaths() {
        val noRoom = EdgeGuard.Edges(200, 200, 100, 120, 150)
        assertNull(noRoom.rotate(100, 100, 90))
        assertNull(portrait.rotate(540, 1200, 0))
        assertNull(portrait.rotate(540, 1200, -181))
        assertNull(portrait.rotate(-1, 1200, 90))
        assertNull(portrait.twoFingerSwipe(100, 500, 100, 500))
        assertNull(portrait.twoFingerSwipe(0, 0, 1079, 2399))
        assertNull(portrait.twoFingerSwipe(Int.MAX_VALUE, 500, Int.MIN_VALUE, 510))
    }
}
