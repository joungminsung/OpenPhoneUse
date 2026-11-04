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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.phoneuse.R
import com.phoneuse.app.AppGraph
import com.phoneuse.i18n.tr
import com.phoneuse.proactive.ProactiveSystem
import com.phoneuse.routine.RoutineSystem
import kotlinx.coroutines.CancellationException
import java.time.ZonedDateTime

/**
 * A search-bar widget: the bar opens the quick-ask window with the keyboard up, the mic starts voice input, and
 * up to three chips guess what the user would ask now ([AskChips]: their own repeated asks, a routine coming up,
 * the time of day). A chip starts its prompt in the chat like a card button, so any send still asks first.
 */
class AskBarWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(BAR, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = loadChips(context)
        provideContent {
            var chips by remember { mutableStateOf(initial) }
            val running by AppGraph.runner.running.collectAsState()
            // A finished run may have added an ask: guess again (cheap and local).
            LaunchedEffect(running) { if (!running) chips = loadChips(context) }
            AskBar(chips)
        }
    }

    companion object {
        private val BAR = DpSize(200.dp, 60.dp)
        private val MEDIUM = DpSize(250.dp, 110.dp)
        private val LARGE = DpSize(300.dp, 170.dp)

        suspend fun loadChips(context: Context): List<AskChip> = try {
            val asks = ProactiveSystem.get(context).recentAsks()
            AskChips.pick(ZonedDateTime.now(), asks, RoutineSystem.get(context).store.all())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AskChips.timeOfDay(ZonedDateTime.now().hour)
        }
    }
}

@Composable
private fun AskBar(chips: List<AskChip>) {
    val context = LocalContext.current
    val size = LocalSize.current
    WidgetRoot(padding = if (size.height < 100.dp) 6.dp else 12.dp) {
        if (size.height >= 160.dp) {
            Text(
                WidgetText.greeting(ZonedDateTime.now().hour),
                style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            WidgetSub(tr("What can I do for you?", "무엇을 도와드릴까요?"))
            VSpace(10.dp)
        }
        SearchBar(context)
        if (size.height >= 100.dp && chips.isNotEmpty()) {
            VSpace(8.dp)
            val shown = chips.take(if (size.width < 260.dp) 2 else 3)
            Row(GlanceModifier.fillMaxWidth()) {
                shown.forEachIndexed { i, chip ->
                    if (i > 0) HSpace(6.dp)
                    WidgetPill(
                        chip.label,
                        actionStartActivity(WidgetLinks.prompt(context, WidgetPrompt(chip.prompt, typed = false))),
                        GlanceModifier.defaultWeight(),
                        icon = if (i == 0) R.drawable.ic_widget_spark else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBar(context: Context) {
    Row(
        GlanceModifier.fillMaxWidth().height(48.dp).cornerRadius(24.dp).background(GlanceTheme.colors.surface)
            .clickable(actionStartActivity(WidgetLinks.quickAsk(context, QuickAskMode.ASK)))
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WidgetIcon(R.drawable.ic_widget_search, GlanceTheme.colors.onSurfaceVariant, 20.dp)
        HSpace(10.dp)
        Text(
            tr("Ask ${WidgetText.appName}", "${WidgetText.appName}에게 물어보기"),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 15.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        Box(
            GlanceModifier.size(36.dp).cornerRadius(18.dp).background(GlanceTheme.colors.primary)
                .clickable(actionStartActivity(WidgetLinks.quickAsk(context, QuickAskMode.VOICE))),
            contentAlignment = Alignment.Center,
        ) { WidgetIcon(R.drawable.ic_widget_mic, GlanceTheme.colors.onPrimary, 20.dp, tr("Voice", "음성")) }
    }
}

class AskBarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AskBarWidget()
}
