package com.phoneuse.ui.chat

import com.phoneuse.i18n.tr
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.app.MODEL_PRESETS
import com.phoneuse.app.ReasoningEffort
import com.phoneuse.app.SettingsStore
import com.phoneuse.auth.PlanConfig
import com.phoneuse.ui.theme.ButtonKind
import com.phoneuse.ui.theme.PlexMono
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.PuRadio
import com.phoneuse.ui.theme.PuSwitch
import com.phoneuse.ui.theme.Segmented
import com.phoneuse.ui.theme.pu

val EFFORT_OPTIONS get() = listOf(ReasoningEffort.LOW to tr("Low", "낮음"), ReasoningEffort.MEDIUM to tr("Medium", "보통"), ReasoningEffort.HIGH to tr("High", "높음"))

/** The names of these two settings, shared by this sheet and Settings so the two screens cannot drift apart. */
val FAST_MODE_LABEL get() = tr("Fast mode", "빠른 모드")
val REASONING_EFFORT_LABEL get() = tr("Reasoning effort", "추론 강도")

/**
 * What the model list offers for the chosen provider: [presets] first, then the ids its own models endpoint
 * [listed] for the user's key ([listError] when that failed), whether fast mode applies, and whether requests use
 * the signed-in ChatGPT plan ([planUsage]).
 */
data class ModelChoices(
    val presets: List<String> = MODEL_PRESETS,
    val listed: List<String> = emptyList(),
    val defaultModel: String = SettingsStore.DEFAULT_MODEL,
    val fastSupported: Boolean = true,
    val listError: String? = null,
    val planUsage: Boolean = false,
)

/** The ModelSheet board: model presets + custom id, fast mode, reasoning effort. Changes apply at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    model: String,
    fast: Boolean,
    effort: ReasoningEffort,
    onModel: (String) -> Unit,
    onFast: (Boolean) -> Unit,
    onEffort: (ReasoningEffort) -> Unit,
    onDismiss: () -> Unit,
    choices: ModelChoices = ModelChoices(),
    auto: Boolean = false,
    onAuto: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = pu.surface,
        scrimColor = pu.scrim,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 10.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(pu.inputBorder))
        },
    ) {
        ModelSettingsContent(model, fast, effort, onModel, onFast, onEffort, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp), choices, auto, onAuto)
    }
}

@Composable
fun ModelSettingsContent(
    model: String,
    fast: Boolean,
    effort: ReasoningEffort,
    onModel: (String) -> Unit,
    onFast: (Boolean) -> Unit,
    onEffort: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
    choices: ModelChoices = ModelChoices(),
    auto: Boolean = false,
    onAuto: (() -> Unit)? = null,
) {
    val c = pu
    val context = LocalContext.current
    val known = (choices.presets + choices.listed).distinct()
    var custom by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf(if (model in known) "" else model) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Model", "모델"), Modifier.padding(start = 4.dp, bottom = 6.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text)
        if (choices.planUsage) {
            Text(
                tr("Using ChatGPT plan · Manage usage", "ChatGPT 요금제 사용 중 · 사용량 관리"),
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PlanConfig.MANAGE_USAGE_URL))) }
                    }
                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 8.dp),
                fontSize = 12.5.sp,
                color = c.textSub,
                textDecoration = TextDecoration.Underline,
            )
        }
        // "자동": follows the newest of the default's family; any row below pins that model, today's newest too.
        if (onAuto != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (auto) c.accentSoft else Color.Transparent)
                    .selectable(selected = auto, role = Role.RadioButton) { onAuto(); custom = false }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PuRadio(auto)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("Auto · always the newest", "자동 · 항상 최신 모델"),
                        fontSize = 15.sp,
                        fontWeight = if (auto) FontWeight.Medium else FontWeight.Normal,
                        color = c.text,
                        maxLines = 1,
                    )
                    Text(tr("Now: ", "지금: ") + modelDisplayName(choices.defaultModel), fontSize = 12.5.sp, color = c.textSub, maxLines = 1)
                }
            }
        }
        val options = if (model in known) known else known + model
        for (id in options) {
            val on = id == model && !(onAuto != null && auto)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) c.accentSoft else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onModel(id); custom = false }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PuRadio(on)
                Spacer(Modifier.width(12.dp))
                Text(
                    modelDisplayName(id),
                    Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = c.text,
                    maxLines = 1,
                )
                if (onAuto == null && id == choices.defaultModel) {
                    Text(
                        tr("Default", "기본"),
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.surface)
                            .border(1.dp, c.accentBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.accentText,
                    )
                }
            }
        }
        if (!custom) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) { custom = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PuIcons.Plus, null, tint = c.text2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(tr("Enter manually", "직접 입력"), fontSize = 15.sp, color = c.text2)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val save = {
                    if (customText.isNotBlank()) {
                        onModel(customText.trim())
                        custom = false
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, c.inputBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (customText.isEmpty()) Text(tr("Model ID (e.g. ${choices.defaultModel})", "모델 ID (예: ${choices.defaultModel})"), fontSize = 14.sp, color = c.textSub)
                    BasicTextField(
                        customText,
                        { customText = it },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = PlexMono, fontSize = 14.sp, color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PuButton(tr("Save", "저장"), save, height = 44.dp, radius = 12.dp, enabled = customText.isNotBlank(), kind = ButtonKind.PRIMARY, fontSize = 14)
            }
        }

        choices.listError?.let { Text(it, Modifier.padding(horizontal = 14.dp), fontSize = 12.5.sp, color = c.textSub) }

        Box(Modifier.padding(horizontal = 4.dp, vertical = 10.dp).fillMaxWidth().height(1.dp).background(c.lineSoft))

        // Fast mode is OpenAI's priority tier; other providers have none.
        if (choices.fastSupported) Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PuIcons.Bolt, null, tint = c.text, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(FAST_MODE_LABEL, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.text)
                Text(tr("About 1.5x faster · uses your plan or credits faster", "약 1.5배 빠르게 · 요금제나 크레딧을 더 빨리 써요"), fontSize = 12.5.sp, color = c.textSub)
            }
            PuSwitch(fast, onFast, FAST_MODE_LABEL, width = 52.dp, height = 32.dp)
        }

        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(REASONING_EFFORT_LABEL, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.text)
            Segmented(EFFORT_OPTIONS, effort, onEffort)
            Text(tr("Lower is faster. If complex tasks keep going astray, turn it up.", "낮을수록 빨라요. 복잡한 작업이 자꾸 헤매면 올려 보세요."), fontSize = 12.5.sp, lineHeight = 18.sp, color = c.textSub)
        }
    }
}
