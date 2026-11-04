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
import androidx.glance.layout.Row
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
        is LiveView.Running -> WidgetRoot(onClick = actionStartActivity(WidgetLinks.open(context, null))) {
            WidgetHeader(
                R.drawable.ic_widget_spark,
                if (view.needsYou) tr("Needs your OK", "확인이 필요해요") else tr("Working on it", "작업 중"),
                tr("Step ${view.steps}", "${view.steps}단계"),
            )
            Text(view.title, style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = if (compact) 1 else 2)
            if (!compact) {
                VSpace(2.dp)
                WidgetSub(view.step.ifEmpty { tr("Thinking…", "생각하는 중…") }, maxLines = 2)
            }
            VSpace(8.dp)
            LinearProgressIndicator(
                progress = view.progress,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
                color = if (view.needsYou) GlanceTheme.colors.tertiary else GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.secondaryContainer,
            )
            if (!compact) {
                VSpace(10.dp)
                Row(GlanceModifier.fillMaxWidth()) {
                    WidgetPill(tr("Open", "열기"), actionStartActivity(WidgetLinks.open(context, null)), GlanceModifier.defaultWeight(), R.drawable.ic_widget_open)
                    HSpace(8.dp)
                    WidgetPill(tr("Stop", "중단"), WidgetActions.stop(context), GlanceModifier.defaultWeight(), R.drawable.ic_widget_stop, strong = true)
                }
            }
        }
        is LiveView.Idle -> WidgetRoot {
            WidgetHeader(R.drawable.ic_widget_check, tr("Last task", "마지막 작업"))
            if (view.title.isNotEmpty() && !compact) WidgetSub(view.title)
            Text(
                view.result,
                style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 14.sp),
                maxLines = if (compact) 1 else 3,
                modifier = GlanceModifier.clickable(actionStartActivity(WidgetLinks.open(context, view.conversationId))),
            )
            if (!compact) {
                VSpace(10.dp)
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
        LiveView.Empty -> WidgetRoot(onClick = actionStartActivity(WidgetLinks.quickAsk(context, QuickAskMode.ASK))) {
            WidgetHeader(R.drawable.ic_widget_spark, WidgetText.appName)
            Text(tr("Nothing running", "진행 중인 작업 없음"), style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            if (!compact) {
                WidgetSub(tr("Tasks you start show up here, live.", "시작한 작업이 여기에 실시간으로 보여요."), maxLines = 2)
                VSpace(10.dp)
                WidgetPill(tr("Ask something", "물어보기"), actionStartActivity(WidgetLinks.quickAsk(context, QuickAskMode.ASK)), icon = R.drawable.ic_widget_search, strong = true)
            }
        }
    }
}

class LiveTaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LiveTaskWidget()
}
