package com.phoneuse.understand

import android.content.Context
import android.util.Log
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
import com.phoneuse.i18n.Lang
import com.phoneuse.model.ModelOptions
import com.phoneuse.phone.NotificationStore
import com.phoneuse.phone.PhoneUseNotificationListener
import com.phoneuse.phone.StoredNotification
import com.phoneuse.proactive.ProactiveSystem
import com.phoneuse.usage.RequestKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Runs [UnderstandRun] in the background and books the next run from its result. */
class UnderstandWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        if (!PhoneUseNotificationListener.enabled(app)) return@withContext Result.success()
        val result = try {
            // A provider without a light model answers with the chosen (larger) model: give it longer.
            val mainModel = AppGraph.runner.automaticBackend()?.let { it.lightModel == null } ?: false
            lock.withLock { UnderstandRun(AppDeps(app), if (mainModel) MAIN_MODEL_TIMEOUT_MS else UnderstandRun.TIMEOUT_MS).once() }
        } catch (e: Exception) {
            Log.w(TAG, "notification analysis failed: ${e.javaClass.simpleName}")
            RunResult.Failed(0)
        }
        val now = System.currentTimeMillis()
        val nextReady = runCatching { NotificationStore.get(app).understanding.nextReadyAt() }.getOrNull()
        UnderstandRun.nextDelay(result, now, nextReady)?.let { UnderstandScheduler.scheduleNext(app, it) }
        Result.success()
    }

    private class AppDeps(private val app: Context) : UnderstandDeps {
        private val notifications = NotificationStore.get(app)
        override val store: UnderstandingStore = notifications.understanding
        override suspend fun enabled(): Boolean = AppGraph.settings.understandNotifications.first()

        override suspend fun client(): Pair<com.phoneuse.model.ModelClient, String>? {
            val backend = AppGraph.runner.automaticBackend() ?: return null
            val model = backend.lightModel ?: AppGraph.settings.model.first()
            return backend.client(ModelOptions(model, fast = false, effort = "low"), AppGraph.http) to model
        }

        override suspend fun notifications(keys: List<String>): List<StoredNotification> = notifications.byKeys(keys)
        override suspend fun recordUsage(inputTokens: Int, outputTokens: Int) =
            AppGraph.usage.record(RequestKind.UNDERSTAND, inputTokens = inputTokens, outputTokens = outputTokens)
        override fun appLanguage(): String = Lang.tag
        override fun zone(): ZoneId = ZoneId.systemDefault()
        override fun now(): Long = System.currentTimeMillis()
        override suspend fun pokeProactive() = ProactiveSystem.get(app).poke()
    }

    private companion object {
        const val TAG = "UnderstandWorker"
        const val MAIN_MODEL_TIMEOUT_MS = 90_000L

        /** One analysis at a time in the process, whichever request started it. */
        val lock = Mutex()
    }
}

/** Books [UnderstandWorker]: soon after a notification, at the time the last run asked for, and an hourly sweep. */
object UnderstandScheduler {
    private const val ARRIVAL = "understand"
    private const val NEXT = "understand-next"
    private const val SWEEP = "understand-sweep"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** A notification was stored: run once the first wait is over. A run already booked stays as it is. */
    fun kick(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ARRIVAL,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UnderstandWorker>()
                .setInitialDelay(UnderstandingStore.FIRST_WAIT_MS, TimeUnit.MILLISECONDS)
                .setConstraints(online)
                .build(),
        )
    }

    /** The run a finished run asked for, replacing any earlier such request. */
    fun scheduleNext(context: Context, delayMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NEXT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UnderstandWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(online)
                .build(),
        )
    }

    /** Every hour, for what waited offline or failed once. Booked at app start. */
    fun ensureSweep(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SWEEP,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UnderstandWorker>(1, TimeUnit.HOURS).setConstraints(online).build(),
        )
    }

    /** Settings switched analysis on or off: off cancels everything booked, on starts at once. */
    suspend fun setEnabled(context: Context, on: Boolean) {
        AppGraph.settings.setUnderstandNotifications(on)
        if (on) kick(context) else cancel(context)
    }

    /** Settings switched analysis off: nothing booked may send anything. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(ARRIVAL)
            cancelUniqueWork(NEXT)
        }
    }
}
