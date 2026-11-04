package com.phoneuse.ui.home

import com.phoneuse.i18n.tr
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.cards.CardAction
import com.phoneuse.proactive.NudgeAction
import com.phoneuse.proactive.NudgeKind
import com.phoneuse.proactive.ProactiveEngine
import com.phoneuse.proactive.ProactiveSystem
import com.phoneuse.proactive.SentNudge
import com.phoneuse.ui.cards.runCardAction
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import java.time.ZoneId

/**
 * Runs a nudge button, from its home card or its notification: a prompt starts a card-originated run in a new
 * conversation ([startPrompt], so the usual confirmations apply), a place opens a map app, "나중에" puts it off.
 */
fun runNudgeAction(context: Context, id: String, action: NudgeAction, startPrompt: (String) -> Boolean) {
    val system = ProactiveSystem.get(context)
    when (val card = action.cardAction()) {
        null -> system.ignored(id)
        is CardAction.Prompt -> if (startPrompt(card.text)) {
            system.opened(id)
        } else {
            Toast.makeText(context, tr("You can do that when the current task finishes", "지금 작업이 끝나면 할 수 있어요"), Toast.LENGTH_SHORT).show()
        }
        else -> {
            runCardAction(context, card) { }
            system.opened(id)
        }
    }
}

/** Board LockNudges' cards on the home screen, above the "오늘 챙길 것" tiles: the nudges not yet answered. */
@Composable
fun NudgeCards(nudges: List<SentNudge>, now: Long, onAction: (SentNudge, NudgeAction) -> Unit, onClose: (SentNudge) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (n in nudges) NudgeCard(n, now, onAction, onClose)
    }
}

@Composable
private fun NudgeCard(sent: SentNudge, now: Long, onAction: (SentNudge, NudgeAction) -> Unit, onClose: (SentNudge) -> Unit) {
    val c = pu
    val n = sent.nudge
    val shape = RoundedCornerShape(18.dp)
    val marked = n.kind == NudgeKind.ANNIVERSARY
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape),
    ) {
        // The board marks the anniversary card with the accent bar.
        if (marked) Box(Modifier.width(4.dp).fillMaxHeight().background(c.accent))
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.kind.label, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (marked) c.accentText else c.textSub)
                Text(sentAt(sent.sentAt, now), style = MonoStyle, fontSize = 12.sp, color = c.textSub)
                IconBtn(PuIcons.Close, tr("Close", "닫기"), { onClose(sent) }, tint = c.textSub, iconSize = 16.dp)
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(n.title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                if (n.body.isNotBlank()) Text(n.body, fontSize = 13.5.sp, lineHeight = 20.sp, color = c.textSub)
                if (n.actions.isNotEmpty()) {
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        n.actions.forEachIndexed { i, a -> NudgeButton(a.label, primary = i == 0) { onAction(sent, a) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun NudgeButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .height(36.dp)
            .clip(shape)
            .background(if (primary) c.primary else c.sunken)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (primary) c.onPrimary else c.text, maxLines = 1)
    }
}

/** "지금" within a minute, else the clock time it was sent. */
private fun sentAt(at: Long, now: Long): String =
    if (now - at < 60_000L) tr("Now", "지금") else ProactiveEngine.hm(at, ZoneId.systemDefault())
