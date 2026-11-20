package com.phoneuse.overlay

import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode
import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffPlacementTest {
    private val area = Bounds(40, 80, 360, 680)

    private fun button(bounds: Bounds, window: Int = 1) =
        UiNode(window, emptyList(), "Button", null, null, null, null, bounds, clickable = true)

    @Test
    fun dockedKeyboardLeavesTheSpaceAboveItIncludingAnExactFit() {
        val keyboard = Bounds(0, 400, 400, 700)
        assertEquals(area.copy(bottom = 400), handoffArea(area, keyboard, 48))
        val exact = keyboard.copy(top = area.top + 48)
        assertEquals(area.copy(bottom = exact.top), handoffArea(area, exact, 48))
    }

    @Test
    fun moveCanLeaveAndReturnToASingleRowAboveTheKeyboard() {
        val strip = area.copy(bottom = area.top + 48)
        val keyboard = area.copy(top = strip.bottom)
        val inStrip = Bounds(120, strip.top, 280, strip.bottom)
        val overKeyboard = Bounds(120, area.bottom - 48, 280, area.bottom)
        assertEquals(strip, handoffArea(area, keyboard, 48))
        assertEquals(area, handoffArea(area, keyboard, 48, inStrip))
        assertEquals(strip, handoffArea(area, keyboard, 48, overKeyboard))
    }

    @Test
    fun fullHeightKeyboardOrTooShortAnExposedStripKeepsBothEdgesAvailable() {
        val usable = handoffArea(area, area, 48)
        assertEquals(area, usable)
        val previousBar = Bounds(120, 80, 280, 128)
        val bottomControl = Bounds(120, 620, 280, 680)
        assertEquals(572, handoffBarTop(usable, 160, 48, listOf(previousBar, bottomControl), true, listOf(area)))
        val narrowStrip = area.copy(top = area.top + 47)
        assertEquals(area, handoffArea(area, narrowStrip, 48))
    }

    @Test
    fun floatingKeyboardLeavesTheFullAreaAvailable() {
        assertEquals(area, handoffArea(area, Bounds(120, 400, 340, 660), 48))
        assertEquals(area, handoffArea(area, null, 48))
    }

    @Test
    fun clickableBackgroundDoesNotForceTheBarOverItsPinKeys() {
        val key = button(Bounds(120, 80, 280, 180))
        val background = button(area).copy(children = listOf(key))
        val controls = ScreenSnapshot(null, 400, 700, listOf(background)).handoffControls(setOf(1))

        assertEquals(listOf(key.bounds), controls.targets)
        assertEquals(listOf(area), controls.containers)
        assertEquals(180, handoffBarTop(area, 160, 48, controls.targets, false, controls.containers))
    }

    @Test
    fun avoidsTheWholeClickableContainerWhenSpacePermits() {
        val key = button(Bounds(120, 80, 280, 180))
        val panel = button(Bounds(40, 80, 360, 320)).copy(children = listOf(key))
        val controls = ScreenSnapshot(null, 400, 700, listOf(panel)).handoffControls(setOf(1))
        assertEquals(320, handoffBarTop(area, 160, 48, controls.targets, false, controls.containers))
    }

    @Test
    fun retainsKeypadControlsBehindPictureInPictureAndSkipsClosedOrSystemWindows() {
        val picture = button(Bounds(40, 80, 100, 180), window = 2)
        val key = button(Bounds(120, 80, 280, 180), window = 1)
        val closed = button(area, window = 3)
        val system = button(area, window = 4)
        val screen = ScreenSnapshot(null, 400, 700, listOf(picture, key, closed, system), systemWindowIds = setOf(4))
        val controls = screen.handoffControls(setOf(1, 2, 4))
        assertEquals(listOf(picture.bounds, key.bounds), controls.targets)
        assertEquals(180, handoffBarTop(area, 160, 48, controls.targets, false, controls.containers))
    }

    @Test
    fun bottomKeypadLeavesTheBarAtTheTop() {
        val keypad = listOf(Bounds(40, 400, 360, 680))
        assertEquals(80, handoffBarTop(area, 160, 48, keypad, preferBottom = false))
        assertEquals(352, handoffBarTop(area, 160, 48, keypad, preferBottom = true))
    }

    @Test
    fun topKeypadPlacesTheBarJustBelowItsLastRow() {
        val rows = listOf(Bounds(40, 150, 360, 230), Bounds(40, 70, 360, 160))
        assertEquals(230, handoffBarTop(area, 160, 48, rows, preferBottom = false))
    }

    @Test
    fun centralGapFitsTheBarExactlyFromEitherDirection() {
        val controls = listOf(Bounds(40, 310, 360, 680), Bounds(40, 70, 360, 262))
        assertEquals(262, handoffBarTop(area, 160, 48, controls, preferBottom = false))
        assertEquals(262, handoffBarTop(area, 160, 48, controls, preferBottom = true))
    }

    @Test
    fun controlsTouchingTheBarAtItsSidesDoNotDisplaceIt() {
        val sides = listOf(Bounds(40, 80, 120, 680), Bounds(280, 80, 360, 680))
        assertEquals(80, handoffBarTop(area, 160, 48, sides, preferBottom = false))
        assertEquals(632, handoffBarTop(area, 160, 48, sides, preferBottom = true))
    }

    @Test
    fun noGapTallEnoughFallsBackToTheRequestedEdge() {
        val controls = listOf(Bounds(40, 80, 360, 280), Bounds(40, 300, 360, 680))
        assertEquals(80, handoffBarTop(area, 160, 48, controls, preferBottom = false))
        assertEquals(632, handoffBarTop(area, 160, 48, controls, preferBottom = true))
    }

    @Test
    fun areaShorterThanTheBarAnchorsItAtTheTop() {
        val short = Bounds(40, 120, 360, 140)
        assertEquals(120, handoffBarTop(short, 160, 48, emptyList(), preferBottom = false))
        assertEquals(120, handoffBarTop(short, 160, 48, emptyList(), preferBottom = true))
    }

    @Test
    fun narrowWindowUsesItsFullWidth() {
        val narrow = Bounds(75, 80, 105, 680)
        val control = Bounds(75, 80, 77, 130)
        assertEquals(130, handoffBarTop(narrow, 160, 48, listOf(control), preferBottom = false))
    }
}
