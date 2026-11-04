package com.phoneuse.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeCompressorTest {
    private val full = Bounds(0, 0, 1080, 2520)

    private fun node(
        text: String? = null,
        desc: String? = null,
        cls: String? = "android.view.View",
        b: Bounds = Bounds(0, 0, 100, 100),
        clickable: Boolean = false,
        editable: Boolean = false,
        checkable: Boolean = false,
        password: Boolean = false,
        children: List<UiNode> = emptyList(),
    ) = UiNode(
        windowId = 1, path = emptyList(), className = cls, text = text,
        contentDescription = desc, hint = null, viewId = null, bounds = b,
        clickable = clickable, editable = editable, checkable = checkable,
        password = password, children = children,
    )

    private fun root(vararg kids: UiNode) = node(b = full, children = kids.toList())
    private fun snap(vararg kids: UiNode) =
        ScreenSnapshot("com.android.settings", 1080, 2520, listOf(root(*kids)))

    @Test fun switchLine() {
        val sw = node(text = "다크 모드", cls = "android.widget.Switch", b = Bounds(0, 800, 1080, 824), clickable = true, checkable = true)
        val c = TreeCompressor.compress(ScreenSnapshot("com.android.settings", 1080, 2520, listOf(root(sw))))
        assertEquals("app: com.android.settings | screen 1080x2520", c.text.lines()[0])
        assertTrue(c.text.contains("""[1] Switch "다크 모드" clickable unchecked (540,812)"""))
        assertEquals(sw, c.elements[1])
        assertEquals("다크 모드", c.labels[1])
        assertEquals("com.android.settings", c.packageName)
    }

    @Test fun clickableParentAbsorbsChildText() {
        val child = node(text = "디스플레이", cls = "android.widget.TextView")
        val parent = node(cls = "android.widget.LinearLayout", clickable = true, children = listOf(child))
        val c = TreeCompressor.compress(snap(parent))
        assertEquals(2, c.text.lines().size)
        assertTrue(c.text.contains("""[1] LinearLayout "디스플레이" clickable"""))
        assertEquals(1, c.elements.size)
    }

    @Test fun skipsEmptyAndOffscreenBounds() {
        val empty = node(text = "빈", b = Bounds(10, 10, 10, 50))
        val off = node(text = "밖", b = Bounds(0, 3000, 100, 3100))
        val ok = node(text = "안")
        val c = TreeCompressor.compress(snap(empty, off, ok))
        assertFalse(c.text.contains("빈"))
        assertFalse(c.text.contains("밖"))
        assertTrue(c.text.contains("안"))
        assertEquals(1, c.elements.size)
    }

    @Test fun truncatesLongLabel() {
        val c = TreeCompressor.compress(snap(node(text = "가".repeat(100))))
        assertEquals("가".repeat(77) + "...", c.labels[1])
        assertTrue(c.text.contains("\"" + "가".repeat(77) + "...\""))
    }

    @Test fun truncatesAtMaxElements() {
        val kids = (1..350).map { node(text = "t$it") }.toTypedArray()
        val c = TreeCompressor.compress(snap(*kids), maxElements = 300)
        assertEquals(300, c.elements.size)
        assertEquals("... (50 more elements omitted)", c.text.lines().last())
    }

    @Test fun passwordAndEditableFlags() {
        val e = node(cls = "android.widget.EditText", editable = true, password = true)
        val c = TreeCompressor.compress(snap(e))
        assertTrue(c.text.contains("""[1] EditText "" editable password (50,50)"""))
    }

    @Test fun keyboardHeaderAndMissingClassFallback() {
        val n = node(cls = null, b = Bounds(0, 0, 10, 10), clickable = true)
        val c = TreeCompressor.compress(ScreenSnapshot("p", 10, 20, listOf(n), keyboardVisible = true))
        assertEquals("app: p | screen 10x20 | keyboard open", c.text.lines()[0])
        assertTrue(c.text.contains("""[1] View "" clickable (5,5)"""))
    }

    @Test fun fullLabelsKeepUntruncatedText() {
        val long = "가".repeat(116) + "결제하기"
        val c = TreeCompressor.compress(snap(node(text = "  $long ", clickable = true)))
        assertEquals(long, c.fullLabels[1])
        assertEquals(80, c.labels[1]!!.length)
        assertFalse(c.text.contains("결제하기"))
    }

    @Test fun centerIsClippedToDisplay() {
        val c = TreeCompressor.compress(snap(node(text = "아래", b = Bounds(-100, 2400, 300, 2800))))
        assertTrue(c.text, c.text.contains("""[1] View "아래" (150,2460)"""))
        assertEquals(150 to 2460, c.tapPoint(1))
    }

    @Test fun truncationNeverSplitsSurrogatePair() {
        val emoji = String(Character.toChars(0x1F600))
        val c = TreeCompressor.compress(snap(node(text = "a".repeat(76) + emoji.repeat(5))))
        val label = c.labels[1]!!
        assertEquals("a".repeat(76) + "...", label)
        assertFalse(label.any { it.isSurrogate() })
    }

    @Test fun aListThatGoesOnSaysSoInItsLineAndTheHeader() {
        val list = UiNode(
            windowId = 1, path = emptyList(), className = "androidx.recyclerview.widget.RecyclerView", text = null,
            contentDescription = "결과", hint = null, viewId = null, bounds = Bounds(0, 200, 1080, 2400),
            scrollable = true, moreAfter = true, moreBefore = true,
        )
        val c = TreeCompressor.compress(snap(list))
        assertTrue(c.text.lines()[0].endsWith(TreeCompressor.LIST_CONTINUES))
        assertTrue(c.text.contains("scrollable more-above more-below"))
        assertTrue(c.listContinues)
    }

    @Test fun aCarouselOrAFinishedListDoesNotSayTheListGoesOn() {
        fun list(more: Boolean, horizontal: Boolean) = UiNode(
            windowId = 1, path = emptyList(), className = "View", text = "목록", contentDescription = null,
            hint = null, viewId = null, bounds = Bounds(0, 200, 1080, 600), scrollable = true,
            moreAfter = more, horizontal = horizontal,
        )
        val carousel = TreeCompressor.compress(snap(list(more = true, horizontal = true)))
        assertFalse(carousel.listContinues)
        assertTrue(carousel.text.contains("more-right"))
        assertFalse(TreeCompressor.compress(snap(list(more = false, horizontal = false))).listContinues)
    }
}
