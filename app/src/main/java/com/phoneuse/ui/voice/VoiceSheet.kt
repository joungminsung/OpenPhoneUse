package com.phoneuse.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneuse.i18n.tr
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu

/**
 * The assistant's voice sheet (AssistVoiceActivity): a compact card at the bottom, styled like the assistant
 * sheet, with the [VoiceOrb], the live words and a × to cancel. Slides in, and listening starts right away
 * when [autoStart]. Tapping the scrim calls [onDismiss].
 */
@Composable
fun VoiceSheet(voice: VoiceInput, autoStart: Boolean, onDismiss: () -> Unit) {
    val c = pu
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        shown = true
        if (autoStart) voice.toggle()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(c.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 3 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 3 },
        ) {
            val shape = RoundedCornerShape(28.dp)
            Column(
                Modifier
                    .padding(8.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    // Like the side-button sheet: a neutral shadow, never a coloured glow.
                    .shadow(16.dp, shape, ambientColor = Color(0x2E14161A), spotColor = Color(0x2E14161A))
                    .clip(shape)
                    .background(c.surface)
                    // Taps inside the card must not reach the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .semantics { paneTitle = tr("Voice input", "음성 입력") }
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconBtn(PuIcons.Close, tr("Cancel voice input", "음성 입력 취소"), onDismiss, tint = pu.textSub, iconSize = 20.dp)
                }
                VoiceOrb(voice.state, voice::toggle, size = 76.dp)
                VoiceCaption(
                    voice.state,
                    idleTitle = tr("Tap to speak", "눌러서 말하기"),
                    idleHint = tr("Say what you want me to do", "할 일을 말해 주세요"),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    align = TextAlign.Center,
                )
            }
        }
    }
}
