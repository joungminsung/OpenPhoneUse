package com.phoneuse.routine

import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** Something that may start a routine. */
sealed interface RoutineEvent {
    /** The scheduler found [routineId]'s time due. */
    data class TimeDue(val routineId: Long) : RoutineEvent

    /** [routineId]'s geofence was crossed. */
    data class PlaceCrossed(val routineId: Long, val edge: PlaceEdge) : RoutineEvent

    /** A new notification of another app (text written by third parties). */
    data class NotificationPosted(val packageName: String, val title: String?, val text: String?, val postedAt: Long) : RoutineEvent

    /** A device state changed; [detail] is the Bluetooth device or Wi-Fi name when known. */
    data class DeviceChanged(val kind: DeviceKind, val detail: String?) : RoutineEvent

    /** [routineId]'s minutes-before point of an event with a location. */
    data class EventSoon(val routineId: Long, val title: String, val location: String, val startsAt: Long) : RoutineEvent

    /** "지금 실행", "한 번 해보기" or the "잠금 해제 후 실행" notification: no trigger, condition or cooldown check. */
    data object Manual : RoutineEvent
}

/** What the host knows when an event comes in. [insidePlaces]: routine ids whose geofence was last entered. */
data class EngineState(
    val runnerBusy: Boolean = false,
    val locked: Boolean = false,
    val insidePlaces: Set<Long> = emptySet(),
    val ownPackage: String = "com.phoneuse",
)

enum class Decision { RUN, DEFER, NOTIFY_LOCKED, SKIP_COOLDOWN, SKIP_CONDITION }

/** Receives trigger events; the Android side is RoutineSystem. */
fun interface RoutineHost {
    fun onEvent(event: RoutineEvent)
}

/**
 * Whether a routine runs for an event (pure), and the entry points the platform calls with events.
 * Order: the trigger must match, then the conditions (days, hours, unlocked only), then the cooldown. A routine
 * that operates the phone does not run while it is locked: the user gets a "잠금 해제 후 실행" notification.
 * A run that needs the agent waits while another run is going (up to [DEFER_MAX_MS]).
 */
object RoutineEngine {
    /** How long a deferred run may wait for the runner to become free. */
    const val DEFER_MAX_MS = 30 * 60_000L

    /** The shortest cooldown of a notification- or device-triggered routine that calls the model or opens an app. */
    const val EVENT_MIN_COOLDOWN_MIN = 5

    fun shouldRun(routine: Routine, event: RoutineEvent, now: ZonedDateTime, state: EngineState): Decision {
        if (event != RoutineEvent.Manual) {
            if (!routine.enabled || !matches(routine, event, state)) return Decision.SKIP_CONDITION
            if (!conditionsHold(routine.conditions, now, state)) return Decision.SKIP_CONDITION
            if (coolingDown(routine, now)) return Decision.SKIP_COOLDOWN
        }
        if (state.locked && routine.needsDevice) return Decision.NOTIFY_LOCKED
        if (state.runnerBusy && routine.action !is RoutineAction.Notify) return Decision.DEFER
        return Decision.RUN
    }

    /** Days, hours and unlocked-only of [routine] for a run that was deferred (no trigger or cooldown check). */
    fun conditionsStillHold(routine: Routine, now: ZonedDateTime, state: EngineState): Boolean =
        conditionsHold(routine.conditions, now, state)

    fun stillDue(queuedAt: Long, now: Long): Boolean = now - queuedAt <= DEFER_MAX_MS

    /** The inside set after [routineId]'s geofence reported [edge]. */
    fun afterCrossing(inside: Set<Long>, routineId: Long, edge: PlaceEdge): Set<Long> =
        if (edge == PlaceEdge.ENTER) inside + routineId else inside - routineId

    fun matches(routine: Routine, event: RoutineEvent, state: EngineState = EngineState()): Boolean = when (val t = routine.trigger) {
        is Trigger.Time -> event is RoutineEvent.TimeDue && event.routineId == routine.id
        is Trigger.BeforeEvent -> event is RoutineEvent.EventSoon && event.routineId == routine.id
        is Trigger.Place -> event is RoutineEvent.PlaceCrossed && event.routineId == routine.id && event.edge == t.on &&
            // Leaving counts only after an arrival was seen.
            (t.on == PlaceEdge.ENTER || routine.id in state.insidePlaces)
        is Trigger.Device -> event is RoutineEvent.DeviceChanged && event.kind == t.kind &&
            (t.detail == null || event.detail?.let { contains(it, t.detail) } == true)
        is Trigger.Notification -> event is RoutineEvent.NotificationPosted && event.packageName != state.ownPackage &&
            (t.packageName == null || t.packageName == event.packageName) &&
            (t.keyword == null || contains(listOfNotNull(event.title, event.text).joinToString(" "), t.keyword))
    }

    private fun conditionsHold(c: Conditions, now: ZonedDateTime, state: EngineState): Boolean {
        if (c.days != null && now.dayOfWeek !in c.days) return false
        if (c.timeFrom != null && c.timeTo != null && !inWindow(now.toLocalTime(), c.timeFrom, c.timeTo)) return false
        if (c.onlyWhenUnlocked && state.locked) return false
        return true
    }

    /** [from]–[to] inclusive of from, exclusive of to; wraps past midnight when from is after to. */
    private fun inWindow(t: LocalTime, from: LocalTime, to: LocalTime): Boolean =
        if (!from.isAfter(to)) !t.isBefore(from) && t.isBefore(to) else !t.isBefore(from) || t.isBefore(to)

    private fun coolingDown(routine: Routine, now: ZonedDateTime): Boolean {
        val last = routine.lastRunAt ?: return false
        // A chatty app or a flapping connection must not start model runs back to back.
        val eventDriven = routine.trigger is Trigger.Notification || routine.trigger is Trigger.Device
        val cooldown = if (eventDriven && routine.action !is RoutineAction.Notify) {
            maxOf(routine.conditions.cooldownMin, EVENT_MIN_COOLDOWN_MIN)
        } else {
            routine.conditions.cooldownMin
        }
        if (cooldown <= 0) return false
        if (cooldown >= Conditions.ONCE_A_DAY) {
            return Instant.ofEpochMilli(last).atZone(now.zone).toLocalDate() == now.toLocalDate()
        }
        return now.toInstant().toEpochMilli() - last < cooldown * 60_000L
    }

    /** Case and spacing ignored. */
    private fun contains(haystack: String, needle: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), "")
        val n = norm(needle)
        return n.isNotEmpty() && norm(haystack).contains(n)
    }

    // ---- platform entry points ------------------------------------------------------------------

    /** Set by RoutineSystem at process start; events before that are dropped. */
    @Volatile var host: RoutineHost? = null

    /**
     * Call for every new notification (the notification listener's onNotificationPosted). [title]/[text] are
     * third-party text; a routine run started by one never sends without the confirm card.
     */
    fun onNotification(packageName: String, title: String?, text: String?, postedAt: Long) {
        host?.onEvent(RoutineEvent.NotificationPosted(packageName, title, text, postedAt))
    }

    /** Called by the device watcher in the accessibility service. */
    fun onDevice(kind: DeviceKind, detail: String?) {
        host?.onEvent(RoutineEvent.DeviceChanged(kind, detail))
    }
}
