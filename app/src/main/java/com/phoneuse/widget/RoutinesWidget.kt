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
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.phoneuse.R
import com.phoneuse.i18n.tr
import com.phoneuse.routine.RoutineSystem
import java.time.ZonedDateTime

/**
 * The user's routines with their trigger and next scheduled run, each with a run button that does what the
 * routines screen's "지금 실행" does (its own conditions and confirmations apply).
 */
class RoutinesWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = RoutineSystem.get(context).store
        provideContent {
            val routines by store.routines.collectAsState()
            Routines(RoutineRows.rows(routines, ZonedDateTime.now()))
        }
    }

    companion object {
        private val SMALL = DpSize(180.dp, 110.dp)
        private val MEDIUM = DpSize(260.dp, 180.dp)
    }
}

@Composable
private fun Routines(rows: List<RoutineRow>) {
    val context = LocalContext.current
    val wide = LocalSize.current.width >= 240.dp
    val active = rows.count { it.enabled }
    WidgetRoot(padding = 12.dp) {
        Column(GlanceModifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
            WidgetLabel(tr("Routines", "루틴"), if (rows.isEmpty()) null else tr("$active on", "${active}개 켜짐"))
        }
        if (rows.isEmpty()) {
            Column(GlanceModifier.fillMaxWidth().defaultWeight().padding(start = 4.dp)) {
                VSpace(4.dp)
                Text(tr("No routines yet", "아직 루틴이 없어요"), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.Medium))
                WidgetSub(tr("Say \"every day at 7, brief me\" to make one.", "\"매일 7시에 브리핑해줘\"처럼 말해서 만들어요."), maxLines = 2)
                Spacer(GlanceModifier.defaultWeight())
                WidgetPill(tr("Make one", "만들기"), actionStartActivity(WidgetLinks.quickAsk(context, QuickAskMode.ASK)), icon = R.drawable.ic_widget_add, strong = true)
            }
        } else LazyColumn {
            items(rows, itemId = { it.id }) { row ->
                Column(GlanceModifier.fillMaxWidth()) {
                    RoutineLine(context, row, wide)
                }
            }
        }
    }
}

/** A routine: its name over a mono "when" line, and the round run button. */
@Composable
private fun RoutineLine(context: Context, row: RoutineRow, wide: Boolean) {
    Row(
        GlanceModifier.fillMaxWidth().cornerRadius(16.dp)
            .clickable(actionStartActivity(WidgetLinks.open(context, null)))
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(GlanceModifier.defaultWeight()) {
            Text(
                row.title,
                style = TextStyle(
                    color = if (row.enabled) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            val detail = when {
                !row.enabled -> tr("Off", "꺼짐") + " · " + row.whenText
                row.next != null && wide -> row.next + " · " + row.whenText
                row.next != null -> row.next
                else -> row.whenText
            }
            WidgetMono(detail, GlanceTheme.colors.onSurfaceVariant, 11.5f)
        }
        HSpace(6.dp)
        WidgetRoundButton(R.drawable.ic_widget_play, WidgetActions.runRoutine(context, row.id), tr("Run now", "지금 실행"), glyph = 16.dp)
    }
}

class RoutinesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RoutinesWidget()
}
