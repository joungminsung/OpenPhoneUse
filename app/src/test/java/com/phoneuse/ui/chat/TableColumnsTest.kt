package com.phoneuse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableColumnsTest {
    // Room for four columns in dp: screen width - 40 (chat padding) - 28 (panel padding) - 36 (three column gaps).
    private val phone = 411 - 40 - 28 - 36
    private val narrowPhone = 360 - 40 - 28 - 36
    private val minText = 64

    /** A stand-in for the real measurement: about 14sp text in dp, Hangul about twice as wide as Latin. */
    private fun cell(text: String, scale: Double = 1.0) =
        TableCell(text, (text.sumOf { if (it in '가'..'힣') 13.0 else 7.0 } * scale).toInt())

    private fun columns(rows: List<List<String>>, scale: Double = 1.0) =
        rows[0].indices.map { c -> rows.map { cell(it[c], scale) } }

    private val english = listOf(
        listOf("Product", "Price", "Per oz", "Rating"),
        listOf("Eucerin Advanced Repair 16.9 oz", "\$12.98", "\$0.68", "4.8 (98K)"),
        listOf("CeraVe Moisturizing Cream 19 oz", "\$17.78", "\$0.94", "4.8 (156K)"),
        listOf("Aveeno Daily Moisturizing 18 oz", "\$9.97", "\$0.55", "4.7 (41K)"),
    )

    private val korean = listOf(
        listOf("제품", "가격", "ml당", "평점"),
        listOf("피지오겔 DMT 200ml", "23,900원", "120원", "4.8 (1.8만)"),
        listOf("세타필 모이스춰라이징 로션 473ml", "21,500원", "45원", "4.7 (9.2천)"),
        listOf("일리윤 세라마이드 아토 로션 528ml", "18,900원", "36원", "4.9 (3.1만)"),
    )

    private fun assertOneLine(columns: List<List<TableCell>>, widths: List<Int>, column: Int) {
        val needed = columns[column].maxOf { it.width }
        assertTrue("column $column is ${widths[column]}, needs $needed", widths[column] >= needed)
    }

    @Test
    fun englishTableKeepsValuesWholeAndWrapsTheProductName() {
        val cols = columns(english)
        val widths = tableColumnWidths(cols, phone, minText)
        for (value in 1..3) assertOneLine(cols, widths, value)
        assertTrue("product $widths wraps", widths[0] < cols[0].maxOf { it.width })
        assertEquals(phone, widths.sum())
    }

    @Test
    fun largerTextStillKeepsValuesWhole() {
        val cols = columns(english, scale = 1.3)
        val widths = tableColumnWidths(cols, phone, minText)
        for (value in 1..3) assertOneLine(cols, widths, value)
        assertTrue("product $widths wraps", widths[0] < cols[0].maxOf { it.width })
        assertTrue("product $widths keeps its longest word", widths[0] >= (cols[0].maxOf { it.width } * 12 / 31))
    }

    @Test
    fun koreanTableKeepsValuesWholeAndWrapsTheProductName() {
        for (room in listOf(phone, narrowPhone)) {
            val cols = columns(korean)
            val widths = tableColumnWidths(cols, room, minText)
            for (value in 1..3) assertOneLine(cols, widths, value)
            assertTrue("product $widths wraps", widths[0] < cols[0].maxOf { it.width })
            assertEquals(room, widths.sum())
        }
    }

    @Test
    fun theOldWeightsWouldHaveBrokenThePrice() {
        // Weighted by characters, capped at 30: the case behind "$12.9/8". This is why the weights had to go.
        val price = cell("\$12.98").width
        val byCharacters = phone * 6 / (30 + 6 + 6 + 10)
        assertTrue(byCharacters < price)
        assertTrue(tableColumnWidths(columns(english), phone, minText)[1] >= price)
    }

    @Test
    fun aTableThatFitsFillsThePanelWithEveryCellOnOneLine() {
        val cols = columns(listOf(listOf("Item", "Qty"), listOf("Milk", "2"), listOf("Bread", "1")))
        val widths = tableColumnWidths(cols, 300, minText)
        assertOneLine(cols, widths, 0)
        assertOneLine(cols, widths, 1)
        assertEquals(300, widths.sum())
        assertTrue("wider content, wider column: $widths", widths[0] > widths[1])
    }

    @Test
    fun aValueWiderThanAnEvenShareStillStaysWhole() {
        val value = TableCell("\$1,234,567.89", 80)
        val text = TableCell("A long description of something that wraps over several lines", 300)
        val widths = tableColumnWidths(listOf(listOf(text), listOf(value), listOf(text)), 220, minText)
        assertEquals(listOf(70, 80, 70), widths)
    }

    @Test
    fun narrowerTextIsSatisfiedBeforeLongTextTakesTheWrap() {
        val short = TableCell("Seoul Station West Exit", 90)
        val long = TableCell("A long description of something that wraps over several lines", 300)
        val widths = tableColumnWidths(listOf(listOf(short), listOf(long)), 200, minText)
        assertEquals(listOf(90, 110), widths)
    }

    @Test
    fun aVeryWideTableOnANarrowPhoneScrollsInsteadOfBreakingValues() {
        val rows = listOf(List(8) { "Column $it" }, List(8) { "\$1,234.56" })
        val cols = columns(rows)
        val widths = tableColumnWidths(cols, narrowPhone, minText)
        for (column in cols.indices) assertOneLine(cols, widths, column)
        assertTrue("wider than the room, so the table scrolls: $widths", widths.sum() > narrowPhone)
    }

    @Test
    fun manyTextColumnsScrollAtAReadableWidthInsteadOfBeingCrushed() {
        val text = TableCell("Quite a long sentence about a thing", 245)
        val widths = tableColumnWidths(List(6) { listOf(text) }, narrowPhone, minText)
        assertEquals(List(6) { minText }, widths)
    }

    @Test
    fun aLongUnbrokenWordDoesNotTakeTheWholeTable() {
        val url = TableCell("https://example.com/a/very/long/path/that/goes/on/and/on/forever", 450)
        val widths = tableColumnWidths(listOf(listOf(url), listOf(cell("\$12.98"))), 200, minText)
        assertEquals(listOf(158, 42), widths)
    }

    @Test
    fun emptyInputsAreSafe() {
        assertEquals(emptyList<Int>(), tableColumnWidths(emptyList(), 300, minText))
        assertEquals(listOf(150, 150), tableColumnWidths(listOf(listOf(TableCell("", 0)), listOf(TableCell("", 0))), 300, minText))
        assertEquals(listOf(0, 0), tableColumnWidths(listOf(listOf(TableCell("", 0)), listOf(TableCell("", 0))), -10, minText))
    }

    @Test
    fun atomicValues() {
        for (value in listOf("\$12.98", "\$0.68", "4.8 (98K)", "16.9 oz", "23,900원", "120원", "4.8 (1.8만)", "2026-11-04", "Per oz", "Rating", " 12 ")) {
            assertTrue(value, isAtomicValue(value))
        }
        for (text in listOf("Eucerin Advanced Repair 16.9 oz", "피지오겔 DMT 200ml", "Seoul Station", "https://example.com/a/long/path")) {
            assertFalse(text, isAtomicValue(text))
        }
    }
}
