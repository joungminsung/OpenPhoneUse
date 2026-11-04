package com.phoneuse.ui.chat

import com.phoneuse.i18n.tr
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.chat.Conversation
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import java.time.ZoneId

/**
 * The Drawer board: app name + 새 대화, conversations grouped by day, settings with the sign-in line.
 * Long-press a conversation to delete it.
 */
@Composable
fun DrawerContent(
    conversations: List<Conversation>,
    current: Long?,
    accountLine: String,
    onNewChat: () -> Unit,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** The board's "루틴 n" tool button; [routinesOn] is how many are switched on. */
    onRoutines: (() -> Unit)? = null,
    routinesOn: Int = 0,
) {
    val c = pu
    var confirmDelete by remember { mutableStateOf<Conversation?>(null) }
    val now = remember(conversations) { System.currentTimeMillis() }
    val zone = remember { ZoneId.systemDefault() }
    val groups = remember(conversations, now) { groupConversations(conversations, now, zone) }

    Column(modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.systemBars)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("OpenPhoneUse", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = c.text)
            val pill = RoundedCornerShape(20.dp)
            Row(
                Modifier
                    .height(40.dp)
                    .clip(pill)
                    .background(c.surface)
                    .border(1.dp, c.line, pill)
                    .clickable(role = Role.Button, onClick = onNewChat)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PuIcons.Plus, null, tint = c.text, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("New chat", "새 대화"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.text)
            }
        }

        if (onRoutines != null) {
            val tool = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 4.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(tool)
                    .background(c.sunken)
                    .border(1.dp, c.line, tool)
                    .clickable(role = Role.Button, onClick = onRoutines)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("Routines", "루틴"), Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Text("$routinesOn", fontSize = 13.sp, color = c.textSub, style = com.phoneuse.ui.theme.MonoStyle)
            }
        }

        if (groups.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
                Text(tr("No chats yet", "아직 대화가 없어요"), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text(tr("Chats pile up here once you ask for something.", "할 일을 말하면 여기에 쌓여요."), fontSize = 13.sp, color = c.textSub)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                for ((label, list) in groups) {
                    item(key = "sec-$label") {
                        Text(
                            label,
                            Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = c.textSub,
                        )
                    }
                    items(list, key = { it.id }) { conv ->
                        ConversationRow(
                            conv,
                            selected = conv.id == current,
                            time = conversationTime(conv.updatedAt, now, zone),
                            onClick = { onSelect(conv.id) },
                            onLongClick = { confirmDelete = conv },
                        )
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
        Row(
            Modifier
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 18.dp)
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClick = onSettings)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(c.bubble), contentAlignment = Alignment.Center) {
                Icon(PuIcons.Gear, null, tint = c.text2, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Settings", "설정"), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = c.text)
                Text(accountLine, fontSize = 12.5.sp, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    confirmDelete?.let { conv ->
        DeleteDialog(
            title = tr("Delete this chat?", "대화를 지울까요?"),
            body = tr("“${conv.title}” and all its messages will be deleted. This can't be undone.", "‘${conv.title}’ 대화와 메시지가 모두 지워져요. 되돌릴 수 없어요."),
            confirm = tr("Delete", "삭제"),
            onConfirm = {
                onDelete(conv.id)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(conv: Conversation, selected: Boolean, time: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = pu
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.bubble else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = tr("Delete chat", "대화 삭제"),
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            conv.title.ifBlank { tr("New chat", "새 대화") },
            Modifier.weight(1f),
            fontSize = 14.5.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(time, style = MonoStyle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.textSub)
    }
}

/** A plain confirmation dialog whose confirm action is drawn in the danger colour. */
@Composable
fun DeleteDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = pu
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
        text = { Text(body, fontSize = 14.5.sp, lineHeight = 21.sp, color = c.text2) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = c.danger, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "취소"), color = c.text, fontSize = 15.sp) }
        },
    )
}
