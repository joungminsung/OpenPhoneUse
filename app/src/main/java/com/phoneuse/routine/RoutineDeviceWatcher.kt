package com.phoneuse.routine

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.util.Log

/**
 * Device triggers (charger, Bluetooth, headset, Wi-Fi) and calendar changes. Dynamic receivers only arrive
 * while the process lives, so this is started by the accessibility service, which the system keeps running.
 * Reports that only describe the state at registration (the sticky headset broadcast, the Wi-Fi already
 * connected) are not treated as new connections.
 */
class RoutineDeviceWatcher(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var started = false
    private var network: ConnectivityManager.NetworkCallback? = null
    private var calendar: ContentObserver? = null
    private var startedAt = 0L

    /** The Wi-Fi network last reported, so one connection fires once. */
    private var wifi: Network? = null

    fun start() {
        if (receiver != null || started) return
        started = true
        startedAt = SystemClock.elapsedRealtime()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> RoutineEngine.onDevice(DeviceKind.CHARGER_CONNECTED, null)
                    Intent.ACTION_HEADSET_PLUG -> if (!isInitialStickyBroadcast && intent.getIntExtra("state", 0) == 1) {
                        RoutineEngine.onDevice(DeviceKind.HEADSET_PLUGGED, intent.getStringExtra("name"))
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> RoutineEngine.onDevice(DeviceKind.BLUETOOTH_CONNECTED, bluetoothName(intent))
                    // Location back on: place routines paused by GEOFENCE_NOT_AVAILABLE register again. A manifest
                    // receiver would not get this broadcast (API 26+), so it is registered here in the long-lived process.
                    LocationManager.PROVIDERS_CHANGED_ACTION -> RoutineSystem.get(c).locationChanged()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        try {
            // System broadcasts only; nothing else may send these to the app.
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            receiver = r
        } catch (e: Exception) {
            Log.w(TAG, "device receiver not registered: ${e.javaClass.simpleName}")
        }
        watchWifi()
        watchCalendar()
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        started = false
        network?.let { cb -> runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        network = null
        calendar?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        calendar = null
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothName(intent: Intent): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        return try {
            device?.alias ?: device?.name
        } catch (e: SecurityException) {
            null
        }
    }

    private fun watchWifi() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        // FLAG_INCLUDE_LOCATION_INFO (12+) lets the Wi-Fi name through when location is allowed.
        val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) = onWifi(n, caps)
                override fun onLost(n: Network) = lost(n)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) = onWifi(n, caps)
                override fun onLost(n: Network) = lost(n)
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
            network = cb
        } catch (e: Exception) {
            Log.w(TAG, "wifi callback not registered: ${e.javaClass.simpleName}")
        }
    }

    private fun lost(n: Network) {
        if (n == wifi) wifi = null
    }

    private fun onWifi(n: Network, caps: NetworkCapabilities) {
        if (n == wifi) return
        wifi = n
        // A network that was already up when watching began is not a new connection.
        if (SystemClock.elapsedRealtime() - startedAt < SETTLE_MS) return
        val ssid = (caps.transportInfo as? WifiInfo)?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
        RoutineEngine.onDevice(DeviceKind.WIFI_CONNECTED, ssid)
    }

    private fun watchCalendar() {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        // Reads off the main thread.
        val refresh = Runnable { RoutineSystem.get(context).calendarChanged() }
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                // Sync writes come in bursts: read once they settle.
                main.removeCallbacks(refresh)
                main.postDelayed(refresh, CALENDAR_DEBOUNCE_MS)
            }
        }
        try {
            context.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer)
            calendar = observer
        } catch (e: Exception) {
            Log.w(TAG, "calendar observer not registered: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "Routines"
        const val SETTLE_MS = 5_000L
        const val CALENDAR_DEBOUNCE_MS = 10_000L
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
