package com.phoneuse.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenBusyTest {
    private fun node(cls: String, text: String? = null, bounds: Bounds = Bounds(0, 0, 100, 100), children: List<UiNode> = emptyList()) =
        UiNode(0, listOf(0), cls, text, null, null, null, bounds, children = children)

    private fun screen(vararg roots: UiNode) = ScreenSnapshot("com.app", 1080, 2400, roots.toList())

    @Test fun progressBarIsBusy() {
        assertTrue(ScreenBusy.isBusy(screen(node("android.widget.FrameLayout", children = listOf(node("android.widget.ProgressBar"))))))
    }

    @Test fun aScreenBetweenTwoAppsIsBusy() {
        // Only the status bar is left while one activity hands over to the next.
        assertTrue(ScreenBusy.isBusy(ScreenSnapshot(null, 1080, 2400, listOf(node("android.widget.TextView", "8:33")))))
    }

    @Test fun loadingTextIsBusy() {
        assertTrue(ScreenBusy.isBusy(screen(node("android.widget.TextView", "불러오는 중…"))))
        assertTrue(ScreenBusy.isBusy(screen(node("android.widget.TextView", "Loading"))))
    }

    @Test fun longTextThatMentionsLoadingIsNotBusy() {
        assertFalse(ScreenBusy.isBusy(screen(node("android.widget.TextView", "어제 앱 loading 속도가 느리다고 했던 거 기억나?"))))
    }

    @Test fun seekBarAndOffscreenSpinnerAreNotBusy() {
        assertFalse(ScreenBusy.isBusy(screen(node("android.widget.SeekBar"), node("android.widget.TextView", "결과 3건"))))
        assertFalse(ScreenBusy.isBusy(screen(node("android.widget.ProgressBar", bounds = Bounds(0, 3000, 100, 3100)))))
        assertFalse(ScreenBusy.isBusy(screen(node("android.widget.ProgressBar", bounds = Bounds(0, 0, 0, 0)))))
    }
}
