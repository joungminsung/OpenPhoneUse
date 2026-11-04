package com.phoneuse.phone

import com.phoneuse.i18n.tr
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.net.URLEncoder

/**
 * Hands public-transport directions to an installed map app: Naver Map, then Kakao Map, then the Google Maps
 * app, else Google Maps on the web. Used by card buttons ([route]) and the open_route tool ([routeTarget]).
 */
object MapsIntents {
    const val NAVER = "com.nhn.android.nmap"
    const val KAKAO = "net.daum.android.map"
    const val GOOGLE = "com.google.android.apps.maps"

    fun appName(pkg: String?): String = when (pkg) {
        NAVER -> tr("Naver Map", "네이버 지도")
        KAKAO -> tr("KakaoMap", "카카오맵")
        GOOGLE -> tr("Google Maps", "Google 지도")
        else -> tr("Web map", "웹 지도")
    }

    /** Where a route goes: the URI and the app package it is meant for (null: any browser or app). */
    data class Target(val uri: String, val packageName: String?)

    fun routeTarget(lat: Double, lng: Double, name: String, installed: (String) -> Boolean): Target = when {
        installed(NAVER) -> Target(
            "nmap://route/public?dlat=$lat&dlng=$lng&dname=${encode(name)}&appname=com.phoneuse",
            NAVER,
        )
        installed(KAKAO) -> Target("kakaomap://route?ep=$lat,$lng&by=PUBLICTRANSIT", KAKAO)
        // google.navigation: has no transit mode, so the directions link goes to the app instead.
        installed(GOOGLE) -> Target("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=transit", GOOGLE)
        else -> Target("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng", null)
    }

    /**
     * [target] as one URI string for a controller that cannot set a package: an https link meant for an app
     * becomes an intent: URI naming that package, so it opens in the Google Maps app, not a browser.
     */
    fun launchUri(target: Target): String {
        val pkg = target.packageName ?: return target.uri
        if (!target.uri.startsWith("https://")) return target.uri
        return "intent://" + target.uri.removePrefix("https://") + "#Intent;scheme=https;package=$pkg;end"
    }

    fun route(context: Context, lat: Double, lng: Double, name: String): Intent {
        val target = routeTarget(lat, lng, name) { isInstalled(context, it) }
        return Intent(Intent.ACTION_VIEW, Uri.parse(target.uri)).apply {
            target.packageName?.let { setPackage(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // Visible through the <queries> entries in the manifest.
    @Suppress("DEPRECATION") // The flags overload needs API 33; minSdk is 30.
    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Percent-encoding with %20 for spaces (URLEncoder writes '+', which map apps show literally). */
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
