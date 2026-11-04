package com.phoneuse.ui.chat

import com.phoneuse.i18n.tr
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.PlexSans
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.SendCircle
import com.phoneuse.ui.theme.StopCircle
import com.phoneuse.ui.theme.pu
import com.phoneuse.ui.voice.VoiceBar
import com.phoneuse.ui.voice.rememberVoiceInput

/**
 * The pill-shaped input from the boards: the + attach button (× while the attach sheet is open), multi-line
 * text, 🎤, and a 44dp round send button that becomes ■ stop while the agent runs. Typing while it runs steers
 * it (the small arrow sends the text). [hasAttachments] lets a message without text be sent.
 * 🎤 listens in place: the pill turns into [VoiceBar] (waveform, live words, × and ✓) and the result goes to
 * [onVoiceResult].
 */
@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    running: Boolean,
    showMic: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoiceResult: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    attachOpen: Boolean = false,
    onAttach: (() -> Unit)? = null,
    hasAttachments: Boolean = false,
) {
    val c = pu
    val voice = rememberVoiceInput(onVoiceResult)
    val listening = voice.state.active
    // The mic is gone (a confirmation card is up): stop listening with it.
    LaunchedEffect(showMic) { if (!showMic) voice.cancel() }
    val hasText = value.text.isNotBlank()
    val shape = RoundedCornerShape(26.dp)
    val border by animateColorAsState(
        when {
            listening -> c.accentBorder
            running -> c.line
            else -> c.inputBorder
        },
        label = "composerBorder",
    )
    Box(
        modifier
            .fillMaxWidth()
            .then(if (running) Modifier.shadow(3.dp, shape, ambientColor = c.text, spotColor = c.text) else Modifier)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, border, shape)
            .padding(5.dp),
    ) {
        AnimatedContent(
            targetState = listening,
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f)) togetherWith fadeOut(tween(150))
            },
            label = "composerVoice",
        ) { active ->
            if (active) {
                VoiceBar(voice.state, onCancel = voice::cancel, onDone = voice::done, onRetry = voice::toggle)
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (onAttach != null) AttachButton(attachOpen, onAttach)
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .padding(start = if (onAttach != null) 4.dp else 12.dp, end = 4.dp, top = 11.dp, bottom = 11.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.text.isEmpty()) {
                            Text(placeholder, fontSize = 16.sp, lineHeight = 22.sp, color = c.textSub, maxLines = 1)
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .semantics { contentDescription = placeholder },
                            textStyle = TextStyle(fontFamily = PlexSans, fontSize = 16.sp, lineHeight = 22.sp, color = c.text),
                            cursorBrush = SolidColor(c.accent),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        )
                    }
                    when {
                        running && hasText -> IconBtn(PuIcons.Send, tr("Send instruction", "지시 보내기"), onSend, tint = c.text, iconSize = 20.dp)
                        showMic -> IconBtn(PuIcons.Mic, tr("Voice input", "음성 입력"), onClick = voice::toggle, tint = c.textSub)
                    }
                    if (running) StopCircle(onStop) else SendCircle(onSend, enabled = hasText || hasAttachments)
                }
            }
        }
    }
}

/** 44dp circle: + on the soft fill (화면, 사진, 파일 첨부), or a filled × while the sheet is open (ChatPhoto). */
@Composable
private fun AttachButton(open: Boolean, onClick: () -> Unit) {
    val c = pu
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (open) c.primary else c.bg)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (open) tr("Close attachments", "첨부 닫기") else tr("Attach screen, photo or file", "화면, 사진, 파일 첨부") },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (open) PuIcons.Close else PuIcons.Plus,
            contentDescription = null,
            tint = if (open) c.onPrimary else c.text,
            modifier = Modifier.size(if (open) 18.dp else 20.dp),
        )
    }
}
