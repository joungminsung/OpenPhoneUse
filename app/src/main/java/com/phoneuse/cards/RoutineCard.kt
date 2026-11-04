package com.phoneuse.cards

import com.phoneuse.routine.Routine
import com.phoneuse.routine.RoutineJson
import com.phoneuse.routine.RoutineParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Made by make_routine, never by show_card: a routine preview (trigger, conditions, steps) with [루틴 저장].
 * [routine] is routine JSON in the model's form (no stored-only fields, so "확인 없이 보내기" is always off).
 * [placeFrom] says where a place's coordinates came from ("기억에서", "지도 검색") and [placeAddress] what it
 * found there; [permission] is the first runtime permission the routine still needs, shown as the generic
 * [권한 허용] card under it.
 */
@Serializable
@SerialName("routine")
data class RoutineCard(
    val routine: JsonObject,
    @SerialName("place_from") val placeFrom: String? = null,
    @SerialName("place_address") val placeAddress: String? = null,
    val permission: PermissionCard? = null,
) : CardSpec {
    override fun validate(): String? = (RoutineJson.parse(routine, fromModel = true) as? RoutineParse.Error)?.reason

    /** The routine to save, or null for a stored card that no longer reads. */
    fun parsed(): Routine? = (RoutineJson.parse(routine, fromModel = true) as? RoutineParse.Ok)?.routine?.copy(cardKey = key)

    /** Identifies what was saved from this card ("저장됨"). */
    val key: String get() = "card-" + Integer.toHexString(routine.toString().hashCode())
}
