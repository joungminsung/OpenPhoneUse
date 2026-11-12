package com.phoneuse.phone

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The permissions this build's manifest asks for. Galaxy phones refuse to install an APK whose permissions look like
 * a phishing app's (texts, calls, call log and contacts next to accessibility), so the release leaves those out and
 * the tools that need them are not offered; the agent does the same work through the screen instead.
 */
object Declared {
    @Volatile private var cached: Set<String>? = null

    fun has(context: Context, permission: String): Boolean = permission in all(context)

    private fun all(context: Context): Set<String> = cached ?: try {
        val app = context.applicationContext
        val pm = app.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        }
        info.requestedPermissions.orEmpty().toSet()
    } catch (e: PackageManager.NameNotFoundException) {
        emptySet()
    }.also { cached = it }
}
