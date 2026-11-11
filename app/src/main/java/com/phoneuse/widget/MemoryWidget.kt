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
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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
    val count = memories?.size
    val remember = actionStartActivity(WidgetLinks.quickAsk(context, QuickAskMode.REMEMBER))
    // While memory is on, the whole card is the capture button too: a tap anywhere jots something down.
    WidgetRoot(padding = 16.dp, onClick = if (on) remember else null) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) { WidgetLabel(tr("Memory", "기억")) }
            if (on) {
                HSpace(8.dp)
                WidgetRoundButton(R.drawable.ic_widget_add, remember, tr("Remember this", "기억해 줘"), strong = true)
            }
        }
        VSpace(4.dp)
        if (!on) {
            WidgetSub(tr("Memory is off in Settings.", "설정에서 기억이 꺼져 있어요."), maxLines = 2)
            Spacer(GlanceModifier.defaultWeight())
            WidgetPill(tr("Open app", "앱 열기"), actionStartActivity(WidgetLinks.open(context, null)), icon = R.drawable.ic_widget_open)
        } else {
            // The two newest memories take what room the cell has; a short cell clips them and keeps the count.
            Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
                val newest = MemoryWidget.newest(memories.orEmpty(), 2)
                if (newest.isEmpty()) {
                    WidgetSub(tr("Jot down anything to keep.", "기억할 것을 바로 적어 두세요."), maxLines = 2)
                }
                newest.forEach { m ->
                    Text(m.text, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.5.sp), maxLines = 1, modifier = GlanceModifier.padding(bottom = 4.dp))
                }
            }
            if (count != null) WidgetSub(tr("$count saved", "${count}개 저장됨"), size = 12.5f)
        }
    }
}

class MemoryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemoryWidget()
}
