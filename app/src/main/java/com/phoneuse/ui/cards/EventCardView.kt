package com.phoneuse.ui.cards

import com.phoneuse.i18n.Lang
import com.phoneuse.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.cards.CardAction
import com.phoneuse.cards.CardActions
import com.phoneuse.cards.EventCard
import com.phoneuse.cards.parseIso
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.pu
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Board "Calendar": the event preview, any overlap, then 고치기 / 그대로 저장. */
@Composable
fun EventCardView(card: EventCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    val c = pu
    val start = parseIso(card.start)
    val end = parseIso(card.end)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CardBox(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp), label = tr("Event preview", "일정 미리보기")) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (start != null) {
                    Column(
                        Modifier
                            .width(58.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.primary)
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(start.dayOfWeek.getDisplayName(TextStyle.SHORT, if (Lang.korean) Locale.KOREAN else Locale.ENGLISH), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.onPrimary)
                        Text("${start.dayOfMonth}", style = MonoStyle, fontSize = 24.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = c.onPrimary)
                        Text(if (Lang.korean) "${start.monthValue}월" else start.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), fontSize = 11.sp, color = c.onPrimary.copy(alpha = 0.8f))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    Text(card.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text)
                    Text(timeRange(start, end, card), style = MonoStyle, fontSize = 14.sp, color = c.text)
                    card.location?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 12.5.sp, color = c.textSub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            card.reminderMin?.let { min ->
                val at = start?.minusMinutes(min.toLong())?.let { " · %02d:%02d".format(it.hour, it.minute) }.orEmpty()
                FieldRow(tr("Reminder", "알림"), if (min == 0) tr("At start$at", "시작할 때$at") else tr("$min min before$at", "${min}분 전$at"))
            }
        }
        if (card.conflicts.isNotEmpty()) ConflictBox(card, start, end, onAction)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton(tr("Edit", "고치기"), { onAction(CardActions.editEvent()) }, Modifier.weight(1f), height = 48.dp)
            CardButton(tr("Save as is", "그대로 저장"), { onAction(CardActions.saveEvent()) }, Modifier.weight(2f), primary = true, height = 48.dp)
        }
    }
}

private fun hm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

private fun timeRange(start: LocalDateTime?, end: LocalDateTime?, card: EventCard): String {
    if (start == null || end == null) return "${card.start} – ${card.end}"
    val allDay = start.toLocalTime().toSecondOfDay() == 0 && end.toLocalTime().toSecondOfDay() == 0 && !card.start.contains('T')
    if (allDay) return tr("All day", "하루 종일")
    return if (start.toLocalDate() == end.toLocalDate()) {
        "${hm(start)} – ${hm(end)}"
    } else {
        "${hm(start)} – ${end.monthValue}/${end.dayOfMonth} ${hm(end)}"
    }
}

@Composable
private fun FieldRow(key: String, value: String) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(pu.lineSoft))
        Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(key, Modifier.weight(1f), fontSize = 14.5.sp, color = pu.textSub)
            Text(value, fontSize = 14.5.sp, color = pu.text)
        }
    }
}

/** The amber overlap box with a small hour timeline (event on the right, the clash on the left). */
@Composable
private fun ConflictBox(card: EventCard, start: LocalDateTime?, end: LocalDateTime?, onAction: (CardAction) -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(18.dp)
    val first = card.conflicts.first()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.moneySoft)
            .border(1.dp, c.moneyBorder, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (card.conflicts.size == 1) tr("You have “${first.title}” at the same time", "같은 시간에 ‘${first.title}’ 일정이 있어요") else tr("You have ${card.conflicts.size} events at the same time", "같은 시간에 일정 ${card.conflicts.size}개가 있어요"),
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.money,
        )
        val cs = parseIso(first.start)
        val ce = parseIso(first.end)
        if (start != null && end != null && cs != null && ce != null && start.toLocalDate() == cs.toLocalDate()) {
            Timeline(start, end, card.title, cs, ce, first.title)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton(tr("Decline", "불참 알리기"), { onAction(CardActions.notifyConflict(0)) }, height = 40.dp)
            CardButton(tr("Find free time", "비는 시간 찾기"), { onAction(CardActions.findFreeTime()) }, height = 40.dp)
        }
    }
}

@Composable
private fun Timeline(start: LocalDateTime, end: LocalDateTime, title: String, cs: LocalDateTime, ce: LocalDateTime, conflict: String) {
    val c = pu
    val from = minOf(start, cs).truncatedTo(ChronoUnit.HOURS)
    val last = maxOf(end, ce)
    val hours = (ChronoUnit.MINUTES.between(from, last) / 60.0).let { kotlin.math.ceil(it).toInt() }.coerceIn(1, 6)
    val hourDp = 28.dp
    fun y(t: LocalDateTime) = hourDp * (ChronoUnit.MINUTES.between(from, t).toFloat() / 60f).coerceIn(0f, hours.toFloat())
    Row(Modifier.fillMaxWidth().height(hourDp * hours)) {
        Column(Modifier.width(40.dp)) {
            repeat(hours) { h ->
                Text(
                    "%02d:00".format((from.hour + h) % 24),
                    Modifier.height(hourDp),
                    style = MonoStyle,
                    fontSize = 11.sp,
                    color = c.textSub,
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp)) {
            repeat(hours) { h -> Box(Modifier.offset(y = hourDp * h).fillMaxWidth().height(1.dp).background(c.moneyBorder)) }
            Block(conflict, y(cs), y(ce), Modifier.width(maxWidth * 0.58f), c.chip, c.text2)
            Block(title, y(start), y(end), Modifier.align(Alignment.TopEnd).width(maxWidth * 0.40f), c.primary, c.onPrimary)
        }
    }
}

@Composable
private fun Block(text: String, top: androidx.compose.ui.unit.Dp, bottom: androidx.compose.ui.unit.Dp, modifier: Modifier, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        modifier
            .offset(y = top)
            .height((bottom - top).coerceAtLeast(18.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        fontSize = 11.5.sp,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
