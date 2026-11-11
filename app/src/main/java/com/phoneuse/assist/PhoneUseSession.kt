package com.phoneuse.assist

import com.phoneuse.i18n.tr
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.foundation.layout.height
import com.phoneuse.app.AppGraph
import com.phoneuse.app.AssistAction
import com.phoneuse.app.FontSize
import com.phoneuse.app.SettingsStore
import com.phoneuse.app.Source
import com.phoneuse.app.ThemeMode
import com.phoneuse.model.ModelOptions
import com.phoneuse.model.OpenAiResponsesClient
import com.phoneuse.screen.TreeCompressor
import com.phoneuse.service.AccessibilityTreeReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.phoneuse.chat.Conversation
import com.phoneuse.overlay.OverlayLifecycleOwner
import com.phoneuse.service.PhoneUseAccessibilityService
import com.phoneuse.ui.theme.PhoneUseTheme
import com.phoneuse.ui.theme.PlexSans
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.isDark
import com.phoneuse.ui.theme.pu
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
 * text toggle to continue the most recent conversation, and the input with one button (🎤, or send once typed). Without
 * suggestions it is "무엇을 도와드릴까요?" with the input focused and the keyboard up. Sending hides the sheet, then runs the message once the screen settles;
 * the run's progress shows in the overlay dock and the conversation is saved in the chat app.
 */
class PhoneUseSession(context: Context) : VoiceInteractionSession(context) {
    private val owner = OverlayLifecycleOwner()

    override fun onCreate() {
        super.onCreate()
        window?.window?.apply {
            // The session window keeps away from the IME by default; the sheet needs the keyboard.
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            // The keyboard comes up from the sheet itself: at once for the plain input, but not over the suggestions.
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Insets reach Compose, which pads for the keyboard and navigation bar itself.
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
        owner.resume()
    }

    override fun onHide() {
        owner.pause()
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
    val target = targetConversation(chipSelected, recent)
    // Why the last send or 🎤 could not start; the text stays so it can be sent again.
    var notice by remember { mutableStateOf<String?>(null) }
    val submit = {
        val result = onSend(text, target, false)
        if (result is SubmitResult.Blocked) notice = result.reason
    }
    // "이 화면에서" (null while they load); none = the plain input with the keyboard up.
    var suggestions by remember { mutableStateOf<AssistSuggestions?>(null) }
    // The sheet rises once composed, so its first frame is never a sheet already in place.
    var risen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        risen = true
        suggestions = loadSuggestions()
        if (suggestions?.items.isNullOrEmpty()) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    val shown = suggestions?.items.orEmpty()

    Box(
        Modifier
            .fillMaxSize()
            .background(c.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .imePadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = risen,
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(160)),
        ) {
            val shape = RoundedCornerShape(28.dp)
            Column(
                Modifier
                    .padding(8.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .shadow(16.dp, shape, ambientColor = SheetShadow, spotColor = SheetShadow)
                    .clip(shape)
                    .background(c.surface)
                    // Taps inside the sheet must not reach the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val loading = suggestions == null
                val plain = !loading && shown.isEmpty()
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val title = suggestions?.title.orEmpty()
                    val head = when {
                        loading -> tr("Looking at the screen…", "화면을 살펴보는 중…")
                        plain -> tr("How can I help?", "무엇을 도와드릴까요?")
                        title.isEmpty() -> tr("On this screen", "이 화면에서")
                        else -> tr("On this screen", "이 화면에서") + " · " + title
                    }
                    Text(
                        head,
                        fontSize = if (plain) 17.sp else 13.sp,
                        fontWeight = if (plain) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (plain) c.text else c.textSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (recent != null) ContinueToggle(recent, chipSelected) { chipSelected = !chipSelected }
                }
                if (!serviceOn) ServiceBanner(onOpenAccessibility)
                if (loading) SuggestionSkeleton()
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
                        .padding(4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    val placeholder = if (chipSelected) tr("Keep talking", "이어서 말해 주세요") else tr("Say it or type it", "직접 말하거나 입력하세요")
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) Text(placeholder, fontSize = 16.sp, lineHeight = 22.sp, color = c.textSub, maxLines = 1)
                        BasicTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                notice = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
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
                        )
                    }
                    MicOrSend(
                        send = text.isNotBlank(),
                        onSend = { submit() },
                        onVoice = { onVoice(target)?.let { notice = it } },
                    )
                }
            }
        }
    }
}

/** The sheet floats over another app: a neutral shadow, never a coloured glow. */
private val SheetShadow = androidx.compose.ui.graphics.Color(0x2E14161A)

/**
 * The suggestions for the screen under the sheet, or none: when "측면 버튼 길게 누르기" is the plain input, a run is
 * in progress, the accessibility service is off, nobody is signed in, the plan is paused after a usage limit, or the
 * light model call fails or takes over 4 seconds. Only the screen's text goes to the model (never a screenshot).
 */
private suspend fun loadSuggestions(): AssistSuggestions {
    val none = AssistSuggestions.NONE
    return try {
        if (AppGraph.settings.assistAction.first() != AssistAction.SUGGEST) return none
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
