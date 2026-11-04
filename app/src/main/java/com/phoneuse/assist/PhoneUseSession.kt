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
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.PhoneUseTheme
import com.phoneuse.ui.theme.PlexSans
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.SendCircle
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
 * The side-button sheet (board AssistSheet): "이 화면으로 할 일" (five suggestions for the screen underneath, never
 * run until tapped), a chip to continue the most recent conversation, and the input, 🎤 and send. Without
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
    // Board AssistSheet: "이 화면으로 할 일" (null while they load); none = the plain input with the keyboard up.
    var suggestions by remember { mutableStateOf<AssistSuggestions?>(null) }

    LaunchedEffect(Unit) {
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
        val shape = RoundedCornerShape(26.dp)
        Column(
            Modifier
                .padding(8.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .shadow(16.dp, shape, ambientColor = c.glow, spotColor = c.glow)
                .clip(shape)
                .background(c.surface)
                .border(1.5.dp, c.glow.copy(alpha = 0.7f), shape)
                // Taps inside the sheet must not reach the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val loading = suggestions == null
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    if (loading || shown.isNotEmpty()) tr("What to do with this screen", "이 화면으로 할 일") else tr("How can I help?", "무엇을 도와드릴까요?"),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.text,
                    modifier = Modifier.weight(1f),
                )
                val side = if (loading) tr("Looking at the screen…", "화면을 살펴보는 중…") else suggestions?.title.orEmpty().takeIf { shown.isNotEmpty() }
                side?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontSize = 12.5.sp, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!serviceOn) ServiceBanner(onOpenAccessibility)
            if (shown.isNotEmpty()) {
                Column {
                    shown.forEachIndexed { i, s ->
                        SuggestionRow(s, last = i == shown.lastIndex) {
                            val result = onSend(s.prompt, target, true)
                            if (result is SubmitResult.Blocked) notice = result.reason
                        }
                    }
                }
            }
            // The banner already says the service is off; other reasons (a run in progress) show here.
            notice?.takeUnless { !serviceOn && it == AssistSender.NO_SERVICE }?.let {
                Text(it, fontSize = 14.sp, color = c.danger, modifier = Modifier.padding(horizontal = 6.dp))
            }
            if (recent != null) ContinueChip(recent, chipSelected) { chipSelected = !chipSelected }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(c.sunken)
                    .padding(4.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val placeholder = when {
                    chipSelected -> tr("Keep talking", "이어서 말해 주세요")
                    shown.isNotEmpty() -> tr("Or tell me yourself", "아니면 직접 말해 주세요")
                    else -> tr("Tell me what you need", "직접 말해 주세요")
                }
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
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
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                    )
                }
                IconBtn(PuIcons.Mic, tr("Voice input", "음성 입력"), onClick = { onVoice(target)?.let { notice = it } }, tint = c.textSub)
                SendCircle(onClick = { submit() }, enabled = text.isNotBlank())
            }
        }
    }
}

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
private fun SuggestionRow(s: AssistSuggestion, last: Boolean, onClick: () -> Unit) {
    val c = pu
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(s.label, fontSize = 15.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The exact request a tap sends, all of it (at most PROMPT_CHARS), so nothing written from the
            // screen goes unseen.
            Text(s.prompt, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.textSub)
        }
        if (s.hint.isNotEmpty()) {
            Text(s.hint, Modifier.padding(start = 8.dp), fontSize = 12.5.sp, color = c.textSub, maxLines = 1)
        }
    }
    if (last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
}

@Composable
private fun ContinueChip(recent: Conversation, selected: Boolean, onToggle: () -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) c.accentSoft else c.chip)
            .border(1.dp, if (selected) c.accentBorder else c.line, shape)
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) Icon(PuIcons.Check, null, tint = c.accentText, modifier = Modifier.size(16.dp))
        Text(tr("Continue", "이어서"), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (selected) c.accentText else c.text)
        Text(
            recent.title,
            fontSize = 13.5.sp,
            color = if (selected) c.accentText else c.textSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 240.dp),
        )
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
