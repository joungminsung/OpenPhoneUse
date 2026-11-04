package com.phoneuse.ui.chat

import com.phoneuse.i18n.tr
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.phoneuse.app.AppGraph
import com.phoneuse.app.ReadAloud
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import com.phoneuse.cards.CardAction
import com.phoneuse.cards.ResultsCard
import com.phoneuse.cards.SourceLink
import com.phoneuse.chat.ChatMessage
import com.phoneuse.chat.ForgottenMemory
import com.phoneuse.chat.MemoryRef
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.phoneuse.chat.MessageRole
import com.phoneuse.chat.StepLog
import com.phoneuse.notify.NotifyText
import com.phoneuse.ui.cards.ResultCard
import com.phoneuse.ui.fold.PaneCard
import com.phoneuse.ui.cards.rememberCardActionRunner
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu

/**
 * The conversation, newest at the bottom. Uses a reversed layout so the newest content stays anchored
 * above the composer while the keyboard opens or an answer streams in.
 */
@Composable
fun MessageList(
    messages: List<ChatMessage>,
    live: Boolean,
    streaming: String,
    liveSteps: List<StepLog>,
    state: LazyListState,
    modifier: Modifier = Modifier,
    onCardPrompt: (String) -> Unit = {},
    liveSearches: Int = 0,
    /** Ids of the memories that still exist (null until known); a "기억했어요" line whose memory is gone shows as undone. */
    memoryIds: Set<Long>? = null,
    onForgetMemory: (Long) -> Unit = {},
    memoryTexts: Set<String>? = null,
    onRestoreMemory: (ForgottenMemory) -> Unit = {},
    onOpenMemory: () -> Unit = {},
    /** The card the unfolded result pane shows (PaneCard.key); it is not repeated in the conversation. */
    paneCardKey: String? = null,
) {
    val onCardAction = rememberCardActionRunner(onCardPrompt)
    // Prompt buttons work only on the newest card of their kind (CardStaleness).
    val superseded = remember(messages) { com.phoneuse.cards.CardStaleness.superseded(messages) }
    LazyColumn(
        modifier = modifier,
        state = state,
        reverseLayout = true,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.Bottom),
    ) {
        if (live) {
            item(key = "live") { LiveAnswer(streaming, liveSteps, liveSearches) }
        }
        items(messages.asReversed(), key = { it.id }) { m ->
            when (m.role) {
                MessageRole.USER -> UserMessage(m)
                MessageRole.ASSISTANT -> AssistantMessage(m, onCardAction, memoryIds, onForgetMemory, onOpenMemory, memoryTexts, onRestoreMemory, paneCardKey, superseded)
            }
        }
    }
}

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("OpenPhoneUse", text))
    // Android 13+ shows its own confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, tr("Copied", "복사했어요"), Toast.LENGTH_SHORT).show()
}

/** Long-press copies [text]; no ripple, so it does not flash the whole answer. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.copyOnLongPress(text: String): Modifier {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    return this
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = {},
            onLongClickLabel = tr("Copy", "복사"),
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                copyToClipboard(context, text)
            },
        )
}

/** An answer; long-press opens 복사 and 소리 내어 읽기 (읽기 멈추기 while it is being read). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnswerText(text: String) {
    val c = pu
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val speaking by ReadAloud.speaking.collectAsState()
    Box(Modifier.fillMaxWidth()) {
        MarkdownText(
            text,
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClickLabel = tr("Copy or read aloud", "복사 또는 소리 내어 읽기"),
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                ),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
            DropdownMenuItem(text = { Text(tr("Copy", "복사"), color = c.text) }, onClick = {
                menu = false
                copyToClipboard(context, NotifyText.plain(text).ifBlank { text })
            })
            DropdownMenuItem(text = { Text(if (speaking) tr("Stop reading", "읽기 멈추기") else tr("Read aloud", "소리 내어 읽기"), color = c.text) }, onClick = {
                menu = false
                if (speaking) ReadAloud.stop() else ReadAloud.speak(context, text, AppGraph.voiceLanguage.value.tag)
            })
        }
    }
}

private val BubbleShape =RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp)

/** A user message: its photos and files (if any) above the text bubble. */
@Composable
private fun UserMessage(m: ChatMessage) {
    if (m.attachments.isEmpty()) return UserBubble(m.text)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        UserAttachments(m.attachments)
        if (m.text.isNotBlank()) UserBubble(m.text)
    }
}

@Composable
fun UserBubble(text: String) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            modifier = Modifier
                .widthIn(max = maxWidth * 0.78f)
                .clip(BubbleShape)
                .background(pu.bubble)
                .copyOnLongPress(text)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            fontSize = 15.sp,
            lineHeight = 22.5.sp,
            color = pu.text,
        )
    }
}

