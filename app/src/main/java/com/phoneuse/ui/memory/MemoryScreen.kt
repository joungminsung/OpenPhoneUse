package com.phoneuse.ui.memory

import com.phoneuse.i18n.tr
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.memory.Confidence
import com.phoneuse.memory.Memory
import com.phoneuse.memory.MemoryKind
import com.phoneuse.memory.MemoryRanker
import com.phoneuse.memory.MemoryStore
import com.phoneuse.memory.MemoryText
import com.phoneuse.memory.NewMemory
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PlexSans
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Texts of the memory screen; pure so they can be tested. */
object MemoryLabels {
    /** "사람 · 오늘 ‘엄마 생신 선물’ 대화에서", "장소 · 10.12 대화에서", "나 · 직접 알려줌", or "… · 확실하지 않아요, 맞나요?". */
    fun sub(m: Memory, today: LocalDate, zone: ZoneId): String {
        val where = when {
            m.confidence == Confidence.GUESS -> tr("Not sure, is this right?", "확실하지 않아요, 맞나요?")
            m.conversationId == null -> tr("Told by you", "직접 알려줌")
            else -> {
                val day = Instant.ofEpochMilli(m.createdAt).atZone(zone).toLocalDate()
                if (day == today) {
                    m.origin?.takeIf { it.isNotBlank() }?.let { tr("From ‘$it’ chat today", "오늘 ‘$it’ 대화에서") } ?: tr("From a chat today", "오늘 대화에서")
                } else {
                    tr("From a chat on ${day.monthValue}/${day.dayOfMonth}", "${day.monthValue}.${day.dayOfMonth} 대화에서")
                }
            }
        }
        val extra = listOfNotNull(m.date?.takeIf { it.isNotBlank() }, m.due?.takeIf { it.isNotBlank() }?.let { tr("Due $it", "기한 $it") })
        return (listOf(m.kind.label) + extra + where).joinToString(" · ")
    }

    /** "이번 주 새로 n개": memories created in the last 7 days. */
    fun newThisWeek(memories: List<Memory>, nowMs: Long): Int = memories.count { nowMs - it.createdAt < WEEK_MS }

    /** The visible memories for a tab ([kind] null = 전체) and a search [query], best match first. */
    fun filter(memories: List<Memory>, kind: MemoryKind?, query: String): List<Memory> {
        val inTab = if (kind == null) memories else memories.filter { it.kind == kind }
        if (MemoryText.terms(query).isEmpty()) return inTab
        return MemoryRanker.rank(query, inTab).filter { it.score > 0 }.map { it.memory }
    }

    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
}

/** Tabs: 전체, the four everyday kinds, and the others once something of that kind exists. */
private val BASE_TABS = listOf(MemoryKind.ME, MemoryKind.PERSON, MemoryKind.PLACE, MemoryKind.HABIT)

/**
 * Board Memory (and the detail pane of board UnfoldedSettings when [wide]): how many memories there are,
 * search, kind tabs, each memory with fix/delete, "맞나요?" for guesses, adding one by hand, and turning
 * memory off.
 */
