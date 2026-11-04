package com.phoneuse.widget

import android.content.Context
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import com.phoneuse.app.AppGraph
import com.phoneuse.i18n.tr
import com.phoneuse.routine.RoutineSystem
import com.phoneuse.ui.LaunchToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widget buttons that act without opening the app. Each carries a [LaunchToken] over what it does, checked
 * before acting, like every other intent into the app (Glance delivers them through its own unexported receiver).
 */
object WidgetActions {
    private val ACTION = ActionParameters.Key<String>("action")
    private val TOKEN = ActionParameters.Key<String>("token")
    private val ROUTINE = ActionParameters.Key<Long>("routine")

    private fun signed(context: Context, action: String, vararg extra: ActionParameters.Pair<out Any>): ActionParameters =
        actionParametersOf(ACTION to action, TOKEN to LaunchToken.of(context).sign(LaunchToken.widgetAction(action)), *extra)

    /** True when [p] carries [action] signed by this install. */
    internal fun verified(context: Context, p: ActionParameters, action: String): Boolean =
        p[ACTION] == action && LaunchToken.of(context).verify(LaunchToken.widgetAction(action), p[TOKEN])

    fun stop(context: Context): Action = actionRunCallback<StopTask>(signed(context, STOP))

    fun refreshBrief(context: Context): Action = actionRunCallback<RefreshBrief>(signed(context, REFRESH))

    fun runRoutine(context: Context, id: Long): Action = actionRunCallback<RunRoutine>(signed(context, "routine:$id", ROUTINE to id))

    internal fun routineId(p: ActionParameters): Long? = p[ROUTINE]

    const val STOP = "stop"
    const val REFRESH = "refresh-brief"

    /** The live-task widget's Stop: the same as the overlay's and the chat's stop. */
    class StopTask : ActionCallback {
        override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
            if (!verified(context, parameters, STOP)) return
            AppGraph.runner.stop()
        }
    }

    /** The brief's refresh: a background rewrite (reads only), shown as "refreshing" right away. */
    class RefreshBrief : ActionCallback {
        override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
            if (!verified(context, parameters, REFRESH)) return
            BriefWorker.refresh(context)
            TodayBriefWidget().update(context, glanceId)
        }
    }

    /**
     * A routine's run button: the routines screen's "지금 실행", so the routine's own conditions, its trust setting
     * and every confirmation apply, and a busy runner defers it.
     */
    class RunRoutine : ActionCallback {
        override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
            val id = routineId(parameters) ?: return
            if (!verified(context, parameters, "routine:$id")) return
            RoutineSystem.get(context).runNow(id)
            withContext(Dispatchers.Main) {
                Toast.makeText(context.applicationContext, tr("Routine started", "루틴을 실행했어요"), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
