package com.phoneuse.ui.cards

import com.phoneuse.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.cards.CardAction
import com.phoneuse.cards.CardActions
import com.phoneuse.cards.RouteCard
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuColors
import com.phoneuse.ui.theme.pu

/** Board "Route": the leave time large, a timeline of legs, then reminder / map / share buttons. */
@Composable
fun RouteCardView(card: RouteCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    val c = pu
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CardBox(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), label = tr("Departure time", "출발 시간")) {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr("Leave at this time", "이때 나가면 돼요"), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
                    Text(card.departAt, style = MonoStyle, fontSize = 40.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp, color = c.text)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row {
                        Text(tr("Arrive ", "도착 "), fontSize = 13.sp, color = c.textSub)
                        Text(card.arriveAt, style = MonoStyle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    }
                    if (card.summary.isNotBlank()) Text(card.summary, fontSize = 13.sp, color = c.textSub)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
            Spacer(Modifier.height(16.dp))
            for (leg in card.legs) {
                LegRow(leg.at, leg.label, leg.detail, dotColor(leg.kind, c), lineColor(leg.kind, c), dashed = leg.kind == "walk", last = false)
            }
            LegRow(card.arriveAt, card.dest.name, "", c.accent, null, dashed = false, last = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val reminder = CardActions.routeReminder(card)
            CardButton(tr("Remind me to get ready at ${CardActions.prepareAt(card.departAt)}", "${CardActions.prepareAt(card.departAt)}에 나갈 준비 알림"), { onAction(reminder) }, Modifier.fillMaxWidth(), primary = true, height = 48.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton(tr("Open in maps app", "지도 앱에서 열기"), { onAction(CardActions.openRoute(card)) }, Modifier.weight(1f))
                CardButton(tr("Share arrival time", "도착 시간 보내기"), { onAction(CardActions.shareArrival(card)) }, Modifier.weight(1f))
            }
        }
    }
}

private fun dotColor(kind: String, c: PuColors): Color = when (kind) {
    "bus" -> c.agentDot
    "subway" -> c.success
    "train" -> c.secure
    "car" -> c.textSub
    else -> c.primary
}

private fun lineColor(kind: String, c: PuColors): Color = if (kind == "walk") c.radioOff else dotColor(kind, c)

@Composable
private fun LegRow(at: String, title: String, detail: String, dot: Color, line: Color?, dashed: Boolean, last: Boolean) {
    val c = pu
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(at, Modifier.width(48.dp).padding(top = 1.dp), style = MonoStyle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(c.surface)
                    .border(3.dp, dot, CircleShape),
            )
            if (line != null) {
                Canvas(Modifier.width(3.dp).weight(1f).heightIn(min = 26.dp)) {
                    val x = size.width / 2
                    drawLine(
                        line,
                        Offset(x, 0f),
                        Offset(x, size.height),
                        strokeWidth = size.width,
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null,
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 12.dp else 14.dp)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            if (detail.isNotBlank()) Text(detail, Modifier.padding(top = 2.dp), fontSize = 12.5.sp, color = c.textSub)
        }
    }
}
