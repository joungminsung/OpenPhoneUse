package com.phoneuse.assist

import com.phoneuse.i18n.tr
import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.foundation.layout.height
import com.phoneuse.app.AppGraph
import com.phoneuse.app.AssistAction
import com.phoneuse.app.FontSize
import com.phoneuse.app.voiceLanguageNow
import com.phoneuse.app.Source
import com.phoneuse.app.ThemeMode
import com.phoneuse.model.ModelOptions
import com.phoneuse.model.OpenAiResponsesClient
import com.phoneuse.screen.TreeCompressor
import com.phoneuse.service.AccessibilityTreeReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.phoneuse.chat.Conversation
import com.phoneuse.overlay.OverlayLifecycleOwner
import com.phoneuse.service.PhoneUseAccessibilityService
import com.phoneuse.ui.theme.PhoneUseTheme
import com.phoneuse.ui.theme.PlexSans
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.isDark
import com.phoneuse.ui.theme.pu
import com.phoneuse.ui.voice.VoiceCaption
import com.phoneuse.ui.voice.VoiceOrb
import com.phoneuse.voice.VoiceEngine
import com.phoneuse.voice.VoicePhase
import com.phoneuse.voice.VoiceRecognizer
import com.phoneuse.voice.speechLanguageTag
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide sheet sender; it outlives the session, which ends as soon as the sheet hides. */
object AssistSession {
    val sender: AssistSender by lazy {
        AssistSender(
            CoroutineScope(SupervisorJob() + Dispatchers.Main),
            send = { id, text -> AppGraph.runner.send(id, text, Source.ASSIST) },
            blocker = { currentBlocker() },
            sendSuggested = { id, text -> AppGraph.runner.send(id, text, Source.ASSIST, suggested = true) },
        )
    }

    /** Checked before the sheet hides, so a message that cannot run keeps the sheet and its text. */
    fun currentBlocker(): String? =
        assistBlocker(PhoneUseAccessibilityService.instance?.overlay != null, AppGraph.runner.running.value)
}

/**
 * The side-button sheet: "이 화면에서" (three suggestions for the screen underneath, never run until tapped), a
 * text toggle to continue the most recent conversation, and the input with one button (🎤, or send once typed).
 * "Talk right away" listens in the sheet when possible. Tapping the editor switches to typing.
 * Sending hides the sheet, then runs the message once the screen settles;
 * the run's progress shows in the overlay dock and the conversation is saved in the chat app.
 */
class PhoneUseSession(context: Context) : VoiceInteractionSession(context) {
    private val owner = OverlayLifecycleOwner()
    private var allowVoiceOnShow = false

    override fun onCreate() {
        super.onCreate()
        window?.window?.apply {
            // The session window keeps away from the IME by default; the sheet needs the keyboard.
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // The sheet pads for the navigation bar itself and leaves the keyboard to the system, which pans the
            // window until the focused field is above the keyboard. Android 16 pans a session window whichever
            // soft input mode it requests; asking for it states what the layout depends on for every version.
            // Padding or fitting the window for the keyboard as well moves the sheet twice.
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            setDecorFitsSystemWindows(false)
        }
    }

