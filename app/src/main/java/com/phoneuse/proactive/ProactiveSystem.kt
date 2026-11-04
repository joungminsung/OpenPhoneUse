package com.phoneuse.proactive

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.phoneuse.R
import com.phoneuse.app.AppGraph
import com.phoneuse.cards.LatLng
import com.phoneuse.i18n.tr
import com.phoneuse.memory.Memory
import com.phoneuse.notify.Notifier
import com.phoneuse.model.InputItem
import com.phoneuse.model.ModelOptions
import com.phoneuse.model.OpenAiResponsesClient
import com.phoneuse.phone.AndroidCalendarSource
import com.phoneuse.phone.AndroidLocationSource
import com.phoneuse.phone.AndroidPhone
import com.phoneuse.phone.AndroidSmsSource
import com.phoneuse.phone.NotificationStore
import com.phoneuse.phone.PhoneUseNotificationListener
import com.phoneuse.phone.PlacesDistance
import com.phoneuse.routine.PlaceResolver
import com.phoneuse.routine.RoutineScheduler
import com.phoneuse.routine.UpcomingEvent
import com.phoneuse.ui.LaunchToken
import com.phoneuse.ui.MainActivity
import com.phoneuse.usage.RequestKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "알아서 챙기기" on Android: every [ProactiveEngine.CHECK_EVERY_MS] (RoutineScheduler's alarm, exact for a leave
 * time or the briefing) and a few seconds after a notification arrives, it reads the phone ([NudgeSources]), asks
 * the engine what is due, posts those on the "알아서 챙기기" channel and logs them. It never runs the agent or
 * touches the device: a nudge's button opens the app, which starts a card-originated run (the usual confirmations
 * apply) or a map app.
 *
 * A check is bounded ([CHECK_BUDGET_MS], well inside a broadcast's time) and always sets the next alarm, even when
 * it fails or times out. A notification only re-reads notifications and texts; the calendar, memories and typed
 * asks are re-read on the timed checks (at most [SLOW_MAX_AGE_MS] old).
 */
class ProactiveSystem private constructor(private val app: Context) {
    val log = NudgeLog(File(app.filesDir, NudgeLog.FILE_NAME))
    val prefs = NudgePrefs(app)
    private val asks = AskLog(File(app.filesDir, AskLog.FILE_NAME))
    private val alarms = RoutineScheduler(app)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "nudge error", e) },
    )
    private val lock = Mutex()
    private val pokePending = AtomicBoolean(false)

    /** Travel estimates by [NudgeSources.leaveKey]; worked out once per event, two hours before it. */
    private val travel = ConcurrentHashMap<String, TravelEstimate>()

    /** What changes slowly (calendar, memories, asks): read on timed checks, reused by notification-driven ones. */
    private class Slow(val events: List<UpcomingEvent>, val memories: List<Memory>, val asks: List<AskRecord>, val at: Long)

    @Volatile private var slow: Slow? = null

    private fun start() {
        scope.launch(Dispatchers.IO) {
            log.load()
            check(full = true)
        }
    }

    /** A notification or text arrived: look again once the listener has stored it (one check per burst). */
    fun poke() {
        if (!pokePending.compareAndSet(false, true)) return
        scope.launch {
            delay(EVENT_SETTLE_MS)
            pokePending.set(false)
            check(full = false)
        }
    }

    /** The settings changed: look again now (a switched-on kind may be due). */
    fun changed() {
        scope.launch { check(full = true) }
    }

    /** The chat screen: the user typed [text] to start conversation [conversationId] (the repeated-ask source). */
    fun typed(text: String, conversationId: Long) {
        scope.launch(Dispatchers.IO) { asks.add(text, System.currentTimeMillis(), conversationId) }
    }

    /** Deleted or expired conversations: their first messages leave the asks too. */
    fun conversationsGone(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        scope.launch(Dispatchers.IO) { asks.forget(ids.toSet()) }
    }

    /** Settings "삭제" of everything: the sent nudges and the typed asks (the files go off the main thread). */
    fun clear() {
        scope.launch { clearNow() }
    }

    /** [clear], waiting for the files to go (the full wipe reports what failed). */
    suspend fun clearNow() {
        slow = null
        withContext(Dispatchers.IO) {
            log.clear()
            asks.clear()
        }
    }

    /** The last next-check a successful check computed (see [ProactiveEngine.fallbackCheck]). */
    @Volatile private var lastNext: ProactiveEngine.NextCheck? = null

    /** Checks that failed in a row since the last one that worked (the retry back-off). */
    @Volatile private var failedChecks = 0

    /**
     * One look at the phone, at most [CHECK_BUDGET_MS] (waiting for a running check included). [full] re-reads the
     * calendar, memories and asks as well. The next alarm is set in any case.
     */
    suspend fun check(full: Boolean = true) {
        val deadline = System.currentTimeMillis() + CHECK_BUDGET_MS
        var next: ProactiveEngine.NextCheck? = null
        try {
            withTimeout(CHECK_BUDGET_MS) { lock.withLock { next = checkLocked(full, deadline) } }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "check ran out of time")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "check failed", e)
        } finally {
            val nowMs = System.currentTimeMillis()
            // A failed check keeps the last known exact due time (a leave alarm) instead of the inexact poll.
            val done = next
            val n = if (done != null) {
                lastNext = done
                failedChecks = 0
                done
            } else {
                ProactiveEngine.fallbackCheck(lastNext, nowMs, failedChecks).also { failedChecks++ }
            }
            alarms.setProactive(Instant.ofEpochMilli(maxOf(n.at, nowMs + MIN_GAP_MS)), n.exact)
        }
    }

    private suspend fun checkLocked(full: Boolean, deadline: Long): ProactiveEngine.NextCheck {
        val now = ZonedDateTime.now()
        val nowMs = now.toInstant().toEpochMilli()
        val expired = withContext(Dispatchers.IO) { log.expire(nowMs) }
        for (e in expired) cancel(e.dedupeKey)
        val settings = settings()
        if (settings.enabled.isEmpty()) return ProactiveEngine.NextCheck(nowMs + ProactiveEngine.CHECK_EVERY_MS, exact = false)
        val sources = try {
            gather(now, settings, full)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "reading the phone failed: ${e.javaClass.simpleName}")
            NudgeSources()
        }
        val due = ProactiveEngine.plan(now, sources, settings, log.all())
        // Logged even when notifications are off: the home screen still shows them as cards.
        val notify = canPost()
        for (n in due) {
            val shown = if (n.kind == NudgeKind.BRIEFING) withWeather(n, now, deadline) else n
            // Post and log as one step: a timeout between them would repeat the nudge on the next check.
            withContext(NonCancellable + Dispatchers.IO) {
                if (notify) post(shown)
                log.add(shown, nowMs)
            }
        }
        return ProactiveEngine.nextCheck(now, sources, settings, log.all())
    }

    /** The nudge switches with the app-wide quiet hours (settings "방해하지 않는 시간"). */
    private suspend fun settings(): NudgeSettings {
        val q = try {
            AppGraph.settings.quietHours.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.phoneuse.app.QuietHours()
        }
        return prefs.settings.value.withQuiet(q.on, q.startMin, q.endMin)
    }

    /** A home card's button, a notification button or the notification itself was used. Written off the main thread. */
    fun opened(id: String) {
        cancel(id)
        scope.launch { settle(id, NudgeOutcome.OPENED) }
    }

    /** "나중에", the home card's close, or the notification swiped away. Written off the main thread. */
    fun ignored(id: String) {
        cancel(id)
        scope.launch { settle(id, NudgeOutcome.IGNORED) }
    }

    /** Records what became of a pending nudge; null when it is unknown or already settled. */
    suspend fun settle(id: String, outcome: NudgeOutcome): SentNudge? = withContext(Dispatchers.IO) {
        log.resolve(id, outcome, System.currentTimeMillis())
    }

    // ---- reading the phone ---------------------------------------------------------------------

    private suspend fun gather(now: ZonedDateTime, settings: NudgeSettings, full: Boolean): NudgeSources = withContext(Dispatchers.IO) {
        val nowMs = now.toInstant().toEpochMilli()
        val notifications = if (PhoneUseNotificationListener.enabled(app)) NotificationStore.get(app).recent(WEEK_HOURS) else emptyList()
        val sms = AndroidSmsSource(app).takeIf { it.canRead() }?.let { safe { it.query(nowMs - SMS_DAYS * DAY_MS, null, SMS_LIMIT) } }.orEmpty()
        val cached = slow
        val current = if (!full && cached != null && nowMs - cached.at < SLOW_MAX_AGE_MS) cached else readSlow(now, settings).also { slow = it }
        val events = current.events
        if (NudgeKind.LEAVE in settings.enabled) {
            estimate(ProactiveEngine.needsTravel(nowMs, events).filterNot { travel.containsKey(NudgeSources.leaveKey(it)) })
        }
        travel.keys.removeAll { k -> events.none { NudgeSources.leaveKey(it) == k } }
        NudgeSources(
            notifications = notifications.filter { nowMs - it.postedAt < DAY_MS },
            payments = NudgeReaders.payments(notifications, sms),
            parcels = NudgeReaders.parcels(notifications, sms),
            events = events,
            memories = current.memories,
            asks = if (NudgeKind.ROUTINE in settings.enabled) current.asks else emptyList(),
            travel = HashMap(travel),
        )
    }

    private suspend fun readSlow(now: ZonedDateTime, settings: NudgeSettings): Slow {
        val nowMs = now.toInstant().toEpochMilli()
        val memories = if (safe { AppGraph.settings.memoryEnabled.first() } == true) {
            MemoryDates.usable(safe { AppGraph.memories.observeOnce() }.orEmpty())
        } else {
            emptyList()
        }
        val asks = if (NudgeKind.ROUTINE in settings.enabled) safe { readAsks(nowMs) }.orEmpty() else emptyList()
        return Slow(readEvents(now), memories, asks, nowMs)
    }

    private fun readEvents(now: ZonedDateTime): List<UpcomingEvent> {
        val source = AndroidCalendarSource(app)
        if (!source.canRead()) return emptyList()
        val zone = now.zone
        return try {
            source.events(now.toLocalDateTime(), now.toLocalDateTime().plusHours(EVENT_HOURS)).map {
                UpcomingEvent(it.title, it.start.atZone(if (it.allDay) java.time.ZoneOffset.UTC else zone).toInstant().toEpochMilli(), it.location, it.allDay)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** [readAsks] now: the home-screen ask bar's "what you'd likely ask" chips read the user's own asks. */
    suspend fun recentAsks(): List<AskRecord> = withContext(Dispatchers.IO) { readAsks(System.currentTimeMillis()) }

    /**
     * What the user typed to start a conversation this week ([AskLog]), leaving out conversations that read
     * third-party text since (or whose flag cannot be read).
     */
    private suspend fun readAsks(nowMs: Long): List<AskRecord> {
        val typed = asks.all()
        val tainted = typed.mapTo(HashSet()) { it.conversationId }
            .filterTo(HashSet()) { id -> safe { AppGraph.chats.thirdPartySeen(id) } != false }
        return AskLog.usable(typed, tainted, nowMs)
    }

    /**
     * From here to each event's place, in a straight line: no map app is opened and the phone is not touched. One
     * location fix ([LOCATION_TIMEOUT_MS]) serves all of them; each place lookup gets [PLACE_TIMEOUT_MS].
     */
    private suspend fun estimate(events: List<UpcomingEvent>) {
        if (events.isEmpty()) return
        val location = AndroidLocationSource(app)
        if (!location.hasPermission() || !location.isEnabled()) return
        val here = safe { withTimeoutOrNull(LOCATION_TIMEOUT_MS) { location.current() } } ?: return
        val resolver = PlaceResolver(AndroidPhone.geocoder(AppGraph.http), AppGraph.memories)
        for (e in events) {
            val name = e.location ?: continue
            val place = safe { withTimeoutOrNull(PLACE_TIMEOUT_MS) { resolver.resolve(name) } } ?: continue
            val meters = PlacesDistance.haversineMeters(LatLng(here.lat, here.lng), LatLng(place.lat, place.lng))
            travel[NudgeSources.leaveKey(e)] = TravelEstimate(Travel.minutes(meters), place.lat, place.lng, meters)
        }
    }

    // ---- the briefing's weather (the one model call) ---------------------------------------------

    /** The briefing with today's weather as its title, when that comes within [WEATHER_TIMEOUT_MS] and the check's time. */
    private suspend fun withWeather(n: Nudge, now: ZonedDateTime, deadline: Long): Nudge {
        val budget = minOf(WEATHER_TIMEOUT_MS, deadline - System.currentTimeMillis() - POST_RESERVE_MS)
        if (budget < 1_000) return n
        val line = try {
            withTimeoutOrNull(budget) { weather(now) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "weather failed: ${e.javaClass.simpleName}")
            null
        }
        return if (line == null) n else n.copy(title = line)
    }

    private suspend fun weather(now: ZonedDateTime): String? {
        val settings = AppGraph.settings
        if (!settings.webSearch.first()) return null
        val location = AndroidLocationSource(app)
        if (!location.hasPermission() || !location.isEnabled()) return null
        val here = withTimeoutOrNull(LOCATION_TIMEOUT_MS) { location.current() } ?: return null
        // About a kilometre is plenty for the city and district, which is all that is used.
        val area = BriefingText.area(AndroidPhone.geocoder(AppGraph.http).reverse(BriefingText.coarse(here.lat), BriefingText.coarse(here.lng)))
            ?: return null
        val backend = AppGraph.runner.automaticBackend() ?: return null
        val options = ModelOptions(settings.routineModel.first(), settings.fastMode.first(), "low", webSearch = true)
        val model = backend.client(options, AppGraph.http)
        // No function tools: the answer can only be text, and it is only shown.
        val turn = model.next(BriefingText.WEATHER_INSTRUCTIONS, listOf(InputItem.UserText(BriefingText.weatherAsk(area, now.toLocalDate()))), emptyList())
        AppGraph.usage.record(RequestKind.ROUTINE)
        return BriefingText.weatherLine(turn.text)
    }

    // ---- notifications -------------------------------------------------------------------------

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(n: Nudge) {
        // Not on the quiet channel: the engine already keeps nudges out of the quiet hours.
        val builder = Notifier.builder(app, Notifier.Channel.NUDGES)
            .setSubText(n.kind.label)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(Notification.BigTextStyle().bigText(n.body))
            .setContentIntent(activity(n.id, OPEN_HOME))
            .setDeleteIntent(dismissed(n.id))
            .setAutoCancel(true)
        n.actions.take(Nudge.MAX_BUTTONS).forEachIndexed { i, a ->
            val target = if (a.cardAction() == null) dismissed(n.id) else activity(n.id, i)
            builder.addAction(Notification.Action.Builder(null, a.label, target).build())
        }
        if (!Notifier.post(app, TAG_NUDGE, n.id.hashCode(), builder.build())) Log.w(TAG, "nudge notification refused")
    }

    /** Takes a nudge's notification away (what became of it is recorded separately). */
    fun cancel(id: String) {
        app.getSystemService(NotificationManager::class.java)?.cancel(TAG_NUDGE, id.hashCode())
    }

    private fun activity(id: String, action: Int): PendingIntent = PendingIntent.getActivity(
        app, 0, openIntent(app, id, action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun dismissed(id: String): PendingIntent = PendingIntent.getBroadcast(
        app, 0,
        Intent(app, ProactiveReceiver::class.java).setAction(ProactiveReceiver.ACTION_DISMISS)
            .setData(Uri.fromParts("nudge", id, null)).putExtra(EXTRA_NUDGE, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "nudge source failed: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "Nudges"
        const val CHANNEL_ID = "phoneuse_nudges"
        private const val TAG_NUDGE = "nudge"
        const val EXTRA_NUDGE = "com.phoneuse.extra.NUDGE"
        const val EXTRA_NUDGE_ACTION = "com.phoneuse.extra.NUDGE_ACTION"

        /** The notification body was tapped: show the home screen, where the nudge's card is. */
        const val OPEN_HOME = -1

        private const val DAY_MS = 24 * 60 * 60_000L
        private const val WEEK_HOURS = 7 * 24
        private const val SMS_DAYS = 62L
        private const val SMS_LIMIT = 1000
        private const val EVENT_HOURS = 36L
        private const val EVENT_SETTLE_MS = 5_000L
        private const val MIN_GAP_MS = 60_000L
        private const val LOCATION_TIMEOUT_MS = 10_000L
        private const val PLACE_TIMEOUT_MS = 5_000L
        private const val WEATHER_TIMEOUT_MS = 15_000L
        private const val POST_RESERVE_MS = 2_000L

        /** A whole check: well inside the time a background broadcast may take (the alarm's goAsync). */
        const val CHECK_BUDGET_MS = 25_000L

        /** How old the calendar, memories and asks may be when a notification triggers the check. */
        private const val SLOW_MAX_AGE_MS = 20 * 60_000L

        @Volatile private var instance: ProactiveSystem? = null

        /** The process's nudges; the first call starts the checks. */
        fun get(context: Context): ProactiveSystem =
            instance ?: synchronized(this) {
                instance ?: ProactiveSystem(context.applicationContext).also {
                    instance = it
                    it.start()
                }
            }

        /** From the notification listener: something arrived. */
        fun onPhoneEvent(context: Context) {
            get(context).poke()
        }

        /** Opens the app on a nudge: [action] is a button index, or [OPEN_HOME]. Each pair is its own PendingIntent. */
        fun openIntent(context: Context, id: String, action: Int): Intent =
            Intent(context, MainActivity::class.java)
                .setData(Uri.fromParts("nudge", "$id#$action", null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_NUDGE, id)
                .putExtra(EXTRA_NUDGE_ACTION, action)
                // MainActivity is exported: only a signed nudge starts anything.
                .putExtra(LaunchToken.EXTRA, LaunchToken.of(context).sign(LaunchToken.nudge(id, action)))
    }
}

/**
 * The check alarm (set through RoutineScheduler) and a nudge swiped away or put off ("나중에"). Both run in a
 * coroutine under goAsync; a check is bounded by [ProactiveSystem.CHECK_BUDGET_MS] and re-arms its own alarm.
 */
class ProactiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val system = ProactiveSystem.get(context)
        val work: suspend () -> Unit = when (intent.action) {
            ACTION_TICK -> {
                { system.check(full = true) }
            }
            ACTION_DISMISS -> {
                val id = intent.getStringExtra(ProactiveSystem.EXTRA_NUDGE) ?: return
                system.cancel(id)
                val settle: suspend () -> Unit = { system.settle(id, NudgeOutcome.IGNORED) }
                settle
            }
            else -> return
        }
        val done = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                work()
            } catch (e: Exception) {
                Log.e("Nudges", "nudge broadcast failed", e)
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "com.phoneuse.NUDGE_TICK"
        const val ACTION_DISMISS = "com.phoneuse.NUDGE_DISMISS"
    }
}
