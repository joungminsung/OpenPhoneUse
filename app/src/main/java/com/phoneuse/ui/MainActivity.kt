package com.phoneuse.ui

import com.phoneuse.i18n.tr
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.phoneuse.app.AppGraph
import com.phoneuse.app.ModelProviders
import com.phoneuse.app.ReasoningEffort
import com.phoneuse.app.SettingsStore
import com.phoneuse.app.ThemeMode
import com.phoneuse.ui.chat.ChatPaneLayout
import com.phoneuse.ui.chat.ChatPaneState
import com.phoneuse.ui.cards.CardStateStore
import com.phoneuse.ui.cards.CardUiState
import com.phoneuse.ui.cards.LocalCardStates
import androidx.compose.runtime.CompositionLocalProvider
import com.phoneuse.ui.chat.ChatScreen
import com.phoneuse.ui.chat.ChatViewModel
import com.phoneuse.ui.chat.DrawerContent
import com.phoneuse.ui.chat.ModelChoices
import com.phoneuse.ui.chat.ModelSheet
import com.phoneuse.ui.chat.asChatRunner
import com.phoneuse.ui.settings.AccountInfo
import com.phoneuse.ui.settings.SettingsScreen
import com.phoneuse.ui.connectors.ConnectorsScreen
import com.phoneuse.ui.settings.MemorySettings
import com.phoneuse.ui.memory.MemoryScreen
import com.phoneuse.memory.Confidence
import com.phoneuse.memory.Memory
import com.phoneuse.memory.MemoryKind
import com.phoneuse.notify.AppScreen
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.phoneuse.agent.Personalization
import com.phoneuse.ui.digest.DigestScreen
import com.phoneuse.ui.usage.UsageScreen
import com.phoneuse.ui.cards.rememberCardActionRunner
import com.phoneuse.ui.fold.FlexScreen
import com.phoneuse.ui.fold.FoldLayoutPolicy
import com.phoneuse.ui.fold.FoldMode
import com.phoneuse.ui.fold.ResultPane
import com.phoneuse.ui.fold.rememberMultiWindow
import com.phoneuse.ui.fold.rememberPosture
import com.phoneuse.ui.routines.RoutinesScreen
import com.phoneuse.proactive.ProactiveSystem
import com.phoneuse.ui.home.runNudgeAction
import com.phoneuse.ui.settings.NudgeSettingsScreen
import com.phoneuse.ui.theme.PhoneUseTheme
import com.phoneuse.ui.theme.isDark
import com.phoneuse.ui.theme.pu
import com.phoneuse.widget.WidgetLinks
import com.phoneuse.widget.WidgetPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Screen { CHAT, SETTINGS, USAGE, MEMORY, CONNECTORS, DIGEST, ROUTINES, NUDGES }

/** Width from which the conversation list stays on screen (unfolded Fold, tablets). */
private val WIDE = FoldLayoutPolicy.WIDE_DP.dp

