package com.phoneuse.phone

import android.content.Context
import com.phoneuse.BuildConfig
import com.phoneuse.agent.PhoneAccess
import com.phoneuse.cards.CardSpec
import com.phoneuse.model.ModelClient
import com.phoneuse.routine.AlarmReminderScheduler
import okhttp3.OkHttpClient

/** The Android side of [PhoneAccess]. One [Geocoder] per process, so Nominatim's one-a-second rule holds across runs. */
object AndroidPhone {
    @Volatile private var geocoder: Geocoder? = null

    fun geocoder(http: OkHttpClient): Geocoder =
        geocoder ?: synchronized(this) {
            geocoder ?: Geocoder(http, userAgent = "PhoneUse/${BuildConfig.VERSION_NAME} (Android)").also { geocoder = it }
        }

    /**
     * [priorCards] are the cards of the conversation's earlier replies, oldest first (calendar_add and photos_album
     * check them). [vision] is a model client for photos_look (without hosted tools); null leaves it unable to see.
     * [trigger] says who started the run, for confirmed writes.
     */
    fun access(
        context: Context,
        http: OkHttpClient,
        vision: ModelClient? = null,
        trigger: RunTrigger = RunTrigger.Other,
        priorCards: suspend () -> List<CardSpec> = { emptyList() },
    ): PhoneAccess {
        val geo = geocoder(http)
        return PhoneAccess(
            location = LocationTool(AndroidLocationSource(context)) { lat, lng -> geo.reverse(lat, lng) },
            places = geo,
            reminders = AlarmReminderScheduler(context),
            contacts = ContactsTool(AndroidContactsSource(context)),
            calendar = CalendarTool(AndroidCalendarSource(context), priorCards),
            alarms = AlarmTool(AndroidAlarmSource(context)),
            photos = PhotosTool(AndroidPhotoSource(context), vision?.let { ModelPhotoVision(it) }, priorCards, bounds = geo),
            calls = CallTool(AndroidCallSource(context), RecipientResolver(AndroidContactsSource(context))),
            controls = ControlsTool(AndroidControlsSource(context)),
            callLog = CallLogTool(AndroidCallLogSource(context)),
            contactAdder = ContactAdder(AndroidContactWriter(context), AndroidContactsSource(context)),
            trigger = trigger,
        )
    }
}
