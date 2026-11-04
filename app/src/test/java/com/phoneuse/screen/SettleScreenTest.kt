package com.phoneuse.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the settle looks at on a screen: loading, the structural hash, covered elements. */
class SettleScreenTest {
    private fun node(
        cls: String = "android.widget.TextView",
        text: String? = null,
        b: Bounds = Bounds(0, 0, 100, 100),
        windowId: Int = 1,
        hasRange: Boolean = false,
        visibleToUser: Boolean = true,
        covered: Boolean = false,
        uncovered: Bounds? = null,
        clickable: Boolean = false,
        children: List<UiNode> = emptyList(),
    ) = UiNode(
        windowId, emptyList(), cls, text, null, null, null, b, clickable = clickable,
        visibleToUser = visibleToUser, hasRange = hasRange, covered = covered, uncovered = uncovered, children = children,
    )

    private fun app(vararg kids: UiNode) = node("android.widget.FrameLayout", b = Bounds(0, 0, 1080, 2400), children = kids.toList())

    private fun screen(vararg roots: UiNode, system: Set<Int> = emptySet()) =
        ScreenSnapshot("com.app", 1080, 2400, roots.toList(), systemWindowIds = system)

    // ---- busy ---------------------------------------------------------------------------------

    @Test fun determinateProgressBarIsNotLoading() {
        // A playback, download or storage bar carries a range and may sit there for minutes.
        assertFalse(ScreenBusy.isBusy(screen(app(node("android.widget.ProgressBar", hasRange = true)))))
        assertTrue(ScreenBusy.isBusy(screen(app(node("android.widget.ProgressBar")))))
    }

    @Test fun hiddenOrCoveredSpinnerIsNotLoading() {
        assertFalse(ScreenBusy.isBusy(screen(app(node("android.widget.ProgressBar", visibleToUser = false)))))
        assertFalse(ScreenBusy.isBusy(screen(app(node("android.widget.ProgressBar", covered = true)))))
        // Children of an invisible container are invisible too.
        val hidden = node("android.widget.FrameLayout", visibleToUser = false, children = listOf(node("android.widget.ProgressBar")))
        assertFalse(ScreenBusy.isBusy(screen(app(hidden))))
    }

    @Test fun progressInASystemWindowIsNotTheAppLoading() {
        val bar = node("android.widget.ProgressBar", windowId = 9) // a download in a heads-up notification
        assertFalse(ScreenBusy.isBusy(screen(app(), bar, system = setOf(9))))
    }

    @Test fun ownAppInFrontIsNotATransition() {
        assertTrue(ScreenBusy.isBusy(ScreenSnapshot(null, 1080, 2400, emptyList())))
        assertFalse(ScreenBusy.isBusy(ScreenSnapshot(null, 1080, 2400, emptyList(), ownAppInFront = true)))
    }

    @Test fun aWindowWithNothingInItYetIsBlank() {
        // A new page before its first layout: only empty containers in the app window.
        assertTrue(ScreenBusy.isBlank(screen(app(node("android.widget.FrameLayout")), node(text = "8:33", windowId = 9), system = setOf(9))))
        assertFalse(ScreenBusy.isBlank(screen(app(node(text = "Android version")))))
        assertTrue(ScreenBusy.isBlank(screen(app(node(text = "under the keyboard", covered = true)))))
        // The pulled-down shade covers the app; it is what the user sees, not a blank page.
        val shade = ScreenSnapshot(
            "com.android.systemui", 1080, 2400,
            listOf(app(node(text = "home", covered = true)), node(text = "Wi-Fi", windowId = 9)),
            systemWindowIds = setOf(9), activeIsSystem = true,
        )
        assertFalse(ScreenBusy.isBlank(shade))
    }

    // ---- stability hash -----------------------------------------------------------------------

