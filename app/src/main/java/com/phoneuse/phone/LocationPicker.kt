package com.phoneuse.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** One provider's fix and how old it is. */
data class ProviderFix(val lat: Double, val lng: Double, val accuracyM: Float?, val ageMs: Long)

/** The LocationManager calls [LocationPicker] needs; either read may throw SecurityException. */
interface LocationProviders {
    /** Providers present and switched on. */
    fun enabled(): List<String>

    /** ACCESS_FINE_LOCATION held (not only "approximate"). */
    fun fineGranted(): Boolean

    fun lastKnown(provider: String): ProviderFix?

    suspend fun current(provider: String): ProviderFix?
}

/**
 * Picks a fix: the freshest last-known one across providers if it is recent, else a fresh fix from fused, then
 * network, then GPS within one [timeoutMs] budget, else the stale last-known fix (with its age). GPS is skipped
 * with "approximate" only, and a provider that refuses (SecurityException) is just skipped, so an approximate
 * grant still answers instead of asking for the permission again.
 */
class LocationPicker(
    private val api: LocationProviders,
    private val timeoutMs: Long = 10_000L,
    private val freshMs: Long = 2 * 60 * 1000L,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    suspend fun pick(): LocationFix? {
        val enabled = api.enabled().toSet()
        val fine = api.fineGranted()
        val providers = ORDER.filter { it in enabled && (fine || it != GPS) }
        val last = (providers + PASSIVE).distinct()
            .mapNotNull { p -> refused { api.lastKnown(p) } }
            .minByOrNull { it.ageMs }
        if (last != null && last.ageMs < freshMs) return last.toFix()
        val deadline = now() + timeoutMs
        for (p in providers) {
            val left = deadline - now()
            if (left <= 0) break
            val fresh = withTimeoutOrNull(left) { refused { api.current(p) } }
            if (fresh != null) return fresh.toFix()
        }
        return last?.toFix()
    }

    private inline fun <T> refused(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        // Unknown provider on this device.
        null
    }

    private fun ProviderFix.toFix() = LocationFix(lat, lng, accuracyM, ageS = ageMs / 1000)

    companion object {
        const val GPS = "gps"
        const val PASSIVE = "passive"

        /** "fused" is LocationManager.FUSED_PROVIDER (API 31+); on API 30 it is simply absent. */
        val ORDER = listOf("fused", "network", GPS)
    }
}
