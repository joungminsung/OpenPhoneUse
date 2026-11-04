package com.phoneuse.ui.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.i18n.tr
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import com.phoneuse.voice.VoicePhase
import com.phoneuse.voice.VoiceState
import kotlin.math.PI
import kotlin.math.sin

/**
 * Live waveform: [bars] rounded bars that breathe while quiet and rise with [level] (0..1), tallest in the
 * middle. Shared by the composer, the fold voice button and the assistant's voice sheet.
 */
@Composable
fun VoiceWave(level: Float, color: Color, modifier: Modifier = Modifier, bars: Int = 28) {
    val smooth by animateFloatAsState(level, spring(stiffness = Spring.StiffnessMediumLow), label = "voiceLevel")
    val phase by rememberInfiniteTransition(label = "voiceWave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "voicePhase",
    )
    Canvas(modifier) {
        val step = size.width / bars
        val stroke = (step * 0.5f).coerceAtMost(3.dp.toPx())
        val mid = size.height / 2
        for (i in 0 until bars) {
            val pos = (i + 0.5f) / bars
            val envelope = sin(PI * pos).toFloat()
            val wobble = 0.5f + 0.5f * sin(phase + i * 0.55f)
            val amount = (0.1f + 0.08f * wobble + smooth * envelope * (0.5f + 0.5f * wobble)).coerceAtMost(1f)
            val h = (size.height * amount).coerceAtLeast(stroke)
            val x = step * (i + 0.5f)
            drawLine(color, Offset(x, mid - h / 2), Offset(x, mid + h / 2), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/**
 * The composer while listening: × cancel, the live waveform over what has been heard so far (or the inline
 * error), and a round done button (✓; a spinner while the final text comes; 🎤 to try again after an error).
 */
@Composable
fun VoiceBar(state: VoiceState, onCancel: () -> Unit, onDone: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val c = pu
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconBtn(PuIcons.Close, tr("Cancel voice input", "음성 입력 취소"), onCancel, tint = c.textSub, iconSize = 20.dp)
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .padding(vertical = 4.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            if (state.phase == VoicePhase.ERROR) {
                VoiceErrorText(state, fontSize = 14.5f)
            } else {
                VoiceWave(
                    level = if (state.phase == VoicePhase.LISTENING) state.level else 0f,
                    color = if (state.phase == VoicePhase.PROCESSING) c.textSub else c.accent,
                    modifier = Modifier.fillMaxWidth().height(if (state.partial.isEmpty()) 30.dp else 18.dp),
                )
                if (state.partial.isNotEmpty()) {
                    Text(
                        state.partial,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        color = c.text,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        VoiceDoneButton(state, onDone, onRetry)
    }
}

@Composable
private fun VoiceDoneButton(state: VoiceState, onDone: () -> Unit, onRetry: () -> Unit, size: Dp = 44.dp) {
    val c = pu
    val error = state.phase == VoicePhase.ERROR
    val processing = state.phase == VoicePhase.PROCESSING
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(c.primary)
            .clickable(enabled = !processing, role = Role.Button, onClick = if (error) onRetry else onDone)
            .semantics { contentDescription = if (error) tr("Try again", "다시 말하기") else tr("Done", "완료") },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state.phase,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "voiceDone",
        ) { phase ->
            when (phase) {
                VoicePhase.PROCESSING -> CircularProgressIndicator(Modifier.size(18.dp), color = c.onPrimary, strokeWidth = 2.dp)
                VoicePhase.ERROR -> Icon(PuIcons.Mic, null, tint = c.onPrimary, modifier = Modifier.size(20.dp))
                else -> Icon(PuIcons.Check, null, tint = c.onPrimary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun VoiceErrorText(state: VoiceState, fontSize: Float, align: TextAlign = TextAlign.Start) {
    Text(
        state.error?.message.orEmpty(),
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        fontSize = fontSize.sp,
        lineHeight = (fontSize + 6).sp,
        color = pu.accentText,
        textAlign = align,
    )
}

/**
 * The big round mic (fold flex mode, the assistant's voice sheet): a [size] filled circle in a halo that swells
 * with the voice. Shows 🎤 when idle and the live waveform while listening. Tapping it starts or stops.
 */
@Composable
fun VoiceOrb(state: VoiceState, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val c = pu
    val listening = state.phase == VoicePhase.LISTENING
    val halo by animateFloatAsState(
        if (listening) 1f + 0.22f * state.level else if (state.active) 1.04f else 1f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "voiceHalo",
    )
    val haloColor by animateColorAsState(if (state.active && !isError(state)) c.accentBorder else c.inputBorder, label = "voiceHaloColor")
    Box(modifier.size(size * 1.21f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 1.21f).scale(halo).clip(CircleShape).background(haloColor))
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(c.primary)
                .clickable(enabled = state.phase != VoicePhase.PROCESSING, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = if (state.listening) tr("Stop listening", "듣기 끝내기") else tr("Speak by voice", "음성으로 말하기") },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = state.listening || state.phase == VoicePhase.PROCESSING,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "voiceOrb",
            ) { live ->
                if (live) {
                    VoiceWave(
                        level = if (listening) state.level else 0f,
                        color = c.onPrimary,
                        modifier = Modifier.size(size * 0.5f, size * 0.36f),
                        bars = 9,
                    )
                } else {
                    Icon(PuIcons.Mic, null, tint = c.onPrimary, modifier = Modifier.size(size * 0.4f))
                }
            }
        }
    }
}

private fun isError(state: VoiceState) = state.phase == VoicePhase.ERROR

/**
 * The words next to or under [VoiceOrb]: [idleTitle]/[idleHint] at rest; while listening, what has been heard
 * (or "듣고 있어요") and how to finish; the inline error after a failure.
 */
@Composable
fun VoiceCaption(state: VoiceState, idleTitle: String, idleHint: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    val c = pu
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (state.phase) {
            VoicePhase.IDLE -> {
                Text(idleTitle, Modifier.fillMaxWidth(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = align)
                Text(idleHint, Modifier.fillMaxWidth(), fontSize = 13.5.sp, color = c.textSub, textAlign = align)
            }
            VoicePhase.ERROR -> VoiceErrorText(state, fontSize = 14.5f, align = align)
            else -> {
                Text(
                    state.partial.ifEmpty { tr("Listening…", "듣고 있어요…") },
                    Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = if (state.partial.isEmpty()) FontWeight.SemiBold else FontWeight.Normal,
                    color = c.text,
                    textAlign = align,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.phase == VoicePhase.PROCESSING) tr("Finishing…", "마무리하는 중…") else tr("Tap the mic again when you're done", "다 말했으면 마이크를 다시 누르세요"),
                    Modifier.fillMaxWidth(),
                    fontSize = 13.5.sp,
                    color = c.textSub,
                    textAlign = align,
                )
            }
        }
    }
}
