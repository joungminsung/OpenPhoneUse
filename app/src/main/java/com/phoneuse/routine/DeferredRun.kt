package com.phoneuse.routine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A run waiting for the runner to become free (kept in routines.json so a process death does not lose it).
 * [event] is what triggered it: the run keeps that event's limits (a notification-started run stays tainted).
 */
data class DeferredRun(val routineId: Long, val event: RoutineEvent, val queuedAt: Long) {
    fun json(): JsonObject = buildJsonObject {
        put("routine_id", routineId)
        put("queued_at", queuedAt)
        when (val e = event) {
            is RoutineEvent.TimeDue -> put("type", "time")
            is RoutineEvent.PlaceCrossed -> {
                put("type", "place")
                put("edge", e.edge.wire)
            }
            is RoutineEvent.NotificationPosted -> {
                put("type", "notification")
                put("package", e.packageName)
                e.title?.let { put("title", it) }
                e.text?.let { put("text", it) }
                put("posted_at", e.postedAt)
            }
            is RoutineEvent.DeviceChanged -> {
                put("type", "device")
                put("kind", e.kind.wire)
                e.detail?.let { put("detail", it) }
            }
            is RoutineEvent.EventSoon -> {
                put("type", "event")
                put("title", e.title)
                put("location", e.location)
                put("starts_at", e.startsAt)
            }
            RoutineEvent.Manual -> put("type", "manual")
        }
    }

    companion object {
        /** Null for anything unreadable (dropped rather than run with the wrong limits). */
        fun read(o: JsonObject): DeferredRun? {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun long(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
            val id = long("routine_id") ?: return null
            val at = long("queued_at") ?: return null
            val event: RoutineEvent = when (str("type")) {
                "time" -> RoutineEvent.TimeDue(id)
                "place" -> RoutineEvent.PlaceCrossed(id, PlaceEdge.fromWire(str("edge")) ?: return null)
                "notification" -> RoutineEvent.NotificationPosted(str("package") ?: return null, str("title"), str("text"), long("posted_at") ?: 0)
                "device" -> RoutineEvent.DeviceChanged(DeviceKind.fromWire(str("kind")) ?: return null, str("detail"))
                "event" -> RoutineEvent.EventSoon(id, str("title").orEmpty(), str("location").orEmpty(), long("starts_at") ?: 0)
                "manual" -> RoutineEvent.Manual
                else -> return null
            }
            return DeferredRun(id, event, at)
        }
    }
}
