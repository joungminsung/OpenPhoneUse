package com.phoneuse.ui.routines

import com.phoneuse.i18n.tr
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.routine.Routine
import kotlinx.coroutines.launch
import com.phoneuse.routine.RoutineDraft
import com.phoneuse.routine.RoutinePermissions
import com.phoneuse.routine.RoutineSystem
import com.phoneuse.routine.RoutineTemplate
import com.phoneuse.routine.RoutineTemplates
import com.phoneuse.routine.RoutineText
import com.phoneuse.routine.Trigger
import com.phoneuse.ui.cards.CardButton
import com.phoneuse.ui.cards.PermissionCardView
import com.phoneuse.ui.cards.rememberCardActionRunner
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.PuSwitch
import com.phoneuse.ui.theme.pu
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Board Routines: a sentence box (sent to the chat, where make_routine shows a preview), a suggestion, the routines
 * with their switches, last result and "지금 실행", and the eight templates. Tapping a routine or a template opens
 * the builder (board RoutineBuilder). [resumeTick] re-reads permissions when the user comes back from settings.
 */
@Composable
fun RoutinesScreen(
    onBack: () -> Unit,
    /** False when the sentence could not start a run now (one is going); the field keeps it. */
    onMakeFromSentence: (String) -> Boolean,
    onOpenConversation: (Long) -> Unit,
    resumeTick: Int,
    modifier: Modifier = Modifier,
) {
    val c = pu
    val context = LocalContext.current
    val system = remember(context) { RoutineSystem.get(context) }
    val scope = rememberCoroutineScope()
    val routines by system.store.routines.collectAsState()
    val placesOk = remember(context) { system.geofences.available() }
    var editing by remember { mutableStateOf<RoutineDraft?>(null) }
    var sentence by rememberSaveable { mutableStateOf("") }
    var suggestionDismissed by rememberSaveable { mutableStateOf(false) }
    // After a permission is allowed, register geofences again instead of starting an agent run.
    val onCardAction = rememberCardActionRunner { system.changed() }
    val placesPaused by system.store.paused.collectAsState()
    // Back from Settings (a permission or location turned on there): register geofences if anything changed.
    LaunchedEffect(resumeTick) { system.changed() }

    editing?.let { draft ->
        BackHandler { editing = null }
        RoutineBuilderScreen(
            initial = draft,
            placesSupported = placesOk,
            onClose = { editing = null },
            onSaved = { editing = null },
            modifier = modifier,
        )
        return
    }

    // Templates not made yet; the first one is the suggestion.
    val unused = RoutineTemplates.all.filter { t -> routines.none { it.title == t.draft.title } }
    val suggestion = unused.firstOrNull()?.takeIf { !suggestionDismissed }

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(PuIcons.Back, tr("Back", "뒤로"), onBack)
            Text(tr("Routines", "루틴"), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
            IconBtn(PuIcons.Plus, tr("New routine", "새 루틴"), { editing = RoutineDraft() })
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "input") {
                SentenceBox(sentence, { sentence = it }) {
                    val text = sentence.trim()
                    if (text.isNotEmpty()) {
                        if (onMakeFromSentence(text)) sentence = ""
                    }
                }
            }
            if (suggestion != null) {
                item(key = "suggestion") {
                    Suggestion(suggestion, onMake = { editing = suggestion.draft }, onDismiss = { suggestionDismissed = true })
                }
            }
            system.store.brokenAside?.let { aside ->
                item(key = "broken") {
                    Text(system.brokenText(aside), Modifier.padding(horizontal = 4.dp, vertical = 8.dp), fontSize = 13.5.sp, color = c.textSub)
                }
            }
            if (routines.isEmpty()) {
                item(key = "empty") {
                    Text(
                        tr("No routines yet. Say it in a sentence or pick a template below.", "아직 루틴이 없어요. 문장으로 말하거나 아래 템플릿에서 골라 보세요."),
                        Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        fontSize = 13.5.sp,
                        color = c.textSub,
                    )
                }
            }
            items(routines, key = { "r${it.id}" }) { r ->
                // Re-read with resumeTick: the user may have just granted a permission in settings.
                val missing = remember(r, resumeTick) { if (r.enabled) system.missingPermission(r) else null }
                // A routine that cannot fire even so (accessibility service off, location off) says why.
                val warning = remember(r, resumeTick, placesPaused) { if (r.enabled) system.warning(r) else null }
                RoutineRow(
                    r,
                    placesOk = placesOk,
                    onToggle = { on ->
                        scope.launch {
                            if (!system.edit { update(r.id) { it.copy(enabled = on) } }) Toast.makeText(context, tr("Couldn't save", "저장하지 못했어요"), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpen = { editing = RoutineDraft.from(r) },
                    onRunNow = {
                        system.runNow(r.id)
                        Toast.makeText(context, tr("Running it", "실행할게요"), Toast.LENGTH_SHORT).show()
                    },
                    onOpenConversation = r.conversationId?.let { id -> { onOpenConversation(id) } },
                )
                if (warning != null && (r.trigger !is Trigger.Place || placesOk)) {
                    Text(warning, Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp), fontSize = 12.5.sp, color = c.textSub)
                }
                if (missing != null && (r.trigger !is Trigger.Place || placesOk)) {
                    Column(Modifier.padding(top = 6.dp)) {
                        PermissionCardView(missing, onCardAction)
                        if (missing.label == RoutinePermissions.ALWAYS_LABEL) {
                            Text(RoutinePermissions.ALWAYS_HINT, Modifier.padding(4.dp), fontSize = 12.5.sp, color = c.textSub)
                        }
                    }
                }
            }
            item(key = "templates-cap") { Cap(tr("Templates", "템플릿"), Modifier.padding(top = 10.dp)) }
            items(RoutineTemplates.all, key = { "t-${it.title}" }) { t ->
                TemplateRow(t) { editing = t.draft }
            }
        }
    }
}

