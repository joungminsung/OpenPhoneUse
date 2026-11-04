package com.phoneuse.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.phoneuse.R
import com.phoneuse.app.AppGraph
import com.phoneuse.i18n.tr
import com.phoneuse.memory.Confidence
import com.phoneuse.memory.Memory
import kotlinx.coroutines.flow.catch

/**
 * "Remember this": a one-tap capture into the app's memory (the quick-ask window in remember mode, the user's own
 * words, stored as sure), with how many things are remembered and, when there is room, the newest ones.
 */
class MemoryWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val memories by remember { AppGraph.memories.observe().catch { emit(emptyList()) } }.collectAsState(null)
            val on by remember { AppGraph.settings.memoryEnabled.catch { emit(true) } }.collectAsState(true)
            MemoryContent(memories, on)
        }
    }

    companion object {
        private val SMALL = DpSize(160.dp, 100.dp)
        private val MEDIUM = DpSize(250.dp, 170.dp)

        /** The newest memories the user is sure of (guesses wait for confirmation in the app). */
        fun newest(memories: List<Memory>, count: Int): List<Memory> =
            memories.filter { it.confidence == Confidence.SURE }.sortedByDescending { it.createdAt }.take(count)
    }
}

@Composable
private fun MemoryContent(memories: List<Memory>?, on: Boolean) {
    val context = LocalContext.current
    val size = LocalSize.current
    val count = memories?.size
    WidgetRoot(padding = 12.dp) {
        WidgetHeader(R.drawable.ic_widget_bulb, tr("Memory", "기억"), count?.let { tr("$it saved", "${it}개") })
        if (!on) {
            WidgetSub(tr("Memory is off in Settings.", "설정에서 기억이 꺼져 있어요."), maxLines = 2)
            VSpace(8.dp)
            WidgetPill(tr("Open app", "앱 열기"), actionStartActivity(WidgetLinks.open(context, null)), icon = R.drawable.ic_widget_open)
        } else {
            if (size.height >= 160.dp) {
                val newest = MemoryWidget.newest(memories.orEmpty(), 3)
                if (newest.isEmpty()) {
                    WidgetSub(tr("Things you ask me to remember show up here.", "기억해 달라고 한 것이 여기에 보여요."), maxLines = 2)
                }
                newest.forEach { m ->
                    Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        Text("•  ", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp))
                        Text(m.text, style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 13.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                    }
                }
                VSpace(6.dp)
            } else {
                WidgetSub(tr("Jot down anything to keep.", "기억할 것을 바로 적어 두세요."), maxLines = 2)
                VSpace(8.dp)
            }
            WidgetPill(
                tr("Remember this", "기억해 줘"),
                actionStartActivity(WidgetLinks.quickAsk(context, QuickAskMode.REMEMBER)),
                GlanceModifier.fillMaxWidth(),
                icon = R.drawable.ic_widget_add,
                strong = true,
            )
        }
    }
}

class MemoryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemoryWidget()
}
