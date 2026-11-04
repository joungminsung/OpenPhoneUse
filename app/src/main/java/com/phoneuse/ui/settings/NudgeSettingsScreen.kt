package com.phoneuse.ui.settings

import com.phoneuse.i18n.tr
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import com.phoneuse.app.AppGraph
import com.phoneuse.app.QuietHours
import com.phoneuse.proactive.NudgeKind
import kotlinx.coroutines.launch
import com.phoneuse.proactive.NudgeLog
import com.phoneuse.proactive.NudgeTimes
import com.phoneuse.proactive.ProactiveSystem
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.PuRadio
import com.phoneuse.ui.theme.PuSwitch
import com.phoneuse.ui.theme.pu
import java.time.LocalTime

/** Choices of the board's value rows. */
private val BRIEFING_TIMES = listOf(6 to 30, 7 to 0, 7 to 30, 8 to 0, 8 to 30, 9 to 0).map { (h, m) -> LocalTime.of(h, m) }
private val DAILY_MAX = listOf(3, 6, 10)

private enum class NudgeDialog { BRIEFING, QUIET, MAX }

/**
 * Board NudgeSettings ("알아서 챙기기"): a switch per kind, the briefing time, and "방해하지 않기" (the daily cap, and
 * the quiet hours, which are the app-wide settings "방해하지 않는 시간"). Changes apply at once and trigger a check.
 */
@Composable
fun NudgeSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = pu
    val context = LocalContext.current
    val system = remember { ProactiveSystem.get(context) }
    val settings by system.prefs.settings.collectAsState()
    val quiet by AppGraph.settings.quietHours.collectAsState(QuietHours())
    val scope = rememberCoroutineScope()
    val log by system.log.entries.collectAsState()
    val week = remember(log) { NudgeLog.sentThisWeek(log, System.currentTimeMillis()) }
    var dialog by remember { mutableStateOf<NudgeDialog?>(null) }
    // Leave times and the briefing's weather take a location fix from the background: say so once if it is not allowed.
    var locationHint by remember {
        mutableStateOf(
            !system.prefs.locationHintSeen &&
                context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED,
        )
    }
    fun closeHint() {
        system.prefs.locationHintSeen = true
        locationHint = false
    }

    fun changed(block: () -> Unit) {
        block()
        system.changed()
    }

    Column(modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(PuIcons.Back, tr("Back", "뒤로"), onBack)
            Text(tr("Proactive help", "알아서 챙기기"), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.width(44.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                buildAnnotatedString {
                    append(tr("I tell you before you have to ask. This week I looked after ", "물어보기 전에 먼저 알려줘요. 이번 주 "))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = c.text)) { append("$week") }
                    append(tr(" things, and I'll cut back on what you keep skipping.", "번 챙겼고, 계속 넘긴 건 알아서 줄여요."))
                },
                Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
                color = c.textSub,
            )
            if (locationHint && (NudgeKind.LEAVE in settings.enabled || NudgeKind.BRIEFING in settings.enabled)) {
                LocationHint(
                    onOpen = {
                        closeHint()
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    onClose = { closeHint() },
                )
            }
            Group {
                NudgeKind.entries.forEachIndexed { i, kind ->
                    val briefing = kind == NudgeKind.BRIEFING
                    KindRow(
                        kind,
                        sub = if (briefing) "${kind.sub} — " else kind.sub,
                        time = if (briefing) NudgeTimes.format(settings.briefingAt) else null,
                        checked = kind in settings.enabled,
                        first = i == 0,
                        onTime = { dialog = NudgeDialog.BRIEFING },
                    ) { on -> changed { system.prefs.setEnabled(kind, on) } }
                }
            }
            Text(tr("Do not disturb", "방해하지 않기"), Modifier.padding(start = 4.dp, top = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textSub)
            Group {
                ValueLine(tr("Quiet hours", "조용한 시간"), quietLabel(quiet), first = true) { dialog = NudgeDialog.QUIET }
                ValueLine(tr("Daily maximum", "하루 최대"), tr("${settings.maxPerDay}", "${settings.maxPerDay}개")) { dialog = NudgeDialog.MAX }
            }
        }
    }

    when (dialog) {
        NudgeDialog.BRIEFING -> Choices(
            tr("Morning briefing time", "아침 브리핑 시간"),
            BRIEFING_TIMES.map { NudgeTimes.format(it) },
            BRIEFING_TIMES.indexOf(settings.briefingAt),
            onDismiss = { dialog = null },
        ) { i -> changed { system.prefs.setBriefingAt(BRIEFING_TIMES[i]) } }
        // The same choices as settings "방해하지 않는 시간", and the same setting.
        NudgeDialog.QUIET -> Choices(
            tr("Quiet hours", "조용한 시간"),
            QUIET_OPTIONS.map { quietLabel(it) },
            QUIET_OPTIONS.indexOf(quiet),
            onDismiss = { dialog = null },
        ) { i ->
            scope.launch {
                AppGraph.settings.setQuietHours(QUIET_OPTIONS[i])
                system.changed()
            }
        }
        NudgeDialog.MAX -> Choices(
            tr("Daily maximum", "하루 최대"),
            DAILY_MAX.map { tr("$it", "${it}개") },
            DAILY_MAX.indexOf(settings.maxPerDay),
            onDismiss = { dialog = null },
        ) { i -> changed { system.prefs.setMaxPerDay(DAILY_MAX[i]) } }
        null -> Unit
    }
}

