package com.phoneuse.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsIntentsTest {
    @Test
    fun prefersNaverThenKakaoThenGoogle() {
        val both = MapsIntents.routeTarget(37.5, 127.0, "강남역 11번 출구") { true }
        assertEquals(MapsIntents.NAVER, both.packageName)
        assertEquals(
            "nmap://route/public?dlat=37.5&dlng=127.0&dname=%EA%B0%95%EB%82%A8%EC%97%AD%2011%EB%B2%88%20%EC%B6%9C%EA%B5%AC&appname=com.phoneuse",
            both.uri,
        )
        val kakao = MapsIntents.routeTarget(37.5, 127.0, "x") { it == MapsIntents.KAKAO }
        assertEquals(MapsIntents.KAKAO, kakao.packageName)
        assertEquals("kakaomap://route?ep=37.5,127.0&by=PUBLICTRANSIT", kakao.uri)
        val web = MapsIntents.routeTarget(37.5, 127.0, "x") { false }
        assertEquals(null, web.packageName)
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=37.5,127.0", web.uri)
    }

    @Test
    fun googleMapsAppGetsTransitDirections() {
        val google = MapsIntents.routeTarget(37.5, 127.0, "x") { it == MapsIntents.GOOGLE }
        assertEquals(MapsIntents.GOOGLE, google.packageName)
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=37.5,127.0&travelmode=transit", google.uri)
        // Naver still wins over Google.
        assertEquals(MapsIntents.NAVER, MapsIntents.routeTarget(37.5, 127.0, "x") { it != MapsIntents.KAKAO }.packageName)
        assertEquals("네이버 지도", MapsIntents.appName(MapsIntents.NAVER))
    }

    @Test
    fun launchUriPinsHttpsLinksToTheApp() {
        val google = MapsIntents.routeTarget(37.5, 127.0, "x") { it == MapsIntents.GOOGLE }
        assertEquals(
            "intent://www.google.com/maps/dir/?api=1&destination=37.5,127.0&travelmode=transit#Intent;scheme=https;package=com.google.android.apps.maps;end",
            MapsIntents.launchUri(google),
        )
        // App schemes and the plain web link stay as they are.
        val naver = MapsIntents.routeTarget(37.5, 127.0, "a b") { true }
        assertEquals(naver.uri, MapsIntents.launchUri(naver))
        assertTrue("dname=a%20b" in naver.uri)
        val web = MapsIntents.routeTarget(37.5, 127.0, "x") { false }
        assertEquals(web.uri, MapsIntents.launchUri(web))
    }
}
