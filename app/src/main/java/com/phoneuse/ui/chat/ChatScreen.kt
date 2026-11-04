package com.phoneuse.ui.chat

import com.phoneuse.i18n.tr
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import com.phoneuse.chat.MessageRole
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import com.phoneuse.app.ChatCard
import com.phoneuse.chat.Attachment
import com.phoneuse.files.AttachmentStore
import com.phoneuse.phone.NotificationStore
import com.phoneuse.proactive.ProactiveSystem
import com.phoneuse.today.TodayLoader
import com.phoneuse.today.TodaySummary
import com.phoneuse.today.TodayTile
import com.phoneuse.ui.home.runNudgeAction
import com.phoneuse.ui.theme.FastTag
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import com.phoneuse.ui.fold.FoldLayoutPolicy

/** Where the chat pane sits in the fold layouts (FoldLayoutPolicy). */
enum class ChatPaneLayout {
    NORMAL,

    /** Beside the unfolded result pane: the pane's card is not repeated in the conversation. */
    BESIDE_RESULT,

    /** A small window beside the app being operated (board UnfoldedSplit): progress header and the current request only. */
    PROGRESS,
}

/**
 * The chat screen: top bar (☰, model chip, 새 대화), the home screen or the conversation,
 * a question card when the agent asks, and the composer. Lays itself out above the keyboard.
 * The composer draft ([input]) is owned by the caller so it survives moving between layouts (fold/unfold).
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    model: String,
    fast: Boolean,
    showMenuButton: Boolean,
    onMenu: () -> Unit,
    onModelChip: () -> Unit,
    input: TextFieldValue,
    onInput: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    memoryIds: Set<Long>? = null,
    onForgetMemory: (Long) -> Unit = {},
    memoryTexts: Set<String>? = null,
    onRestoreMemory: (com.phoneuse.chat.ForgottenMemory) -> Unit = {},
    onOpenMemory: () -> Unit = {},
    layout: ChatPaneLayout = ChatPaneLayout.NORMAL,
    /** Board Digest, from the home notification tile. */
    onOpenDigest: () -> Unit = {},
    /** Scroll position and attach sheet; held by the caller so they survive flex mode, where no chat is drawn. */
    paneState: ChatPaneState = remember { ChatPaneState() },
) {
    val c = pu
    val current by vm.current.collectAsState()
    val messages by vm.messages.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val live by vm.live.collectAsState()
    val running by vm.running.collectAsState()
    val runningHere by vm.runningHere.collectAsState()
    val active by vm.activeConversation.collectAsState()
    val streaming by vm.streamingText.collectAsState()
    val liveSteps by vm.liveSteps.collectAsState()
    val liveSearches by vm.liveSearches.collectAsState()
    // Only the card of the run shown here; another chat's question shows as the banner below.
    val card by vm.cardHere.collectAsState()
    val anyCard by vm.card.collectAsState()
    val pending by vm.pending.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AttachmentStore(context) }
    var attachOpen by paneState::attachOpen
    val attach = rememberAttachActions(
        store,
        room = { Attachment.MAX_PER_MESSAGE - vm.pending.value.size },
        onAdded = { added ->
            attachOpen = false
            val extra = vm.attach(added)
            if (extra.isNotEmpty()) {
                scope.launch { store.discard(extra) }
                Toast.makeText(context, tr("Up to ${Attachment.MAX_PER_MESSAGE} at a time, so ${extra.size} left out", "한 번에 ${Attachment.MAX_PER_MESSAGE}개까지라 ${extra.size}개는 빠졌어요"), Toast.LENGTH_SHORT).show()
            }
        },
    )
    val focus = remember { FocusRequester() }
    var focusRequest by remember { mutableStateOf(0) }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) runCatching { focus.requestFocus() }
    }

    val home = current == null && messages.isEmpty()
    val listState = paneState.listState(current)
    // Newest content is item 0 of the reversed list. The list keeps its position by item key, so a new
    // message would land below the screen: follow new messages and the start/end of a live answer.
    // Only for a change: coming back from flex mode keeps the position the user scrolled to.
    val followKey = Triple(current, messages.lastOrNull()?.id, live)
    val reveal = paneState.reveal
    LaunchedEffect(followKey, reveal) {
        if (paneState.follow.shouldFollow(followKey) && !home) listState.animateScrollToItem(0)
        // Opened from a result notification: once that conversation's answer is here, show it from its start.
        val newest = messages.lastOrNull()
        if (reveal != null && reveal == current && !live && newest?.conversationId == reveal && newest.role == MessageRole.ASSISTANT) {
            paneState.reveal = null
            withFrameNanos { }
            revealNewestStart(listState)
        }
    }

    fun submit() {
        val t = input.text
        if (t.isBlank() && pending.isEmpty()) return
        when {
            vm.send(t) -> {
                onInput(TextFieldValue(""))
                attachOpen = false
            }
            pending.isNotEmpty() && (running || card != null) ->
                Toast.makeText(context, tr("You can send photos and files when the current task finishes", "사진과 파일은 지금 작업이 끝나면 보낼 수 있어요"), Toast.LENGTH_SHORT).show()
            running -> Toast.makeText(context, tr("You can send when the task in the other chat finishes", "다른 대화의 작업이 끝나면 보낼 수 있어요"), Toast.LENGTH_SHORT).show()
        }
    }

    val progress = layout == ChatPaneLayout.PROGRESS
    Column(modifier.fillMaxSize().background(c.bg)) {
        if (progress) {
            ProgressHeader(onStop = { vm.stop() })
        } else {
            TopBar(model, fast, showMenuButton, onMenu, onModelChip, onNewChat = { vm.newChat() })
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (home) {
                val notifications = remember { NotificationStore.get(context) }
                val notificationVersion by notifications.changes.collectAsState()
                // Board Main "오늘 챙길 것": read again whenever a notification arrives or the home is shown.
                // A burst of notifications reads once: each arrival restarts this, and only the first read is
                // immediate (the loader queries SMS, the calendar and the notification store).
                val tilesLoaded = remember { booleanArrayOf(false) }
                val tiles by produceState(emptyList<TodayTile>(), notificationVersion) {
                    if (tilesLoaded[0]) kotlinx.coroutines.delay(TILES_DEBOUNCE_MS)
                    tilesLoaded[0] = true
                    value = try {
                        val now = ZonedDateTime.now()
                        TodaySummary.build(now, TodayLoader.load(context, now))
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                val proactive = remember { ProactiveSystem.get(context) }
                val nudgeLog by proactive.log.entries.collectAsState()
                HomeContent(
                    latest = conversations.firstOrNull(),
                    onQuickAction = { qa ->
                        onInput(TextFieldValue(qa.prompt, TextRange(qa.prompt.length)))
                        focusRequest++
                    },
                    onContinue = { vm.select(it) },
                    modifier = Modifier.fillMaxSize(),
                    tiles = tiles,
                    tileActions = TodayTileActions(
                        onParcel = {
                            onInput(TextFieldValue(TodaySummary.PARCEL_PROMPT, TextRange(TodaySummary.PARCEL_PROMPT.length)))
                            focusRequest++
                        },
                        onDigest = onOpenDigest,
                        // The event by its time only (its title may come from someone else's invitation).
                        onEvent = { item ->
                            if (!vm.startCardPrompt(tr("Show my ${item.time} event today as a card", "오늘 ${item.time} 일정 카드로 보여줘"))) {
                                Toast.makeText(context, tr("You can do that when the current task finishes", "지금 작업이 끝나면 할 수 있어요"), Toast.LENGTH_SHORT).show()
                            }
                        },
                    ),
                    nudgeLog = nudgeLog,
                    onNudgeAction = { sent, action -> runNudgeAction(context, sent.dedupeKey, action) { vm.startCardPrompt(it) } },
                    onNudgeClose = { proactive.ignored(it.dedupeKey) },
                )
            } else {
                MessageList(
                    if (progress) FoldLayoutPolicy.latestExchange(messages) else messages,
                    live, streaming, liveSteps, listState, Modifier.fillMaxSize(),
                    liveSearches = liveSearches,
                    memoryIds = memoryIds,
                    onForgetMemory = onForgetMemory,
                    memoryTexts = memoryTexts,
                    onRestoreMemory = onRestoreMemory,
                    onOpenMemory = onOpenMemory,
                    paneCardKey = if (layout == ChatPaneLayout.BESIDE_RESULT) FoldLayoutPolicy.resultCard(messages)?.key else null,
                    // A card button that needs the agent sends its text as the next message here.
                    onCardPrompt = { text ->
                        if (!vm.sendCardPrompt(text)) Toast.makeText(context, tr("You can do that when the current task finishes", "지금 작업이 끝나면 할 수 있어요"), Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(
                visible = card != null,
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut() + slideOutVertically { it / 3 },
            ) {
                // Keep showing the last card while it animates out.
                var shown by remember { mutableStateOf<ChatCard?>(null) }
                card?.let { shown = it }
                shown?.let { ChatCardView(it, onAnswer = { id, text -> vm.answer(id, text) }) }
            }
            if (running && !runningHere) {
                OtherRunBanner(asking = anyCard != null && card == null, onShow = { active?.let { vm.select(it) } })
            }
            if (pending.isNotEmpty() || attach.busy()) {
                PendingAttachments(
                    pending,
                    busy = attach.busy(),
                    onRemove = { a ->
                        vm.detach(a)
                        scope.launch { store.discard(listOf(a)) }
                    },
                )
            }
            AnimatedVisibility(
                visible = attachOpen,
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut() + slideOutVertically { it / 3 },
            ) {
                AttachSheet(attach)
            }
            Composer(
                value = input,
                onValueChange = onInput,
                placeholder = when {
                    card is ChatCard.Confirm -> tr("Say here if you want it different", "다르게 하려면 여기에 말해요")
                    card is ChatCard.Ask -> tr("You can type another answer", "다른 답을 써도 돼요")
                    progress && runningHere -> tr("Instruct differently", "다르게 지시")
                    runningHere -> tr("You can instruct while I work", "작업 중에도 지시할 수 있어요")
                    attachOpen || pending.isNotEmpty() -> tr("Ask with the photo", "사진과 함께 물어보기")
                    home -> tr("Ask me anything", "무엇이든 말해 주세요")
                    else -> tr("Message or task", "메시지 또는 할 일")
                },
                // Stop and steering belong to the run shown here; another conversation's run has the banner.
                running = runningHere,
                showMic = card !is ChatCard.Confirm,
                onSend = { submit() },
                onStop = { vm.stop() },
                onVoiceResult = { spoken ->
                    val base = input.text.trimEnd()
                    val text = if (base.isEmpty()) spoken else "$base $spoken"
                    onInput(TextFieldValue(text, TextRange(text.length)))
                },
                focusRequester = focus,
                attachOpen = attachOpen,
                onAttach = { attachOpen = !attachOpen },
                hasAttachments = pending.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun TopBar(
    model: String,
    fast: Boolean,
    showMenuButton: Boolean,
    onMenu: () -> Unit,
    onModelChip: () -> Unit,
    onNewChat: () -> Unit,
) {
    val c = pu
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showMenuButton) IconBtn(PuIcons.Menu, tr("Conversations", "대화 목록"), onMenu) else Spacer(Modifier.width(44.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .clickable(role = Role.Button, onClick = onModelChip)
                    .semantics { contentDescription = tr("Model: ${modelDisplayName(model)}${if (fast) ", fast mode" else ""}. Change", "모델: ${modelDisplayName(model)}${if (fast) ", 빠른 모드" else ""}. 바꾸기") }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    modelChipName(model),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (fast) FastTag()
                Icon(PuIcons.ChevronDown, null, tint = c.textSub, modifier = Modifier.size(14.dp))
            }
        }
        IconBtn(PuIcons.Plus, tr("New chat", "새 대화"), onNewChat)
    }
}

/** Board UnfoldedSplit: "● 다른 앱 조작 중 [■ 중단]" in place of the top bar. */
@Composable
private fun ProgressHeader(onStop: () -> Unit) {
    val c = pu
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 18.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentDot()
        Spacer(Modifier.width(10.dp))
        Text(tr("Operating another app", "다른 앱 조작 중"), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1)
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.dangerSoft)
                .clickable(role = Role.Button, onClickLabel = tr("Stop task", "작업 중단"), onClick = onStop)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c.danger))
            Text(tr("Stop", "중단"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.danger)
        }
    }
}

/** Shown when the agent is working in a different conversation than the one on screen. */
@Composable
private fun OtherRunBanner(asking: Boolean, onShow: () -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.liveBg)
            .clickable(role = Role.Button, onClick = onShow)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentDot()
        Spacer(Modifier.width(10.dp))
        Text(
            if (asking) tr("A question is waiting in another chat", "다른 대화에서 답을 기다리고 있어요") else tr("Working in another chat", "다른 대화에서 작업 중이에요"),
            Modifier.weight(1f),
            fontSize = 13.5.sp,
            color = c.liveText,
        )
        Text(tr("View", "보기"), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = c.liveText)
    }
}

/**
 * Scrolls the reversed message list so the newest item (index 0) shows from its top: an answer taller than the
 * list would otherwise show only its end. A short answer is left where it is.
 */
private suspend fun revealNewestStart(state: LazyListState) {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == 0 } ?: return
    val viewport = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
    if (viewport <= 0 || item.size <= viewport) return
    state.scrollToItem(0, item.size - viewport)
}

/** Notifications arriving within this of each other re-read the home tiles once. */
private const val TILES_DEBOUNCE_MS = 2_000L
