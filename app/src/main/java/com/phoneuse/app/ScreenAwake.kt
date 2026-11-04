package com.phoneuse.app

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the screen on while a run operates the device: with a short screen timeout the screen would otherwise turn
 * off while the model thinks, and a dark screen reads empty and takes no taps. (The overlay cannot do it itself: an
 * accessibility overlay with FLAG_KEEP_SCREEN_ON fails with BadTokenException.) Held from the run's first device
 * use, never for a chat-only run; released when the run ends. The lock lapses on its own after [LIMIT_MS] should a
 * run never end.
 */
class ScreenAwake(context: Context) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Suppress("DEPRECATION")
    private val waking by lazy {
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "OpenPhoneUse:run",
        ).apply { setReferenceCounted(false) }
    }

    /** For runs nobody started just now (routines): keeps a lit screen on, never turns a dark one on. */
    @Suppress("DEPRECATION")
    private val keeping by lazy {
        power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "OpenPhoneUse:routine").apply { setReferenceCounted(false) }
    }

    /** Holds the screen on; [wakeUp] also turns it on when it is off. */
    fun hold(wakeUp: Boolean) {
        runCatching { (if (wakeUp) waking else keeping).acquire(LIMIT_MS) }
    }

    fun release() {
        runCatching { if (waking.isHeld) waking.release() }
        runCatching { if (keeping.isHeld) keeping.release() }
    }

    private companion object {
        const val LIMIT_MS = 2 * 60 * 60 * 1000L
    }
}
