package com.phoneuse.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** LocationManager without Play services; which fix to use is [LocationPicker]'s choice. */
class AndroidLocationSource(context: Context) : LocationSource {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)

    override fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    override fun isEnabled(): Boolean = manager?.isLocationEnabled == true

    override suspend fun current(): LocationFix? {
        val lm = manager ?: return null
        return LocationPicker(Providers(lm)).pick()
    }

    private fun granted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // LocationTool checks the permission first; LocationPicker skips a provider that throws SecurityException.
    @Suppress("MissingPermission")
    private inner class Providers(private val lm: LocationManager) : LocationProviders {
        override fun enabled(): List<String> =
            lm.allProviders.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        override fun fineGranted() = granted(Manifest.permission.ACCESS_FINE_LOCATION)

        override fun lastKnown(provider: String): ProviderFix? = lm.getLastKnownLocation(provider)?.toFix()

        override suspend fun current(provider: String): ProviderFix? =
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, EXECUTOR) { location ->
                    if (cont.isActive) cont.resume(location?.toFix())
                }
            }
    }

    private fun Location.toFix() = ProviderFix(
        latitude,
        longitude,
        if (hasAccuracy()) accuracy else null,
        ageMs = ((SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0),
    )

    private companion object {
        val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
