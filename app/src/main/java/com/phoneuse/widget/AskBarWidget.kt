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
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
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
    if (size.height < 100.dp) {
        // One row: the widget is the bar itself, a white pill.
        WidgetRoot(padding = 0.dp, radius = size.height / 2) { SearchBar(context, alone = true) }
        return
    }
    WidgetRoot(padding = 16.dp) {
        if (size.height >= 160.dp) {
            Text(
                WidgetText.greeting(ZonedDateTime.now().hour),
                style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            WidgetSub(tr("What can I do for you?", "무엇을 도와드릴까요?"))
            VSpace(10.dp)
        }
        SearchBar(context, alone = false)
        if (chips.isNotEmpty()) {
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

/**
 * The bar: the placeholder opens the quick-ask window, the round ink mic starts voice. When [alone] the bar is the
 * whole widget (on the card itself, with the design's 56dp mic); otherwise it sits in a taller card on the grey fill.
 */
@Composable
private fun SearchBar(context: Context, alone: Boolean) {
    val base = if (alone) {
        GlanceModifier.fillMaxSize().padding(start = 22.dp, end = 10.dp)
    } else {
        GlanceModifier.fillMaxWidth().height(56.dp).cornerRadius(28.dp).background(GlanceTheme.colors.surfaceVariant)
            .padding(start = 18.dp, end = 6.dp)
    }
    val mic = if (alone) 56.dp else 44.dp
    Row(
        base.clickable(actionStartActivity(WidgetLinks.quickAsk(context, QuickAskMode.ASK))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tr("Ask me anything", "무엇이든 시켜보세요"),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        HSpace(10.dp)
        WidgetRoundButton(
            R.drawable.ic_widget_mic,
            actionStartActivity(WidgetLinks.quickAsk(context, QuickAskMode.VOICE)),
            tr("Voice", "음성"),
            size = mic,
            glyph = 22.dp,
            strong = true,
        )
    }
}

class AskBarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AskBarWidget()
}