    @Test fun stabilityHashIgnoresPositions() {
        val a = screen(app(node(text = "서울역", b = Bounds(10, 10, 200, 60), clickable = true)))
        val b = screen(app(node(text = "서울역", b = Bounds(14, 30, 204, 80), clickable = true)))
        assertNotEquals(TreeCompressor.compress(a).text, TreeCompressor.compress(b).text)
        assertEquals(TreeCompressor.stabilityHash(a), TreeCompressor.stabilityHash(b))
    }

    @Test fun stabilityHashIgnoresTickingDigitsInShortLabels() {
        val a = screen(app(node(text = "0:12 / 3:45"), node(text = "350 m")))
        val b = screen(app(node(text = "0:13 / 3:45"), node(text = "340 m")))
        assertEquals(TreeCompressor.stabilityHash(a), TreeCompressor.stabilityHash(b))
    }

    @Test fun stabilityHashSeesRealChanges() {
        val a = screen(app(node(text = "검색 결과가 없습니다 다른 검색어를 입력하세요")))
        val b = screen(app(node(text = "스타벅스 강남점 영업 중 오후 10시에 영업 종료")))
        assertNotEquals(TreeCompressor.stabilityHash(a), TreeCompressor.stabilityHash(b))
        val c = screen(app(node(text = "확인", clickable = true)))
        val d = screen(app(node(text = "취소", clickable = true)))
        assertNotEquals(TreeCompressor.stabilityHash(c), TreeCompressor.stabilityHash(d))
    }

    @Test fun stabilityHashSkipsSystemWindowsAndSurfaces() {
        val clock1 = node(text = "8:33", windowId = 9)
        val clock2 = node(text = "8:34 1.2 KB/s 5G LTE", windowId = 9)
        assertEquals(
            TreeCompressor.stabilityHash(screen(app(node(text = "지도")), clock1, system = setOf(9))),
            TreeCompressor.stabilityHash(screen(app(node(text = "지도")), clock2, system = setOf(9))),
        )
        val map1 = node("com.google.android.gms.maps.MapView", children = listOf(node(text = "마커 강남역 출구 1")))
        val map2 = node("com.google.android.gms.maps.MapView", children = listOf(node(text = "마커 역삼역 출구 3")))
        assertEquals(TreeCompressor.stabilityHash(screen(app(map1))), TreeCompressor.stabilityHash(screen(app(map2))))
    }

    // ---- covered elements ---------------------------------------------------------------------

    @Test fun coveredElementsAreNotListedAndPartlyCoveredOnesAreTappedInTheirVisiblePart() {
        val under = node(text = "보내기", b = Bounds(0, 2000, 200, 2100), clickable = true, covered = true)
        val half = node(text = "목록", b = Bounds(0, 1000, 1080, 2000), clickable = true, uncovered = Bounds(0, 1000, 1080, 1400))
        val c = TreeCompressor.compress(screen(app(under, half)))
        assertFalse(c.text.contains("보내기"))
        val i = c.elements.entries.single { it.value.text == "목록" }.key
        assertEquals(540 to 1200, c.tapPoint(i))
        assertTrue(c.text.contains("(540,1200)"))
    }

    @Test fun occlusionCutsAnElementAtTheKeyboard() {
        val keyboard = Bounds(0, 1500, 1080, 2400)
        assertEquals(Bounds(0, 1000, 1080, 1500), Occlusion.visiblePart(Bounds(0, 1000, 1080, 2000), listOf(keyboard)))
        assertNull(Occlusion.visiblePart(Bounds(0, 1600, 200, 1700), listOf(keyboard)))
        assertEquals(Bounds(0, 100, 200, 300), Occlusion.visiblePart(Bounds(0, 100, 200, 300), listOf(keyboard)))
    }

    @Test fun occlusionByASmallWindowCountsOnlyOverTheCentre() {
        val bubble = Bounds(900, 900, 1000, 1000)
        // A corner under a chat bubble: still tapped at its centre.
        assertEquals(Bounds(800, 800, 950, 950), Occlusion.visiblePart(Bounds(800, 800, 950, 950), listOf(bubble)))
        assertNull(Occlusion.visiblePart(Bounds(850, 850, 1050, 1050), listOf(bubble)))
    }
}
