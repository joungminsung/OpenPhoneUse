package com.phoneuse.routine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import java.time.Instant

/**
 * [ReminderScheduler] for remind_at: the reminder is kept in the routine store and delivered by the routine
 * scheduler's one exact alarm (RoutineSystem), so it survives a reboot. Exact while idle: USE_EXACT_ALARM (API 33+,
 * granted at install for this personal build) or SCHEDULE_EXACT_ALARM (API 31–32); inexact only if the system
 * still refuses.
 */
class AlarmReminderScheduler(context: Context) : ReminderScheduler {
    private val app = context.applicationContext

    override fun canNotify(): Boolean = notificationsOn(app)

    override fun notifyPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ReminderScheduler.PERMISSIONS else emptyList()

    override fun oneShot(at: Instant, text: String): Boolean = RoutineSystem.get(app).oneShot(at, text)

    companion object {
        const val CHANNEL_ID = "phoneuse_reminders"
        const val ACTION = "com.phoneuse.REMIND"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"

        /** The app's notifications are on: the runtime permission on 13+, the settings switch on every version. */
        fun notificationsOn(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

/** Reminders set before routines took them over (an alarm still pending from an older version). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmReminderScheduler.ACTION) return
        val text = intent.getStringExtra(AlarmReminderScheduler.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        RoutineSystem.postReminder(context, intent.getIntExtra(AlarmReminderScheduler.EXTRA_ID, 0), text)
    }
}
