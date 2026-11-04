package com.phoneuse.agent

import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.TreeCompressor
import com.phoneuse.screen.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenJournalTest {
    private fun screen(vararg texts: String) = TreeCompressor.compress(
        ScreenSnapshot(
            "viva.republica.toss", 1080, 2400,
            texts.mapIndexed { i, t ->
                UiNode(
                    windowId = 0, path = listOf(i), className = "Button", text = t, contentDescription = null,
                    hint = null, viewId = null, bounds = Bounds(0, 100 + i * 100, 500, 160 + i * 100), clickable = true,
                )
            },
        ),
    )

    @Test fun tabsVisitedBackAndForthAreNamed() {
        val j = ScreenJournal()
        val assets = screen("자산", "수익", "총자산 1,234,000원")
        val profit = screen("자산", "수익", "수익률 +3.2%")
        assertNull(j.revisit(1, "탭: 수익", profit))
        assertNull(j.revisit(2, "탭: 자산", assets))
        assertEquals(
            "Seen before: this screen looks the same as after step 1 (탭: 수익) (2 visits).",
            j.revisit(3, "탭: 수익", profit),
        )
    }

    @Test fun livePricesDoNotMakeAScreenNew() {
        val j = ScreenJournal()
        j.revisit(1, "탭: 새로고침", screen("삼성전자", "70,100원"))
        assertEquals(
            "Seen before: this screen looks the same as after step 1 (탭: 새로고침) (2 visits).",
            j.revisit(2, "탭: 새로고침", screen("삼성전자", "70,300원")),
        )
    }

    @Test fun manyVisitsNameTheLatestFour() {
        val j = ScreenJournal()
        val s = screen("자산")
        for (i in 1..6) j.revisit(i, "탭 $i", s)
        assertEquals(
            "Seen before: this screen looks the same as after step 3 (탭 3), step 4 (탭 4), step 5 (탭 5), step 6 (탭 6) and 2 more (7 visits).",
            j.revisit(7, "탭 7", s),
        )
    }

    @Test fun digestKeepsTheAppAndFirstLabels() {
        assertEquals("[earlier screen: viva.republica.toss · 자산 | 수익]", ScreenJournal.digest(screen("자산", "수익")))
    }
}
