package com.phoneuse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.phoneuse.ui.theme.PlexMono
import com.phoneuse.ui.theme.PuColors
import com.phoneuse.ui.theme.pu

fun List<MdSpan>.toAnnotated(c: PuColors): AnnotatedString = buildAnnotatedString {
    for (s in this@toAnnotated) {
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) PlexMono else null,
            fontSize = if (s.code) 0.9.em else androidx.compose.ui.unit.TextUnit.Unspecified,
            background = if (s.code) c.sunken else Color.Unspecified,
        )
        val url = s.url
        if (url != null) {
            withLink(LinkAnnotation.Url(url, TextLinkStyles(style.copy(color = c.accentText)))) { append(s.text) }
        } else {
            withStyle(style) { append(s.text) }
        }
    }
}

/** An assistant answer: no bubble, 15sp/1.65 body, with the Markdown subset of [Markdown]. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = pu.text) {
    val c = pu
    val blocks = remember(text) { Markdown.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> Text(block.spans.toAnnotated(c), fontSize = 15.sp, lineHeight = 25.sp, color = color)
                is MdBlock.Heading -> Text(
                    block.spans.toAnnotated(c),
                    modifier = Modifier.padding(top = 4.dp),
                    fontSize = when (block.level) {
                        1 -> 19.sp
                        2 -> 17.sp
                        else -> 15.5.sp
                    },
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (item in block.items) {
                        Row(Modifier.padding(start = (item.level * 18).dp)) {
                            Text("•", Modifier.width(18.dp), fontSize = 15.sp, lineHeight = 24.sp, color = c.textSub)
                            Text(item.spans.toAnnotated(c), fontSize = 15.sp, lineHeight = 24.sp, color = color)
                        }
                    }
                }
                is MdBlock.Numbered -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in block.items) {
                        Row {
                            Text(
                                item.number.toString(),
                                Modifier.width(22.dp),
                                fontFamily = PlexMono,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.5.sp,
                                lineHeight = 23.sp,
                                color = c.accentText,
                            )
                            Spacer10()
                            Text(item.spans.toAnnotated(c), fontSize = 14.5.sp, lineHeight = 23.sp, color = color)
                        }
                    }
                }
                is MdBlock.Table -> TableBlock(block, color)
                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(c.sunken, RoundedCornerShape(12.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(block.text, fontFamily = PlexMono, fontSize = 13.sp, lineHeight = 20.sp, color = c.text2, softWrap = false)
                }
                is MdBlock.Quote -> Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.accentSoft, RoundedCornerShape(12.dp))
                        .padding(vertical = 10.dp),
                ) {
                    Box(Modifier.padding(start = 10.dp).width(3.dp).height(22.dp).background(c.accent, RoundedCornerShape(2.dp)))
                    Text(
                        block.spans.toAnnotated(c),
                        Modifier.padding(start = 10.dp, end = 12.dp),
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        color = pu.text,
                    )
                }
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
            }
        }
    }
}

@Composable
private fun Spacer10() = Box(Modifier.width(10.dp))

/**
 * ChatAnswer's `.ing` box: a white rounded panel; header in the secondary colour. Column widths come from
 * [tableColumnWidths], fed with each cell's real one-line width. Measured here rather than ahead of time, so the
 * widths follow when the downloaded font arrives. Scrolls sideways only when the columns cannot all fit.
 */
@Composable
private fun TableBlock(table: MdBlock.Table, color: Color) {
    val c = pu
    val shape = RoundedCornerShape(14.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(c.surface, shape)
            .border(1.dp, c.line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        val panelWidth = constraints.maxWidth
        Layout(
            content = {
                for (cell in table.header) {
                    Text(cell.toAnnotated(c), fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
                }
                Box(Modifier.background(c.lineSoft))
                for (row in table.rows) {
                    for (cell in row) Text(cell.toAnnotated(c), fontSize = 14.sp, lineHeight = 21.sp, color = color)
                }
            },
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) { measurables, _ ->
            val n = table.header.size
            if (n == 0) return@Layout layout(0, 0) {}
            val columnGap = 12.dp.roundToPx()
            val rowGap = 6.dp.roundToPx()
            val divider = measurables[n]
            val cells = measurables.subList(0, n) + measurables.subList(n + 1, measurables.size)
            val rows = listOf(table.header) + table.rows
            val columns = List(n) { col ->
                List(rows.size) { row ->
                    TableCell(rows[row][col].joinToString("") { it.text }, cells[row * n + col].maxIntrinsicWidth(Constraints.Infinity))
                }
            }
            val widths = tableColumnWidths(columns, panelWidth - columnGap * (n - 1), 64.dp.roundToPx())
            val placeables = List(rows.size) { row -> List(n) { col -> cells[row * n + col].measure(Constraints.fixedWidth(widths[col])) } }
            val tableWidth = widths.sum() + columnGap * (n - 1)
            val line = divider.measure(Constraints.fixed(tableWidth, 1.dp.roundToPx()))
            val rowHeights = placeables.map { row -> row.maxOf { it.height } }
            layout(tableWidth, rowHeights.sum() + line.height + rowGap * rows.size) {
                var y = 0
                placeables.forEachIndexed { r, row ->
                    var x = 0
                    row.forEachIndexed { col, cell ->
                        cell.place(x, y)
                        x += widths[col] + columnGap
                    }
                    y += rowHeights[r] + rowGap
                    if (r == 0) {
                        line.place(0, y)
                        y += line.height + rowGap
                    }
                }
            }
        }
    }
}
