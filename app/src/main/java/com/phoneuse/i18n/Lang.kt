package com.phoneuse.i18n

import android.os.LocaleList
import java.util.Locale

/**
 * The app's language: English by default, Korean when the device language is Korean.
 *
 * User-facing text is written in place as [tr]("English", "한국어"), so both languages stay next to each other
 * and plain JVM code (routines, nudges, cards) can be localized without a Context. Unit tests run in Korean
 * (`user.language=ko` in app/build.gradle.kts), so existing assertions keep their Korean text.
 */
object Lang {
    @Volatile
    var korean: Boolean = isKorean(Locale.getDefault())

    /** Follows [locales]' first language; called at startup and when the configuration changes. */
    fun update(locales: LocaleList) {
        val locale = locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
        korean = isKorean(locale)
    }

    fun isKorean(locale: Locale): Boolean = locale.language == "ko"

    /** The language tag the model is told to answer in when the user's own words do not settle it. */
    val tag: String get() = if (korean) "ko" else "en"
}

/** [en], or [ko] when the app runs in Korean. */
fun tr(en: String, ko: String): String = if (Lang.korean) ko else en
