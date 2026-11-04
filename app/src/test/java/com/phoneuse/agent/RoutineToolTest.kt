package com.phoneuse.agent

import com.phoneuse.cards.PermissionCard
import com.phoneuse.cards.RoutineCard
import com.phoneuse.memory.InMemoryMemoryStore
import com.phoneuse.memory.MemoryKind
import com.phoneuse.memory.NewMemory
import com.phoneuse.model.InputItem
import com.phoneuse.phone.GeoPlace
import com.phoneuse.phone.PlaceSearch
import com.phoneuse.routine.PlaceEdge
import com.phoneuse.routine.Trigger
import com.phoneuse.screen.ScreenSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineToolTest {
    private class Search : PlaceSearch {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String, nearLat: Double?, nearLng: Double?): List<GeoPlace> {
            queries += query
            return if (query.contains("테헤란로") || query == "회사") listOf(GeoPlace("테헤란로 152", 37.5003, 127.0365, "서울 강남구 테헤란로 152")) else emptyList()
        }
    }

    private val search = Search()
    private val memories = InMemoryMemoryStore()
    private val dev = FakeDeviceController(listOf(ScreenSnapshot("com.app", 1080, 2400, emptyList())))

    private fun executor(access: RoutineAccess? = RoutineAccess(search, memories)) = ToolExecutor(dev, FakeUserGate(), routines = access)

    private fun run(args: String, ex: ToolExecutor = executor()) = runBlocking { ex.execute(InputItem.FunctionCall("c", "make_routine", args), null) }

    @Test
    fun previewCardOnlyNothingSaved() {
        val r = run("""{"routine":{"title":"아침 브리핑","trigger":{"type":"time","hour":7,"minute":30},"action":{"type":"ask","prompt":"브리핑해줘"}},"sentence":"매일 7시 반에 브리핑"}""")
        val card = r.card as RoutineCard
        assertTrue(r.output, r.output.startsWith("OK: 미리보기 카드"))
        assertTrue(r.output, r.output.contains("[루틴 저장]"))
        val routine = card.parsed()!!
        assertEquals(Trigger.Time(7, 30), routine.trigger)
        assertEquals("매일 7시 반에 브리핑", routine.sentence)
        assertFalse(routine.sendWithoutConfirm)
        assertNull(card.validate())
    }

    @Test
    fun placeNameComesFromPlaceMemory() {
        runBlocking { memories.add(NewMemory("회사는 서울 강남구 테헤란로 152에 있어", MemoryKind.PLACE)) }
        val r = run("""{"routine":{"title":"일 모드","trigger":{"type":"place","name":"회사","on":"enter"},"action":{"type":"ask","prompt":"무음으로","needs_device":true}}}""")
        val card = r.card as RoutineCard
        assertEquals("기억에서", card.placeFrom)
        assertEquals("서울 강남구 테헤란로 152", card.placeAddress)
        assertEquals(listOf("서울 강남구 테헤란로 152"), search.queries)
        assertEquals(Trigger.Place("회사", 37.5003, 127.0365, 200, PlaceEdge.ENTER), card.parsed()!!.trigger)
    }

    @Test
    fun placeCoordinatesInAMemoryNeedNoSearch() {
        runBlocking { memories.add(NewMemory("집 위치 37.5665, 126.9780", MemoryKind.PLACE)) }
        val r = run("""{"routine":{"title":"귀가","trigger":{"type":"place","name":"집"},"action":{"type":"notify","text":"집 도착"}}}""")
        assertEquals(Trigger.Place("집", 37.5665, 126.978), (r.card as RoutineCard).parsed()!!.trigger)
        assertTrue(search.queries.isEmpty())
    }

    @Test
    fun placeGeocodedWhenNotRemembered() {
        val r = run("""{"routine":{"title":"일 모드","trigger":{"type":"place","name":"회사"},"action":{"type":"notify","text":"도착"}}}""")
        assertEquals("지도 검색", (r.card as RoutineCard).placeFrom)
        val missing = run("""{"routine":{"title":"x","trigger":{"type":"place","name":"어딘가"},"action":{"type":"notify","text":"도착"}}}""")
        assertNull(missing.card)
        assertTrue(missing.output, missing.output.startsWith("ERROR:") && missing.output.contains("주소"))
    }

    @Test
    fun invalidRoutineGoesBackToTheModel() {
        val r = run("""{"routine":{"title":"x","trigger":{"type":"place","name":"회사","lat":37.5,"lng":127.0,"radius_m":50},"action":{"type":"notify","text":"a"}}}""")
        assertNull(r.card)
        assertTrue(r.output, r.output.startsWith("ERROR: 루틴 오류:") && r.output.contains("150"))
    }

    @Test
    fun placeRoutinesNeedPlayServices() {
        val r = run(
            """{"routine":{"title":"x","trigger":{"type":"place","name":"회사","lat":37.5,"lng":127.0},"action":{"type":"notify","text":"a"}}}""",
            executor(RoutineAccess(search, memories, placesSupported = false)),
        )
        assertTrue(r.output, r.output.contains("이 기기에서 못 씀"))
        assertNull(r.card)
    }

    @Test
    fun missingPermissionRidesOnTheCard() {
        val card = PermissionCard("근처 기기", listOf("android.permission.BLUETOOTH_CONNECT"))
        val r = run(
            """{"routine":{"title":"차","trigger":{"type":"device","kind":"bluetooth","detail":"내 차"},"action":{"type":"notify","text":"a"}}}""",
            executor(RoutineAccess(search, memories, permissionFor = { card })),
        )
        assertEquals(card, (r.card as RoutineCard).permission)
        assertTrue(r.output, r.output.contains("근처 기기 권한"))
    }

    @Test
    fun hiddenWithoutAccess() {
        assertFalse(executor(null).specs.any { it.name == "make_routine" })
        assertTrue(executor().specs.any { it.name == "make_routine" })
        assertTrue(run("""{"routine":{}}""", executor(null)).output.startsWith("ERROR"))
    }
}