    override fun onCreateContentView(): View {
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(owner)
            decor.setViewTreeSavedStateRegistryOwner(owner)
            decor.setViewTreeViewModelStoreOwner(owner)
        }
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent {
                // The theme read at process start (AppGraph), so the sheet never flashes the other theme first.
                val mode by AppGraph.themeMode.collectAsState()
                val fontSize by AppGraph.fontSize.collectAsState()
                PhoneUseTheme(isDark(mode ?: ThemeMode.SYSTEM), (fontSize ?: FontSize.DEFAULT).scale) {
                    AssistSheet(
                        lifecycle = owner.lifecycle,
                        canListenOnOpen = ::canListenOnOpen,
                        onDismiss = { hide() },
                        onSend = { text, conversation, suggested ->
                            AssistSession.sender.submit(text, conversation, suggested) { hide() }
                        },
                        onVoice = { conversation -> startVoice(conversation) },
                        onOpenAccessibility = { openAccessibilitySettings() },
                    )
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        allowVoiceOnShow = showFlags and (SHOW_SOURCE_APPLICATION or SHOW_SOURCE_ACTIVITY) == 0
        owner.resume()
    }

    private fun pauseSession() {
        allowVoiceOnShow = false
        owner.pause()
    }

    /**
     * Checked on open and again just before the microphone starts. An unreadable lock state counts as locked, and an
     * unreadable screen state as off.
     */
    private fun canListenOnOpen(action: AssistAction?, inSheetRecognizer: Boolean): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val accessibility = context.getSystemService(AccessibilityManager::class.java)
        val microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        return shouldListenOnOpen(
            action = action,
            openedBySystem = allowVoiceOnShow,
            micGranted = microphone == PackageManager.PERMISSION_GRANTED,
            inSheetRecognizer = inSheetRecognizer,
            canStartTask = AssistSession.currentBlocker() == null,
            keyguardLocked = keyguard?.isKeyguardLocked != false,
            screenOn = power?.isInteractive == true,
            touchExploration = accessibility?.isTouchExplorationEnabled == true,
        )
    }

    override fun hide() {
        // Back and Home request an asynchronous hide. Cancel recognition before a queued result can send.
        pauseSession()
        super.hide()
    }

    override fun onHide() {
        pauseSession()
        super.onHide()
        // One sheet per invocation: the next long-press starts fresh.
        finish()
    }

    override fun onDestroy() {
        // The window goes first, so the ComposeView is never attached to a destroyed lifecycle.
        super.onDestroy()
        owner.destroy()
    }

