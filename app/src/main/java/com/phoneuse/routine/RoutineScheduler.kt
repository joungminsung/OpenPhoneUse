package com.phoneuse.routine

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.phoneuse.proactive.ProactiveReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The routine scheduler's alarm: only the next due thing (a time routine, a reminder, a before-event run or the
 * daily calendar read) is set, with setExactAndAllowWhileIdle; when it rings, RoutineSystem.tick handles what is
 * due and sets the next. Exact needs USE_EXACT_ALARM (13+) or SCHEDULE_EXACT_ALARM (12–12L); inexact otherwise.
 */
class RoutineScheduler(context: Context) {
    private val app = context.applicationContext

    fun exactAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return app.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    /** Sets the one alarm at [at], or cancels it when null. */
    fun set(at: Instant?) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            app, REQUEST_CODE,
            Intent(app, RoutineAlarmReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            am.cancel(pending)
            return
        }
        val millis = at.toEpochMilli()
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        }
    }

    /**
     * The "알아서 챙기기" check (ProactiveSystem): the 15-minute poll is inexact; [exact] for a nudge that must come on
     * time (a leave time, the briefing), falling back to inexact without exact-alarm access. Null cancels.
     */
    fun setProactive(at: Instant?, exact: Boolean = false) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            app, PROACTIVE_REQUEST_CODE,
            Intent(app, ProactiveReceiver::class.java).setAction(ProactiveReceiver.ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            am.cancel(pending)
            return
        }
        val millis = at.toEpochMilli()
        if (exact && exactAllowed()) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
                return
            } catch (e: SecurityException) {
                // Access was taken away meanwhile: inexact below.
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
    }

    companion object {
        const val ACTION_TICK = "com.phoneuse.ROUTINE_TICK"
        private const val REQUEST_CODE = 7100
        private const val PROACTIVE_REQUEST_CODE = 7102
    }
}

/**
 * Place routines as low-power geofences (Google Play services; the same mechanism map apps use, so it costs
 * little battery). Without Play services, place routines are "이 기기에서 못 씀". Needs precise and background
 * location; until both are granted nothing is registered and the routine screen shows the permission card.
 */
class RoutineGeofences(context: Context) {
    private val app = context.applicationContext

    /** Whether Google Play services can run geofences on this device. */
    fun available(): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS
    } catch (e: Exception) {
        false
    }

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        app, REQUEST_CODE,
        Intent(app, GeofenceReceiver::class.java).setAction(ACTION_GEOFENCE),
        // Play services fills in the event, so the intent must be mutable.
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun client(): GeofencingClient? = if (!available()) {
        null
    } else {
        try {
            LocationServices.getGeofencingClient(app)
        } catch (e: Exception) {
            null
        }
    }

    /** Removes every routine geofence. */
    fun clear() {
        client()?.removeGeofences(pending())
    }

    /**
     * Replaces the registered geofences with the enabled place routines (at most 100). [onAdded] runs once Play
     * services has them; [onUnavailable] when it refused because location is off (GEOFENCE_NOT_AVAILABLE).
     */
    @SuppressLint("MissingPermission")
    fun sync(routines: List<Routine>, onAdded: () -> Unit = {}, onUnavailable: () -> Unit = {}) {
        val client = client() ?: return
        val places = places(routines)
        client.removeGeofences(pending()).addOnCompleteListener {
            if (places.isEmpty()) return@addOnCompleteListener
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) || !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                Log.i(TAG, "place routines waiting for location permission")
                return@addOnCompleteListener
            }
            val fences = places.map { r ->
                val p = r.trigger as Trigger.Place
                Geofence.Builder()
                    .setRequestId(REQUEST_PREFIX + r.id)
                    .setCircularRegion(p.lat, p.lng, p.radiusM.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    // Both edges, so leaving can be told apart from "never arrived".
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setNotificationResponsiveness(RESPONSIVENESS_MS)
                    .build()
            }
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(fences)
                .build()
            try {
                client.addGeofences(request, pending())
                    .addOnSuccessListener { onAdded() }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "geofences not added: ${e.message}")
                        if ((e as? ApiException)?.statusCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) onUnavailable()
                    }
            } catch (e: SecurityException) {
                Log.w(TAG, "geofences need location permission")
            }
        }
    }

    companion object {
        private fun places(routines: List<Routine>) = routines.filter { it.enabled && it.trigger is Trigger.Place }.take(Routine.MAX_PLACES)

        /** What would be registered for [routines]; equal keys need no re-registration. Empty = no place routine. */
        fun key(routines: List<Routine>): String = places(routines).joinToString(";") { r ->
            val p = r.trigger as Trigger.Place
            "${r.id}:${p.lat}:${p.lng}:${p.radiusM}"
        }

        const val ACTION_GEOFENCE = "com.phoneuse.ROUTINE_GEOFENCE"
        const val REQUEST_PREFIX = "routine-"
        private const val REQUEST_CODE = 7101
        private const val RESPONSIVENESS_MS = 2 * 60_000
        private const val TAG = "Routines"
    }
}

/** The one routine alarm rang. */
class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RoutineScheduler.ACTION_TICK) return
        val done = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                RoutineSystem.get(context).tick()
            } catch (e: Exception) {
                Log.e("Routines", "tick failed", e)
            } finally {
                done.finish()
            }
        }
    }
}

/** After a reboot, an update or a clock change: alarms and geofences are set again (they do not survive a reboot). */
class RoutineBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Geofences are gone after a reboot (and re-added after an update); a clock change keeps them.
        val force = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> true
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> false
            else -> return
        }
        val done = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                RoutineSystem.get(context).resync(force)
            } catch (e: Exception) {
                Log.e("Routines", "resync failed", e)
            }
            // The daily summary's alarm is set for a wall-clock time too.
            try {
                com.phoneuse.app.DailySummary.schedule(context, com.phoneuse.app.AppGraph.settings.summaryMinute.first())
            } catch (e: Exception) {
                Log.e("Routines", "daily summary reschedule failed", e)
            } finally {
                done.finish()
            }
        }
    }
}

/** Geofence crossings from Play services. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            // Location was turned off: Play services dropped every geofence. Paused until location is back.
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                val lost = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        RoutineSystem.get(context).placesLost()
                    } finally {
                        lost.finish()
                    }
                }
            }
            return
        }
        val edge = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> PlaceEdge.ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> PlaceEdge.EXIT
            else -> return
        }
        val ids = event.triggeringGeofences.orEmpty().mapNotNull { it.requestId.removePrefix(RoutineGeofences.REQUEST_PREFIX).toLongOrNull() }
        // The broadcast may have started this process: keep it alive until the crossing is handled.
        val done = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val system = RoutineSystem.get(context)
                for (id in ids) system.onPlace(id, edge)
            } catch (e: Exception) {
                Log.e("Routines", "geofence report failed", e)
            } finally {
                done.finish()
            }
        }
    }
}

/** The "잠금 해제 후 실행" notification's target: runs the routine (Android asks to unlock first) and closes. */
class RoutineRunActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ID, 0)
        if (id != 0L) RoutineSystem.get(this).runNow(id, fromLockedNotice = true)
        finish()
    }

    companion object {
        private const val EXTRA_ID = "routine_id"

        fun intent(context: Context, id: Long): Intent =
            Intent(context, RoutineRunActivity::class.java).putExtra(EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
