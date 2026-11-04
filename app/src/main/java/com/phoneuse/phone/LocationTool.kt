package com.phoneuse.phone

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** [ageS]: how old the fix is, in seconds (null when unknown). */
data class LocationFix(val lat: Double, val lng: Double, val accuracyM: Float?, val ageS: Long? = null)

/** The phone's location provider; Android's is [AndroidLocationSource]. */
interface LocationSource {
    fun hasPermission(): Boolean

    /** False when location is switched off in the system settings. */
    fun isEnabled(): Boolean

    /** A current (or recent) fix; null when none could be had in time. */
    suspend fun current(): LocationFix?
}

/** What get_location answers; [text] is the tool output. */
sealed interface LocationReply {
    val text: String

    data class Ok(val lat: Double, val lng: Double, val accuracyM: Float?, val address: String?, val ageS: Long? = null) : LocationReply {
        /** Older than [LocationTool.STALE_S]: the phone could not get a new fix, so it may be somewhere else now. */
        val stale: Boolean get() = ageS != null && ageS > LocationTool.STALE_S

        override val text: String
            get() = buildJsonObject {
                put("lat", lat)
                put("lng", lng)
                accuracyM?.let { put("accuracy_m", it.roundToInt()) }
                ageS?.let { put("age_s", it) }
                address?.let { put("address", it) }
                if (stale) {
                    put("stale", true)
                    put("note", "${ageS!! / 60}분 전 위치예요. 새 위치를 못 잡아서 지금 위치와 다를 수 있어요. 답할 때 그렇게 알려 주세요.")
                }
            }.toString()
    }

    data object PermissionNeeded : LocationReply {
        override val text = LocationTool.PERMISSION_NEEDED
    }

    data class Failed(val reason: String) : LocationReply {
        override val text get() = "ERROR: $reason"
    }
}

/**
 * The get_location tool. Never asks for the permission itself (tools run without an Activity): without it the
 * answer is [LocationReply.PermissionNeeded] and the chat shows a [권한 허용] card. [reverse] adds an address
 * when it can; failing that, the fix is returned without one.
 */
class LocationTool(
    private val source: LocationSource,
    private val reverse: (suspend (Double, Double) -> String?)? = null,
) {
    suspend fun get(): LocationReply {
        if (!source.hasPermission()) return LocationReply.PermissionNeeded
        if (!source.isEnabled()) return LocationReply.Failed("위치가 꺼져 있어요. 사용자에게 빠른 설정에서 위치를 켜 달라고 하세요.")
        val fix = try {
            source.current()
        } catch (e: SecurityException) {
            // Revoked between the check and the read.
            return LocationReply.PermissionNeeded
        } ?: return LocationReply.Failed("위치를 아직 못 잡았어요. 잠시 뒤 다시 시도하세요.")
        val address = reverse?.let {
            try {
                it(fix.lat, fix.lng)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        return LocationReply.Ok(fix.lat, fix.lng, fix.accuracyM, address, fix.ageS)
    }

    companion object {
        const val PERMISSION_NEEDED = "권한 필요: 위치"

        /** A fix older than this (10 minutes) is reported as stale. */
        const val STALE_S = 10 * 60L
        val PERMISSIONS = listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
    }
}