@Composable
fun MemoryScreen(
    store: MemoryStore,
    memoryOn: Boolean,
    onMemoryOn: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val c = pu
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val memories by remember(store) { store.observe() }.collectAsState(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf<MemoryKind?>(null) }
    var editing by remember { mutableStateOf<Memory?>(null) }
    var adding by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(memories) { System.currentTimeMillis() }
    val today = remember(now) { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }
    val shown = remember(memories, tab, query) { MemoryLabels.filter(memories, tab, query) }

    val snackbar = remember { SnackbarHostState() }

    // Every store call from here: a failure shows a toast instead of crashing the app.
    fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, tr("Couldn't change the memory", "기억을 바꾸지 못했어요"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // "지웠어요 [되돌리기]": the deleted memory comes back as it was.
    fun delete(m: Memory) {
        act {
            store.delete(m.id)
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(tr("Deleted · ${m.text}", "지웠어요 · ${m.text}"), actionLabel = tr("Undo", "되돌리기"), duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) store.restore(m)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
    ) {
        if (!wide) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) IconBtn(PuIcons.Back, tr("Back", "뒤로"), onBack) else Spacer(Modifier.width(44.dp))
                Text(tr("Memory", "메모리"), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
                IconBtn(PuIcons.Plus, tr("Add memory", "기억 추가"), { adding = true })
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = if (wide) PaddingValues(horizontal = 28.dp, vertical = 22.dp) else PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        ) {
            // No list spacing: the memory rows form one card; the items above it space themselves.
            item(key = "head") {
                Header(memories.size, MemoryLabels.newThisWeek(memories, now), memoryOn, wide, onMemoryOn, onAdd = { adding = true })
            }
            if (!memoryOn) {
                item(key = "off") {
                    Text(
                        tr("Memory is off. It isn't used in answers and nothing new is saved.", "기억이 꺼져 있어요. 답할 때 쓰지 않고, 새로 저장하지도 않아요."),
                        Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.sunken).padding(horizontal = 12.dp, vertical = 10.dp),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = c.text2,
                    )
                }
            }
            item(key = "search") {
                Box(Modifier.padding(bottom = 12.dp)) {
                    SearchField(query, { query = it }, if (wide) tr("Search memories", "기억 검색") else tr("Search memories · e.g. Mom", "기억 검색 · 예: 엄마"))
                }
            }
            item(key = "tabs") {
                val counts = memories.groupingBy { it.kind }.eachCount()
                val kinds = BASE_TABS + MemoryKind.entries.filter { it !in BASE_TABS && (counts[it] ?: 0) > 0 }
                Row(Modifier.padding(bottom = 12.dp).fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tab(tr("All", "전체"), null, tab == null) { tab = null }
                    for (k in kinds) Tab(k.label, counts[k]?.takeIf { it > 0 }, tab == k) { tab = k }
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        when {
                            memories.isEmpty() -> tr("Nothing remembered yet. Tell me in a chat or add something yourself.", "아직 기억하고 있는 게 없어요. 대화에서 알려주거나 직접 추가해 보세요.")
                            query.isNotBlank() -> tr("No memories match ‘$query’.", "‘$query’에 맞는 기억이 없어요.")
                            else -> tr("No memories in this category yet.", "이 분류에는 아직 기억이 없어요.")
                        },
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        fontSize = 14.sp,
                        color = c.textSub,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                // One rounded card, rows divided by hairlines (board .m rows).
                itemsIndexed(shown, key = { _, m -> m.id }) { i, m ->
                    MemoryRow(
                        m,
                        MemoryLabels.sub(m, today, zone),
                        first = i == 0,
                        last = i == shown.lastIndex,
                        onEdit = { editing = m },
                        onDelete = { delete(m) },
                        onConfirm = { act { store.confirm(m.id) } },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.fillMaxWidth())
    }

    if (adding || editing != null) {
        val target = editing
        MemoryEditDialog(
            title = if (target == null) tr("Add memory", "기억 추가") else tr("Edit memory", "기억 고치기"),
            initialText = target?.text ?: "",
            initialKind = target?.kind ?: tab ?: MemoryKind.ME,
            onSave = { text, kind ->
                // An edit confirms the memory (it is no longer a guess) and merges into a same-text one.
                act {
                    if (target == null) store.add(NewMemory(text, kind)) else store.update(target.id, text, kind)
                }
                adding = false
                editing = null
            },
            onDismiss = {
                adding = false
                editing = null
            },
        )
    }
}

@Composable
private fun Header(count: Int, newThisWeek: Int, memoryOn: Boolean, wide: Boolean, onMemoryOn: (Boolean) -> Unit, onAdd: () -> Unit) {
    val c = pu
    Row(Modifier.fillMaxWidth().padding(start = if (wide) 0.dp else 4.dp, end = if (wide) 0.dp else 4.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (wide) {
                Text(tr("Memory", "메모리"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Memories ", "기억하고 있는 것 "), fontSize = 13.sp, color = c.textSub)
                    Text("$count", style = MonoStyle, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.text)
                    Text(tr(" · $newThisWeek new this week", " · 이번 주 새로 ${newThisWeek}개"), fontSize = 13.sp, color = c.textSub)
                }
            } else {
                Text("$count", style = MonoStyle, fontSize = 30.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp, color = c.text)
                Text(tr("Memories · $newThisWeek new this week", "기억하고 있는 것 · 이번 주 새로 ${newThisWeek}개"), fontSize = 13.sp, color = c.textSub)
            }
        }
        SmallButton(if (memoryOn) tr("Turn memory off", "기억 끄기") else tr("Turn memory on", "기억 켜기"), primary = false) { onMemoryOn(!memoryOn) }
        if (wide) {
            Spacer(Modifier.width(6.dp))
            SmallButton(tr("Add manually", "직접 추가"), primary = true, onClick = onAdd)
        }
    }
}

@Composable
private fun SmallButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .height(38.dp)
            .clip(shape)
            .background(if (primary) c.primary else c.surface)
            .then(if (primary) Modifier else Modifier.border(1.dp, c.inputBorder, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            color = if (primary) c.onPrimary else c.text,
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val c = pu
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = tr("Search memories", "기억 검색") },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = c.textSub)
        BasicTextField(
            value,
            onChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = PlexSans, fontSize = 15.sp, color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Tab(label: String, count: Int?, on: Boolean, onClick: () -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(17.dp)
    Row(
        Modifier
            .height(34.dp)
            .clip(shape)
            .background(if (on) c.primary else c.surface)
            .border(1.dp, if (on) c.primary else c.inputBorder, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (on) c.onPrimary else c.text
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg)
        if (count != null) {
            Spacer(Modifier.width(4.dp))
            Text("$count", style = MonoStyle, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg)
        }
    }
}

@Composable
private fun MemoryRow(
    m: Memory,
    sub: String,
    first: Boolean,
    last: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = pu
    val r = 18.dp
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
    val guess = m.confidence == Confidence.GUESS
    // Each row draws its slice of the one card (rounded at the ends) and the hairline above it.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (guess) c.guessBg else c.surface),
    ) {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(m.text, fontSize = 14.5.sp, lineHeight = 21.sp, color = c.text)
                Text(sub, Modifier.padding(top = 2.dp), fontSize = 12.sp, color = c.textSub)
                if (guess) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TinyButton(tr("Correct", "맞아요"), primary = true, onClick = onConfirm)
                        TinyButton(tr("Delete", "지우기"), primary = false, onClick = onDelete)
                    }
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconBtn(PuIcons.More, tr("Edit or delete this memory", "이 기억 고치거나 지우기"), { menu = true }, Modifier.size(40.dp), tint = c.textSub, iconSize = 18.dp)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                    DropdownMenuItem(text = { Text(tr("Edit", "고치기"), color = c.text) }, onClick = {
                        menu = false
                        onEdit()
                    })
                    DropdownMenuItem(text = { Text(tr("Delete", "지우기"), color = c.danger) }, onClick = {
                        menu = false
                        onDelete()
                    })
                }
            }
        }
    }
}

@Composable
private fun TinyButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .height(32.dp)
            .clip(shape)
            .background(if (primary) c.primary else c.surface)
            .then(if (primary) Modifier else Modifier.border(1.dp, c.inputBorder, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.5.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium, color = if (primary) c.onPrimary else c.text)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryEditDialog(
    title: String,
    initialText: String,
    initialKind: MemoryKind,
    onSave: (String, MemoryKind) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = pu
    var text by rememberSaveable { mutableStateOf(initialText) }
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val shape = RoundedCornerShape(14.dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp)
                        .clip(shape)
                        .border(1.dp, c.inputBorder, shape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (text.isEmpty()) Text(tr("e.g. Mom's birthday is Nov 20", "예: 엄마 생신은 11월 20일"), fontSize = 15.sp, color = c.textSub)
                    BasicTextField(
                        text,
                        { text = it.take(MemoryStore.MAX_TEXT) },
                        textStyle = TextStyle(fontFamily = PlexSans, fontSize = 15.sp, lineHeight = 22.sp, color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = tr("Memory text", "기억 내용") },
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in MemoryKind.entries.filter { it != MemoryKind.APP_TIP || initialKind == MemoryKind.APP_TIP }) {
                        Tab(k.label, null, kind == k) { kind = k }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, kind) }, enabled = text.isNotBlank()) {
                Text(tr("Save", "저장"), color = if (text.isNotBlank()) c.text else c.textSub, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "취소"), color = c.text, fontSize = 15.sp) }
        },
    )
}