    /** Returns the reason voice input cannot run (the sheet stays open), or null once it started. */
    private fun startVoice(conversation: Long?): String? {
        AssistSession.currentBlocker()?.let { return it }
        launch(
            Intent(context, AssistVoiceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(AssistVoiceActivity.EXTRA_CONVERSATION, conversation ?: -1L),
        )
        return null
    }

    private fun openAccessibilitySettings() {
        launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** Starts [intent] through the session (the sanctioned way for an assistant), then hides the sheet. */
    @Suppress("DEPRECATION") // The Bundle overload is API 34+; minSdk is 30.
    private fun launch(intent: Intent) {
        try {
            startAssistantActivity(intent)
        } catch (e: RuntimeException) {
            Toast.makeText(context, tr("Can't open the screen", "화면을 열 수 없어요"), Toast.LENGTH_SHORT).show()
        }
        hide()
    }
}

@Composable
private fun AssistSheet(
    lifecycle: Lifecycle,
    canListenOnOpen: (action: AssistAction?, inSheetRecognizer: Boolean) -> Boolean,
    onDismiss: () -> Unit,
    onSend: (text: String, conversation: Long?, suggested: Boolean) -> SubmitResult,
    onVoice: (conversation: Long?) -> String?,
    onOpenAccessibility: () -> Unit,
) {
    val c = pu
    val conversations by remember { AppGraph.chats.conversations() }.collectAsState(emptyList())
    val serviceOn by PhoneUseAccessibilityService.connected.collectAsState()
    val recent = recentConversation(conversations)
    var chipSelected by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current
    val target = targetConversation(chipSelected, recent)
    // Why the last send or 🎤 could not start; the text stays so it can be sent again.
    var notice by remember { mutableStateOf<String?>(null) }
    var inputChosen by remember { mutableStateOf(false) }
    val voice = rememberSheetRecognizer(
        lifecycle,
        onText = { heard ->
            val result = onSend(heard, target, false)
            if (result is SubmitResult.Blocked) {
                text = heard
                notice = result.reason
            }
        },
        // Words heard before listening was interrupted go to the editor. They are never sent on their own.
        onInterrupted = { heard -> text = heard },
    )
    val voiceState by voice.state.collectAsState()
    val submit = {
        val result = onSend(text, target, false)
        if (result is SubmitResult.Blocked) notice = result.reason
    }
    // Keep the editor usable while reading the saved mode or starting recognition.
    var suggestions by remember { mutableStateOf<AssistSuggestions?>(null) }
    var loadingIdeas by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val action = try {
            AppGraph.settings.assistAction.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (action == AssistAction.SUGGEST) {
            loadingIdeas = true
            suggestions = loadSuggestions()
            loadingIdeas = false
        } else {
            val mayListen = { canListenOnOpen(action, voice.engine != VoiceEngine.Dialog) }
            if (mayListen()) {
                // The microphone opens once the sheet has focus. If focus never arrives, the editor opens instead.
                withTimeoutOrNull(FOCUS_WAIT_MS) { snapshotFlow { windowInfo.isWindowFocused }.first { it } }
                val language = AppGraph.voiceLanguageNow()
                if (!inputChosen && windowInfo.isWindowFocused && mayListen()) {
                    voice.start(speechLanguageTag(language, Locale.getDefault()))
                }
            }
            suggestions = AssistSuggestions.NONE
        }
    }
    LaunchedEffect(voiceState.error) {
        voiceState.error?.let {
            notice = it.message
            voice.clearError()
        }
    }
    val shown = suggestions?.items.orEmpty()

    Box(
        Modifier
            .fillMaxSize()
            .background(c.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier
                .padding(SheetMargin)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .shadow(16.dp, shape, ambientColor = SheetShadow, spotColor = SheetShadow)
                .clip(shape)
                .background(c.surface)
                // Taps inside the sheet must not reach the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = CardBottomPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val plain = !loadingIdeas && shown.isEmpty()
            val openKeyboard = suggestions != null && shown.isEmpty()
            val showVoice = voiceState.listening || voiceState.phase == VoicePhase.PROCESSING
            LaunchedEffect(openKeyboard, voiceState.active) {
                if (openKeyboard && !voice.state.value.active) {
                    focus.requestFocus()
                    keyboard?.show()
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val title = suggestions?.title.orEmpty()
                val head = when {
                    loadingIdeas -> tr("Looking at the screen…", "화면을 살펴보는 중…")
                    plain -> tr("How can I help?", "무엇을 도와드릴까요?")
                    title.isEmpty() -> tr("On this screen", "이 화면에서")
                    else -> tr("On this screen", "이 화면에서") + " · " + title
                }
                if (showVoice) {
                    VoiceCaption(voiceState, idleTitle = "", idleHint = "", modifier = Modifier.weight(1f))
                } else {
                    Text(
                        head,
                        fontSize = if (plain) 17.sp else 13.sp,
                        fontWeight = if (plain) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (plain) c.text else c.textSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (recent != null) ContinueToggle(recent, chipSelected) { chipSelected = !chipSelected }
            }
            if (!serviceOn) ServiceBanner(onOpenAccessibility)
            if (loadingIdeas) SuggestionSkeleton()
            AnimatedVisibility(
                visible = shown.isNotEmpty(),
                enter = fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 6 },
            ) {
                Column {
                    shown.forEach { s ->
                        SuggestionRow(s) {
                            val result = onSend(s.prompt, target, true)
                            if (result is SubmitResult.Blocked) notice = result.reason
                        }
                    }
                }
            }
            // The banner already says the service is off; other reasons (a run in progress) show here.
            notice?.takeUnless { !serviceOn && it == AssistSender.NO_SERVICE }?.let {
                Text(it, fontSize = 14.sp, color = c.danger, modifier = Modifier.padding(horizontal = 8.dp))
            }
            Row(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(c.sunken)
                    .padding(InputRowPadding),
                verticalAlignment = Alignment.Bottom,
            ) {
                val placeholder = if (chipSelected) tr("Keep talking", "이어서 말해 주세요") else tr("Say it or type it", "직접 말하거나 입력하세요")
                BasicTextField(
                    value = text,
                    onValueChange = {
                        inputChosen = true
                        if (voice.state.value.active) voice.cancel()
                        text = it
                        notice = null
                    },
                    modifier = Modifier
                        .weight(1f)
                        .liftsSheetAboveKeyboard(BelowEditor)
                        .onFocusChanged {
                            if (it.isFocused) {
                                inputChosen = true
                                // Switching to typing keeps the words already heard, in the editor.
                                if (voice.state.value.active) voice.interrupt()
                            }
                        }
                        .focusRequester(focus)
                        .semantics { contentDescription = placeholder },
                    textStyle = TextStyle(fontFamily = PlexSans, fontSize = 16.sp, lineHeight = 22.sp, color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) submit() }),
                    decorationBox = { field ->
                        Box(
                            Modifier
                                .padding(bottom = BelowEditor)
                                .heightIn(min = 48.dp)
                                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (text.isEmpty()) {
                                Text(placeholder, fontSize = 16.sp, lineHeight = 22.sp, color = c.textSub, maxLines = 1)
                            }
                            field()
                        }
                    },
                )
                if (showVoice) {
                    VoiceOrb(voiceState, voice::stop, size = 40.dp)
                } else {
                    MicOrSend(
                        send = text.isNotBlank(),
                        onSend = { submit() },
                        onVoice = {
                            inputChosen = true
                            onVoice(target)?.let { notice = it }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The system can hide the session before Compose disposes its view. Drop microphone callbacks at hide. When the screen
 * turns off the session stays up, so listening is interrupted there instead: the words heard so far go to the editor.
 */
@Composable
private fun rememberSheetRecognizer(
    lifecycle: Lifecycle,
    onText: (String) -> Unit,
    onInterrupted: (String) -> Unit,
): VoiceRecognizer {
    val context = LocalContext.current
    val latestText by rememberUpdatedState(onText)
    val latestInterrupted by rememberUpdatedState(onInterrupted)
    val voice = remember(context) {
        VoiceRecognizer(
            context,
            onText = { latestText(it) },
            onPartial = {},
            onInterrupted = { latestInterrupted(it) },
            onAudioActivity = {},
        )
    }
    DisposableEffect(lifecycle, voice) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) voice.cancel()
        }
        lifecycle.addObserver(observer)
        // Nothing hides the session when the screen turns off, so without this the microphone would stay open and
        // send what it heard from a dark phone.
        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (voice.state.value.active) voice.interrupt()
            }
        }
        // A system broadcast only; nothing else may send it to the app.
        ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(screenOff) }
            voice.destroy()
        }
    }
    return voice
}

/** How long "Talk right away" waits for the sheet's window to gain focus before it gives up on listening. */
private const val FOCUS_WAIT_MS = 1_500L

/** The gap around the sheet, the padding under the card's content, and the padding inside the input row. */
private val SheetMargin = 8.dp
private val CardBottomPadding = 12.dp
private val InputRowPadding = 4.dp

/**
 * Everything that lies under the editor, down to the bottom of the sheet's margin: the three values above. Anything
 * else put under the editor has to be added here too, or the keyboard covers the bottom of the sheet.
 */
private val BelowEditor = InputRowPadding + CardBottomPadding + SheetMargin

/**
 * Makes the system lift the whole sheet above the keyboard. The system pans a session window until the focused field
 * clears the keyboard, and while the user types Compose asks for the cursor's line to stay in view. Both stop at the
 * text and leave the rest of the sheet behind the keyboard. So the field's bounds reach [below] further down, to the
 * bottom of the sheet's margin, and a request to keep part of the field in view asks for all of it. The row is told
 * the field's usual height.
 *
 * The field is therefore laid out [below] taller than it looks. Its decoration box pads the same amount back, so the
 * text does not move, but the accessibility focus outline reaches that far under the row.
 */
private fun Modifier.liftsSheetAboveKeyboard(below: Dp): Modifier = this
    .layout { measurable, constraints ->
        val extra = below.roundToPx()
        val max = if (constraints.hasBoundedHeight) constraints.maxHeight + extra else constraints.maxHeight
        val field = measurable.measure(constraints.copy(maxHeight = max))
        layout(field.width, (field.height - extra).coerceAtLeast(0)) { field.place(0, 0) }
    }
    .then(WholeFieldIntoView)

private data object WholeFieldIntoView : ModifierNodeElement<WholeFieldIntoViewNode>() {
    override fun create() = WholeFieldIntoViewNode()
    override fun update(node: WholeFieldIntoViewNode) = Unit
}

private class WholeFieldIntoViewNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) =
        bringIntoView()
}

