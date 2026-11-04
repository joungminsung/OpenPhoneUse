package com.phoneuse.ui.chat

import com.phoneuse.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.app.ChatCard
import com.phoneuse.app.ChatUserGate
import com.phoneuse.risk.RiskCategory
import com.phoneuse.ui.theme.ButtonKind
import com.phoneuse.ui.theme.ButtonPair
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.PuColors
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu

/** Colours and icon of a risk category (Cards / ChatConfirm / OverlayConfirm boards). */
data class CategoryLook(val icon: ImageVector, val fg: Color, val soft: Color, val border: Color, val danger: Boolean)

fun RiskCategory.look(c: PuColors): CategoryLook = when (this) {
    RiskCategory.MONEY -> CategoryLook(PuIcons.Card, c.money, c.moneySoft, c.moneyBorder, false)
    RiskCategory.SECURITY -> CategoryLook(PuIcons.Shield, c.secure, c.secureSoft, c.secureBorder, false)
    RiskCategory.DELETE -> CategoryLook(PuIcons.Trash, c.danger, c.dangerSoft, c.dangerBorder, true)
    RiskCategory.SEND -> CategoryLook(PuIcons.Plane, c.accentText, c.accentSoft, c.accentBorder, false)
    RiskCategory.CHANGE -> CategoryLook(PuIcons.Gear, c.accentText, c.accentSoft, c.accentBorder, false)
}

/** The floating card shell: white, 22dp corners, soft shadow. */
@Composable
fun CardShell(
    modifier: Modifier = Modifier,
    border: Color = pu.line,
    radius: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(10.dp, shape, ambientColor = Color(0x2914161A), spotColor = Color(0x2914161A))
            .clip(shape)
            .background(pu.surface)
            .border(1.dp, border, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Icon square + optional kicker + question. */
@Composable
fun CardHeader(icon: ImageVector, fg: Color, soft: Color, kicker: String?, question: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(soft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            if (kicker != null) {
                Text(kicker, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, color = fg, letterSpacing = 0.2.sp)
            }
            Text(question, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = pu.text)
        }
    }
}

/** The grey box of details under a confirmation question. */
@Composable
fun ConfirmDetails(copy: ConfirmCopy) {
    if (copy.details.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(pu.sunken)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = if (copy.monoDetails) 8.dp else 10.dp),
    ) {
        for (line in copy.details) {
            if (copy.monoDetails) {
                Text(
                    line,
                    style = MonoStyle,
                    fontSize = 12.5.sp,
                    color = pu.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            } else {
                Text(line, fontSize = 13.5.sp, lineHeight = 20.sp, color = pu.text2)
            }
        }
    }
}

/** A question card from [ChatUserGate], shown above the composer. Answers go to [onAnswer] with the card id. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatCardView(card: ChatCard, onAnswer: (Long, String) -> Unit, modifier: Modifier = Modifier) {
    val c = pu
    when (card) {
        is ChatCard.Confirm -> {
            val look = card.category.look(c)
            val copy = confirmCopy(card.category, card.summary)
            CardShell(modifier, border = look.border) {
                CardHeader(look.icon, look.fg, look.soft, copy.kicker, copy.question)
                ConfirmDetails(copy)
                copy.note?.let { Text(it, fontSize = 12.5.sp, lineHeight = 18.sp, color = c.textSub) }
                ButtonPair {
                    PuButton(tr("Deny", "거부"), { onAnswer(card.id, ChatUserGate.CONFIRM_NO) }, Modifier.weight(1f), ButtonKind.OUTLINE)
                    PuButton(
                        copy.allowLabel,
                        { onAnswer(card.id, ChatUserGate.CONFIRM_YES) },
                        Modifier.weight(1f),
                        if (look.danger) ButtonKind.DANGER else ButtonKind.PRIMARY,
                    )
                }
            }
        }
        is ChatCard.Ask -> CardShell(modifier) {
            CardHeader(PuIcons.Question, c.accentText, c.accentSoft, null, card.question)
            OptionButtons(card.options) { onAnswer(card.id, it) }
            Text(tr("You can also type another answer below", "다른 답은 아래 입력창에 써도 돼요"), fontSize = 12.5.sp, color = c.textSub)
        }
        is ChatCard.HandOff -> CardShell(modifier) {
            CardHeader(PuIcons.Lock, c.secure, c.secureSoft, tr("Please enter it yourself", "직접 입력해 주세요"), card.reason)
            Text(
                tr("Tap Done when you finish and I'll continue.", "다 하고 완료를 누르면 이어서 할게요."),
                fontSize = 13.5.sp,
                lineHeight = 21.sp,
                color = c.text2,
            )
            PuButton(tr("Done, continue", "완료, 이어서 해줘"), { onAnswer(card.id, ChatUserGate.HAND_OFF_DONE) }, Modifier.fillMaxWidth())
        }
    }
}

/** Short answers sit side by side; the most options in one row. */
private const val ROW_MAX_OPTIONS = 3
private const val ROW_MAX_CHARS = 8

/** True when [options] are few and short enough to share one row; otherwise each gets a full-width button. */
fun optionsFitOneRow(options: List<String>): Boolean =
    options.size in 1..ROW_MAX_OPTIONS && options.all { it.trim().length <= ROW_MAX_CHARS }

/** An ask card's answers as big one-tap buttons (at least 52dp tall): one row when short, else stacked. */
@Composable
fun OptionButtons(options: List<String>, onPick: (String) -> Unit) {
    if (options.isEmpty()) return
    if (optionsFitOneRow(options)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in options) OptionPill(option, Modifier.weight(1f)) { onPick(option) }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in options) OptionPill(option, Modifier.fillMaxWidth()) { onPick(option) }
        }
    }
}

@Composable
fun OptionPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(pu.surface)
            .border(1.dp, pu.inputBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.Medium,
            color = pu.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
