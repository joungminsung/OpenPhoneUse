package com.phoneuse.i18n

import android.os.LocaleList
import java.util.Locale

/**
 * The device's region (ISO country, upper case), independent of the app language: things written by local
 * services, such as parcel notifications, follow where the phone is, not which language the app shows.
 */
object Region {
    @Volatile
    var country: String = Locale.getDefault().country.uppercase(Locale.ROOT)

    /** Follows the first locale that names a country; called at startup and when the configuration changes. */
    fun update(locales: LocaleList) {
        for (i in 0 until locales.size()) {
            val c = locales.get(i).country
            if (c.isNotEmpty()) {
                country = c.uppercase(Locale.ROOT)
                return
            }
        }
    }
}
