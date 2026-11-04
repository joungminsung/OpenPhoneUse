package com.phoneuse.ui.usage

import com.phoneuse.i18n.tr
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.app.AuthMode
import com.phoneuse.app.SettingsStore
import com.phoneuse.auth.PlanConfig
import com.phoneuse.ui.theme.ButtonKind
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.PuSwitch
import com.phoneuse.ui.theme.pu
import com.phoneuse.usage.DayUsage
import com.phoneuse.usage.RequestKind
import com.phoneuse.usage.UsageStore
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale
import com.phoneuse.i18n.Lang

/** Texts of the usage screens; pure so they can be tested. */
object UsageText {
    /** "월"…"일" for past days; the last day is "오늘". */
    fun dayLabel(day: DayUsage, isToday: Boolean): String =
        if (isToday) tr("Today", "오늘") else day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, if (Lang.korean) Locale.KOREAN else Locale.ENGLISH)

    /** Whole-number shares of each kind adding up to 100 (largest remainder); empty when there are no requests. */
    fun shares(counts: Map<RequestKind, Int>): Map<RequestKind, Int> {
        val total = counts.values.sum()
        if (total <= 0) return emptyMap()
        val raw = RequestKind.entries.associateWith { (counts[it] ?: 0) * 100.0 / total }
        val out = raw.mapValues { it.value.toInt() }.toMutableMap()
        var left = 100 - out.values.sum()
        for (k in raw.entries.sortedByDescending { it.value - it.value.toInt() }.map { it.key }) {
            if (left <= 0) break
            out[k] = out.getValue(k) + 1
            left--
        }
        return out
    }

    val KIND_NAMES get() = mapOf(
        RequestKind.DEVICE to tr("Phone control", "폰 조작"),
        RequestKind.CHAT to tr("Chat", "대화"),
        RequestKind.SEARCH to tr("Search", "검색"),
        RequestKind.ROUTINE to tr("Routines", "루틴"),
    )
}

/** Board Usage: the way to the ChatGPT plan's usage, the last 7 days of requests, and the usage switches. */
@Composable
fun UsageScreen(
    settings: SettingsStore,
    usage: UsageStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = pu
    val scope = rememberCoroutineScope()
    val version by usage.version.collectAsState()
    val days by produceState(emptyList<DayUsage>(), version) { value = usage.daily(7) }
    val routineLight by settings.routineLightModel.collectAsState(false)
    val helper by settings.helperModel.collectAsState(true)
    val authMode by settings.authMode.collectAsState(null)

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(PuIcons.Back, tr("Back", "뒤로"), onBack)
            Text(tr("Usage", "사용량"), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.width(44.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The plan's usage only applies to ChatGPT sign-in; with an API key only request counts are shown.
            if (authMode == AuthMode.CHATGPT) PlanUsageCard()
            RequestsCard(days)
            SwitchGroup {
                UsageSwitch(tr("Use a light model for routines", "루틴은 가벼운 모델로"), routineLight, first = true) { v -> scope.launch { settings.setRoutineLightModel(v) } }
                UsageSwitch(tr("Quick helper (light model for simple repeats)", "빠른 도우미 (단순 반복은 가벼운 모델)"), helper) { v -> scope.launch { settings.setHelperModel(v) } }
            }
        }
    }
}

@Composable
private fun CardColumn(label: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(pu.surface)
            .border(1.dp, pu.line, shape)
            .semantics { contentDescription = label }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

/** The plan route reports no usage, so this only points to ChatGPT settings and states no percent or reset time. */
@Composable
private fun PlanUsageCard() {
    val c = pu
    val context = LocalContext.current
    CardColumn(tr("ChatGPT plan usage", "ChatGPT 요금제 사용량")) {
        Text(tr("ChatGPT plan usage", "ChatGPT 요금제 사용량"), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = c.textSub)
        Text(
            tr("Your remaining usage is shown in ChatGPT settings.", "남은 사용량은 ChatGPT 설정에서 볼 수 있어요."),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = c.textSub,
        )
        PuButton(tr("Manage usage", "사용량 관리"), {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PlanConfig.MANAGE_USAGE_URL))) }
            Unit
        }, kind = ButtonKind.OUTLINE, height = 40.dp, radius = 12.dp, fontSize = 14)
    }
}

@Composable
private fun RequestsCard(days: List<DayUsage>) {
    val c = pu
    val total = days.sumOf { it.total }
    val counts = RequestKind.entries.associateWith { k -> days.sumOf { it.counts[k] ?: 0 } }
    val shares = UsageText.shares(counts)
    val colors = mapOf(
        RequestKind.DEVICE to c.primary,
        RequestKind.CHAT to c.radioOff,
        RequestKind.SEARCH to c.accent,
        RequestKind.ROUTINE to c.inputBorder,
    )
    CardColumn(tr("Last 7 days", "최근 7일")) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(tr("Requests, last 7 days", "최근 7일 요청"), Modifier.weight(1f), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = c.textSub)
            Text(tr("Total $total", "합계 $total"), style = MonoStyle, fontSize = 13.sp, color = c.textSub)
        }
        if (days.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DayBars(days)
                Row(Modifier.fillMaxWidth()) {
                    days.forEachIndexed { i, d ->
                        val today = i == days.lastIndex
                        Text(
                            UsageText.dayLabel(d, today),
                            Modifier.weight(1f),
                            fontSize = 11.5.sp,
                            textAlign = TextAlign.Center,
                            fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
                            color = if (today) c.text else c.textSub,
                        )
                    }
                }
            }
        }
        if (shares.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                for (k in RequestKind.entries) {
                    val n = counts.getValue(k)
                    if (n > 0) Box(Modifier.weight(n.toFloat()).height(10.dp).background(colors.getValue(k)))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RequestKind.entries.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth()) {
                        for (k in pair) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(colors.getValue(k)))
                                Spacer(Modifier.width(6.dp))
                                Text(UsageText.KIND_NAMES.getValue(k) + " ", fontSize = 13.sp, color = c.text)
                                Text("${shares[k] ?: 0}%", style = MonoStyle, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.text)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayBars(days: List<DayUsage>) {
    val c = pu
    val max = days.maxOf { it.total }.coerceAtLeast(1)
    val description = tr("Requests per day, last 7 days: ", "최근 7일 요청 수 막대: ") +
        days.mapIndexed { i, d -> "${UsageText.dayLabel(d, i == days.lastIndex)} ${d.total}" }.joinToString(", ")
    val past = c.switchOff
    val today = c.primary
    val base = c.line
    Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = description }) {
        val slot = size.width / days.size
        val barW = slot * 0.74f
        val floor = size.height - 8.dp.toPx()
        val top = 6.dp.toPx()
        days.forEachIndexed { i, d ->
            if (d.total > 0) {
                val h = (floor - top) * d.total / max
                drawRoundRect(
                    color = if (i == days.lastIndex) today else past,
                    topLeft = Offset(slot * i + (slot - barW) / 2, floor - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
        }
        drawLine(base, Offset(0f, floor + 0.5f), Offset(size.width, floor + 0.5f), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun SwitchGroup(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(pu.surface).border(1.dp, pu.line, shape),
    ) { content() }
}

@Composable
private fun UsageSwitch(title: String, checked: Boolean, first: Boolean = false, onChange: (Boolean) -> Unit) {
    val c = pu
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontSize = 14.5.sp, color = c.text)
        PuSwitch(checked, onChange, title)
    }
}
