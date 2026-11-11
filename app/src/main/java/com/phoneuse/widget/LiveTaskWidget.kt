package com.phoneuse.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.phoneuse.R
import com.phoneuse.app.AppGraph
import com.phoneuse.assist.recentConversation
import com.phoneuse.chat.MessageRole
import com.phoneuse.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The current task, live: its title, the step the agent is on and a progress bar, with Stop; when idle, the last
 * result's first line with Open and Follow up. [WidgetSync] pushes runner changes here (throttled), and while the
 * widget's session is up it follows [WidgetSync.live] itself.
 */
class LiveTaskWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = lastResult(AppGraph.runner.activeConversation.value)
        provideContent {
            val snap by remember { WidgetSync.live() }.collectAsState(WidgetSync.snapshot())
            var last by remember { mutableStateOf(initial) }
            LaunchedEffect(snap.running, snap.conversation) { if (!snap.running) last = lastResult(snap.conversation) }
            val title = snap.task.ifBlank { last?.title.orEmpty() }
            val view = LiveTask.map(snap.running, title, snap.steps, snap.streaming, snap.awaitingConfirm, last?.reply, last?.conversationId)
            Live(view)
        }
    }

    companion object {
        private val SMALL = DpSize(180.dp, 70.dp)
        private val MEDIUM = DpSize(250.dp, 140.dp)

        /** The last run's conversation, or (after a restart) the most recently updated one, with its last reply. */
        suspend fun lastResult(conversation: Long?): LastResult? = try {
            val all = AppGraph.chats.conversations().first()
            val chat = if (conversation != null) all.firstOrNull { it.id == conversation } else recentConversation(all)
            chat?.let { c ->
                AppGraph.chats.messages(c.id).first().lastOrNull { it.role == MessageRole.ASSISTANT }?.text
                    ?.let { LastResult(c.id, c.title, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/** The conversation the idle widget offers: its id, title and last reply. */
data class LastResult(val conversationId: Long, val title: String, val reply: String)

@Composable
private fun Live(view: LiveView) {
    val context = LocalContext.current
    val compact = LocalSize.current.height < 130.dp
    when (view) {
        is LiveView.Running -> WidgetRoot(padding = if (compact) 10.dp else 12.dp, onClick = actionStartActivity(WidgetLinks.open(context, null))) {
            // The accent dot, the task and its step over a thin bar, and Stop.
            Row(GlanceModifier.fillMaxSize().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                WidgetDot()
                HSpace(14.dp)
                Column(GlanceModifier.defaultWeight()) {
                    Text(view.title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = if (compact) 1 else 2)
                    VSpace(if (compact) 2.dp else 4.dp)
                    val count = tr("Step ${view.steps}", "${view.steps}단계")
                    if (view.needsYou) {
                        Text(
                            tr("Needs your OK", "확인이 필요해요") + " · " + count,
                            style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1,
                        )
                    } else {
                        WidgetSub(view.step.ifEmpty { tr("Thinking…", "생각하는 중…") } + " · " + count, maxLines = if (compact) 1 else 2)
                    }
                    VSpace(if (compact) 6.dp else 8.dp)
                    LinearProgressIndicator(
                        progress = view.progress,
                        modifier = GlanceModifier.fillMaxWidth().height(3.dp).cornerRadius(2.dp),
                        color = GlanceTheme.colors.onSurface,
                        backgroundColor = GlanceTheme.colors.secondaryContainer,
                    )
                }
                HSpace(14.dp)
                WidgetPill(tr("Stop", "중단"), WidgetActions.stop(context), danger = true)
            }
        }
        is LiveView.Idle -> WidgetRoot(padding = if (compact) 14.dp else 16.dp) {
            WidgetLabel(tr("Last task", "마지막 작업"))
            if (view.title.isNotEmpty() && !compact) {
                VSpace(6.dp)
                Text(view.title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            }
            VSpace(if (compact) 2.dp else 4.dp)
            Text(
                view.result,
                style = TextStyle(color = if (compact) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant, fontSize = if (compact) 14.sp else 13.sp),
                maxLines = if (compact) 1 else 2,
                modifier = GlanceModifier.clickable(actionStartActivity(WidgetLinks.open(context, view.conversationId))),
            )
            if (!compact) {
                Spacer(GlanceModifier.defaultWeight())
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    WidgetPill(tr("Open", "열기"), actionStartActivity(WidgetLinks.open(context, view.conversationId)), GlanceModifier.defaultWeight(), R.drawable.ic_widget_open)
                    HSpace(8.dp)
                    WidgetPill(
                        tr("Follow up", "이어서 묻기"),
                        actionStartActivity(WidgetLinks.quickAsk(context, QuickAskMode.FOLLOW_UP, view.conversationId)),
                        GlanceModifier.defaultWeight(),
                        R.drawable.ic_widget_reply,
                        strong = true,
                    )
                }
            }
        }
        LiveView.Empty -> WidgetRoot(padding = if (compact) 14.dp else 16.dp, onClick = actionStartActivity(WidgetLinks.quickAsk(context, QuickAskMode.ASK))) {
            WidgetLabel(WidgetText.appName)
            VSpace(if (compact) 2.dp else 6.dp)
            Text(tr("Nothing running", "진행 중인 작업 없음"), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            if (!compact) {
                VSpace(4.dp)
                WidgetSub(tr("Tasks you start show up here, live.", "시작한 작업이 여기에 실시간으로 보여요."), maxLines = 2)
                Spacer(GlanceModifier.defaultWeight())
                WidgetPill(tr("Ask something", "물어보기"), actionStartActivity(WidgetLinks.quickAsk(context, QuickAskMode.ASK)), icon = R.drawable.ic_widget_search, strong = true)
            }
        }
    }
}

class LiveTaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LiveTaskWidget()
}
