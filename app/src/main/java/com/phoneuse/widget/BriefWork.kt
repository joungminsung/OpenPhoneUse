package com.phoneuse.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.phoneuse.app.AppGraph
import com.phoneuse.app.SettingsStore
import com.phoneuse.model.ModelOptions
import kotlinx.coroutines.flow.first
import com.phoneuse.routine.NextRun
import com.phoneuse.routine.RoutineSystem
import com.phoneuse.today.TodayLoader
import com.phoneuse.today.TodaySummary
import com.phoneuse.today.TodayTile
import com.phoneuse.usage.RequestKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** The Today brief as the widget shows it: [text] written at [at] (0: never), by the model when [ai]. */
data class BriefState(val text: String = "", val at: Long = 0L, val ai: Boolean = false, val refreshing: Boolean = false)

/** The last brief, cached in private preferences so the widget never waits for a model call. */
class BriefStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(BriefState(prefs.getString(TEXT, null).orEmpty(), prefs.getLong(AT, 0L), prefs.getBoolean(AI, false)))
    val state: StateFlow<BriefState> = _state.asStateFlow()

    fun save(text: String, at: Long, ai: Boolean) {
        prefs.edit().putString(TEXT, text).putLong(AT, at).putBoolean(AI, ai).apply()
        _state.value = BriefState(text, at, ai, refreshing = false)
    }

    fun refreshing(on: Boolean) {
        _state.value = _state.value.copy(refreshing = on)
    }

    /** Whether the cached brief is missing or older than [BriefWorker.STALE_MS]. */
    fun stale(now: Long): Boolean = _state.value.at == 0L || now - _state.value.at > BriefWorker.STALE_MS

    companion object {
        private const val PREFS = "widget_brief"
        private const val TEXT = "text"
        private const val AT = "at"
        private const val AI = "ai"

        @Volatile private var instance: BriefStore? = null

        fun get(context: Context): BriefStore = instance ?: synchronized(this) {
            instance ?: BriefStore(context).also { instance = it }
        }
    }
}

/**
 * Writes the Today brief in the background: reads the phone (calendar, alarm, reminders, parcels, notifications,
 * routines), asks the light model for one paragraph (no tools: it can only write text), and falls back to
 * [BriefPrompt.local] without sign-in or on any failure. It only reads; nothing on the phone is changed.
 */
class BriefWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val store = BriefStore.get(app)
        store.refreshing(true)
        runCatching { TodayBriefWidget().updateAll(app) }
        try {
            val facts = facts(app)
            val written = write(facts)
            store.save(written ?: BriefPrompt.local(facts), System.currentTimeMillis(), ai = written != null)
        } catch (e: CancellationException) {
            store.refreshing(false)
            // The spinner must not stay on the home screen after a cancelled run.
            withContext(NonCancellable) { runCatching { TodayBriefWidget().updateAll(app) } }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "brief failed: ${e.javaClass.simpleName}")
            store.refreshing(false)
        }
        runCatching { TodayBriefWidget().updateAll(app) }
        return Result.success()
    }

    private suspend fun facts(app: Context): BriefFacts {
        val now = ZonedDateTime.now()
        val sources = TodayLoader.load(app, now)
        val tiles = TodaySummary.build(now, sources)
        val notifications = tiles.filterIsInstance<TodayTile.Notifications>().firstOrNull()
        val store = RoutineSystem.get(app).store
        val endOfDay = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        val routines = store.all().mapNotNull { r ->
            NextRun.compute(r, now)?.takeIf { it.isBefore(endOfDay) }?.let { r.title to it.toInstant().toEpochMilli() }
        }
        return BriefFacts(
            now = now,
            events = sources.events,
            nextAlarm = sources.nextAlarm,
            reminders = store.reminders(),
            parcels = tiles.filterIsInstance<TodayTile.Parcels>().firstOrNull(),
            unread = notifications?.unread ?: 0,
            replies = notifications?.reply ?: 0,
            routines = routines,
        )
    }

    /** The model's paragraph, or null (not signed in, paused after a usage limit, timed out, failed, empty). */
    private suspend fun write(facts: BriefFacts): String? {
        val backend = AppGraph.runner.automaticBackend() ?: return null
        val model = backend.client(
            ModelOptions(backend.lightModel ?: AppGraph.settings.model.first(), fast = false, effort = "low"),
            AppGraph.http,
        )
        val turn = try {
            withTimeoutOrNull(MODEL_TIMEOUT_MS) { model.next(BriefPrompt.INSTRUCTIONS, BriefPrompt.input(facts), emptyList()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "brief model call failed: ${e.javaClass.simpleName}")
            null
        } ?: return null
        runCatching { AppGraph.usage.record(RequestKind.ROUTINE) }
        return BriefPrompt.clean(turn.text)
    }

    companion object {
        private const val TAG = "BriefWorker"
        private const val NOW = "widget_brief_now"
        private const val PERIODIC = "widget_brief_periodic"
        private const val MODEL_TIMEOUT_MS = 30_000L
        const val STALE_MS = 3 * 60 * 60_000L

        /** A refresh now (the widget's button, or a stale brief); one at a time. */
        fun refresh(context: Context) {
            BriefStore.get(context).refreshing(true)
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BriefWorker>().build(),
            )
        }

        /** Every three hours while a brief widget is on the home screen, when online. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BriefWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}