@Composable
private fun SentenceBox(value: String, onChange: (String) -> Unit, onMake: () -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(shape)
            .background(c.surface)
            .border(1.5.dp, c.primary, shape)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(tr("e.g. Silence my phone when I get to work", "예: 회사 도착하면 무음으로"), fontSize = 15.5.sp, color = c.textSub)
            androidx.compose.foundation.text.BasicTextField(
                value,
                onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = com.phoneuse.ui.theme.PlexSans, fontSize = 15.5.sp, color = c.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
        }
        Box(
            Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.primary)
                .clickable(role = Role.Button, onClick = onMake)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(tr("Create", "만들기"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.onPrimary)
        }
    }
}

@Composable
private fun Suggestion(t: RoutineTemplate, onMake: () -> Unit, onDismiss: () -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.accentSoft).border(1.dp, c.accentBorder, shape).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(tr("How about this", "이런 건 어때요"), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = c.accentText)
        Text(tr("${t.title} — ${t.description}. Make it a routine?", "${t.title} — ${t.description}. 루틴으로 만들어 둘까요?"), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton(tr("Make it a routine", "루틴으로 만들기"), onMake, primary = true, height = 40.dp)
            CardButton(tr("No thanks", "괜찮아요"), onDismiss, height = 40.dp)
        }
    }
}

@Composable
private fun RoutineRow(
    r: Routine,
    placesOk: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRunNow: () -> Unit,
    onOpenConversation: (() -> Unit)?,
) {
    val c = pu
    val context = LocalContext.current
    val shape = RoundedCornerShape(18.dp)
    val unsupported = r.trigger is Trigger.Place && !placesOk
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .clickable(role = Role.Button, onClickLabel = tr("Edit routine", "루틴 고치기"), onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                WhenLine(r.trigger, context)
                Text(r.title, Modifier.padding(top = 2.dp, bottom = 6.dp), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                ActTags(RoutineText.steps(r.action) { appLabel(context, it) }.map { it.text } + RoutineText.conditionChips(r.conditions))
            }
            Spacer(Modifier.width(12.dp))
            PuSwitch(
                r.enabled && !unsupported,
                onToggle,
                tr("${r.title} routine ${if (r.enabled) "on" else "off"}", "${r.title} 루틴 ${if (r.enabled) "켜짐" else "꺼짐"}"),
                Modifier.padding(top = 2.dp),
                width = 48.dp,
                height = 28.dp,
                enabled = !unsupported,
            )
        }
        if (unsupported) {
            Text(tr("Not available on this device · place routines need Google Play services", "이 기기에서 못 씀 · 장소 루틴은 Google Play 서비스가 필요해요"), Modifier.padding(top = 8.dp), fontSize = 12.5.sp, color = c.danger)
        }
        val last = r.lastRunAt
        if (last != null || onOpenConversation != null) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(1.dp).background(c.lineSoft))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    last?.let { tr("Last run ${whenRan(it)}", "마지막 실행 ${whenRan(it)}") + (r.lastResult?.let { res -> " · $res" } ?: "") } ?: tr("Hasn't run yet", "아직 실행 안 됨"),
                    Modifier.weight(1f),
                    fontSize = 12.5.sp,
                    color = c.textSub,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onOpenConversation != null) {
                    Text(tr("Chat", "대화"), Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onOpenConversation).padding(8.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.text)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            CardButton(tr("Run now", "지금 실행"), onRunNow, height = 36.dp)
        }
    }
}

@Composable
private fun WhenLine(t: Trigger, context: Context) {
    val c = pu
    if (t is Trigger.Time) {
        Row {
            Text(RoutineText.clock(t.hour, t.minute), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub, style = MonoStyle)
            Text(" · ${RoutineText.days(t.days)}", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
        }
    } else {
        Text(RoutineText.whenText(t) { appLabel(context, it) }, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.textSub)
    }
}

@Composable
private fun TemplateRow(t: RoutineTemplate, onClick: () -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.sunken)
            .border(1.dp, c.line, shape)
            .clickable(role = Role.Button, onClickLabel = tr("Make from template", "템플릿으로 만들기"), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(t.description, fontSize = 12.5.sp, color = c.textSub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(tr("Add", "추가"), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.text)
    }
}

/** "오늘 08:00", "11월 2일 08:00". */
private fun whenRan(at: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.now().atZone(zone).toLocalDate()
    val clock = RoutineText.clock(t.hour, t.minute)
    return when (t.toLocalDate()) {
        today -> tr("Today $clock", "오늘 $clock")
        today.minusDays(1) -> tr("Yesterday $clock", "어제 $clock")
        else -> tr("${t.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${t.dayOfMonth} $clock", "${t.monthValue}월 ${t.dayOfMonth}일 $clock")
    }
}

/** The app's name for [pkg], or null when it is not installed. */
internal fun appLabel(context: Context, pkg: String): String? = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) {
    null
}

/** Launchable apps as (label, package), by label. */
internal fun launchableApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, 0)
        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        .filter { it.second != context.packageName }
        .distinctBy { it.second }
        .sortedBy { it.first }
}
