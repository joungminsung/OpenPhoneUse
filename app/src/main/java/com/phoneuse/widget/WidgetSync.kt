package com.phoneuse.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.phoneuse.app.AppGraph
import com.phoneuse.routine.RoutineSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/** The runner's state as the live-task widget needs it; the streaming text is cut to its first line. */
data class LiveSnapshot(
    val running: Boolean = false,
    val task: String = "",
    val steps: List<String> = emptyList(),
    val streaming: String = "",
    val awaitingConfirm: Boolean = false,
    val conversation: Long? = null,
)

/**
 * Keeps the widgets current: runner changes go to the live-task widget at most every [LIVE_THROTTLE_MS], a run's
 * end also refreshes the ask bar (a new ask) and the memory widget, and routine edits refresh the routines widget.
 * A widget kind that is not on the home screen costs nothing but the check.
 */
object WidgetSync {
    private const val TAG = "WidgetSync"
    const val LIVE_THROTTLE_MS = 1_000L
    private const val STORE_THROTTLE_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun snapshot(): LiveSnapshot {
        val r = AppGraph.runner
        return LiveSnapshot(
            running = r.running.value,
            task = r.currentTask.value,
            steps = r.liveSteps.value.map { it.text },
            streaming = LiveTask.firstLine(r.streamingText.value),
            awaitingConfirm = r.chatGate.card.value != null,
            conversation = r.activeConversation.value,
        )
    }

    /** [snapshot] on every change, throttled: streaming text alone would redraw the widget many times a second. */
    fun live(): Flow<LiveSnapshot> {
        val r = AppGraph.runner
        return combine(r.running, r.currentTask, r.liveSteps, r.streamingText, r.chatGate.card) { running, task, steps, stream, card ->
            LiveSnapshot(running, task, steps.map { it.text }, LiveTask.firstLine(stream), card != null, r.activeConversation.value)
        }.distinctUntilChanged().throttleLatest(LIVE_THROTTLE_MS)
    }

    /** Called once from AppGraph.init, after the runner exists. */
    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            // Not dropped: after a process restart the first (idle) snapshot clears a stale "Working on it" widget.
            live().collect { update(app, LiveTaskWidget()) }
        }
        scope.launch {
            AppGraph.runner.running.drop(1).distinctUntilChanged().collect { running ->
                if (!running) {
                    update(app, AskBarWidget())
                    update(app, MemoryWidget())
                }
            }
        }
        scope.launch {
            RoutineSystem.get(app).store.routines.drop(1).map { list -> list.map { Triple(it.id, it.title, it.enabled) to it.lastRunAt } }
                .distinctUntilChanged().throttleLatest(STORE_THROTTLE_MS)
                .collect { update(app, RoutinesWidget()) }
        }
        scope.launch {
            QuickTileStore.get(app).custom.drop(1).collect { update(app, QuickActionsWidget()) }
        }
        scope.launch {
            AppGraph.memories.observe().map { it.size to it.firstOrNull()?.id }.distinctUntilChanged().drop(1)
                .catch { Log.w(TAG, "memory updates stopped: ${it.javaClass.simpleName}") }
                .throttleLatest(STORE_THROTTLE_MS)
                .collect { update(app, MemoryWidget()) }
        }
    }

    /** Redraws every placed widget of [widget]'s kind; nothing when none is placed. */
    suspend fun update(context: Context, widget: GlanceAppWidget) {
        try {
            if (GlanceAppWidgetManager(context).getGlanceIds(widget.javaClass).isEmpty()) return
            widget.updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "widget update failed: ${e.javaClass.simpleName}")
        }
    }
}

/** The first value at once, then at most one per [ms], always ending on the latest. */
fun <T> Flow<T>.throttleLatest(ms: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(ms)
}