/** The sheet floats over another app: a neutral shadow, never a coloured glow. */
private val SheetShadow = androidx.compose.ui.graphics.Color(0x2E14161A)

/**
 * Suggestions for the screen under the sheet, or none when a run is in progress,
 * the accessibility service is off, nobody is signed in, the plan is paused after a usage limit, or the
 * light model call fails or takes over 4 seconds. Only the screen's text goes to the model (never a screenshot).
 */
private suspend fun loadSuggestions(): AssistSuggestions {
    val none = AssistSuggestions.NONE
    return try {
        if (AppGraph.runner.running.value) return none
        val service = PhoneUseAccessibilityService.instance ?: return none
        // The tree read and sign-in count toward the same 4 s budget as the model call.
        AssistSuggester.suggest {
            val backend = AppGraph.runner.automaticBackend() ?: return@suggest null
            val screen = withContext(Dispatchers.Default) {
                val bounds = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
                TreeCompressor.compress(AccessibilityTreeReader(service).snapshot(bounds.width(), bounds.height())).text
            }
            // A light call: the light preset, effort low, never the priority tier, whatever the chat uses.
            val model = backend.client(
                ModelOptions(backend.lightModel ?: AppGraph.settings.model.first(), fast = false, effort = "low"),
                AppGraph.http,
            )
            model to screen
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        none
    }
}

@Composable
private fun SuggestionRow(s: AssistSuggestion, onClick: () -> Unit) {
    val c = pu
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(s.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // The exact request a tap sends, all of it (at most PROMPT_CHARS), so nothing written from the
        // screen goes unseen.
        Text(s.prompt, fontSize = 13.sp, lineHeight = 18.sp, color = c.textSub)
    }
}

/** Three grey bars while the suggestions load. */
@Composable
private fun SuggestionSkeleton() {
    val c = pu
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(
        Modifier.padding(horizontal = 8.dp, vertical = 10.dp).alpha(pulse),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        listOf(0.7f, 0.55f, 0.62f).forEach { w ->
            Box(Modifier.fillMaxWidth(w).height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.segmentBg))
        }
    }
}

