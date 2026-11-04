package com.phoneuse.ui.theme

import com.phoneuse.i18n.tr
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The boards' `.ib`: a 44dp round transparent icon button. */
@Composable
fun IconBtn(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = pu.text,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Black (text-colour) filled circle, 44dp: send. */
@Composable
fun SendCircle(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) pu.primary else pu.primary.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = tr("Send", "보내기") },
        contentAlignment = Alignment.Center,
    ) {
        Icon(PuIcons.Send, null, tint = if (enabled) pu.onPrimary else pu.onPrimary.copy(alpha = 0.8f), modifier = Modifier.size(if (size < 44.dp) 18.dp else 20.dp))
    }
}

/** Black filled circle with a white rounded square: stop. */
@Composable
fun StopCircle(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(pu.primary)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = tr("Stop", "중단") },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(pu.onPrimary))
    }
}

/** The boards' switch: [width]×[height], filled with the text colour when on. */
@Composable
fun PuSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    width: Dp = 44.dp,
    height: Dp = 26.dp,
    enabled: Boolean = true,
) {
    val knob = height - 6.dp
    val bg by animateColorAsState(if (checked) pu.primary else pu.switchOff, label = "switchBg")
    val x by animateDpAsState(if (checked) width - knob - 3.dp else 3.dp, label = "switchKnob")
    Box(
        modifier
            .size(width, height)
            .clip(CircleShape)
            .background(bg)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .offset(x = x, y = 3.dp)
                .size(knob)
                .clip(CircleShape)
                .background(if (checked) pu.onPrimary else Color.White),
        )
    }
}

/** ModelSheet radio: 20dp ring, a thick text-colour ring when selected. */
@Composable
fun PuRadio(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(20.dp)
            .border(if (selected) 6.dp else 2.dp, if (selected) pu.primary else pu.radioOff, CircleShape),
    )
}

/** The segmented control from ModelSheet (`.seg`). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pu.segmentBg)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(10.dp)) else Modifier)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) pu.segmentOn else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) pu.text else pu.text2,
                )
            }
        }
    }
}

/** The orange "빠름" tag next to the model name. */
@Composable
fun FastTag(modifier: Modifier = Modifier) {
    Text(
        tr("Fast", "빠름"),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(pu.accentSoft)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = pu.accentText,
    )
}

enum class ButtonKind { PRIMARY, OUTLINE, DANGER }

/** The boards' 48dp card buttons (`.bp` filled with the text colour, `.bo` outlined). */
@Composable
fun PuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    height: Dp = 48.dp,
    radius: Dp = 14.dp,
    enabled: Boolean = true,
    loading: Boolean = false,
    fontSize: Int = 15,
) {
    val shape = RoundedCornerShape(radius)
    val (bg, fg) = when (kind) {
        ButtonKind.PRIMARY -> pu.primary to pu.onPrimary
        ButtonKind.OUTLINE -> pu.surface to pu.text
        ButtonKind.DANGER -> pu.danger to (if (pu.dark) pu.onPrimary else Color.White)
    }
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .then(if (kind == ButtonKind.OUTLINE) Modifier.border(1.dp, pu.inputBorder, shape) else Modifier)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = fg, strokeWidth = 2.dp)
        } else {
            Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.Medium, color = fg, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

/** Two equal buttons side by side (`.b2`). */
@Composable
fun ButtonPair(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
