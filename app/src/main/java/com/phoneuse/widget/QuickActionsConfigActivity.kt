package com.phoneuse.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import com.phoneuse.app.AppGraph
import com.phoneuse.app.ThemeMode
import com.phoneuse.i18n.tr
import com.phoneuse.ui.theme.ButtonKind
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.PhoneUseTheme
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.isDark
import com.phoneuse.ui.theme.pu

/**
 * Edits the quick-actions tiles: a label, the prompt sent on a tap, and a glyph, up to [QuickTiles.MAX_TILES].
 * Opened by the launcher when a grid is placed or reconfigured, and from the widget's pencil. It only edits text;
 * nothing runs from here.
 */
class QuickActionsConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Exported for the launcher: only configure a widget that really is one of ours.
        val owner = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.packageName
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || owner != packageName) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        // Backing out of a first placement keeps the widget, with the tiles as they are.
        setResult(RESULT_OK, result())
        val store = QuickTileStore.get(this)
        setContent {
            val theme by AppGraph.themeMode.collectAsState()
            PhoneUseTheme(isDark(theme ?: ThemeMode.SYSTEM)) {
                Editor(
                    initial = store.tiles(),
                    custom = store.custom.collectAsState().value != null,
                    onSave = { tiles ->
                        store.save(tiles)
                        done()
                    },
                    onReset = {
                        store.reset()
                        done()
                    },
                )
            }
        }
    }

    private fun result() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun done() {
        setResult(RESULT_OK, result())
        finish()
    }
}

@Composable
private fun Editor(initial: List<QuickTile>, custom: Boolean, onSave: (List<QuickTile>) -> Unit, onReset: () -> Unit) {
    val c = pu
    val tiles = remember { mutableStateListOf<QuickTile>().apply { addAll(initial) } }
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().imePadding().padding(16.dp)) {
        Text(tr("Quick actions", "빠른 실행"), color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            tr(
                "Each tile sends its prompt as a new task. Sends, payments and deletions still ask you first.",
                "타일을 누르면 그 문장으로 새 작업을 시작해요. 보내기·결제·삭제는 그래도 먼저 확인해요.",
            ),
            color = c.textSub, fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(tiles) { i, tile ->
                TileEditor(
                    tile,
                    onChange = { tiles[i] = it },
                    onRemove = { tiles.removeAt(i) },
                )
            }
            if (tiles.size < QuickTiles.MAX_TILES) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, c.inputBorder, RoundedCornerShape(16.dp))
                            .clickable { tiles.add(QuickTile("", "", TileIcon.SPARK)) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(PuIcons.Plus, null, tint = c.text, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Add a tile", "타일 추가"), color = c.text, fontSize = 14.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            PuButton(tr("Reset", "기본값"), onReset, Modifier.weight(1f), kind = ButtonKind.OUTLINE, enabled = custom || tiles != QuickTiles.defaults())
            Spacer(Modifier.width(10.dp))
            PuButton(tr("Save", "저장"), { onSave(tiles.toList()) }, Modifier.weight(1f), enabled = QuickTiles.clean(tiles).isNotEmpty())
        }
    }
}

@Composable
private fun TileEditor(tile: QuickTile, onChange: (QuickTile) -> Unit, onRemove: () -> Unit) {
    val c = pu
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(tile.label, tr("Label", "이름"), QuickTiles.LABEL_CHARS, Modifier.weight(1f), bold = true) { onChange(tile.copy(label = it)) }
            IconBtn(PuIcons.Trash, tr("Remove", "삭제"), onRemove, tint = c.textSub)
        }
        Spacer(Modifier.height(6.dp))
        Field(tile.prompt, tr("What to do, e.g. Turn on Do Not Disturb", "할 일, 예: 방해 금지 모드 켜줘"), QuickTiles.PROMPT_CHARS, Modifier.fillMaxWidth()) {
            onChange(tile.copy(prompt = it))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TileIcon.entries.forEach { icon ->
                val on = icon == tile.icon
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(if (on) c.accentSoft else c.sunken)
                        .border(1.dp, if (on) c.accent else c.line, CircleShape)
                        .clickable { onChange(tile.copy(icon = icon)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(icon.drawable()), icon.wire, tint = if (on) c.accentText else c.textSub, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun Field(value: String, hint: String, max: Int, modifier: Modifier, bold: Boolean = false, onValue: (String) -> Unit) {
    val c = pu
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(c.sunken).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(hint, color = c.textSub, fontSize = 14.sp, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = { onValue(it.take(max)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = c.text, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal),
            cursorBrush = SolidColor(c.accent),
            maxLines = 3,
        )
    }
}