@Composable
private fun AssistantMessage(
    m: ChatMessage,
    onCardAction: (com.phoneuse.cards.CardAction) -> Unit,
    memoryIds: Set<Long>?,
    onForgetMemory: (Long) -> Unit,
    onOpenMemory: () -> Unit,
    memoryTexts: Set<String>?,
    onRestoreMemory: (ForgottenMemory) -> Unit,
    paneCardKey: String?,
    superseded: Set<com.phoneuse.cards.CardStaleness.CardAt> = emptySet(),
) {
    val context = LocalContext.current
    // An older card of a kind shown again later: its prompt would be read against the newer card.
    val olderCardAction: (com.phoneuse.cards.CardAction) -> Unit = { action ->
        if (action is com.phoneuse.cards.CardAction.Prompt) {
            Toast.makeText(
                context,
                tr("A newer card of this kind is below. Use that one, or say which one you mean", "아래에 더 새 카드가 있어요. 그 카드를 쓰거나 어느 것인지 말해 주세요"),
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            onCardAction(action)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (m.steps.isNotEmpty()) StepsPanel(m.steps)
        if (m.extras.webSearches > 0) SearchLine(m.extras.webSearches)
        // Board ChatMemory: the memories this answer drew on, then what it saved, both above the answer.
        if (m.extras.usedMemories.isNotEmpty()) UsedMemoryChips(m.extras.usedMemories, onOpenMemory)
        for (r in m.extras.remembered) RememberedLine(r, kept = memoryIds?.contains(r.id) ?: true, onUndo = { onForgetMemory(r.id) })
        for (f in m.extras.forgotten) {
            ForgottenLine(f, restored = memoryTexts?.contains(f.text) ?: false, onRestore = { onRestoreMemory(f) })
        }
        if (m.text.isNotBlank()) AnswerText(m.text)
        // The ChatGPT plan's usage limit: the one thing to do next is in its usage settings.
        if (m.extras.planUsageLimit) {
            PuButton(
                tr("Manage usage", "사용량 관리"),
                { onCardAction(CardAction.OpenUrl(com.phoneuse.auth.PlanConfig.MANAGE_USAGE_URL)) },
                height = 40.dp,
                radius = 12.dp,
                fontSize = 14,
            )
        }
        // Result cards from show_card, in the order the agent produced them.
        // Keyed by message and position, so a card keeps its selection when it moves to or from the result pane.
        m.cards.forEachIndexed { i, card ->
            val key = PaneCard.keyOf(m.id, i)
            val action = if (com.phoneuse.cards.CardStaleness.promptsAllowed(superseded, m.id, i)) onCardAction else olderCardAction
            if (key != paneCardKey) ResultCard(card, action, Modifier.padding(top = 4.dp), stateKey = key)
        }
        // A results card lists the sources itself (board Search); otherwise they go under the answer.
        if (m.sources.isNotEmpty() && m.cards.none { it is ResultsCard }) SourcesLine(m.sources, onCardAction)
        m.extras.notice?.takeIf { it.isNotBlank() }?.let { NoticeLine(it) }
    }
}

/** "기억 사용 [chip] [chip]": purple chips for the memories the prompt matched; a tap opens the memory screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsedMemoryChips(used: List<MemoryRef>, onOpen: () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Memories used", "기억 사용"), fontSize = 12.5.sp, color = pu.textSub)
        for (m in used.take(MAX_MEMORY_CHIPS)) {
            Box(
                Modifier
                    .height(26.dp)
                    .widthIn(max = 220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(pu.memorySoft)
                    .clickable(role = Role.Button, onClickLabel = tr("Open memory", "메모리 열기"), onClick = onOpen)
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(m.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = pu.memory, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (used.size > MAX_MEMORY_CHIPS) Text("+${used.size - MAX_MEMORY_CHIPS}", style = MonoStyle, fontSize = 12.5.sp, color = pu.textSub)
    }
}

private const val MAX_MEMORY_CHIPS = 3

/** "기억했어요 · <text> [취소]"; once undone (the memory is gone) it reads "기억 취소됨". */
@Composable
private fun RememberedLine(memory: MemoryRef, kept: Boolean, onUndo: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(pu.memorySoft)
            .semantics(mergeDescendants = true) {}
            .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                append(if (kept) tr("Remembered · ", "기억했어요 · ") else tr("Memory undone · ", "기억 취소됨 · "))
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, textDecoration = if (kept) null else TextDecoration.LineThrough)) {
                    append(memory.text)
                }
            },
            Modifier.weight(1f).padding(vertical = 6.dp),
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            color = pu.memoryText,
        )
        if (kept) {
            Box(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = tr("Undo this memory", "이 기억 취소"), onClick = onUndo)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(tr("Undo", "취소"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pu.memory)
            }
        }
    }
}

/** "잊었어요 · <text> [되돌리기]": what forget removed this turn; 되돌리기 puts it back as it was. */
@Composable
private fun ForgottenLine(memory: ForgottenMemory, restored: Boolean, onRestore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(pu.memorySoft)
            .semantics(mergeDescendants = true) {}
            .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                append(if (restored) tr("Restored · ", "되돌렸어요 · ") else tr("Forgot · ", "잊었어요 · "))
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, textDecoration = if (restored) null else TextDecoration.LineThrough)) {
                    append(memory.text)
                }
            },
            Modifier.weight(1f).padding(vertical = 6.dp),
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            color = pu.memoryText,
        )
        if (!restored) {
            Box(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = tr("Restore this memory", "이 기억 되돌리기"), onClick = onRestore)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(tr("Restore", "되돌리기"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pu.memory)
            }
        }
    }
}