/** "이어서 하기", or the conversation it continues once on: a text button, not a chip. */
@Composable
private fun ContinueToggle(recent: Conversation, selected: Boolean, onToggle: () -> Unit) {
    val c = pu
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics { contentDescription = tr("Continue: ", "이어서: ") + recent.title }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selected) {
            Text(
                recent.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(tr("continued", "에 이어서"), fontSize = 13.sp, color = c.text, maxLines = 1)
            Icon(PuIcons.Close, null, tint = c.textSub, modifier = Modifier.size(14.dp))
        } else {
            Text(tr("Continue", "이어서 하기"), fontSize = 13.sp, color = c.textSub, maxLines = 1)
        }
    }
}

/** The input's one round button: the mic while it is empty, send once there is text. */
@Composable
private fun MicOrSend(send: Boolean, onSend: () -> Unit, onVoice: () -> Unit) {
    val c = pu
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(c.primary)
            .clickable(role = Role.Button, onClick = if (send) onSend else onVoice)
            .semantics { contentDescription = if (send) tr("Send", "보내기") else tr("Voice input", "음성 입력") },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(send, animationSpec = tween(160), label = "micOrSend") { isSend ->
            Icon(if (isSend) PuIcons.Send else PuIcons.Mic, null, tint = c.onPrimary, modifier = Modifier.size(if (isSend) 20.dp else 22.dp))
        }
    }
}

@Composable
private fun ServiceBanner(onOpen: () -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.dangerSoft)
            .border(1.dp, c.dangerBorder, shape)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Please turn on the accessibility service", "접근성 서비스를 켜주세요"), fontSize = 14.sp, color = c.danger, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(tr("Open settings", "설정 열기"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        }
    }
}
