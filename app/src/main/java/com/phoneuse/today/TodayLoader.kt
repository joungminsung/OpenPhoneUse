package com.phoneuse.today

import android.content.Context
import com.phoneuse.phone.AndroidAlarmSource
import com.phoneuse.phone.AndroidCalendarSource
import com.phoneuse.phone.AndroidSmsSource
import com.phoneuse.phone.NotificationStore
import com.phoneuse.phone.PhoneUseNotificationListener
import com.phoneuse.proactive.NudgeReaders
import com.phoneuse.routine.UpcomingEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Reads [TodaySources] from the phone for the home tiles. Each source needs its own access (notification access,
 * SMS, calendar); one that is missing or fails just leaves its tiles out. Nothing leaves the phone.
 */
object TodayLoader {
    private const val SMS_LIMIT = 1000

    suspend fun load(context: Context, now: ZonedDateTime): TodaySources = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val notifications = safe { if (PhoneUseNotificationListener.enabled(app)) NotificationStore.get(app).recent(24) else null }.orEmpty()
        // From the start of last month, for the month-on-month spending.
        val since = now.toLocalDate().withDayOfMonth(1).minusMonths(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
        val smsSource = AndroidSmsSource(app)
        val smsReadable = safe { smsSource.canRead() } == true
        val sms = if (smsReadable) safe { smsSource.query(since, null, SMS_LIMIT) }.orEmpty() else emptyList()
        val week = safe { if (PhoneUseNotificationListener.enabled(app)) NotificationStore.get(app).recent(7 * 24) else null }.orEmpty()
        val events = safe {
            val calendar = AndroidCalendarSource(app)
            if (!calendar.canRead()) return@safe null
            val start = now.toLocalDateTime()
            calendar.events(start, now.toLocalDate().plusDays(1).atStartOfDay()).map {
                UpcomingEvent(it.title, it.start.atZone(if (it.allDay) ZoneOffset.UTC else now.zone).toInstant().toEpochMilli(), it.location, it.allDay)
            }
        }.orEmpty()
        TodaySources(
            notifications = notifications,
            parcels = NudgeReaders.parcels(notifications, sms),
            payments = NudgeReaders.payments(week, sms),
            events = events,
            nextAlarm = safe { AndroidAlarmSource(app).next()?.toInstant()?.toEpochMilli() },
            // Notifications reach back a week only; the month and its comparison need the card texts.
            monthCovered = smsReadable,
        )
    }

    private inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
