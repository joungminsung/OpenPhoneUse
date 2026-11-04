package com.phoneuse.widget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.phoneuse.app.AppGraph
import com.phoneuse.app.ThemeMode
import com.phoneuse.i18n.tr
import com.phoneuse.memory.MemoryKind
import com.phoneuse.memory.NewMemory
import com.phoneuse.ui.LaunchToken
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.PhoneUseTheme
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.SendCircle
import com.phoneuse.ui.theme.isDark
import com.phoneuse.ui.theme.pu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The widgets' compact quick-ask: a sheet over the home screen with the keyboard up. What the user types (or says)
 * goes to the chat as their own message ([WidgetPrompt.typed]), through the same path as the composer; a chip goes
 * like a card button. In remember mode the text is stored as a memory instead (the user's own words, so "sure").
 * Not exported; the intent still has to carry our [LaunchToken].
 */
class QuickAskActivity : ComponentActivity() {
    private var mode = QuickAskMode.ASK
    private var conversation: Long? = null

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else {
            null
        }
        if (!text.isNullOrBlank()) submit(text) else if (mode == QuickAskMode.VOICE) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = QuickAskMode.fromWire(intent.getStringExtra(EXTRA_MODE))
        val target = intent.getLongExtra(WidgetLinks.EXTRA_TARGET, -1L).takeIf { it >= 0 }
        val ok = requested != null && LaunchToken.of(this).verify(
            LaunchToken.widgetAction(WidgetLinks.quickAskAction(requested, target)),
            intent.getStringExtra(LaunchToken.EXTRA),
        )
        if (!ok) {
            finish()
            return
        }
        mode = requested!!
        conversation = target
        if (mode == QuickAskMode.VOICE && savedInstanceState == null) listen()
        setContent {
            val theme by AppGraph.themeMode.collectAsState()
            PhoneUseTheme(isDark(theme ?: ThemeMode.SYSTEM)) {
                if (mode != QuickAskMode.VOICE) {
                    Sheet(mode, onSubmit = ::submit, onChip = ::sendChip, onMic = ::listen, onDismiss = ::finish)
                }
            }
        }
    }

    private fun listen() {
        val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, tr("Speak now", "말해 주세요"))
            .apply {
                AppGraph.voiceLanguage.value.tag?.let {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, it)
                }
            }
        try {
            voice.launch(speech)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, tr("Voice input isn't available on this device", "이 기기에서 음성 입력을 쓸 수 없어요"), Toast.LENGTH_SHORT).show()
            if (mode == QuickAskMode.VOICE) finish()
        }
    }

    /** The user's own words: a new message (or a follow-up), or in remember mode a memory. */
    private fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (mode == QuickAskMode.REMEMBER) return saveMemory(t)
        startActivity(WidgetLinks.prompt(this, WidgetPrompt(t, typed = true, conversationId = conversation)))
        finish()
    }

    private fun sendChip(chip: AskChip) {
        startActivity(WidgetLinks.prompt(this, WidgetPrompt(chip.prompt, typed = false)))
        finish()
    }

    private fun saveMemory(text: String) {
        val app = applicationContext
        lifecycleScope.launch {
            val message = try {
                AppGraph.memories.add(NewMemory(text, MemoryKind.ME))
                tr("Remembered", "기억했어요")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tr("Couldn't save the memory", "기억하지 못했어요")
            }
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_MODE = "com.phoneuse.extra.QUICK_ASK_MODE"
    }
}

@Composable
private fun Sheet(
    mode: QuickAskMode,
    onSubmit: (String) -> Unit,
    onChip: (AskChip) -> Unit,
    onMic: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = pu
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var chips by remember { mutableStateOf<List<AskChip>>(emptyList()) }
    val memoryOn by remember { AppGraph.settings.memoryEnabled }.collectAsState(true)
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
        if (mode == QuickAskMode.ASK) chips = AskBarWidget.loadChips(context)
    }
    val title = when (mode) {
        QuickAskMode.FOLLOW_UP -> tr("Follow up", "이어서 묻기")
        QuickAskMode.REMEMBER -> tr("Remember this", "기억해 줘")
        else -> tr("Ask ${WidgetText.appName}", "${WidgetText.appName}에게 물어보기")
    }
    val hint = when (mode) {
        QuickAskMode.FOLLOW_UP -> tr("Ask about the last result", "방금 결과에 이어서 물어보세요")
        QuickAskMode.REMEMBER -> tr("e.g. My locker code is on the fridge", "예: 우리 집 와이파이는 거실 공유기")
        else -> tr("What should I do?", "무엇을 해 드릴까요?")
    }
    Box(
        Modifier.fillMaxSize().background(c.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        ) {
            Text(title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (mode == QuickAskMode.REMEMBER && !memoryOn) {
                Spacer(Modifier.height(4.dp))
                Text(tr("Memory is off in Settings, so this won't be used in answers.", "설정에서 기억이 꺼져 있어 답에 쓰이지 않아요."), color = c.textSub, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(c.sunken)
                        .border(1.dp, c.inputBorder, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text(hint, color = c.textSub, fontSize = 15.sp, maxLines = 1)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        textStyle = TextStyle(color = c.text, fontSize = 15.sp),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSubmit(text) }),
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (text.isBlank() && mode != QuickAskMode.REMEMBER) {
                    IconBtn(PuIcons.Mic, tr("Voice", "음성"), onMic)
                } else {
                    SendCircle(onClick = { onSubmit(text) }, enabled = text.isNotBlank())
                }
            }
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { chip ->
                        Box(
                            Modifier.clip(RoundedCornerShape(18.dp)).background(c.chip)
                                .border(1.dp, c.line, RoundedCornerShape(18.dp))
                                .clickable { onChip(chip) }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) { Text(chip.label, color = c.text, fontSize = 13.sp, maxLines = 1) }
                    }
                }
            }
        }
    }
}
