package com.phoneuse.phone

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/**
 * The phone's torch, volumes, media buttons and state through public APIs: no runtime permission, no
 * accessibility service. Only the network read needs a manifest permission (ACCESS_NETWORK_STATE, a normal one).
 */
class AndroidControlsSource(private val context: Context) : ControlsSource {
    private val audio: AudioManager
        get() = context.getSystemService(AudioManager::class.java) ?: throw IllegalStateException("no audio service")

    override fun torch(on: Boolean): TorchResult {
        val cameras = context.getSystemService(CameraManager::class.java) ?: return TorchResult.NO_FLASH
        return try {
            val id = cameras.cameraIdList.firstOrNull { camera ->
                val c = cameras.getCameraCharacteristics(camera)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return TorchResult.NO_FLASH
            cameras.setTorchMode(id, on)
            TorchResult.DONE
        } catch (e: CameraAccessException) {
            when (e.reason) {
                CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE -> TorchResult.IN_USE
                else -> TorchResult.FAILED
            }
        } catch (e: IllegalArgumentException) {
            // The camera went away between the lookup and the call.
            TorchResult.FAILED
        }
    }

    override fun volumeFixed(): Boolean = audio.isVolumeFixed

    override fun maxVolume(stream: VolumeStream): Int = audio.getStreamMaxVolume(code(stream))

    override fun volume(stream: VolumeStream): Int = audio.getStreamVolume(code(stream))

    // Flags 0: no volume panel over whatever is on screen.
    override fun setVolume(stream: VolumeStream, level: Int) = audio.setStreamVolume(code(stream), level, 0)

    override fun ringer(): RingerMode? = when (audio.ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> RingerMode.SOUND
        AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
        AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
        else -> null
    }

    override fun setRinger(mode: RingerMode) {
        audio.ringerMode = when (mode) {
            RingerMode.SOUND -> AudioManager.RINGER_MODE_NORMAL
            RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
        }
    }

    override fun mediaKey(keyCode: Int) {
        val at = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_UP, keyCode, 0))
    }

    override fun musicActive(): Boolean = audio.isMusicActive

    override fun battery(): BatteryLevel? {
        // The sticky broadcast: a null receiver only reads the last one, nothing is registered.
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val manager = context.getSystemService(BatteryManager::class.java)
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            level * 100 / scale
        } else {
            manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        }
        if (percent !in 0..100) return null
        // Plugged in counts as charging, also when the battery is already full.
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val charging = if (plugged >= 0) plugged != 0 else manager?.isCharging == true
        return BatteryLevel(percent, charging)
    }

    /** Readable without notification-policy access (only changing it needs that). */
    override fun doNotDisturb(): Boolean? = when (context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> false
        NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_ALARMS, NotificationManager.INTERRUPTION_FILTER_NONE -> true
        else -> null
    }

    override fun airplaneMode(): Boolean = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    override fun network(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: throw IllegalStateException("no connectivity service")
        val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return "none"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }

    override fun freeStorageBytes(): Long = StatFs(Environment.getDataDirectory().path).availableBytes

    private fun code(stream: VolumeStream): Int = when (stream) {
        VolumeStream.MEDIA -> AudioManager.STREAM_MUSIC
        VolumeStream.RING -> AudioManager.STREAM_RING
        VolumeStream.ALARM -> AudioManager.STREAM_ALARM
        VolumeStream.CALL -> AudioManager.STREAM_VOICE_CALL
    }
}
