package com.phoneuse.phone

import com.phoneuse.i18n.tr
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

private fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** Contacts by display name (ContactsContract's filter, which also matches initials and nicknames). */
class AndroidContactsSource(private val context: Context) : ContactsSource {
    override fun hasPermission() = context.granted(android.Manifest.permission.READ_CONTACTS)

    override fun search(name: String, limit: Int): List<Contact> {
        val resolver = context.contentResolver
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(name))
        val people = ArrayList<Pair<Long, String>>()
        resolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c ->
            while (c.moveToNext() && people.size < limit) {
                val display = c.getString(1) ?: continue
                people += c.getLong(0) to display
            }
        }
        return people.map { (id, display) ->
            Contact(
                display,
                column(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.CONTACT_ID, id),
                column(ContactsContract.CommonDataKinds.Email.CONTENT_URI, ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.CommonDataKinds.Email.CONTACT_ID, id),
            )
        }
    }

    private fun column(uri: Uri, column: String, idColumn: String, id: Long): List<String> {
        val out = LinkedHashSet<String>()
        context.contentResolver.query(uri, arrayOf(column), "$idColumn = ?", arrayOf(id.toString()), null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out.toList()
    }
}

/** Event instances of visible calendars; new events go to the primary writable one. */
class AndroidCalendarSource(private val context: Context, private val zone: () -> ZoneId = ZoneId::systemDefault) : CalendarSource {
    override fun canRead() = context.granted(android.Manifest.permission.READ_CALENDAR)
    override fun canWrite() = context.granted(android.Manifest.permission.WRITE_CALENDAR)

    override fun events(from: LocalDateTime, to: LocalDateTime): List<CalendarEvent> {
        val z = zone()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.atZone(z).toInstant().toEpochMilli())
            ContentUris.appendId(it, to.atZone(z).toInstant().toEpochMilli())
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
        )
        val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
            "(${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ${CalendarContract.Events.STATUS_CANCELED}) AND " +
            // NULL (no attendee row for the owner) is not "declined".
            "(${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL OR " +
            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})"
        val out = ArrayList<CalendarEvent>()
        context.contentResolver.query(uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(5) != 0
                // All-day times are UTC midnights.
                val tz = if (allDay) ZoneOffset.UTC else z
                fun at(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), tz)
                out += CalendarEvent(c.getLong(0), c.getString(1).orEmpty().ifBlank { tr("(no title)", tr("(no title)", "(제목 없음)")) }, at(c.getLong(2)), at(c.getLong(3)), c.getString(4), allDay)
            }
        }
        // Instances are queried in device time; all-day ones near the edges may fall outside the range.
        return out.filter { it.start < to && from < (if (it.end.isAfter(it.start)) it.end else it.start.plusMinutes(1)) }
    }

    override fun insert(title: String, start: LocalDateTime, end: LocalDateTime, location: String?, reminderMin: Int?, allDay: Boolean): Long? {
        val calendar = writableCalendar() ?: return null
        val tz = if (allDay) ZoneOffset.UTC else zone()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.atZone(tz).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.atZone(tz).toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone().id)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            put(CalendarContract.Events.HAS_ALARM, if (reminderMin != null) 1 else 0)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        val id = ContentUris.parseId(uri)
        // The event is saved even if its reminder cannot be (a failure here must not read as "not saved").
        if (reminderMin != null) try {
            context.contentResolver.insert(
                CalendarContract.Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id)
                    put(CalendarContract.Reminders.MINUTES, reminderMin)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                },
            )
        } catch (e: Exception) {
            android.util.Log.w("PhoneUse", "reminder insert failed: ${e.javaClass.simpleName}")
        }
        return id
    }

    /** The primary visible calendar the user may add to; else the first such one. */
    private fun writableCalendar(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        var first: Long? = null
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, "${CalendarContract.Calendars._ID} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(1) == 1) return c.getLong(0)
                if (first == null) first = c.getLong(0)
            }
        }
        return first
    }
}

/** The clock app's next alarm, and setting one without its screen. */
class AndroidAlarmSource(private val context: Context) : AlarmSource {
    override fun next(): ZonedDateTime? {
        val info = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        return Instant.ofEpochMilli(info.triggerTime).atZone(ZoneId.systemDefault())
    }

    override fun set(hour: Int, minute: Int, label: String): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
