package com.phoneuse

import android.app.Application
import com.phoneuse.app.AppGraph
import com.phoneuse.i18n.Lang
import org.osmdroid.config.Configuration
import java.io.File

class PhoneUseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        updateLanguage(resources.configuration)
        // OSM tile policy: identify the app; keep tiles in the cache dir (no storage permission needed).
        Configuration.getInstance().apply {
            userAgentValue = "PhoneUse/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        AppGraph.init(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLanguage(newConfig)
    }

    /** A per-app language (Android 13+) wins: a configuration change can carry the system's languages instead. */
    private fun updateLanguage(config: android.content.res.Configuration) {
        val perApp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getSystemService(android.app.LocaleManager::class.java).applicationLocales
        } else {
            android.os.LocaleList.getEmptyLocaleList()
        }
        Lang.update(if (perApp.isEmpty) config.locales else perApp)
    }
}