/** The conversation list as a rail (two-pane, and very wide screens beside the result pane). */
private val LIST_RAIL = 300.dp

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so screens re-read state changed in system settings. */
    private var resumeTick by mutableIntStateOf(0)

    /** A conversation to show, from [open]'s intent; cleared once the chat has switched to it. */
    private var openRequest by mutableStateOf<OpenRequest?>(null)

    /** A screen a notification opens (usage, today, …); cleared once shown. */
    private var screenRequest by mutableStateOf<AppScreen?>(null)

    /** A nudge notification tapped: its id and button index (ProactiveSystem.OPEN_HOME for the body). */
    private var nudgeRequest by mutableStateOf<Pair<String, Int>?>(null)

    /** A home-screen widget's prompt (ask bar, quick action), verified; cleared once the chat started it. */
    private var promptRequest by mutableStateOf<WidgetPrompt?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openRequest = conversationOf(intent)
        if (savedInstanceState == null) nudgeRequest = nudgeOf(intent)
        if (savedInstanceState == null) screenRequest = screenOf(intent)
        if (savedInstanceState == null) promptRequest = WidgetLinks.promptOf(this, intent)
        // The theme is read off the main thread (AppGraph); hold the first frame until it is known, so the
        // screen never flashes the other theme. The window background (values/values-night) shows meanwhile.
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (AppGraph.themeMode.value == null || AppGraph.fontSize.value == null) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
        setContent {
            val loaded by AppGraph.themeMode.collectAsState()
            val theme = loaded ?: return@setContent
            val dark = isDark(theme)
            val fontSizeLoaded by AppGraph.fontSize.collectAsState()
            val fontSize = fontSizeLoaded ?: com.phoneuse.app.FontSize.DEFAULT
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            PhoneUseTheme(dark, fontSize.scale) {
                // Card state by card, above every layout: a card keeps its selection when it moves between
                // the chat, the unfolded result pane and flex mode.
                AppRoot(theme, resumeTick, openRequest, nudgeRequest, { nudgeRequest = null }, screenRequest, { screenRequest = null }, promptRequest, { promptRequest = null }) { openRequest = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        conversationOf(intent)?.let { openRequest = it }
        nudgeOf(intent)?.let { nudgeRequest = it }
        screenOf(intent)?.let { screenRequest = it }
        WidgetLinks.promptOf(this, intent)?.let { promptRequest = it }
    }

    override fun onStart() {
        super.onStart()
        AppGraph.uiVisible.value = true
    }

    override fun onResume() {
        super.onResume()
        lastResumedAt = SystemClock.elapsedRealtime()
        resumeTick++
    }

    override fun onStop() {
        AppGraph.uiVisible.value = false
        super.onStop()
    }

    // MainActivity is exported (the launcher); extras count only with our own signature (LaunchToken).
    private fun conversationOf(intent: Intent?): OpenRequest? {
        val id = intent?.getLongExtra(EXTRA_CONVERSATION, -1L)?.takeIf { it >= 0 } ?: return null
        if (!LaunchToken.of(this).verify(LaunchToken.conversation(id), intent.getStringExtra(LaunchToken.EXTRA))) return null
        return OpenRequest(id, intent.getBooleanExtra(EXTRA_REVEAL, false))
    }

    private fun screenOf(intent: Intent?): AppScreen? {
        val name = intent?.getStringExtra(EXTRA_SCREEN) ?: return null
        if (!LaunchToken.of(this).verify(LaunchToken.screen(name), intent.getStringExtra(LaunchToken.EXTRA))) return null
        return AppScreen.entries.firstOrNull { it.name == name }
    }

    private fun nudgeOf(intent: Intent?): Pair<String, Int>? {
        val id = intent?.getStringExtra(ProactiveSystem.EXTRA_NUDGE) ?: return null
        val action = intent.getIntExtra(ProactiveSystem.EXTRA_NUDGE_ACTION, ProactiveSystem.OPEN_HOME)
        if (!LaunchToken.of(this).verify(LaunchToken.nudge(id, action), intent.getStringExtra(LaunchToken.EXTRA))) return null
        return id to action
    }

    companion object {
        const val EXTRA_CONVERSATION = "com.phoneuse.extra.CONVERSATION"
        private const val EXTRA_REVEAL = "com.phoneuse.extra.REVEAL"
        private const val EXTRA_SCREEN = "com.phoneuse.extra.SCREEN"

        /** elapsedRealtime of the last onResume; the return-to-chat fallback checks it. */
        @Volatile
        var lastResumedAt: Long = 0L
            private set

        /**
         * Brings the chat to the front showing [conversationId] (after a run that operated another app).
         * Callable from the accessibility service or the application context.
         */
        fun open(context: Context, conversationId: Long) {
            context.startActivity(openIntent(context, conversationId))
        }

        /**
         * The intent [open] starts; also used by the result notification's tap. Its data URI is the
         * conversation's own, so two notifications' PendingIntents never merge; the token signs the id.
         * [reveal]: scroll to the start of the newest answer (a result notification), not to its end.
         */
        fun openIntent(context: Context, conversationId: Long, reveal: Boolean = false): Intent =
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse(LaunchTargets.uri(LaunchTargets.Kind.CONVERSATION, conversationId.toString())))
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                .putExtra(EXTRA_CONVERSATION, conversationId)
                .putExtra(EXTRA_REVEAL, reveal)
                .putExtra(LaunchToken.EXTRA, LaunchToken.of(context).sign(LaunchToken.conversation(conversationId)))

        /** Opens the app on [screen] (a notification's tap); signed like a conversation, since the activity is exported. */
        fun screenIntent(context: Context, screen: AppScreen): Intent =
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse(LaunchTargets.uri(LaunchTargets.Kind.SCREEN, screen.name.lowercase())))
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                .putExtra(EXTRA_SCREEN, screen.name)
                .putExtra(LaunchToken.EXTRA, LaunchToken.of(context).sign(LaunchToken.screen(screen.name)))
    }
}