/** The one-time card: without "항상 허용" location, leave times and the briefing's weather stay quiet. */
@Composable
private fun LocationHint(onOpen: () -> Unit, onClose: () -> Unit) {
    val c = pu
    Group {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(tr("Location must be set to Allow all the time", "위치를 '항상 허용'해야 해요"), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = c.text)
            Text(
                tr("Leave-time alerts and the briefing's weather use your location when the app isn't open. It isn't allowed right now, so those two stay quiet.", "출발 알림과 아침 브리핑의 날씨는 앱을 안 쓸 때 위치를 봐요. 지금은 허용되지 않아 이 두 가지는 조용히 빠져요."),
                Modifier.padding(top = 2.dp),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = c.textSub,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text(tr("Close", "닫기"), color = c.textSub) }
                TextButton(onClick = onOpen) { Text(tr("Permission settings", "권한 설정"), color = c.text, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(pu.surface).border(1.dp, pu.line, shape)) { content() }
}

@Composable
private fun Divider(first: Boolean) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(pu.lineSoft))
}

@Composable
private fun KindRow(
    kind: NudgeKind,
    sub: String,
    time: String?,
    checked: Boolean,
    first: Boolean,
    onTime: () -> Unit,
    onChange: (Boolean) -> Unit,
) {
    val c = pu
    Divider(first)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .then(if (time != null) Modifier.clickable(role = Role.Button, onClickLabel = tr("Change time", "시간 바꾸기"), onClick = onTime) else Modifier),
        ) {
            Text(kind.label, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = c.text)
            Text(
                buildAnnotatedString {
                    append(sub)
                    if (time != null) withStyle(MonoStyle.toSpanStyle().copy(color = c.text)) { append(time) }
                },
                Modifier.padding(top = 2.dp),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = c.textSub,
            )
        }
        PuSwitch(checked, onChange, "${kind.label} ${if (checked) tr("on", "켜짐") else tr("off", "꺼짐")}")
    }
}

@Composable
private fun ValueLine(title: String, value: String, first: Boolean = false, onClick: () -> Unit) {
    val c = pu
    Divider(first)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = c.text)
        Text(value, style = MonoStyle, fontSize = 14.sp, color = c.text)
    }
}

@Composable
private fun Choices(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val c = pu
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
        text = {
            Column {
                options.forEachIndexed { i, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(selected = i == selected, role = Role.RadioButton) {
                                onPick(i)
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PuRadio(i == selected)
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MonoStyle, fontSize = 15.sp, color = c.text)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "닫기"), color = c.text) } },
    )
}
