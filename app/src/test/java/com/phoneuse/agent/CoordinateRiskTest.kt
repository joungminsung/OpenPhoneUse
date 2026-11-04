package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.risk.RiskCategory
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.TreeCompressor
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coordinate taps and swipes are judged on the live node, its clickable container and nearby text. */
class CoordinateRiskTest {
    private fun node(
        path: List<Int>,
        bounds: Bounds,
        text: String? = null,
        desc: String? = null,
        className: String = "android.view.View",
        clickable: Boolean = false,
        children: List<UiNode> = emptyList(),
    ) = UiNode(
        windowId = 0, path = path, className = className, text = text, contentDescription = desc, hint = null,
        viewId = null, bounds = bounds, clickable = clickable, children = children,
    )

    private fun snap(pkg: String, vararg roots: UiNode) = ScreenSnapshot(pkg, 1080, 2400, roots.toList(), density = 2.75f)

    private fun call(name: String, args: String) = InputItem.FunctionCall("c1", name, args)

    private suspend fun run(s: ScreenSnapshot, name: String, args: String): Pair<FakeUserGate, FakeDeviceController> {
        val dev = FakeDeviceController(listOf(s))
        val gate = FakeUserGate().apply { confirmResult = false }
        ToolExecutor(dev, gate).execute(call(name, args), TreeCompressor.compress(s))
        return gate to dev
    }

    private val filler = listOf(
        node(listOf(0), Bounds(0, 0, 1080, 100), text = "상단", clickable = true),
        node(listOf(1), Bounds(0, 2200, 1080, 2300), text = "하단", clickable = true),
    )

    @Test fun unlabeledPointInAPayAppConfirmsAsMoney() = runTest {
        val s = snap("viva.republica.toss", *filler.toTypedArray())
        val (gate, dev) = run(s, "tap", """{"x":540,"y":1200}""")
        assertEquals(RiskCategory.MONEY, gate.confirms.single().first)
        assertTrue(dev.calls.none { it.startsWith("tap") })
    }

    @Test fun unlabeledButtonNextToPayTextConfirms() = runTest {
        val s = snap(
            "com.shop",
            *filler.toTypedArray(),
            node(listOf(2), Bounds(100, 1000, 300, 1100), className = "android.widget.ImageButton", clickable = true),
            node(listOf(3), Bounds(320, 1000, 900, 1100), text = "10,000원 결제하기"),
        )
        val (gate, dev) = run(s, "tap", """{"x":200,"y":1050}""")
        assertEquals(RiskCategory.MONEY, gate.confirms.single().first)
        assertTrue(dev.calls.none { it.startsWith("tap") })
    }

    @Test fun unlabeledIndexTapNextToDeleteTextConfirms() = runTest {
        val s = snap(
            "com.notes",
            *filler.toTypedArray(),
            node(listOf(2), Bounds(100, 1000, 300, 1100), className = "android.widget.ImageButton", clickable = true),
            node(listOf(3), Bounds(320, 1000, 900, 1100), text = "메모 영구 삭제"),
        )
        val screen = TreeCompressor.compress(s)
        val index = screen.elements.entries.first { it.value.className == "android.widget.ImageButton" }.key
        val (gate, _) = run(s, "tap", """{"index":$index}""")
        assertEquals(RiskCategory.DELETE, gate.confirms.single().first)
    }

    @Test fun inAWebViewOnlyATextlessNodeLooksAround() = runTest {
        // The deepest node and its container decide; the radius counts only when that node has no text.
        val web = node(
            listOf(2), Bounds(0, 200, 1080, 2100), className = "android.webkit.WebView",
            children = listOf(
                node(listOf(2, 0), Bounds(100, 1000, 500, 1100), text = "다음", clickable = true),
                node(listOf(2, 1), Bounds(600, 1000, 980, 1100), clickable = true),
                node(listOf(2, 2), Bounds(100, 1150, 980, 1250), text = "총 32,000원 결제"),
            ),
        )
        val s = snap("com.browser", *filler.toTypedArray(), web)
        val (labeled, _) = run(s, "tap", """{"x":300,"y":1050}""")
        assertTrue(labeled.confirms.toString(), labeled.confirms.isEmpty())
        val (bare, _) = run(s, "tap", """{"x":800,"y":1050}""")
        assertEquals(RiskCategory.MONEY, bare.confirms.single().first)
    }