/** A conversation to open; [reveal] shows the start of its newest answer. */
private data class OpenRequest(val id: Long, val reveal: Boolean)

internal suspend fun loadAccount(settings: SettingsStore): AccountInfo {
    val plan = com.phoneuse.auth.SettingsPlanStore(settings).load()
    val mode = settings.authMode.first()
    return AccountInfo(
        mode = mode,
        chatGptSignedIn = plan != null,
        email = plan?.email,
        hasApiKey = settings.providerKey(mode).first().isNotBlank(),
        planEnabled = plan?.canUsePlan == true,
    )
}

@Composable
private fun AppRoot(
    theme: ThemeMode,
    resumeTick: Int,
    openRequest: OpenRequest?,
    nudgeRequest: Pair<String, Int>?,
    onNudgeHandled: () -> Unit,
    screenRequest: AppScreen?,
    onScreenHandled: () -> Unit,
    promptRequest: WidgetPrompt?,
    onPromptHandled: () -> Unit,
    onOpenHandled: () -> Unit,
) {
    val c = pu
    val settings = AppGraph.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Memory actions from the UI: a failure (disk full, locked DB) shows a toast instead of crashing the app.
    fun memoryAction(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.phoneuse.memory.SecretInMemoryException) {
                // A password, card or ID number: say why it was not kept.
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, tr("Couldn't change the memory", "기억을 바꾸지 못했어요"), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val vm = remember {
        // What the user types to start a conversation is the only source of the repeated-ask nudge.
        ChatViewModel(
            AppGraph.chats, AppGraph.runner.asChatRunner(), scope,
            onTypedAsk = { text, id -> ProactiveSystem.get(context).typed(text, id) },
            onDeleted = { ProactiveSystem.get(context).conversationsGone(it) },
        )
    }

    // Keep the open conversation across activity recreation.
    // Also a new chat being followed (sent, not yet created), so its run stays stoppable from here.
    var savedCurrent by rememberSaveable { mutableStateOf<Long?>(null) }
    var savedFollowing by rememberSaveable { mutableStateOf(false) }
    var savedStale by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(vm) {
        val restore = savedCurrent
        if (restore != null) vm.select(restore) else if (savedFollowing) vm.resumeFollowing(savedStale)
        launch {
            vm.followingNew.collect {
                savedFollowing = it
                savedStale = vm.followStale
            }
        }
        vm.current.collect {
            savedCurrent = it
            AppGraph.shownConversation = it
        }
    }

    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    // Where the memory screen was opened from (the chat's memory chips or settings); back returns there.
    var memoryFrom by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    // Where the digest was opened from (settings or the home tile); back returns there.
    var digestFrom by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    fun openDigest(from: Screen) {
        digestFrom = from
        screen = Screen.DIGEST
    }
    fun openMemory(from: Screen) {
        memoryFrom = from
        screen = Screen.MEMORY
    }
    // Coming back after a run that operated another app: show that conversation.
    // The chat pane's scroll state; a result notification asks it to show the answer's start.
    val chatPaneState = remember(vm) { ChatPaneState() }
    LaunchedEffect(openRequest) {
        val request = openRequest ?: return@LaunchedEffect
        vm.select(request.id)
        if (request.reveal) chatPaneState.reveal = request.id
        screen = Screen.CHAT
        onOpenHandled()
    }
    // A notification's own screen: usage alerts open Usage, the daily summary Today (the home screen), and so on.
    LaunchedEffect(screenRequest) {
        val target = screenRequest ?: return@LaunchedEffect
        onScreenHandled()
        when (target) {
            AppScreen.HOME -> {
                vm.newChat()
                screen = Screen.CHAT
            }
            AppScreen.DIGEST -> openDigest(Screen.CHAT)
            AppScreen.ROUTINES -> screen = Screen.ROUTINES
        }
    }
    // A nudge notification: its body shows the home screen (where its card is), a button does what the card's would.
    LaunchedEffect(nudgeRequest) {
        val (id, index) = nudgeRequest ?: return@LaunchedEffect
        onNudgeHandled()
        screen = Screen.CHAT
        val action = ProactiveSystem.get(context).log.get(id)?.nudge?.actions?.getOrNull(index)
        if (action == null) {
            // The body was tapped: the user looked, which counts as opened (not toward the back-off).
            ProactiveSystem.get(context).opened(id)
            vm.newChat()
        } else {
            runNudgeAction(context, id, action) { vm.startCardPrompt(it) }
        }
    }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var accountVersion by remember { mutableIntStateOf(0) }
    // Settings' 커넥터 line: what is connected.
    val connectorServers by AppGraph.connectors.store.servers.collectAsState()
    val connectorGoogle by AppGraph.connectors.store.google.collectAsState()
    LaunchedEffect(Unit) {
        try {
            AppGraph.connectors.store.load()
        } catch (e: Exception) {
            android.util.Log.w("PhoneUse", "loading connectors failed", e)
        }
    }
    val connectorsSummary = (listOfNotNull("Google".takeIf { connectorGoogle?.connected == true }) + connectorServers.filter { it.enabled }.map { it.name })
        .takeIf { it.isNotEmpty() }?.joinToString(" · ", postfix = tr(" connected", " 연결됨")).orEmpty()

    val model by settings.model.collectAsState(SettingsStore.DEFAULT_MODEL)
    val modelAuto by settings.modelAuto.collectAsState(true)
    val fast by settings.fastMode.collectAsState(SettingsStore.DEFAULT_FAST_MODE)
    val effort by settings.reasoningEffort.collectAsState(ReasoningEffort.MEDIUM)
    val authMode by settings.authMode.collectAsState(null)
    // The chosen provider's presets and, with its key, the models its own endpoint lists.
    val modelChoices by produceState(ModelChoices(), authMode, accountVersion) {
        val mode = authMode ?: return@produceState
        val base = ModelChoices(
            presets = ModelProviders.presets(mode), defaultModel = ModelProviders.defaultModel(mode),
            fastSupported = ModelProviders.supportsFast(mode),
        )
        value = base
        // The plan sign-in lists the account's own models.
        val key = if (mode == com.phoneuse.app.AuthMode.CHATGPT) {
            val plan = com.phoneuse.auth.SettingsPlanStore(settings).load() ?: return@produceState
            value = value.copy(planUsage = plan.canUsePlan)
            try {
                com.phoneuse.auth.PlanAuth(com.phoneuse.auth.SettingsPlanStore(settings), com.phoneuse.auth.PlanTokenClient(AppGraph.http))
                    .headers().getValue("Authorization").removePrefix("Bearer ")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                plan.accessToken
            }
        } else {
            settings.providerKey(mode).first()
        }
        if (key.isBlank()) return@produceState
        val shown = value
        value = try {
            val listed = ModelProviders.listModels(mode, key, AppGraph.http)
            // A newer model of the default's family becomes the default, unless one was picked.
            settings.rememberListedModels(mode, listed)
            shown.copy(listed = listed, defaultModel = ModelProviders.upgrades(mode, listed).first ?: shown.defaultModel)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            shown.copy(listError = tr("Couldn't load the model list: ", "모델 목록을 불러오지 못했어요: ") + (e.message ?: e.javaClass.simpleName).take(120))
        }
    }
    val webSearch by settings.webSearch.collectAsState(true)
    val memoryOn by settings.memoryEnabled.collectAsState(true)
    val autoRemember by settings.autoRemember.collectAsState(true)
    val personalization by settings.personalization.collectAsState(Personalization())
    // Null until the first read, so "기억했어요" lines do not flash as undone.
    val memories by remember { AppGraph.memories.observe() }.collectAsState(null)
    val memoryIds = remember(memories) { memories?.mapTo(HashSet()) { it.id } }
    val memoryTexts = remember(memories) { memories?.mapTo(HashSet()) { it.text } }
    // Settings "펼친 화면에서: 대화만" keeps the result pane (and flex mode's card) away.
    val unfoldedLayout by settings.unfoldedLayout.collectAsState(com.phoneuse.app.UnfoldedLayout.SIDE_BY_SIDE)
    val account by produceState(AccountInfo(), accountVersion, resumeTick, authMode) {
        // Off the main thread: reading the sign-in decrypts with the Keystore.
        value = runCatching { withContext(Dispatchers.Default) { loadAccount(settings) } }.getOrDefault(value)
    }
    val conversations by vm.conversations.collectAsState()
    val current by vm.current.collectAsState()
    // Inputs of the fold layouts (FoldLayoutPolicy).
    val messages by vm.messages.collectAsState()
    val runningHere by vm.runningHere.collectAsState()
    val asking by vm.cardHere.collectAsState()
    val liveSteps by vm.liveSteps.collectAsState()
    val resultCard = remember(messages) { FoldLayoutPolicy.resultCard(messages) }
    val streaming by vm.streamingText.collectAsState()
    // Card states belong to the conversation on screen (a new store per conversation, remembered, so a discarded
    // composition changes nothing); every layout below shares it, so a card keeps its state across them.
    val cardStates = remember(current) { CardStateStore { CardUiState() } }
    CompositionLocalProvider(LocalCardStates provides cardStates) {
    // Scroll position and attach sheet of the chat (chatPaneState above), kept while flex mode shows no chat.
    val posture = rememberPosture()
    val multiWindow = rememberMultiWindow()
    val onResultAction = rememberCardActionRunner { text ->
        if (!vm.sendCardPrompt(text)) Toast.makeText(context, tr("You can do that when the current task finishes", "지금 작업이 끝나면 할 수 있어요"), Toast.LENGTH_SHORT).show()
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !settings.notificationsAsked.first()) {
            settings.markNotificationsAsked()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.CHAT }
    BackHandler(enabled = screen == Screen.USAGE) { screen = Screen.SETTINGS }
    BackHandler(enabled = screen == Screen.MEMORY) { screen = memoryFrom }
    BackHandler(enabled = screen == Screen.CONNECTORS) { screen = Screen.SETTINGS }
    BackHandler(enabled = screen == Screen.DIGEST) { screen = digestFrom }
    BackHandler(enabled = screen == Screen.ROUTINES) { screen = Screen.CHAT }
    BackHandler(enabled = screen == Screen.NUDGES) { screen = Screen.SETTINGS }
    val routineList by remember { com.phoneuse.routine.RoutineSystem.get(context).store.routines }.collectAsState()

    // The composer draft lives here, and the chat pane is movable content, so folding or unfolding (which
    // moves the pane between the drawer layout and the two-pane layout) keeps the draft, the scroll
    // position and a pending voice-input result.
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    // A home-screen widget's prompt: started like text from the composer (typed) or a card button (chips, tiles),
    // so every confirmation still applies. While a run is going it waits in the composer instead.
    LaunchedEffect(promptRequest) {
        val p = promptRequest ?: return@LaunchedEffect
        onPromptHandled()
        screen = Screen.CHAT
        val started = when {
            !p.typed -> vm.startCardPrompt(p.text)
            p.conversationId != null -> {
                vm.select(p.conversationId)
                vm.send(p.text)
            }
            else -> vm.startTyped(p.text)
        }
        if (!started) {
            if (p.typed) draft = TextFieldValue(p.text)
            Toast.makeText(context, tr("You can do that when the current task finishes", "지금 작업이 끝나면 할 수 있어요"), Toast.LENGTH_SHORT).show()
        }
    }
    val chatPane = remember(vm) {
        movableContentOf { showMenu: Boolean, modifier: Modifier, layout: ChatPaneLayout ->
            ChatScreen(
                vm = vm,
                model = model,
                fast = fast && authMode?.let { ModelProviders.supportsFast(it) } == true,
                showMenuButton = showMenu,
                onMenu = { scope.launch { drawerState.open() } },
                onModelChip = { sheetOpen = true },
                input = draft,
                onInput = { draft = it },
                modifier = modifier,
                memoryIds = memoryIds,
                onForgetMemory = { memoryId -> memoryAction { AppGraph.memories.delete(memoryId) } },
                memoryTexts = memoryTexts,
                onRestoreMemory = { forgotten ->
                    memoryAction {
                        AppGraph.memories.restore(
                            Memory(
                                id = forgotten.id,
                                text = forgotten.text,
                                kind = runCatching { MemoryKind.valueOf(forgotten.kind) }.getOrDefault(MemoryKind.ME),
                                confidence = runCatching { Confidence.valueOf(forgotten.confidence) }.getOrDefault(Confidence.SURE),
                                conversationId = forgotten.conversationId,
                                origin = forgotten.origin,
                                date = forgotten.date,
                                due = forgotten.due,
                                createdAt = 0L,
                                updatedAt = 0L,
                            ),
                        )
                    }
                },
                onOpenMemory = { openMemory(Screen.CHAT) },
                layout = layout,
                paneState = chatPaneState,
                onOpenDigest = { openDigest(Screen.CHAT) },
            )
        }
    }

    val topInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val wide = maxWidth >= WIDE
        val chatColumn = FoldLayoutPolicy.chatColumnDp(maxWidth.value.toInt()).dp
        val fold = FoldLayoutPolicy.decide(
            widthDp = maxWidth.value.toInt(),
            posture = posture,
            multiWindow = multiWindow,
            hasCard = resultCard != null && unfoldedLayout == com.phoneuse.app.UnfoldedLayout.SIDE_BY_SIDE,
            runningHere = runningHere,
            asking = asking != null,
        )
        // The list is in the drawer on the phone layout, the progress layout and the result pane without a rail.
        val hasDrawer = when (fold.mode) {
            FoldMode.PHONE, FoldMode.SPLIT_PROGRESS -> true
            FoldMode.RESULT_PANE -> !fold.listRail
            FoldMode.TWO_PANE, FoldMode.FLEX -> false
        }
        // Back closes the drawer only where the drawer exists; unfolding with it open just closes it.
        BackHandler(enabled = screen == Screen.CHAT && hasDrawer && drawerState.isOpen) { scope.launch { drawerState.close() } }
        LaunchedEffect(hasDrawer) {
            if (!hasDrawer && drawerState.isOpen) drawerState.snapTo(DrawerValue.Closed)
        }

        @Composable
        fun drawer(modifier: Modifier) = DrawerContent(
            conversations = conversations,
            current = current,
            accountLine = account.summary,
            onNewChat = {
                vm.newChat()
                scope.launch { drawerState.close() }
            },
            onSelect = {
                vm.select(it)
                scope.launch { drawerState.close() }
            },
            onDelete = { vm.delete(it) },
            onSettings = {
                screen = Screen.SETTINGS
                scope.launch { drawerState.close() }
            },
            modifier = modifier,
            onRoutines = {
                screen = Screen.ROUTINES
                scope.launch { drawerState.close() }
            },
            routinesOn = routineList.count { it.enabled },
        )

        @Composable
        fun settingsPane(modifier: Modifier) = SettingsScreen(
            settings = settings,
            http = AppGraph.http,
            account = account,
            model = model,
            fast = fast,
            effort = effort,
            theme = theme,
            resumeTick = resumeTick,
            webSearch = webSearch,
            memory = MemorySettings(memoryOn, autoRemember, memories?.size ?: 0, personalization),
            onAccountChanged = { accountVersion++ },
            onOpenModelSheet = { sheetOpen = true },
            onOpenUsage = { screen = Screen.USAGE },
            onOpenMemory = { openMemory(Screen.SETTINGS) },
            onDeleteAll = {
                // The chat leaves the deleted conversation and any run stops; FullWipe deletes everything else
                // (and the conversations again, in case the screen's own delete raced a new one).
                // The run is stopped and waited for first, and the wipe runs in the process scope (AppGraph.wipeAll).
                vm.newChat()
                com.phoneuse.app.FullWipe.resultText(AppGraph.wipeAll(context))
            },
            onBack = { screen = Screen.CHAT },
            modifier = modifier,
            onOpenConnectors = { screen = Screen.CONNECTORS },
            connectorsSummary = connectorsSummary,
            onOpenDigest = { openDigest(Screen.SETTINGS) },
            onOpenNudges = { screen = Screen.NUDGES },
            onExport = { com.phoneuse.chat.ChatExport.saveToDownloads(context, AppGraph.chats) },
            countExpired = { days -> AppGraph.expiredConversations(days) },
        )

        when (screen) {
            Screen.ROUTINES -> Box(Modifier.fillMaxSize().windowInsetsPadding(topInsets), contentAlignment = Alignment.TopCenter) {
                RoutinesScreen(
                    onBack = { screen = Screen.CHAT },
                    // The sentence goes to a new chat, where make_routine shows the preview card to save.
                    onMakeFromSentence = { text ->
                        val started = vm.startTyped(tr("Make a routine: $text", "루틴 만들어줘: $text"))
                        if (started) {
                            screen = Screen.CHAT
                        } else {
                            Toast.makeText(context, tr("You can do that when the current task finishes", "지금 작업이 끝나면 할 수 있어요"), Toast.LENGTH_SHORT).show()
                        }
                        started
                    },
                    onOpenConversation = { id ->
                        vm.select(id)
                        screen = Screen.CHAT
                    },
                    resumeTick = resumeTick,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
            Screen.NUDGES -> Box(Modifier.fillMaxSize().windowInsetsPadding(topInsets), contentAlignment = Alignment.TopCenter) {
                NudgeSettingsScreen(onBack = { screen = Screen.SETTINGS }, modifier = Modifier.widthIn(max = 640.dp))
            }
            Screen.CONNECTORS -> Box(Modifier.fillMaxSize().windowInsetsPadding(topInsets), contentAlignment = Alignment.TopCenter) {
                ConnectorsScreen(
                    connectors = AppGraph.connectors,
                    resumeTick = resumeTick,
                    onBack = { screen = Screen.SETTINGS },
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
            Screen.SETTINGS -> Box(Modifier.fillMaxSize().windowInsetsPadding(topInsets), contentAlignment = Alignment.TopCenter) {
                settingsPane(Modifier.widthIn(max = 640.dp))
            }
            Screen.DIGEST -> Box(Modifier.fillMaxSize().windowInsetsPadding(topInsets), contentAlignment = Alignment.TopCenter) {
                DigestScreen(
                    onBack = { screen = digestFrom },
                    // 답장 쓰기 and the rest start a new conversation, shown in the chat.
                    onPrompt = { text -> vm.startCardPrompt(text).also { started -> if (started) screen = Screen.CHAT } },
                    resumeTick = resumeTick,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
            // Board UnfoldedSettings: wide screens keep the settings list beside the memory pane.
            Screen.MEMORY -> if (wide) {
                Row(Modifier.fillMaxSize().windowInsetsPadding(topInsets)) {
                    Box(Modifier.width(360.dp).fillMaxHeight()) { settingsPane(Modifier.fillMaxWidth()) }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                    MemoryScreen(
                        store = AppGraph.memories,
                        memoryOn = memoryOn,
                        onMemoryOn = { v -> scope.launch { settings.setMemoryEnabled(v) } },
                        onBack = null,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        wide = true,
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().windowInsetsPadding(topInsets), contentAlignment = Alignment.TopCenter) {
                    MemoryScreen(
                        store = AppGraph.memories,
                        memoryOn = memoryOn,
                        onMemoryOn = { v -> scope.launch { settings.setMemoryEnabled(v) } },
                        onBack = { screen = memoryFrom },
                        modifier = Modifier.widthIn(max = 640.dp),
                    )
                }
            }
            Screen.USAGE -> Box(Modifier.fillMaxSize().windowInsetsPadding(topInsets), contentAlignment = Alignment.TopCenter) {
                UsageScreen(
                    settings = settings,
                    usage = AppGraph.usage,
                    onBack = { screen = Screen.SETTINGS },
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
            Screen.CHAT -> {
                val card = resultCard
                @Composable
                fun withDrawer(content: @Composable () -> Unit) = ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = drawerState.isOpen,
                    scrimColor = c.scrim,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerState = drawerState,
                            modifier = Modifier.width(318.dp),
                            drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                            drawerContainerColor = c.surface,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            drawer(Modifier.fillMaxWidth())
                        }
                    },
                    content = content,
                )
                when {
                    fold.mode == FoldMode.TWO_PANE -> Row(Modifier.fillMaxSize()) {
                        Box(Modifier.width(LIST_RAIL).fillMaxHeight().background(c.surface)) {
                            drawer(Modifier.fillMaxWidth())
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                        Box(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(topInsets), contentAlignment = Alignment.TopCenter) {
                            chatPane(false, Modifier.widthIn(max = 560.dp), ChatPaneLayout.NORMAL)
                        }
                    }
                    // Board UnfoldedFlex: the whole screen; opening the device flat again returns to the chat.
                    fold.mode == FoldMode.FLEX && card != null -> FlexScreen(
                        pane = card,
                        // While the agent answers here, its live text; otherwise the newest reply.
                        reply = FoldLayoutPolicy.flexReply(messages, runningHere, streaming),
                        outdated = FoldLayoutPolicy.cardIsOutdated(messages, card),
                        onAction = onResultAction,
                        runningHere = runningHere,
                        liveSteps = liveSteps,
                        onVoice = { spoken ->
                            if (!vm.send(spoken)) Toast.makeText(context, tr("You can speak when the task in the other chat finishes", "다른 대화의 작업이 끝나면 말할 수 있어요"), Toast.LENGTH_SHORT).show()
                        },
                        onStop = { vm.stop() },
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(topInsets),
                    )
                    // Board Unfolded / UnfoldedPhotos: the chat, then the conversation's last card large.
                    fold.mode == FoldMode.RESULT_PANE && card != null -> {
                        @Composable
                        fun panes() = Row(Modifier.fillMaxSize()) {
                            if (fold.listRail) {
                                Box(Modifier.width(LIST_RAIL).fillMaxHeight().background(c.surface)) {
                                    drawer(Modifier.fillMaxWidth())
                                }
                                Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                            }
                            Box(Modifier.width(chatColumn).fillMaxHeight().windowInsetsPadding(topInsets)) {
                                chatPane(!fold.listRail, Modifier.fillMaxSize(), ChatPaneLayout.BESIDE_RESULT)
                            }
                            Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                            ResultPane(card, onResultAction, Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(topInsets))
                        }
                        if (fold.listRail) panes() else withDrawer { panes() }
                    }
                    else -> withDrawer {
                        Box(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(topInsets)) {
                            // Board UnfoldedSplit: a small window beside the app being operated shows the progress only.
                            val layout = if (fold.mode == FoldMode.SPLIT_PROGRESS) ChatPaneLayout.PROGRESS else ChatPaneLayout.NORMAL
                            chatPane(true, Modifier.fillMaxSize(), layout)
                        }
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        ModelSheet(
            model = model,
            fast = fast,
            effort = effort,
            onModel = { scope.launch { settings.setModel(it) } },
            auto = modelAuto,
            onAuto = { scope.launch { settings.setModelAuto() } },
            onFast = { scope.launch { settings.setFastMode(it) } },
            onEffort = { scope.launch { settings.setReasoningEffort(it) } },
            onDismiss = { sheetOpen = false },
            choices = modelChoices,
        )
    }
    }
}
