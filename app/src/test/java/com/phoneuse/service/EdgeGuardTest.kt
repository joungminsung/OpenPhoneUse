package com.phoneuse.service

import com.phoneuse.service.EdgeGuard.Stroke
import org.junit.Assert.assertEquals
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
}