    @Test fun anOrdinarySwipeIgnoresPayTextElsewhere() = runTest {
        // A swipe judges its start element and container only, never the text around it.
        val s = snap(
            "com.shop",
            *filler.toTypedArray(),
            node(listOf(2), Bounds(0, 300, 1080, 2100), className = "android.webkit.WebView"),
            node(listOf(3), Bounds(100, 1100, 900, 1160), text = "결제하기"),
        )
        val (gate, dev) = run(s, "swipe", """{"x1":540,"y1":1000,"x2":540,"y2":400}""")
        assertTrue(gate.confirms.toString(), gate.confirms.isEmpty())
        assertTrue(dev.calls.any { it.startsWith("swipe") })
    }

    @Test fun theRadiusIsAPhysicalSize() {
        // 96dp at the display's density, not a share of the screen width (the unfolded Fold is wide).
        assertEquals(192.0, PointRisk.radiusPx(2f), 0.01)
        assertEquals(288.0, PointRisk.radiusPx(3f), 0.01)
    }

    @Test fun onTheUnfoldedFoldDistantTextDoesNotCount() = runTest {
        // About 300px away at density 2.3 is ~130dp: outside the radius, though the old width-based one reached it.
        val fold = ScreenSnapshot(
            "com.shop", 1812, 2176,
            listOf(
                node(listOf(0), Bounds(0, 0, 1812, 100), text = "상단", clickable = true),
                node(listOf(1), Bounds(100, 1000, 300, 1100), className = "android.widget.ImageButton", clickable = true),
                node(listOf(2), Bounds(600, 1000, 1200, 1100), text = "결제하기"),
            ),
            density = 2.3f,
        )
        val (gate, _) = run(fold, "tap", """{"x":200,"y":1050}""")
        assertTrue(gate.confirms.toString(), gate.confirms.isEmpty())
    }

    @Test fun containerTextDecidesForAPointOnItsUnlabeledPart() = runTest {
        val row = node(
            listOf(2), Bounds(0, 1000, 1080, 1200), desc = "항목", clickable = true,
            children = listOf(node(listOf(2, 0), Bounds(600, 1000, 1080, 1200), text = "주문 취소")),
        )
        val s = snap("com.shop", *filler.toTypedArray(), row)
        val (gate, _) = run(s, "tap", """{"x":100,"y":1100}""")
        assertEquals(RiskCategory.MONEY, gate.confirms.single().first)
    }

    @Test fun longSwipeFromSlideToPayConfirms() = runTest {
        val slider = node(
            listOf(2), Bounds(0, 1800, 1080, 1950), desc = "슬라이더", clickable = true,
            children = listOf(node(listOf(2, 0), Bounds(300, 1800, 800, 1950), text = "밀어서 결제")),
        )
        val s = snap("com.shop", *filler.toTypedArray(), slider)
        val (gate, dev) = run(s, "swipe", """{"x1":100,"y1":1875,"x2":1000,"y2":1875}""")
        assertEquals(RiskCategory.MONEY, gate.confirms.single().first)
        assertTrue(dev.calls.none { it.startsWith("swipe") })
    }

    @Test fun harmlessUnlabeledPointAndLongSwipeGoThrough() = runTest {
        val s = snap(
            "com.news",
            *filler.toTypedArray(),
            node(listOf(2), Bounds(100, 1000, 300, 1100), className = "android.widget.ImageButton", clickable = true),
            node(listOf(3), Bounds(100, 1800, 900, 1900), text = "오늘의 뉴스"),
            // Risky text far away (> 150dp) from the point does not count.
            node(listOf(4), Bounds(100, 2120, 900, 2190), text = "구독 결제"),
        )
        val (gate, dev) = run(s, "tap", """{"x":200,"y":1050}""")
        assertTrue(gate.confirms.toString(), gate.confirms.isEmpty())
        assertTrue(dev.calls.contains("tap(200,1050)"))
        val (gate2, dev2) = run(s, "swipe", """{"x1":500,"y1":1850,"x2":500,"y2":600}""")
        assertTrue(gate2.confirms.isEmpty())
        assertTrue(dev2.calls.any { it.startsWith("swipe") })
    }
}
