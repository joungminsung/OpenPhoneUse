package com.phoneuse.routine

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.location.LocationManager
import android.os.Build
import android.util.Log
import com.phoneuse.R
import com.phoneuse.app.AppGraph
import com.phoneuse.app.QuietHours
import com.phoneuse.app.Source
import com.phoneuse.cards.PermissionCard
import com.phoneuse.i18n.tr
import com.phoneuse.notify.AppScreen
import com.phoneuse.notify.Notifier
import com.phoneuse.notify.NotifyText
import com.phoneuse.notify.Tap
import com.phoneuse.notify.Taps
import com.phoneuse.phone.AndroidCalendarSource
import com.phoneuse.phone.PhoneUseNotificationListener
import com.phoneuse.service.PhoneUseAccessibilityService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The calendar as BeforeEvent planning reads it; null when it may not be read. */
fun interface UpcomingEvents {
    fun between(from: Long, to: Long): List<UpcomingEvent>?
}

/**
 * Routines on Android: the store, the one exact alarm, geofences, and what happens when a trigger fires
 * ([RoutineEngine.shouldRun], then run, defer, or the "잠금 해제 후 실행" notification). It also delivers
 * remind_at reminders (ReminderScheduler.oneShot comes here) so they survive a reboot.
 * One per process ([get]); the device watcher, notification listener, alarm, boot and geofence receivers all
 * reach it through [RoutineEngine]'s entry points or [get].
 */
