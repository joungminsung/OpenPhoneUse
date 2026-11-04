package com.phoneuse.ui.cards

import com.phoneuse.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import com.phoneuse.cards.CardAction
import com.phoneuse.cards.CardActions
import com.phoneuse.cards.DigestCard
import com.phoneuse.cards.DigestGroup
import com.phoneuse.cards.DraftCard
import com.phoneuse.cards.ResultsCard
import com.phoneuse.cards.StatCard
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu

/** Board "Search": result rows that open their link, then the sources. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultsCardView(card: ResultsCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    val c = pu
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardBox(padding = PaddingValues(0.dp), label = card.title) {
            Text(
                card.title,
                Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.textSub,
            )
            card.items.forEachIndexed { i, item ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onAction(CardActions.openResult(i, item)) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                        item.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = c.textSub) }
                        item.meta?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.5.sp, color = c.text) }
                    }
                    Icon(PuIcons.ChevronRight, null, tint = c.textSub, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (card.sources.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Sources", "출처"), fontSize = 12.5.sp, color = c.textSub, modifier = Modifier.padding(vertical = 4.dp))
                for (s in card.sources) {
                    Text(
                        "· ${s.title}",
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) { onAction(CardAction.OpenUrl(s.url)) }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                        fontSize = 12.5.sp,
                        color = c.textSub,
                    )
                }
            }
        }
    }
}

private val TONES get() = listOf(tr("Softer", "더 부드럽게"), tr("Shorter", "더 짧게"), tr("More formal", "격식 있게"), tr("In Korean", "영어로"))

/** Board "ChatWriting": the draft, tone chips, then copy / edit / send. */
@Composable
fun DraftCardView(card: DraftCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    val c = pu
    val channel = when (card.channel) {
        "email" -> tr("Email", "메일")
        "note" -> tr("Note", "메모")
        else -> tr("Text", "문자")
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardBox(label = tr("$channel draft", "$channel 초안")) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(channel, card.to?.takeIf { it.isNotBlank() }).joinToString(" · "),
                        Modifier.weight(1f),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.textSub,
                    )
                    Text(tr("${card.body.codePointCount(0, card.body.length)} chars", "${card.body.codePointCount(0, card.body.length)}자"), fontSize = 12.sp, color = c.textSub)
                }
                card.subject?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                }
                Text(card.body, fontSize = 15.sp, lineHeight = 25.5.sp, color = c.text)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (tone in TONES) Pill(tone, false, { onAction(CardActions.draftTone(tone)) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton(tr("Copy", "복사"), { onAction(CardAction.CopyText(card.body)) }, Modifier.weight(1f))
            CardButton(tr("Edit", "고치기"), { onAction(CardActions.editDraft()) }, Modifier.weight(1f))
            CardButton(
                when (card.channel) {
                    "email" -> tr("Send as email", "메일로 보내기")
                    "note" -> tr("Save as note", "메모로 저장")
                    else -> tr("Send as text", "문자로 보내기")
                },
                { onAction(CardActions.sendDraft(card)) },
                Modifier.weight(1.4f),
                primary = true,
            )
        }
        if (card.channel != "note") {
            Text(tr("I'll ask once more before sending.", "보내기 전에 한 번 더 확인을 받아요."), fontSize = 12.5.sp, color = c.textSub)
        }
    }
}

/** Board "Digest": one box per group; muted groups are dashed and transparent. */
@Composable
fun DigestCardView(card: DigestCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (group in card.groups) DigestGroupBox(group, onAction)
    }
}

@Composable
private fun DigestGroupBox(group: DigestGroup, onAction: (CardAction) -> Unit) {
    val c = pu
    val muted = group.tone == "muted"
    val labelColor = when (group.tone) {
        "urgent" -> c.accentText
        "money" -> c.money
        "muted" -> c.textSub
        else -> c.text
    }
    val shape = RoundedCornerShape(18.dp)
    val line = c.line
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (muted) {
                    Modifier.drawBehind {
                        val w = 1.dp.toPx()
                        drawRoundRect(
                            line,
                            topLeft = Offset(w / 2, w / 2),
                            size = Size(size.width - w, size.height - w),
                            cornerRadius = CornerRadius(18.dp.toPx()),
                            style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                        )
                    }
                } else {
                    Modifier.background(c.surface).border(1.dp, c.line, shape)
                },
            )
            .semantics { contentDescription = group.label },
    ) {
        Text(
            "${group.label} · ${group.items.size}",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor,
        )
        group.items.forEachIndexed { index, item ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    if (item.who != null || item.at != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item.who?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text) }
                            item.at?.let { Text(it, style = MonoStyle, fontSize = 12.sp, color = c.textSub) }
                        }
                    }
                    Text(
                        item.text,
                        fontSize = 13.sp,
                        color = if (item.who == null) c.text else c.textSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.action?.takeIf { it.isNotBlank() }?.let { label ->
                    CardButton(label, { onAction(CardActions.digestItem(group, index, item)) }, height = 34.dp)
                }
            }
        }
        if (group.items.isNotEmpty()) Box(Modifier.height(3.dp))
    }
}

/** Board "Parcel" (spending): a big figure, the change, and bars with the largest one dark. */
@Composable
fun StatCardView(card: StatCard, modifier: Modifier = Modifier) {
    val c = pu
    CardBox(modifier, label = card.label) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
                Text(card.value, style = MonoStyle, fontSize = 30.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp, color = c.text)
            }
            card.delta?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MonoStyle, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.accentText, textAlign = TextAlign.End)
            }
        }
        if (card.bars.isNotEmpty()) {
            val max = card.bars.maxOf { it.value }.takeIf { it > 0 } ?: 1.0
            val top = card.bars.indexOfFirst { it.value == card.bars.maxOf { b -> b.value } }
            val dark = c.primary
            val light = c.switchOff
            val axis = c.line
            Canvas(
                Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .height(70.dp)
                    .semantics { contentDescription = card.bars.joinToString(", ") { "${it.label} ${it.value}" } },
            ) {
                val n = card.bars.size
                val slot = size.width / n
                val barW = slot * 0.68f
                val base = size.height - 8.dp.toPx()
                val maxH = base - 6.dp.toPx()
                card.bars.forEachIndexed { i, b ->
                    val h = (b.value / max).toFloat() * maxH
                    drawRoundRect(
                        if (i == top) dark else light,
                        topLeft = Offset(i * slot + (slot - barW) / 2, base - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                }
                drawLine(axis, Offset(0f, base + 0.5f), Offset(size.width, base + 0.5f), strokeWidth = 1.dp.toPx())
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                card.bars.forEachIndexed { i, b ->
                    Text(
                        b.label,
                        Modifier.weight(1f),
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = if (i == top) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (i == top) c.text else c.textSub,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
