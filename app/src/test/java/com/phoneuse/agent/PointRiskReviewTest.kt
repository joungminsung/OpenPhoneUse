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

/** Security review #14: unlabeled send icons and slide-to-pay swipes. */
class PointRiskReviewTest {
    private fun node(
        path: List<Int>,
        bounds: Bounds,
        text: String? = null,
        className: String = "android.view.View",
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
    ) = UiNode(
        windowId = 0, path = path, className = className, text = text, contentDescription = null, hint = null,
        viewId = null, bounds = bounds, clickable = clickable, editable = editable, scrollable = scrollable,
    )

    private fun snap(pkg: String, vararg roots: UiNode) = ScreenSnapshot(pkg, 1080, 2400, roots.toList(), density = 2.75f)

    private suspend fun run(s: ScreenSnapshot, name: String, args: String): Pair<FakeUserGate, FakeDeviceController> {
        val dev = FakeDeviceController(listOf(s))
        val gate = FakeUserGate().apply { confirmResult = false }
        ToolExecutor(dev, gate).execute(InputItem.FunctionCall("c1", name, args), TreeCompressor.compress(s))
        return gate to dev
    }

    private val filler = arrayOf(
        node(listOf(0), Bounds(0, 0, 1080, 100), text = "상단", clickable = true),
        node(listOf(1), Bounds(0, 2200, 1080, 2300), text = "하단", clickable = true),
    )

    private fun composer(fieldText: String?, iconTop: Int = 2000) = snap(
        "com.chat.flutter", *filler,
        node(listOf(2), Bounds(20, 2000, 900, 2100), text = fieldText, className = "android.widget.EditText", editable = true),
        node(listOf(3), Bounds(920, iconTop, 1060, iconTop + 100), className = "android.widget.ImageButton", clickable = true),
    )

    @Test fun unlabeledIconNextToFilledFieldIsSend() = runTest {
        val (gate, dev) = run(composer("안녕하세요 오늘 회의"), "tap", """{"x":990,"y":2050}""")
        assertEquals(RiskCategory.SEND, gate.confirms.single().first)
        assertTrue(dev.calls.none { it.startsWith("tap") })
    }

    @Test fun unlabeledIconNextToEmptyFieldIsNotSend() = runTest {
        val (gate, _) = run(composer(null), "tap", """{"x":990,"y":2050}""")
        assertTrue(gate.confirms.toString(), gate.confirms.isEmpty())
    }

    @Test fun unlabeledIconInAnotherRowIsNotSend() = runTest {
        val (gate, _) = run(composer("안녕", iconTop = 1000), "tap", """{"x":990,"y":1050}""")
        assertTrue(gate.confirms.toString(), gate.confirms.isEmpty())
    }

    @Test fun unlabeledSliderSwipeInBankAppConfirms() = runTest {
        val s = snap("com.kbstar.kbbank", *filler, node(listOf(2), Bounds(0, 1800, 1080, 1950), clickable = true))
        val (gate, dev) = run(s, "swipe", """{"x1":100,"y1":1875,"x2":1000,"y2":1875}""")
        assertEquals(RiskCategory.MONEY, gate.confirms.single().first)
        assertTrue(dev.calls.none { it.startsWith("swipe") })
    }

    @Test fun swipeInNonPayAppOrScrollableListIsFree() = runTest {
        val plain = snap("com.shop", *filler, node(listOf(2), Bounds(0, 300, 1080, 2100), clickable = true))
        val (g1, _) = run(plain, "swipe", """{"x1":540,"y1":1000,"x2":540,"y2":400}""")
        assertTrue(g1.confirms.isEmpty())
        val list = snap("viva.republica.toss", *filler, node(listOf(2), Bounds(0, 300, 1080, 2100), scrollable = true))
        val (g2, _) = run(list, "swipe", """{"x1":540,"y1":1000,"x2":540,"y2":400}""")
        assertTrue(g2.confirms.toString(), g2.confirms.isEmpty())
    }
}