class RoutineSystem private constructor(private val app: Context) : RoutineHost {
    val store = RoutineStore(File(app.filesDir, RoutineStore.FILE_NAME))
    val alarms = RoutineScheduler(app)
    val geofences = RoutineGeofences(app)
    private val calendar = UpcomingEvents { from, to ->
        val source = AndroidCalendarSource(app)
        if (!source.canRead()) return@UpcomingEvents null
        val zone = ZoneId.systemDefault()
        source.events(LocalDateTime.ofInstant(Instant.ofEpochMilli(from), zone), LocalDateTime.ofInstant(Instant.ofEpochMilli(to), zone)).map {
            UpcomingEvent(it.title, it.start.atZone(zone).toInstant().toEpochMilli(), it.location, it.allDay)
        }
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "routine error", e) },
    )
    private val eventLock = Mutex()
    private val tickLock = Mutex()
    private val drainLock = Mutex()

    /** What triggered each routine waiting behind a "잠금 해제 후 실행" notification. */
    private val lockedEvents = HashMap<Long, Pair<RoutineEvent, Long>>()

    /** Settings "방해하지 않는 시간": inside it, routine notifications arrive silently (QuietChannel). */
    @Volatile private var quietHours = QuietHours()

    private fun quietNow(): Boolean = quietHours.contains(java.time.LocalTime.now().let { it.hour * 60 + it.minute })

    private fun startWatching() {
        scope.launch {
            try {
                AppGraph.settings.quietHours.collect { quietHours = it }
            } catch (e: Exception) {
                Log.w(TAG, "reading quiet hours failed: ${e.javaClass.simpleName}")
            }
        }
        // Deferred runs go once the runner is free (persisted in the store, so they survive a process death).
        scope.launch {
            AppGraph.runner.running.collect { running -> if (!running) drainDeferred() }
        }
        // Not forced: geofences survive a process death, and re-adding them here would make the crossing that
        // started this process look like Play services' "already inside" report.
        scope.launch { resync() }
        store.brokenAside?.let { postBroken(brokenText(it)) }
    }

    /** What the user is told when routines.json could not be read. */
    fun brokenText(aside: File): String = tr(
        "Couldn't read the routines file, so I started with an empty list. The old file was kept as ${aside.name}.",
        "루틴 파일을 읽지 못해 빈 목록으로 시작했어요. 예전 파일은 ${aside.name}로 남겨 뒀어요.",
    )

    // ---- scheduling ----------------------------------------------------------------------------

    /** remind_at: kept in the store, delivered by the one alarm. True when exact alarms are allowed. */
    fun oneShot(at: Instant, text: String): Boolean {
        store.addReminder(at.toEpochMilli(), text)
        setNextAlarm()
        return alarms.exactAllowed()
    }

    /**
     * Handles everything due, reads the calendar, registers geofences when the place routines changed (or
     * [force]: after a reboot, an update, or location coming back) and sets the next alarm.
     */
    suspend fun resync(force: Boolean = false) {
        syncFences(force)
        refreshCalendar()
        tick()
    }

    private fun syncFences(force: Boolean) {
        val routines = store.all()
        val key = RoutineGeofences.key(routines)
        if (!force && key == store.fenceKey) return
        // Paused with location still off: adding would only fail again; the retry tick and the broadcast come back.
        if (store.placesPaused && key.isNotEmpty() && !locationOn()) return
        if (key.isEmpty()) {
            geofences.clear()
            store.fenceKey = key
            store.placesPaused = false
            return
        }
        // Their initial "already inside" reports come right after: they only set the inside state (see onPlace).
        // A real arrival within that minute after an edit re-registered them is taken as one too (rare; accepted).
        store.fencesAt = System.currentTimeMillis()
        geofences.sync(
            routines,
            onAdded = {
                store.fenceKey = key
                store.placesPaused = false
            },
            onUnavailable = { store.placesPaused = true },
        )
    }

    /** Play services dropped every geofence (location was turned off): paused until location is back. */
    fun placesLost() {
        // Off the caller's (main) thread: both writes are synced to disk.
        scope.launch {
            store.placesPaused = true
            store.fenceKey = null
            // The retry alarm (every PLACES_RETRY_MS while paused): the PROVIDERS_CHANGED listener lives in the
            // accessibility service and is gone when that is off.
            setNextAlarm()
        }
    }

    /** Location providers changed (broadcast from the device watcher): if place routines were paused, register again. */
    fun locationChanged() {
        scope.launch {
            if (RoutinePlan.retryPlaces(store.placesPaused, locationOn())) resync(force = true)
        }
    }

    private fun locationOn(): Boolean = try {
        app.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    } catch (e: Exception) {
        false
    }

    /**
     * A routine added, changed or removed on a screen: written off the main thread, then re-synced. False when
     * routines.json could not be written (the screen says "저장하지 못했어요").
     */
    suspend fun edit(block: RoutineStore.() -> Unit): Boolean = withContext(Dispatchers.IO) {
        // Whatever is due under the old definitions runs first; the check window then restarts at the edit.
        tick()
        store.userEdit(block).also { if (it) changed() else Log.w(TAG, "routine write failed") }
    }

    /** After a routine was added, changed or removed. */
    fun changed() {
        scope.launch {
            // A new, re-enabled or re-timed routine must not fire for a moment that passed before the edit.
            tickLock.withLock { store.checkedAt = System.currentTimeMillis() }
            resync()
        }
    }

    /** The alarm rang (or anything else asked for a check): runs what is due since the last check. */
    suspend fun tick() = tickLock.withLock {
        val now = ZonedDateTime.now()
        val nowMs = now.toInstant().toEpochMilli()
        val last = store.checkedAt?.let { Instant.ofEpochMilli(it).atZone(now.zone) } ?: now
        // Reminders: delivered even when late (the phone was off).
        val dueReminders = store.reminders().filter { it.at <= nowMs }
        // A reminder that could not be shown (notifications off) stays for the next tick until it is a day old.
        val shown = dueReminders.filter { postReminder(app, it.id.toInt(), it.text) || nowMs - it.at > REMINDER_KEEP_MS }
        if (shown.isNotEmpty()) store.removeReminders(shown.mapTo(HashSet()) { it.id })
        // Time routines: run on time, or skip when too late.
        val due = RoutinePlan.dueTimes(store.all(), last, now)
        // Before-event runs: only while the event is still ahead.
        val (dueFires, rest) = store.fires.partition { it.at <= nowMs }
        if (dueFires.isNotEmpty()) store.fires = rest
        if (store.calendarAt?.let { it <= nowMs } == true) refreshCalendarLocked()
        store.checkedAt = nowMs
        // Paused place routines look again on every tick (one comes every PLACES_RETRY_MS while paused).
        if (RoutinePlan.retryPlaces(store.placesPaused, locationOn())) syncFences(force = true)
        setNextAlarm()
        for (d in due) if (d.late) Log.i(TAG, "skipped late time run of ${d.routineId}") else handle(RoutineEvent.TimeDue(d.routineId))
        for (f in dueFires) {
            if (nowMs - f.at <= RoutinePlan.LATE_MAX_MS && f.startsAt > nowMs) handle(RoutineEvent.EventSoon(f.routineId, f.title, f.location, f.startsAt))
        }
    }

    private fun setNextAlarm() {
        val now = ZonedDateTime.now()
        val retry = RoutinePlan.placesRetryAt(store.placesPaused, now.toInstant().toEpochMilli())
        alarms.set(RoutinePlan.nextWake(store.all(), store.reminders(), store.fires, store.calendarAt, now, retry))
    }

    /** Reads the calendar for BeforeEvent routines (daily, on calendar changes and on resync). */
    suspend fun refreshCalendar() = tickLock.withLock { refreshCalendarLocked() }

    /** The calendar changed (debounced by the device watcher). */
    fun calendarChanged() {
        scope.launch { refreshCalendar() }
    }

    /** Under [tickLock], so a tick never writes back fires a refresh just replaced. */
    private fun refreshCalendarLocked() {
        val routines = store.all()
        if (!RoutinePlan.needsCalendar(routines)) {
            store.fires = emptyList()
            store.calendarAt = null
            return
        }
        val now = ZonedDateTime.now()
        val nowMs = now.toInstant().toEpochMilli()
        val events = try {
            calendar.between(nowMs, nowMs + RoutinePlan.EVENT_HORIZON_MS)
        } catch (e: Exception) {
            Log.w(TAG, "calendar read failed: ${e.javaClass.simpleName}")
            null
        }
        store.fires = if (events == null) emptyList() else RoutinePlan.eventFires(routines, events, nowMs)
        store.calendarAt = RoutinePlan.nextCalendarRefresh(now).toInstant().toEpochMilli()
        setNextAlarm()
    }

    // ---- triggers ------------------------------------------------------------------------------

    override fun onEvent(event: RoutineEvent) {
        scope.launch { handle(event) }
    }

    /** A geofence report. */
    suspend fun onPlace(routineId: Long, edge: PlaceEdge) {
        // Only an ENTER right after registering can be Play services' initial report.
        val initial = edge == PlaceEdge.ENTER && System.currentTimeMillis() - (store.fencesAt ?: 0L) < INITIAL_REPORT_MS
        if (initial) {
            // Registering reports "already inside": remember it, but do not treat it as an arrival.
            eventLock.withLock { store.inside = RoutineEngine.afterCrossing(store.inside, routineId, edge) }
        } else {
            handle(RoutineEvent.PlaceCrossed(routineId, edge))
        }
    }

    /**
     * "지금 실행" ([fromLockedNotice] false: a plain manual run), and the "잠금 해제 후 실행" notification (the run
     * keeps what triggered it, so a notification-started run keeps its limits).
     */
    fun runNow(routineId: Long, fromLockedNotice: Boolean = false) {
        scope.launch {
            eventLock.withLock {
                val r = store.get(routineId) ?: return@withLock
                val locked = synchronized(lockedEvents) { lockedEvents.remove(routineId) }?.first
                if (fromLockedNotice && !r.enabled) return@withLock
                val event = if (fromLockedNotice) locked ?: RoutineEvent.Manual else RoutineEvent.Manual
                decide(r, event, checkAs = RoutineEvent.Manual)
            }
        }
    }

    /** The builder's "한 번 해보기": runs a routine that may not be saved yet. */
    fun tryOnce(routine: Routine) {
        scope.launch { eventLock.withLock { decide(routine, RoutineEvent.Manual) } }
    }

    private suspend fun handle(event: RoutineEvent) = eventLock.withLock {
        val stateBefore = store.inside
        for (r in store.all()) decide(r, event)
        if (event is RoutineEvent.PlaceCrossed) store.inside = RoutineEngine.afterCrossing(stateBefore, event.routineId, event.edge)
    }

    private fun state() = EngineState(
        runnerBusy = AppGraph.runner.running.value,
        locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
        insidePlaces = store.inside,
        ownPackage = app.packageName,
    )

    /** [checkAs] is what the decision looks at; the run itself (its prompt, its trust) always uses [event]. */
    private suspend fun decide(r: Routine, event: RoutineEvent, checkAs: RoutineEvent = event) {
        when (RoutineEngine.shouldRun(r, checkAs, ZonedDateTime.now(), state())) {
            Decision.RUN -> execute(r, event)
            Decision.DEFER -> defer(r, event)
            Decision.NOTIFY_LOCKED -> {
                // The tap runs it with what triggered it, so a notification-started run keeps its limits. One
                // notice per routine: later events while it is up neither replace it nor alert again.
                if (r.id == 0L) return
                // A notice the user swiped away must not silence the routine for good: after NOTICE_REPEAT_MS a new event
                // replaces the entry and notifies again.
                val nowMs = System.currentTimeMillis()
                val first = synchronized(lockedEvents) {
                    val old = lockedEvents[r.id]
                    if (old == null || nowMs - old.second >= NOTICE_REPEAT_MS) {
                        lockedEvents[r.id] = event to nowMs
                        true
                    } else {
                        false
                    }
                }
                if (first) notifyLocked(r)
            }
            Decision.SKIP_COOLDOWN, Decision.SKIP_CONDITION -> Unit
        }
    }

    /** Queues a run for when the runner is free; a run the builder is only trying out is not queued. */
    private fun defer(r: Routine, event: RoutineEvent) {
        if (r.id == 0L) {
            post(r, tr("Busy with another task. Try again once it's done", "다른 작업 중이에요. 끝난 뒤에 다시 해 보세요"))
            return
        }
        store.editDeferred { q -> RoutinePlan.enqueue(q, DeferredRun(r.id, event, System.currentTimeMillis())) to Unit }
        // The runner may have finished between the busy check and the enqueue: nothing else would drain it.
        if (!AppGraph.runner.running.value) scope.launch { drainDeferred() }
    }

    /**
     * Runs queued routines while the runner is free: the whole queue in order, skipping deleted, disabled and
     * expired ones, until one starts an agent run (which makes the runner busy; its end drains the rest).
     */
    private suspend fun drainDeferred() = drainLock.withLock {
        while (!AppGraph.runner.running.value) {
            val now = System.currentTimeMillis()
            val step = store.editDeferred { q ->
                val d = RoutinePlan.drainStep(q, now) { id -> store.get(id)?.enabled }
                d.rest to d
            }
            step.expired.forEach { d -> store.get(d.routineId)?.let { post(it, tr("Another task ran long, so this routine didn't run this time", "다른 작업이 길어져서 이번에는 실행하지 못했어요")) } }
            val next = step.next ?: return@withLock
            eventLock.withLock {
                val r = store.get(next.routineId)?.takeIf { it.enabled } ?: return@withLock
                // Deferred runs were already checked against their trigger and cooldown; days, hours and
                // unlocked-only are looked at again, and a run whose window is over is dropped.
                if (next.event != RoutineEvent.Manual && !RoutineEngine.conditionsStillHold(r, ZonedDateTime.now(), state())) return@withLock
                decide(r, next.event, checkAs = RoutineEvent.Manual)
            }
        }
    }

    private suspend fun execute(r: Routine, event: RoutineEvent) {
        // "지금 실행" is the user asking, so it still goes out. No cooldown starts for a run that never happened.
        if (r.action is RoutineAction.Ask && event != RoutineEvent.Manual && AppGraph.runner.planPaused()) {
            skipPaused(r)
            return
        }
        val now = System.currentTimeMillis()
        if (r.id != 0L) store.update(r.id) { it.copy(lastRunAt = now) }
        when (val a = r.action) {
            is RoutineAction.Notify -> {
                post(r, a.text)
                record(r, a.text)
            }
            is RoutineAction.OpenApp -> {
                val launch = app.packageManager.getLaunchIntentForPackage(a.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val opened = launch != null && try {
                    // The bound accessibility service lets the app start an activity from the background.
                    (PhoneUseAccessibilityService.instance ?: app).startActivity(launch)
                    true
                } catch (e: Exception) {
                    false
                }
                val label = a.label ?: a.packageName
                val result = if (opened) tr("Opened $label", "$label 열었어요") else tr("Couldn't open $label", "$label 앱을 열지 못했어요")
                if (!opened) post(r, result)
                record(r, result)
            }
            is RoutineAction.Ask -> ask(r, a, event)
        }
    }

    private suspend fun ask(r: Routine, a: RoutineAction.Ask, event: RoutineEvent) {
        val chats = AppGraph.chats
        val existing = r.conversationId?.takeIf { id -> chats.conversations().first().any { it.id == id } }
        val conversation = existing ?: chats.createConversation(tr("Routine · ${r.title}", "루틴 · ${r.title}").take(TITLE_CHARS))
        if (r.id != 0L && existing == null) store.update(r.id) { it.copy(conversationId = conversation) }
        val run = RoutineRun.of(r, event) { reply -> record(r, reply ?: tr("No result", "결과 없음")) }
        val text = RoutinePrompt.build(r, a.prompt, event, fixedSend = run.fixedSend)
        // Lost a race with a run the user just started: wait for it like any busy runner.
        if (!AppGraph.runner.send(conversation, text, Source.ROUTINE, routine = run)) defer(r, event)
    }

    /** Skipped while the plan is paused after a usage limit. Told once: a routine that keeps firing doesn't keep notifying. */
    private fun skipPaused(r: Routine) {
        val text = tr(
            "Didn't run: paused after reaching the usage limit for your ChatGPT plan or this app",
            "실행하지 않았어요: ChatGPT 요금제 또는 이 앱의 사용량 한도에 도달해서 잠시 멈췄어요",
        )
        if (r.lastResult == text) return
        post(r, text, manageUsage = true)
        record(r, text)
    }

    private fun record(r: Routine, result: String) {
        if (r.id == 0L) return
        store.update(r.id) { it.copy(lastResult = result.lineSequence().firstOrNull { l -> l.isNotBlank() }?.trim()?.take(RESULT_CHARS) ?: result.take(RESULT_CHARS)) }
    }

    // ---- notifications -------------------------------------------------------------------------

    private fun canPost(): Boolean = Notifier.canPost(app)

    /**
     * A routine's own notification (Notify action, a skipped run). Its message may be Markdown the model wrote;
     * tapping it opens the routine's conversation, or the routines list before it has one.
     */
    private fun post(r: Routine, text: String, manageUsage: Boolean = false) {
        if (!canPost()) return
        val plain = NotifyText.plain(text).ifBlank { text }
        val n = Notifier.builder(app, Notifier.Channel.ROUTINES, quietNow())
            .setContentTitle(NotifyText.title(r.title, tr("Routine", "루틴")))
            .setContentText(NotifyText.firstLine(plain))
            .setStyle(Notification.BigTextStyle().bigText(NotifyText.clip(plain, NotifyText.BIG_CHARS)))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(Notifier.open(app, Taps.routine(r.conversationId)))
            .setAutoCancel(true)
            .apply { if (manageUsage) addAction(Notifier.manageUsageAction(app)) }
            .build()
        Notifier.post(app, TAG_ROUTINE, r.id.toInt(), n)
    }

    /** routines.json was unreadable and moved aside; tapping it opens the routines list. */
    private fun postBroken(text: String) {
        if (!canPost()) return
        val n = Notifier.builder(app, Notifier.Channel.ROUTINES, quietNow())
            .setContentTitle(tr("Routine", "루틴"))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ERROR)
            .setContentIntent(Notifier.open(app, Tap.Screen(AppScreen.ROUTINES)))
            .setAutoCancel(true)
            .build()
        Notifier.post(app, TAG_ROUTINE, 0, n)
    }

    /** "잠금 해제 후 실행": tapping it (after unlocking) runs the routine. */
    private fun notifyLocked(r: Routine) {
        if (!canPost() || r.id == 0L) return
        val run = PendingIntent.getActivity(
            app, r.id.toInt(), RoutineRunActivity.intent(app, r.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notifier.builder(app, Notifier.Channel.ROUTINES, quietNow())
            .setContentTitle(tr("Runs after unlock · ${r.title}", "잠금 해제 후 실행 · ${r.title}"))
            .setOnlyAlertOnce(true)
            .setContentText(tr("This routine operates the phone, so it will run once you unlock. Tap to run", "폰을 조작하는 루틴이라 잠금을 풀면 실행할게요. 눌러서 실행"))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(run)
            .addAction(Notification.Action.Builder(null as Icon?, tr("Run", "실행"), run).build())
            .setAutoCancel(true)
            .build()
        Notifier.post(app, TAG_ROUTINE, r.id.toInt(), n)
    }

    /** The permission card a routine still needs, if any. */
    fun missingPermission(r: Routine): PermissionCard? =
        RoutinePermissions.firstMissing(r, Build.VERSION.SDK_INT) {
            if (it == RoutinePermissions.LISTENER) {
                PhoneUseNotificationListener.enabled(app)
            } else {
                app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        }

    /** Why [r] cannot work even with its permissions (accessibility service off, location off), if anything. */
    fun warning(r: Routine): String? =
        RoutinePermissions.warning(r, accessibilityOn = PhoneUseAccessibilityService.instance != null, placesPaused = store.placesPaused)

    companion object {
        private const val TAG = "Routines"
        const val CHANNEL_ID = "phoneuse_routines"
        private const val TAG_ROUTINE = "routine"
        private const val TITLE_CHARS = 30
        private const val RESULT_CHARS = 120
        private const val INITIAL_REPORT_MS = 60_000L
        private const val NOTICE_REPEAT_MS = 10 * 60_000L
        private const val REMINDER_KEEP_MS = 24 * 60 * 60_000L

        @Volatile private var instance: RoutineSystem? = null

        /** The process's routine system; the first call starts it (alarms, geofences, deferred runs). */
        fun get(context: Context): RoutineSystem =
            instance ?: synchronized(this) {
                instance ?: RoutineSystem(context.applicationContext).also {
                    instance = it
                    RoutineEngine.host = it
                    it.startWatching()
                }
            }

        /** Posts a remind_at reminder on the "알림" channel (with a bell, not the app's mark); tapping it opens the app. */
        fun postReminder(context: Context, id: Int, text: String): Boolean {
            if (!AlarmReminderScheduler.notificationsOn(context)) return false
            val notification = Notifier.builder(context, Notifier.Channel.REMINDERS)
                .setSmallIcon(R.drawable.ic_stat_reminder)
                .setContentTitle(tr("Reminder", "알림"))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(Notifier.open(context, Tap.Screen(AppScreen.HOME)))
                .setAutoCancel(true)
                .build()
            Notifier.post(context, "reminder", id, notification)
            return true
        }
    }
}
