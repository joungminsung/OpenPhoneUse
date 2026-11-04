package com.phoneuse.ui.cards

import com.phoneuse.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.cards.CardAction
import com.phoneuse.cards.CardActions
import com.phoneuse.cards.ParcelCard
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.pu

/** Board "Parcel": title, the four stages as a bar (done dark, current accent), latest steps, two requests. */
@Composable
fun ParcelCardView(card: ParcelCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    val c = pu
    val current = ParcelCard.STAGES.indexOf(card.status).coerceAtLeast(0)
    CardBox(modifier, label = tr("Delivery status: ${card.title}, ${card.status}", "배송 상태: ${card.title}, ${card.status}")) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Text(card.title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.text, maxLines = 1)
            val sub = card.source?.takeIf { it.isNotBlank() } ?: listOfNotNull(card.carrier, card.tracking).joinToString(" ").takeIf { it.isNotBlank() }
            sub?.let { Text(it, fontSize = 12.5.sp, color = c.textSub, maxLines = 1) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ParcelCard.STAGES.indices.forEach { i ->
                val color = when {
                    i < current -> c.primary
                    i == current -> if (current == ParcelCard.STAGES.lastIndex) c.primary else c.accent
                    else -> c.line
                }
                Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(color))
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            ParcelCard.STAGES.forEachIndexed { i, stage ->
                Text(
                    stage,
                    Modifier.weight(1f),
                    fontSize = 11.5.sp,
                    fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == current) c.accentText else c.textSub,
                    textAlign = if (i == ParcelCard.STAGES.lastIndex) TextAlign.End else TextAlign.Start,
                )
            }
        }
        card.events.forEachIndexed { i, e ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (i == 0) c.accent else c.switchOff),
                )
                Text(
                    e.text,
                    Modifier.weight(1f),
                    fontSize = 13.sp,
                    fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (i == 0) c.text else c.textSub,
                )
                Text(e.at, style = MonoStyle, fontSize = 13.sp, color = c.textSub)
            }
        }
        if (card.status != ParcelCard.STAGES.last()) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton(tr("Ask to leave at door", "문 앞에 두라고 요청"), { onAction(CardActions.parcelLeaveAtDoor()) }, Modifier.weight(1f), height = 42.dp)
                CardButton(tr("Notify on arrival", "도착하면 알림"), { onAction(CardActions.parcelNotifyArrival()) }, Modifier.weight(1f), height = 42.dp)
            }
        }
    }
}
