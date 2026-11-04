package com.phoneuse.routine

import java.time.DayOfWeek
import java.time.LocalTime

/** A place trigger fires on arriving ([ENTER]) or leaving ([EXIT]). */
enum class PlaceEdge(val wire: String) {
    ENTER("enter"),
    EXIT("exit"),
    ;

    companion object {
        fun fromWire(s: String?): PlaceEdge? = entries.firstOrNull { it.wire == s?.trim()?.lowercase() || it.name == s?.trim() }
    }
}

/** Device states a routine can start on; [wire] is the name in routine JSON. */
enum class DeviceKind(val wire: String) {
    CHARGER_CONNECTED("charger"),
    BLUETOOTH_CONNECTED("bluetooth"),
    HEADSET_PLUGGED("headset"),
    WIFI_CONNECTED("wifi"),
    ;

    companion object {
        fun fromWire(s: String?): DeviceKind? = entries.firstOrNull { it.wire == s?.trim()?.lowercase() || it.name == s?.trim() }
    }
}

/** What starts a routine. */
sealed interface Trigger {
    /** At [hour]:[minute] on [days] (empty = every day). */
    data class Time(val hour: Int, val minute: Int, val days: Set<DayOfWeek> = emptySet()) : Trigger

    /** Arriving at or leaving a circle of [radiusM] metres (at least [Routine.MIN_RADIUS_M]) around [lat],[lng]. */
    data class Place(val name: String, val lat: Double, val lng: Double, val radiusM: Int = Routine.DEFAULT_RADIUS_M, val on: PlaceEdge = PlaceEdge.ENTER) : Trigger

    /** A new notification from [packageName] (any app when null) whose title or text contains [keyword] (any when null). */
    data class Notification(val packageName: String? = null, val keyword: String? = null) : Trigger

    /** A device state; [detail] narrows it to a Bluetooth device name or a Wi-Fi name. */
    data class Device(val kind: DeviceKind, val detail: String? = null) : Trigger

    /** [minutes] before a calendar event that has a location. */
    data class BeforeEvent(val minutes: Int) : Trigger
}

/**
 * When a triggered routine may actually run. [days]/[timeFrom]–[timeTo] narrow it (the window may wrap past
 * midnight); [cooldownMin] keeps a trigger that fires again soon from repeating it, and [ONCE_A_DAY] or more
 * means "once per calendar day" ("하루 한 번").
 */
data class Conditions(
    val days: Set<DayOfWeek>? = null,
    val timeFrom: LocalTime? = null,
    val timeTo: LocalTime? = null,
    val onlyWhenUnlocked: Boolean = false,
    val cooldownMin: Int = DEFAULT_COOLDOWN_MIN,
) {
    companion object {
        const val DEFAULT_COOLDOWN_MIN = 60
        const val ONCE_A_DAY = 24 * 60
        const val MAX_COOLDOWN_MIN = 7 * 24 * 60
    }
}

/** One line of the builder's "할 일" list; [device] shows the "폰 조작" tag. */
data class RoutineStep(val text: String, val device: Boolean = false)

/** What a routine does. */
sealed interface RoutineAction {
    /** An agent run with [prompt]; [needsDevice] when it operates other apps (so it cannot run while locked). */
    data class Ask(val prompt: String, val needsDevice: Boolean = false, val steps: List<RoutineStep> = emptyList()) : RoutineAction

    /** Only a notification, no model call. */
    data class Notify(val text: String) : RoutineAction

    /** Opens an app. */
    data class OpenApp(val packageName: String, val label: String? = null) : RoutineAction
}

/**
 * A saved routine. [sendWithoutConfirm] is the builder's "확인 없이 보내기" switch (default off): only then may a
 * routine run send messages without the confirm card; routine JSON from the model never sets it. Results of
 * agent runs go to [conversationId] (created on the first run). [sentence] is what the user said to make it.
 */
data class Routine(
    val id: Long = 0,
    val title: String,
    val trigger: Trigger,
    val conditions: Conditions = Conditions(),
    val action: RoutineAction,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
    val conversationId: Long? = null,
    val sendWithoutConfirm: Boolean = false,
    /** With [sendWithoutConfirm]: the one recipient and text that may go out without the card. */
    val sendTo: String? = null,
    val sendBody: String? = null,
    val sentence: String? = null,
    /** Set when saved from a make_routine preview card, so the card can show "저장됨". */
    val cardKey: String? = null,
) {
    /** The saved message a run may send without the confirm card; null = every send confirms. */
    val fixedSend: com.phoneuse.agent.FixedSend?
        get() {
            val to = sendTo?.trim().orEmpty()
            val body = sendBody?.trim().orEmpty()
            return if (sendWithoutConfirm && action is RoutineAction.Ask && to.isNotEmpty() && body.isNotEmpty()) com.phoneuse.agent.FixedSend(to, body) else null
        }

    /** Whether a run operates the phone (must not run while it is locked). */
    val needsDevice: Boolean
        get() = when (action) {
            is RoutineAction.Ask -> action.needsDevice
            is RoutineAction.OpenApp -> true
            is RoutineAction.Notify -> false
        }

    companion object {
        const val MIN_RADIUS_M = 150
        const val DEFAULT_RADIUS_M = 200
        const val MAX_RADIUS_M = 5000
        const val MAX_TITLE = 40
        const val MAX_PROMPT = 1000

        /** Android allows an app at most 100 geofences. */
        const val MAX_PLACES = 100
    }
}

/** A one-shot app notification (remind_at), kept so it survives a reboot. */
data class Reminder(val id: Long, val at: Long, val text: String)
