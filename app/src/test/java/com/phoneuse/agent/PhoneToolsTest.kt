package com.phoneuse.agent

import com.phoneuse.cards.LatLng
import com.phoneuse.cards.PermissionCard
import com.phoneuse.cards.PlacesCard
import com.phoneuse.model.InputItem
import com.phoneuse.phone.GeoPlace
import com.phoneuse.phone.LocationFix
import com.phoneuse.phone.LocationSource
import com.phoneuse.phone.LocationTool
import com.phoneuse.phone.MapsIntents
import com.phoneuse.phone.PlaceSearch
import com.phoneuse.phone.PlacesDistance
import com.phoneuse.routine.ReminderScheduler
import com.phoneuse.screen.ScreenSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class PhoneToolsTest {
    private class Source(var permitted: Boolean = true) : LocationSource {
        override fun hasPermission() = permitted
        override fun isEnabled() = true
        override suspend fun current() = LocationFix(37.4979, 127.0276, 10f)
    }

    private class Reminders(var allowed: Boolean = true) : ReminderScheduler {
        val scheduled = mutableListOf<Pair<Instant, String>>()
        var exact = true
        var permissions = ReminderScheduler.PERMISSIONS
        override fun canNotify() = allowed
        override fun notifyPermissions() = permissions
        override fun oneShot(at: Instant, text: String): Boolean {
            scheduled += at to text
            return exact
        }
    }

    private val search = object : PlaceSearch {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String, nearLat: Double?, nearLng: Double?): List<GeoPlace> {
            queries += "$query@$nearLat,$nearLng"
            return listOf(GeoPlace("역삼역", 37.5006, 127.0364, "역삼역, 서울", 830.0))
        }
    }

    private val seoul = ZoneId.of("Asia/Seoul")
    private val source = Source()
    private val reminders = Reminders()
    private val dev = FakeDeviceController(listOf(ScreenSnapshot("com.app", 1080, 2400, emptyList())))
    private val ex = ToolExecutor(
        dev, FakeUserGate(),
        phone = PhoneAccess(LocationTool(source), search, reminders) { ZonedDateTime.of(2026, 11, 1, 17, 0, 0, 0, seoul) },
    )

    private fun run(name: String, args: String) = runBlocking { ex.execute(InputItem.FunctionCall("c", name, args), null) }

    private val places = """{"card":{"kind":"places","title":"카페","items":[{"name":"역삼역","lat":37.5006,"lng":127.0364,"distance_m":1}]}}"""

    @Test
    fun toolsOnlyWithPhoneAccess() {
        val names = ex.specs.map { it.name }
        assertTrue(names.containsAll(listOf("get_location", "geocode", "remind_at", "open_route")))
        val bare = ToolExecutor(dev, FakeUserGate()).specs.map { it.name }
        assertFalse("get_location" in bare || "geocode" in bare || "remind_at" in bare)
        // open_route needs nothing but the device.
        assertTrue("open_route" in bare)
        // Permission cards are the app's own: show_card does not take the kind.
        val fake = run("show_card", """{"card":{"kind":"permission","label":"위치","permissions":["android.permission.CAMERA"]}}""")
        assertNull(fake.card)
        assertTrue(fake.output, fake.output.startsWith("카드 오류"))
    }

    @Test
    fun locationPermissionNeededShowsPermissionCard() {
        source.permitted = false
        val r = run("get_location", "{}")
        assertTrue(r.output, r.output.startsWith("권한 필요: 위치"))
        val card = r.card as PermissionCard
        assertTrue(card.permissions.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertNull(card.validate())
        // No location: a places card keeps no centre and the model's distances.
        val shown = run("show_card", places).card as PlacesCard
        assertNull(shown.center)
        assertEquals(1.0, shown.items[0].distanceM!!, 0.0)
    }

    @Test
    fun placesCardGetsLastLocationAndDistances() {
        val r = run("get_location", "{}")
        assertTrue(r.output, r.output.contains("37.4979"))
        assertNull(r.card)
        val card = run("show_card", places).card as PlacesCard
        assertEquals(LatLng(37.4979, 127.0276), card.center)
        val d = PlacesDistance.haversineMeters(LatLng(37.4979, 127.0276), LatLng(37.5006, 127.0364))
        assertEquals(d.toInt().toDouble(), card.items[0].distanceM!!, 0.0)
        assertEquals(PlacesDistance.walkMinutes(d), card.items[0].walkMin)
        // A centre the model gave wins over the last location.
        val given = run("show_card", places.replace("\"title\":\"카페\"", "\"title\":\"카페\",\"center\":{\"lat\":37.5006,\"lng\":127.0364}")).card as PlacesCard
        assertEquals(0.0, given.items[0].distanceM!!, 0.0)
    }

    @Test
    fun geocodeReturnsJson() {
        val r = run("geocode", """{"query":"역삼역","near_lat":37.5,"near_lng":127.0}""")
        assertEquals(listOf("역삼역@37.5,127.0"), search.queries)
        assertTrue(r.output, r.output.startsWith("[") && r.output.contains("\"name\":\"역삼역\"") && r.output.contains("\"lng\":127.0364"))
        assertTrue(run("geocode", "{}").output.startsWith("ERROR: invalid arguments"))
    }

    @Test
    fun remindAtInATaintedRunAsksFirst() = runBlocking {
        // A page or message must not schedule a OpenPhoneUse-branded notification with its own text unseen.
        val gate = FakeUserGate().apply { confirmResult = false }
        val tainted = ToolExecutor(
            dev, gate,
            phone = PhoneAccess(LocationTool(source), search, reminders) { ZonedDateTime.of(2026, 11, 1, 17, 0, 0, 0, seoul) },
        )
        tainted.observe()
        val r = tainted.execute(InputItem.FunctionCall("c", "remind_at", """{"time":"18:05","text":"계좌 확인하세요"}"""), null)
        assertTrue(r.output, r.output.startsWith("CANCELLED"))
        assertEquals(com.phoneuse.risk.RiskCategory.CHANGE, gate.confirms.single().first)
        assertTrue(gate.confirms.single().second, "계좌 확인하세요" in gate.confirms.single().second)
        assertTrue(reminders.scheduled.isEmpty())
        gate.confirmResult = true
        tainted.execute(InputItem.FunctionCall("c", "remind_at", """{"time":"18:05","text":"계좌 확인하세요"}"""), null)
        assertEquals(1, reminders.scheduled.size)
    }

    @Test
    fun remindAtSchedulesAppNotification() {
        val r = run("remind_at", """{"time":"18:05","text":"나갈 준비"}""")
        assertTrue(r.output, r.output.startsWith("OK") && r.output.contains("18:05"))
        assertEquals(listOf(ZonedDateTime.of(2026, 11, 1, 18, 5, 0, 0, seoul).toInstant() to "나갈 준비"), reminders.scheduled)
        assertTrue(run("remind_at", """{"time":"내일","text":"x"}""").output.startsWith("ERROR"))
        assertTrue(run("remind_at", """{"time":"2026-10-01T08:00","text":"x"}""").output.startsWith("ERROR"))
        reminders.allowed = false
        val denied = run("remind_at", """{"time":"18:05","text":"x"}""")
        assertTrue(denied.output, denied.output.startsWith("권한 필요: 알림"))
        assertTrue((denied.card as PermissionCard).permissions.contains("android.permission.POST_NOTIFICATIONS"))
        assertEquals(1, reminders.scheduled.size)
    }

    @Test
    fun notificationsOffBelowAndroid13OpensSettingsCard() {
        // Nothing to request below 13: the card has no permissions and opens the notification settings.
        reminders.allowed = false
        reminders.permissions = emptyList()
        val card = run("remind_at", """{"time":"18:05","text":"x"}""").card as PermissionCard
        assertEquals(emptyList<String>(), card.permissions)
        assertEquals(PermissionCard.SETTINGS_NOTIFICATIONS, card.settings)
        assertNull(card.validate())
    }

    @Test
    fun inexactReminderSaysItMayBeLate() {
        reminders.exact = false
        val r = run("remind_at", """{"time":"18:05","text":"버스"}""")
        assertTrue(r.output, r.output.startsWith("OK") && r.output.contains("늦게"))
        reminders.exact = true
        assertFalse(run("remind_at", """{"time":"18:06","text":"버스"}""").output.contains("늦게"))
    }

    @Test
    fun openRoutePrefersInstalledMapApp() {
        dev.apps = listOf(AppInfo(MapsIntents.KAKAO, "카카오맵"))
        val r = run("open_route", """{"lat":37.5,"lng":127.0,"name":"회사"}""")
        assertTrue(r.output, r.output.startsWith("OK"))
        assertTrue(dev.calls.toString(), "openUri(kakaomap://route?ep=37.5,127.0&by=PUBLICTRANSIT)" in dev.calls)
        assertTrue(r.deviceTool)
        assertTrue(run("open_route", """{"lat":137.5,"lng":127.0,"name":"x"}""").output.startsWith("ERROR"))
    }

    @Test
    fun openRouteSendsGoogleLinkToTheMapsApp() {
        dev.apps = listOf(AppInfo(MapsIntents.GOOGLE, "Google 지도"))
        val r = run("open_route", """{"lat":37.5,"lng":127.0,"name":"회사"}""")
        assertTrue(r.output, r.output.startsWith("OK") && r.output.contains("Google 지도"))
        val uri = "intent://www.google.com/maps/dir/?api=1&destination=37.5,127.0&travelmode=transit" +
            "#Intent;scheme=https;package=com.google.android.apps.maps;end"
        assertTrue(dev.calls.toString(), "openUri($uri)" in dev.calls)
    }
}