/** Board Search: "웹 n곳 확인" above the answer. */
@Composable
private fun SearchLine(count: Int) {
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PuIcons.Search, null, tint = pu.textSub, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(tr("Checked $count site${if (count == 1) "" else "s"}", "웹 ${count}곳 확인"), fontSize = 12.5.sp, color = pu.textSub)
    }
}

/** "출처 · A · B" under an answer; each source opens its page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesLine(sources: List<SourceLink>, onCardAction: (CardAction) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(tr("Sources", "출처"), fontSize = 12.5.sp, color = pu.textSub, modifier = Modifier.padding(vertical = 4.dp))
        for (s in sources) {
            Text(
                "· ${s.title}",
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClickLabel = tr("Open source", "출처 열기")) { onCardAction(CardAction.OpenUrl(s.url)) }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                fontSize = 12.5.sp,
                color = pu.textSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A one-line note stored with a reply by an earlier version (fast mode was turned off near the usage limit). */
@Composable
private fun NoticeLine(text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(pu.sunken)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PuIcons.Bolt, null, tint = pu.textSub, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.5.sp, color = pu.text2)
    }
}

/** "작업 n단계" — collapsed by default; expands to the step lines. */
@Composable
private fun StepsPanel(steps: List<StepLog>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(pu.surface)
            .border(1.dp, pu.line, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable(onClickLabel = if (open) tr("Collapse steps", "단계 접기") else tr("Expand steps", "단계 펼치기")) { open = !open }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PuIcons.Check, null, tint = pu.success, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (open) tr("${steps.size} step${if (steps.size == 1) "" else "s"}", "작업 ${steps.size}단계") else tr("${steps.size} step${if (steps.size == 1) "" else "s"} · ${steps.last().text}", "작업 ${steps.size}단계 · ${steps.last().text}"),
                modifier = Modifier.weight(1f),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = pu.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(if (open) PuIcons.ChevronUp else PuIcons.ChevronDown, null, tint = pu.textSub, modifier = Modifier.size(16.dp))
        }
        AnimatedVisibility(open, enter = expandVertically(), exit = shrinkVertically()) {
            StepLines(steps, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun StepLines(steps: List<StepLog>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(pu.lineSoft))
        Spacer(Modifier.height(6.dp))
        for (s in steps) {
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Icon(PuIcons.Step, null, tint = pu.textSub, modifier = Modifier.padding(top = 3.dp).size(14.dp))
                Spacer(Modifier.width(10.dp))
                Text(s.text, fontSize = 13.5.sp, lineHeight = 19.sp, color = pu.text2)
            }
        }
    }
}

/** The in-progress answer: the blue "작업 중" row (once there are steps), streamed text, then "생각 중…". */
@Composable
private fun LiveAnswer(streaming: String, steps: List<StepLog>, searches: Int) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (steps.isNotEmpty()) LiveStepsRow(steps)
        if (searches > 0) SearchLine(searches)
        if (streaming.isNotBlank()) MarkdownText(Markdown.withoutOpenLink(streaming), Modifier.fillMaxWidth(), color = pu.text2)
        ThinkingDots()
    }
}

@Composable
private fun LiveStepsRow(steps: List<StepLog>) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(pu.liveBg)
            .border(1.dp, pu.liveBorder, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable(onClickLabel = if (open) tr("Collapse steps", "단계 접기") else tr("Expand steps", "단계 펼치기")) { open = !open }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentDot()
            Spacer(Modifier.width(10.dp))
            Text(
                tr("Working · ${steps.last().text}", "작업 중 · ${steps.last().text}"),
                modifier = Modifier.weight(1f),
                fontSize = 13.5.sp,
                color = pu.liveText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(tr("${steps.size} step${if (steps.size == 1) "" else "s"}", "${steps.size}단계"), style = MonoStyle, fontSize = 12.5.sp, color = pu.liveText.copy(alpha = 0.8f))
        }
        AnimatedVisibility(open, enter = expandVertically(), exit = shrinkVertically()) {
            StepLines(steps, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
        }
    }
}

/** The blue agent dot with its soft halo. */
@Composable
fun AgentDot(color: Color = pu.agentDot) {
    val t = rememberInfiniteTransition(label = "agentDot")
    val halo by t.animateFloat(0.10f, 0.28f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "halo")
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(color.copy(alpha = halo)))
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun ThinkingDots(label: String = tr("Thinking", "생각 중")) {
    val t = rememberInfiniteTransition(label = "thinking")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.5.sp, color = pu.textSub)
        Spacer(Modifier.width(6.dp))
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse, StartOffset(i * 160)),
                label = "dot$i",
            )
            Box(Modifier.padding(horizontal = 2.dp).size(5.dp).alpha(a).clip(CircleShape).background(pu.textSub))
        }
    }
}
