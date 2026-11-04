package com.phoneuse.ui.chat

/** A table cell as the width calculation sees it: its plain [text] and [width], the space it takes on one line. */
internal data class TableCell(val text: String, val width: Int)

/**
 * A short value that a line break would garble: a price, a number, a date, a rating such as "4.8 (98K)", or one
 * short word. Longer text, like a product name, reads fine across two lines.
 */
internal fun isAtomicValue(text: String): Boolean {
    val t = text.trim()
    val limit = when {
        t.none { it.isWhitespace() } -> 16
        t.any { it.isDigit() } -> 12
        else -> 8
    }
    return t.length <= limit
}

/**
 * Column widths for a Markdown table, in the same unit as [TableCell.width]. [columns] holds each column's cells,
 * header included. [available] is the room for the columns, gaps already taken out.
 *
 * Each column has a floor: its atomic values on one line, and for its text at least its longest word (within
 * [minTextWidth] to twice that, so a long URL cannot take the whole table). Above the floors, narrow columns are
 * filled first, up to one line per cell, which leaves the wrapping to the columns with the longest text.
 *
 * - Everything fits on one line: each column gets its one-line width plus a share of the spare room.
 * - The floors do not fit (a very wide table on a narrow phone): the floors are returned, their sum is more than
 *   [available] and the caller scrolls. Numbers stay whole rather than every column being crushed.
 */
internal fun tableColumnWidths(columns: List<List<TableCell>>, available: Int, minTextWidth: Int): List<Int> {
    if (columns.isEmpty()) return emptyList()
    val oneLine = columns.map { column -> column.maxOfOrNull { it.width } ?: 0 }
    val floors = columns.mapIndexed { i, column -> columnFloor(column, minTextWidth).coerceAtMost(oneLine[i]) }
    if (floors.sum() >= available) return floors
    if (oneLine.sum() <= available) return shareSpareRoom(oneLine, available)

    // The highest width limit that still fits: a column under it gets all it wants, the others stop at it.
    var low = 0
    var high = oneLine.max()
    while (low < high) {
        val limit = (low + high + 1) / 2
        if (oneLine.indices.sumOf { limit.coerceIn(floors[it], oneLine[it]) } <= available) low = limit else high = limit - 1
    }
    val widths = oneLine.indices.map { low.coerceIn(floors[it], oneLine[it]) }.toMutableList()
    // What the whole-number limit left over is less than one unit per column still short of its one-line width.
    var left = available - widths.sum()
    for (i in widths.indices) {
        if (left > 0 && widths[i] < oneLine[i]) {
            widths[i]++
            left--
        }
    }
    return widths
}

private fun columnFloor(column: List<TableCell>, minTextWidth: Int): Int = column.maxOfOrNull { cell ->
    if (isAtomicValue(cell.text)) cell.width else longestWordWidth(cell).coerceIn(minTextWidth, minTextWidth * 2)
} ?: 0

/** Estimated from the word's share of the characters: the calculation only has the whole cell's measured width. */
private fun longestWordWidth(cell: TableCell): Int {
    val longest = cell.text.split(' ').maxOf { it.length }
    return (cell.width.toLong() * longest / cell.text.length.coerceAtLeast(1)).toInt()
}

/** Spare room goes to the columns in proportion to their width, so the table fills its panel. */
private fun shareSpareRoom(oneLine: List<Int>, available: Int): List<Int> {
    val spare = (available - oneLine.sum()).toLong()
    val shares = oneLine.map { it.coerceAtLeast(1).toLong() }
    val widths = oneLine.indices.map { oneLine[it] + (spare * shares[it] / shares.sum()).toInt() }.toMutableList()
    widths[widths.indices.maxBy { oneLine[it] }] += available - widths.sum()
    return widths
}
