package com.phoneuse.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.phoneuse.R
import com.phoneuse.i18n.tr

/**
 * A grid of the user's saved prompts ([QuickTileStore]); a tap starts the prompt in the chat like a card button, so
 * sends, payments and deletions still ask first. The pencil opens [QuickActionsConfigActivity].
 */
class QuickActionsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = QuickTileStore.get(context)
        provideContent {
            val custom by store.custom.collectAsState()
            Grid(custom ?: QuickTiles.defaults())
        }
    }

    companion object {
        private val SMALL = DpSize(150.dp, 110.dp)
        private val WIDE = DpSize(280.dp, 110.dp)
        private val TALL = DpSize(180.dp, 200.dp)
        private val LARGE = DpSize(280.dp, 200.dp)

        /** Columns and rows for a widget of [widthDp] x [heightDp]. */
        fun grid(widthDp: Float, heightDp: Float): Pair<Int, Int> = (if (widthDp >= 260f) 3 else 2) to (if (heightDp >= 190f) 2 else 1)
    }
}

@Composable
private fun Grid(tiles: List<QuickTile>) {
    val context = LocalContext.current
    val size = LocalSize.current
    val (cols, rows) = QuickActionsWidget.grid(size.width.value, size.height.value)
    val shown = tiles.take(cols * rows)
    WidgetRoot(padding = 10.dp) {
        Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            WidgetIcon(R.drawable.ic_widget_bolt, GlanceTheme.colors.primary, 16.dp)
            HSpace(6.dp)
            Text(tr("Quick actions", "빠른 실행"), style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            Box(
                GlanceModifier.size(28.dp).cornerRadius(14.dp).clickable(actionStartActivity(configIntent(context))),
                contentAlignment = Alignment.Center,
            ) { WidgetIcon(R.drawable.ic_widget_edit, GlanceTheme.colors.onSurfaceVariant, 16.dp, tr("Edit", "편집")) }
        }
        shown.chunked(cols).forEachIndexed { r, row ->
            if (r > 0) VSpace(6.dp)
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                row.forEachIndexed { c, tile ->
                    if (c > 0) HSpace(6.dp)
                    Tile(context, tile, highlight = r == 0 && c == 0, modifier = GlanceModifier.defaultWeight().fillMaxHeight())
                }
                // Keep a short last row's tiles the same width as the rows above.
                repeat(cols - row.size) {
                    HSpace(6.dp)
                    Box(GlanceModifier.defaultWeight()) {}
                }
            }
        }
    }
}

@Composable
private fun Tile(context: Context, tile: QuickTile, highlight: Boolean, modifier: GlanceModifier) {
    val bg = if (highlight) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.secondaryContainer
    val fg = if (highlight) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSecondaryContainer
    Column(
        modifier.cornerRadius(18.dp).background(bg)
            .clickable(actionStartActivity(WidgetLinks.prompt(context, WidgetPrompt(tile.prompt, typed = false))))
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            GlanceModifier.size(28.dp).cornerRadius(14.dp).background(GlanceTheme.colors.surface),
            contentAlignment = Alignment.Center,
        ) { WidgetIcon(tile.icon.drawable(), GlanceTheme.colors.primary, 16.dp) }
        VSpace(6.dp)
        Text(tile.label, style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 2)
    }
}

/** The tile editor, opened from the widget's pencil (no widget id: the tiles are shared by every grid). */
fun configIntent(context: Context): Intent =
    Intent(context, QuickActionsConfigActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

class QuickActionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickActionsWidget()
}
