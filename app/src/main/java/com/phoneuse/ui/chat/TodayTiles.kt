package com.phoneuse.ui.chat

import com.phoneuse.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.phone.SmsParsers
import com.phoneuse.today.TodaySummary
import com.phoneuse.today.TodayTile
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.pu

/** What tapping a tile does: the parcel question, the digest screen, or an event's card conversation. */
class TodayTileActions(
    val onParcel: () -> Unit = {},
    val onDigest: () -> Unit = {},
    val onEvent: (TodayTile.Events.Item) -> Unit = {},
)

/**
 * Board Main's tiles on the phone: parcels full width with the stage bar, the alarm and the notifications side by
 * side, then the events and the month's spending. Only the tiles [TodaySummary] returned are drawn.
 */
@Composable
fun TodayTiles(tiles: List<TodayTile>, actions: TodayTileActions) {
    val parcels = tiles.filterIsInstance<TodayTile.Parcels>().firstOrNull()
    val alarm = tiles.filterIsInstance<TodayTile.Alarm>().firstOrNull()
    val notifications = tiles.filterIsInstance<TodayTile.Notifications>().firstOrNull()
    val events = tiles.filterIsInstance<TodayTile.Events>().firstOrNull()
    val spending = tiles.filterIsInstance<TodayTile.Spending>().firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parcels?.let { ParcelTile(it, actions.onParcel, Modifier.fillMaxWidth()) }
        if (alarm != null || notifications != null) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                alarm?.let { AlarmTile(it, Modifier.weight(1f).fillMaxHeight()) }
                notifications?.let { NotificationTile(it, wide = false, actions.onDigest, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        events?.let { EventsTile(it, actions.onEvent, Modifier.fillMaxWidth()) }
        spending?.let { SpendingTile(it, Modifier.fillMaxWidth()) }
    }
}

/** Board UnfoldedHome: parcels, alarm and replies in one row (2:1:1), then events and spending (1.2:1). */
@Composable
fun TodayTilesWide(tiles: List<TodayTile>, actions: TodayTileActions) {
    val parcels = tiles.filterIsInstance<TodayTile.Parcels>().firstOrNull()
    val alarm = tiles.filterIsInstance<TodayTile.Alarm>().firstOrNull()
    val notifications = tiles.filterIsInstance<TodayTile.Notifications>().firstOrNull()
    val events = tiles.filterIsInstance<TodayTile.Events>().firstOrNull()
    val spending = tiles.filterIsInstance<TodayTile.Spending>().firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (parcels != null || alarm != null || notifications != null) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                parcels?.let { ParcelTile(it, actions.onParcel, Modifier.weight(2f).fillMaxHeight()) }
                alarm?.let { AlarmTile(it, Modifier.weight(1f).fillMaxHeight()) }
                notifications?.let { NotificationTile(it, wide = true, actions.onDigest, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        if (events != null || spending != null) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                events?.let { EventsTile(it, actions.onEvent, Modifier.weight(1.2f).fillMaxHeight()) }
                spending?.let { SpendingTile(it, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun ParcelTile(t: TodayTile.Parcels, onClick: () -> Unit, modifier: Modifier) {
    val c = pu
    Tile(modifier, onClick, tr("Ask where my parcel is", "택배 어디쯤 왔는지 묻기")) {
        Row(Modifier.fillMaxWidth()) {
            Text(tr("Parcels", "택배"), Modifier.weight(1f), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
            Text(tr("${t.count}", "${t.count}개"), style = MonoStyle, fontSize = 12.5.sp, color = c.textSub)
        }
        Text(t.line, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val current = SmsParsers.PARCEL_STAGES.indexOf(t.stage)
        if (current >= 0) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmsParsers.PARCEL_STAGES.indices.forEach { i ->
                    val color = when {
                        i < current -> c.primary
                        i == current -> if (i == SmsParsers.PARCEL_STAGES.lastIndex) c.primary else c.accent
                        else -> c.line
                    }
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
                }
            }
        }
    }
}

@Composable
private fun AlarmTile(t: TodayTile.Alarm, modifier: Modifier) {
    val c = pu
    Tile(modifier) {
        Text(t.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
        BigNumber(t.time)
        Text(tr("Alarm", "알람"), fontSize = 12.5.sp, color = c.textSub)
    }
}

@Composable
private fun NotificationTile(t: TodayTile.Notifications, wide: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = pu
    Tile(modifier, onClick, tr("Open notification summary", "알림 요약 열기")) {
        if (wide && t.reply > 0) {
            // Board UnfoldedHome: the replies alone, in the accent colour.
            Text(tr("Needs reply", "답장 필요"), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
            BigNumber("${t.reply}", accent = true)
        } else {
            Text(tr("Unread alerts", "안 읽은 알림"), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
            BigNumber("${t.unread}")
            Text(
                if (t.reply > 0) tr("${t.reply} need${if (t.reply == 1) "s" else ""} reply →", "답장 필요 ${t.reply}개 →") else tr("View grouped →", "묶어서 보기 →"),
                fontSize = 12.5.sp,
                fontWeight = if (t.reply > 0) FontWeight.SemiBold else FontWeight.Normal,
                color = if (t.reply > 0) c.accentText else c.textSub,
            )
        }
    }
}

@Composable
private fun EventsTile(t: TodayTile.Events, onEvent: (TodayTile.Events.Item) -> Unit, modifier: Modifier) {
    val c = pu
    Tile(modifier, gap = 0) {
        Text(tr("Upcoming", "남은 일정"), Modifier.padding(bottom = 8.dp), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
        for (item in t.items) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = tr("View event", "일정 보기")) { onEvent(item) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.time, Modifier.width(52.dp), style = MonoStyle, fontSize = 14.sp, color = c.textSub)
                Text(item.title, fontSize = 14.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SpendingTile(t: TodayTile.Spending, modifier: Modifier) {
    val c = pu
    Tile(modifier, gap = 4) {
        Text(t.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
        Text(TodaySummary.won(t.total), style = MonoStyle, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, color = c.text)
        t.diff?.let { diff ->
            Text(
                tr("${TodaySummary.signedWon(diff)} vs last month", "지난달보다 ${TodaySummary.signedWon(diff)}"),
                fontSize = 12.5.sp,
                fontWeight = if (diff > 0) FontWeight.SemiBold else FontWeight.Normal,
                color = if (diff > 0) c.accentText else c.textSub,
            )
        }
    }
}

@Composable
private fun BigNumber(text: String, accent: Boolean = false) {
    val c = pu
    Text(text, style = MonoStyle, fontSize = 30.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp, color = if (accent) c.accentText else c.text)
}

@Composable
private fun Tile(
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    gap: Int = 6,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = pu
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
        content = content,
    )
}
