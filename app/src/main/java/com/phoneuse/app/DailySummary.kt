package com.phoneuse.app

import com.phoneuse.i18n.tr
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import com.phoneuse.notify.Notifier
import com.phoneuse.notify.Taps
import com.phoneuse.phone.DigestBucket
import com.phoneuse.phone.DigestClassifier
import com.phoneuse.phone.NotificationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Settings "오늘 요약 받기": once a day at the chosen time, a notification counting the day's notifications by
 * digest group ("답장 필요 2 · 돈 나간 것 1 · …"); tapping it opens Today. One inexact daily alarm; it is set
 * again at every app start (after boot too), on a clock or time-zone change (RoutineBootReceiver) and whenever
 * the chosen time changes. Nothing is posted on a quiet day.
 */
object DailySummary {
    private const val TAG = "DailySummary"
    private const val REQUEST_CODE = 7301
    private const val NOTIFICATION_ID = 3
    const val ACTION = "com.phoneuse.DAILY_SUMMARY"

    /** The next time [minute] of the day comes after [now]; null when the summary is off (minute < 0). */
    fun nextAt(now: ZonedDateTime, minute: Int): ZonedDateTime? {
        if (minute < 0) return null
        val today = now.toLocalDate().atTime(LocalTime.of(minute / 60, minute % 60)).atZone(now.zone)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** The notification text: each non-empty group with its count, "나머지" last; null when there is nothing. */
    fun text(groups: Map<DigestBucket, List<*>>): String? =
        DigestBucket.entries.mapNotNull { b -> groups[b]?.size?.takeIf { it > 0 }?.let { "${b.label} $it" } }
            .takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** Sets (or with a negative [minute] cancels) the daily alarm. */
    fun schedule(context: Context, minute: Int) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            app, REQUEST_CODE,
            Intent(app, DailySummaryReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = nextAt(ZonedDateTime.now(), minute)
        if (at == null) {
            am.cancel(pending)
            return
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pending)
    }

    /**
     * Posts the summary of the last 24 hours, silently inside the quiet hours. Tapping it opens Today (the home
     * screen's tiles); its button opens the notification summary the counts come from.
     */
    suspend fun post(context: Context, settings: SettingsStore) {
        val app = context.applicationContext
        if (!Notifier.canPost(app)) return
        val text = text(DigestClassifier.group(NotificationStore.get(app).recent(24))) ?: return
        val now = LocalTime.now()
        val quiet = settings.quietHours.first().contains(now.hour * 60 + now.minute)
        val n = Notifier.builder(app, Notifier.Channel.DAILY_SUMMARY, quiet)
            .setContentTitle(tr("Today's summary", "오늘 요약"))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text.replace(" · ", "\n")))
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(Notifier.open(app, Taps.dailySummary))
            .addAction(Notification.Action.Builder(null as Icon?, tr("Notification summary", "알림 요약"), Notifier.open(app, Taps.digest)).build())
            .setAutoCancel(true)
            .build()
        Notifier.post(app, null, NOTIFICATION_ID, n)
    }

    internal fun log(e: Exception) = Log.w(TAG, "daily summary failed", e)
}

/**
 * Settings "방해하지 않는 시간": inside the quiet hours OpenPhoneUse's notifications go to a low-importance channel,
 * so they arrive without sound or vibration (a channel's importance is what the platform honours).
 */
object QuietChannel {
    private const val ID = "phoneuse_quiet"

    /** [normalId] outside the quiet hours; the quiet channel (created on first use) inside them. */
    fun pick(nm: NotificationManager, quiet: Boolean, normalId: String): String {
        if (!quiet) return normalId
        nm.createNotificationChannel(NotificationChannel(ID, tr("Quiet time", "방해하지 않는 시간"), NotificationManager.IMPORTANCE_LOW))
        return ID
    }
}

/** The daily alarm: posts the summary, then sets tomorrow's. */
class DailySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DailySummary.ACTION) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val settings = AppGraph.settings
                val minute = settings.summaryMinute.first()
                if (minute >= 0) DailySummary.post(context, settings)
                DailySummary.schedule(context, minute)
            } catch (e: Exception) {
                DailySummary.log(e)
            } finally {
                result.finish()
            }
        }
    }
}
