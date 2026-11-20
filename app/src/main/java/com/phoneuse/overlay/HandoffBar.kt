package com.phoneuse.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.i18n.tr
import com.phoneuse.ui.theme.ButtonKind
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.StopCircle
import com.phoneuse.ui.theme.pu

/** Keeps the instructions available without covering the app until the user asks to read them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HandoffBar(
    card: DockCard.HandOff,
    availableSize: IntSize?,
    onMove: () -> Unit,
    onDone: () -> Unit,
    onStop: () -> Unit,
) {
    val c = pu
    val shape = RoundedCornerShape(12.dp)
    var expanded by remember(card.id) { mutableStateOf(false) }
    val panelScroll = key(card.id) { rememberScrollState() }
    var actionsHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // Keep one action reachable even when the IME occupies the entire display.
    val maxWidth = with(density) { availableSize?.width?.toDp()?.coerceIn(56.dp, 400.dp) ?: 400.dp }
    val maxHeight = with(density) { availableSize?.height?.toDp()?.coerceAtLeast(56.dp) ?: 400.dp }
    val reasonRoom = with(density) { maxHeight - actionsHeight.toDp() - 8.dp }

    Column(
        Modifier
            .widthIn(max = maxWidth)
            .heightIn(max = maxHeight)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .padding(4.dp)
            .verticalScroll(panelScroll),
    ) {
        FlowRow(
            modifier = Modifier.onSizeChanged { actionsHeight = it.height },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier
                    .size(48.dp)
                    .clip(shape)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics {
                        contentDescription = if (expanded) tr("Hide handoff instructions", "직접 조작 안내 접기")
                        else tr("Show handoff instructions", "직접 조작 안내 펼치기")
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(PuIcons.Lock, null, Modifier.size(20.dp), tint = c.secure)
                Icon(if (expanded) PuIcons.ChevronUp else PuIcons.ChevronDown, null, Modifier.size(14.dp), tint = c.textSub)
            }
            PuButton(
                tr("Move", "이동"),
                onClick = {
                    expanded = false
                    onMove()
                },
                modifier = Modifier.semantics { contentDescription = tr("Move panel to the opposite edge", "패널을 반대쪽 가장자리로 이동") },
                kind = ButtonKind.OUTLINE, fontSize = 14,
            )
            PuButton(tr("Done, continue", "완료, 이어서 해줘"), onDone, fontSize = 14)
            StopCircle(onStop, Modifier.size(48.dp))
        }
        if (expanded) {
            // Keep the actions in place when the instructions have room for a line. In a thinner
            // strip, scroll the whole panel so the user can still reach every action and the full reason.
            val instructions = if (reasonRoom >= 48.dp) {
                Modifier.heightIn(max = minOf(reasonRoom, 144.dp)).verticalScroll(rememberScrollState())
            } else Modifier
            Text(
                card.reason,
                instructions.padding(8.dp),
                color = c.text2, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
    }
}
