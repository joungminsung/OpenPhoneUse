package com.phoneuse.widget

import android.content.Context
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
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.phoneuse.R
import com.phoneuse.i18n.tr
import com.phoneuse.proactive.BriefingText
import java.time.ZonedDateTime

/**
 * An AI paragraph about the rest of the day (next event, reminders, alarm, parcels, replies), cached and rewritten
 * in the background by [BriefWorker]: every three hours while placed, when stale on display, and on the refresh
 * button. The widget never waits for the model; it shows the cached text and when it was written.
 */
class TodayBriefWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = BriefStore.get(context)
        if (store.stale(System.currentTimeMillis()) && !store.state.value.refreshing) BriefWorker.refresh(context)
        provideContent {
            val state by store.state.collectAsState()
            Brief(state)
        }
    }

    companion object {
        private val SMALL = DpSize(180.dp, 100.dp)
        private val MEDIUM = DpSize(250.dp, 150.dp)
        private val LARGE = DpSize(300.dp, 220.dp)
    }
}

@Composable
private fun Brief(state: BriefState) {
    val context = LocalContext.current
    val size = LocalSize.current
    val now = System.currentTimeMillis()
    val stamp = when {
        state.refreshing -> tr("Updating…", "새로 쓰는 중…")
        state.at > 0 -> WidgetText.ago(now, state.at)
        else -> null
    }
    val large = size.height >= 210.dp
    val roomy = size.height >= 140.dp
    WidgetRoot(padding = if (large) 20.dp else 16.dp, onClick = actionStartActivity(WidgetLinks.open(context, null))) {
        // The date in mono with when the brief was written; on a short widget the refresh button sits beside it.
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WidgetMono(
                WidgetText.date(ZonedDateTime.now()) + (stamp?.let { " · $it" } ?: ""),
                GlanceTheme.colors.onSurfaceVariant,
                12.5f,
                GlanceModifier.defaultWeight(),
            )
            if (!roomy) {
                HSpace(8.dp)
                RefreshButton(state.refreshing)
            }
        }
        VSpace(if (roomy) 8.dp else 4.dp)
        Text(
            state.text.ifEmpty { tr("Writing today's brief…", "오늘 브리핑을 쓰고 있어요…") },
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (large) 17.sp else 15.sp, fontWeight = FontWeight.Medium),
            maxLines = when {
                large -> 5
                roomy -> 2
                else -> 1
            },
            modifier = GlanceModifier.defaultWeight(),
        )
        if (roomy) {
            VSpace(6.dp)
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WidgetSub(if (state.ai) tr("Written by AI from your phone", "폰 정보로 AI가 정리") else tr("From your phone", "폰 정보로 정리"), modifier = GlanceModifier.defaultWeight(), size = 12.5f)
                HSpace(8.dp)
                WidgetPill(
                    tr("More", "자세히"),
                    actionStartActivity(WidgetLinks.prompt(context, WidgetPrompt(BriefingText.TODAY_PROMPT, typed = false))),
                    icon = R.drawable.ic_widget_open,
                )
                HSpace(8.dp)
                RefreshButton(state.refreshing)
            }
        }
    }
}

/** The round refresh button; a spinner while the brief is being rewritten. */
@Composable
private fun RefreshButton(refreshing: Boolean) {
    val context = LocalContext.current
    Box(
        GlanceModifier.size(44.dp).cornerRadius(22.dp).background(GlanceTheme.colors.surfaceVariant)
            .clickable(WidgetActions.refreshBrief(context)),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) {
            CircularProgressIndicator(GlanceModifier.size(16.dp), color = GlanceTheme.colors.primary)
        } else {
            WidgetIcon(R.drawable.ic_widget_refresh, GlanceTheme.colors.onSurface, 18.dp, tr("Refresh", "새로고침"))
        }
    }
}

class TodayBriefWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayBriefWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        BriefWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        BriefWorker.cancel(context)
    }
}
