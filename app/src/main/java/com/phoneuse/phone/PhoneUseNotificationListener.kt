package com.phoneuse.phone

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a 7-day history of posted notifications for the digest ([NotificationStore]): only key, app, title,
 * text and time. Skips this app's own, group summaries, ongoing and foreground-service notifications, and
 * those marked secret. Runs only while the user has given notification access in Settings.
 */
class PhoneUseNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val labels = ConcurrentHashMap<String, String>()

    override fun onListenerConnected() {
        instance = this
        prune()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        // Access may have been revoked: nothing adds rows any more, so drop what is past the retention now.
        prune()
    }

    private fun prune() {
        scope.launch {
            try {
                NotificationStore.get(applicationContext).prune()
            } catch (e: Exception) {
                Log.w(TAG, "pruning notifications failed", e)
            }
        }
    }

    /**
     * Read, answered or swiped away by the user (or cleared by its app): no longer a message to answer. The row is
     * kept (marked), so payments and parcels stay in the digest until the 7-day retention ends.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        val key = sbn?.key ?: return
        if (reason !in GONE_REASONS) return
        scope.launch {
            try {
                NotificationStore.get(applicationContext).markRemoved(listOf(key))
            } catch (e: Exception) {
                Log.w(TAG, "marking a notification removed failed", e)
            }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.let { stored(it) } ?: return
        com.phoneuse.routine.RoutineEngine.onNotification(n.packageName, n.title, n.text, n.postedAt)
        com.phoneuse.proactive.ProactiveSystem.onPhoneEvent(applicationContext)
        scope.launch {
            try {
                NotificationStore.get(applicationContext).add(n)
            } catch (e: Exception) {
                Log.w(TAG, "storing a notification failed", e)
            }
        }
    }

    private fun stored(sbn: StatusBarNotification): StoredNotification? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == packageName) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null
        if (n.visibility == Notification.VISIBILITY_SECRET) return null
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null
        // One-time codes are masked before they are stored, so they never reach the disk or the model.
        val both = title + "\n" + text
        return StoredNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = label(sbn.packageName),
            title = SmsParsers.maskCodes(title, both),
            text = SmsParsers.maskCodes(text, both),
            postedAt = sbn.postTime,
            group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
        )
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    companion object {
        private const val TAG = "NotificationListener"

        private val GONE_REASONS = setOf(
            REASON_CLICK, REASON_CANCEL, REASON_CANCEL_ALL, REASON_APP_CANCEL, REASON_APP_CANCEL_ALL, REASON_GROUP_SUMMARY_CANCELED,
        )

        @Volatile private var instance: PhoneUseNotificationListener? = null

        /** True while the user has given OpenPhoneUse notification access. */
        fun enabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /** Clears [keys] from the notification shade while the listener is connected; otherwise nothing. */
        fun cancel(keys: List<String>) {
            val listener = instance ?: return
            try {
                listener.cancelNotifications(keys.toTypedArray())
            } catch (e: SecurityException) {
                Log.w(TAG, "cancel failed", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "cancel failed", e)
            }
        }
    }
}
