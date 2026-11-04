package com.phoneuse.cards

import com.phoneuse.agent.AgentEvent
import com.phoneuse.agent.AgentLoop
import com.phoneuse.agent.FakeDeviceController
import com.phoneuse.agent.FakeUserGate
import com.phoneuse.agent.SteeringQueue
import com.phoneuse.agent.ToolExecutor
import com.phoneuse.model.InputItem
import com.phoneuse.model.ModelClient
import com.phoneuse.model.ModelTurn
import com.phoneuse.model.ToolSpec
import com.phoneuse.screen.ScreenSnapshot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardJsonTest {
    private fun ok(json: String): CardSpec {
        val parsed = CardJson.parse(json)
        assertTrue("expected Ok for $json but got $parsed", parsed is CardParse.Ok)
        return (parsed as CardParse.Ok).card
    }

    private fun error(json: String): String {
        val parsed = CardJson.parse(json)
        assertTrue("expected Error for $json but got $parsed", parsed is CardParse.Error)
        return (parsed as CardParse.Error).reason
    }

    private val places = """
        {"kind":"places","title":"문 연 약국","center":{"lat":37.5,"lng":127.0},
         "items":[{"name":"사거리 약국","lat":37.501,"lng":127.002,"distance_m":280,"walk_min":4,
                   "status_text":"영업 중 ~23:00","phone":"02-123-4567","address":"서울"},
                  {"name":"역 앞 24시 약국","lat":37.505,"lng":127.004}]}
    """

    private val every = listOf(
        places,
        """{"kind":"route","depart_at":"18:12","arrive_at":"18:52","summary":"여유 8분 · 40분",
            "legs":[{"at":"18:12","kind":"walk","label":"집에서 출발","detail":"정류장까지 5분"},
                    {"at":"18:18","kind":"bus","label":"472번 버스","detail":"6정거장"},
                    {"at":"18:31","kind":"subway","label":"2호선 강남 방면","detail":"5정거장"}],
            "dest":{"name":"강남역 11번 출구","lat":37.498,"lng":127.027}}""",
        """{"kind":"event","title":"치과","start":"2026-11-10T15:00","end":"2026-11-10T16:00","location":"바른 치과",
            "reminder_min":30,"conflicts":[{"title":"팀 주간 회의","start":"2026-11-10T14:30","end":"2026-11-10T15:30"}]}""",
        """{"kind":"photos","title":"제주 바다","uris":["content://media/external/images/media/1","content://media/external/images/media/2"],
            "filters":[{"label":"협재","uris":["content://media/external/images/media/1"]}]}""",
        """{"kind":"results","title":"주말 전시","items":[{"title":"오래된 동네 사진전","subtitle":"시립 미술관","meta":"22분","url":"https://example.org/a"}],
            "sources":[{"title":"시립 미술관","url":"https://example.org"}]}""",
        """{"kind":"draft","channel":"sms","to":"집주인","body":"안녕하세요, 302호입니다."}""",
        """{"kind":"digest","groups":[{"label":"답장 필요","tone":"urgent","items":[{"who":"엄마","text":"저녁 먹고 오니?","at":"19:12","action":"답장 쓰기"}]},
            {"label":"나머지","tone":"muted","items":[{"text":"광고 4 · 앱 업데이트 2"}]}]}""",
        """{"kind":"stat","label":"11월 배달 앱","value":"₩186,400","delta":"+₩32,100","bars":[{"label":"1주","value":42000},{"label":"2주","value":61000}]}""",
    )

    @Test fun parsesEveryKind() {
        val cards = every.map { ok(it) }
        assertEquals(
            listOf(
                PlacesCard::class, RouteCard::class, EventCard::class, PhotosCard::class,
                ResultsCard::class, DraftCard::class, DigestCard::class, StatCard::class,
            ),
            cards.map { it::class },
        )
        cards.forEach { assertNull(it.toString(), it.validate()) }

        val p = cards[0] as PlacesCard
        assertEquals(280.0, p.items[0].distanceM!!, 0.0)
        assertEquals(4, p.items[0].walkMin)
        assertEquals(LatLng(37.5, 127.0), p.center)
        assertEquals("bus", (cards[1] as RouteCard).legs[1].kind)
        assertEquals(30, (cards[2] as EventCard).reminderMin)

        // Stored with the message and read back unchanged.
        assertEquals(cards, CardJson.decodeList(CardJson.encodeList(cards)))
    }

    @Test fun numericStatValueIsAccepted() {
        val stat = ok("""{"kind":"stat","label":"걸음","value":8231}""") as StatCard
        assertEquals("8231", stat.value)
    }

    @Test fun unknownKindIsAnError() {
        assertTrue(error("""{"kind":"weather","title":"x"}""").contains("weather"))
        assertTrue(error("""{"title":"x"}""").contains("kind"))
    }

    @Test fun decodeListSkipsBrokenEntriesAndEmptyStoresNull() {
        assertNull(CardJson.encodeList(emptyList()))
        assertEquals(emptyList<CardSpec>(), CardJson.decodeList(null))
        assertEquals(emptyList<CardSpec>(), CardJson.decodeList("not json"))
        val raw = "[" + places + """,{"kind":"nope"}]"""
        assertEquals(listOf(ok(places)), CardJson.decodeList(raw))
    }

    @Test fun invalidCardReturnsErrorAndKeepsText() = runTest {
        val snap = ScreenSnapshot("com.app", 1080, 2400, emptyList())
        val executor = ToolExecutor(FakeDeviceController(listOf(snap)), FakeUserGate())

        // Directly: an invalid card is reported to the model and carries no card.
        val bad = """{"card":{"kind":"places","title":"약국","items":[]}}"""
        val direct = executor.execute(InputItem.FunctionCall("c0", "show_card", bad), null)
        assertTrue(direct.output, direct.output.startsWith(CardJson.ERROR_PREFIX))
        assertNull(direct.card)

        // Through the loop: no card event, the reply text still finishes the run.
        val model = object : ModelClient {
            val inputs = mutableListOf<List<InputItem>>()
            val script = ArrayDeque(
                listOf(
                    ModelTurn(null, listOf(InputItem.FunctionCall("c1", "show_card", bad)), null),
                    ModelTurn("사거리 약국이 가장 가까워요", emptyList(), null),
                ),
            )

            override suspend fun next(
                instructions: String,
                input: List<InputItem>,
                tools: List<ToolSpec>,
                onTextDelta: (String) -> Unit,
            ): ModelTurn {
                inputs += input.toList()
                return script.removeFirst()
            }
        }
        val events = AgentLoop(model, executor, SteeringQueue()).run(emptyList(), "약국", observeFirst = false).toList()
        assertTrue(events.none { it is AgentEvent.Card })
        assertEquals(AgentEvent.Finished("사거리 약국이 가장 가까워요"), events.last())
        val output = model.inputs[1].filterIsInstance<InputItem.FunctionOutput>().single().output
        assertTrue(output, output.startsWith("카드 오류: "))
    }

    @Test fun validCardIsEmittedAsCardEvent() = runTest {
        val snap = ScreenSnapshot("com.app", 1080, 2400, emptyList())
        val executor = ToolExecutor(FakeDeviceController(listOf(snap)), FakeUserGate())
        val result = executor.execute(InputItem.FunctionCall("c0", "show_card", """{"card":$places}"""), null)
        assertNotNull(result.card)
        assertTrue(result.output, !result.output.startsWith(CardJson.ERROR_PREFIX))
        // A card passed as a JSON string is accepted too.
        val quoted = kotlinx.serialization.json.JsonPrimitive(places.trim()).toString()
        assertNotNull(executor.execute(InputItem.FunctionCall("c1", "show_card", """{"card":$quoted}"""), null).card)
    }

    @Test fun placesRejectsOutOfRangeCoordinates() {
        val lat = error("""{"kind":"places","title":"t","items":[{"name":"a","lat":91,"lng":127}]}""")
        assertTrue(lat, lat.contains("lat"))
        val lng = error("""{"kind":"places","title":"t","items":[{"name":"a","lat":37,"lng":-181}]}""")
        assertTrue(lng, lng.contains("lng"))
        val center = error("""{"kind":"places","title":"t","center":{"lat":-95,"lng":0},"items":[{"name":"a","lat":37,"lng":127}]}""")
        assertTrue(center, center.contains("center"))
        val many = (1..11).joinToString(",") { """{"name":"p$it","lat":37,"lng":127}""" }
        assertTrue(error("""{"kind":"places","title":"t","items":[$many]}""").contains("items"))
    }

    @Test fun photosRequiresUris() {
        assertTrue(error("""{"kind":"photos","title":"t","uris":[]}""").contains("uris"))
        assertTrue(error("""{"kind":"photos","title":"t"}""").contains("uris"))
        assertTrue(error("""{"kind":"photos","title":"t","uris":["file:///sdcard/a.jpg"]}""").contains("content://"))
        val many = (1..61).joinToString(",") { "\"content://media/external/images/media/$it\"" }
        assertTrue(error("""{"kind":"photos","title":"t","uris":[$many]}""").contains("uris"))
        val stray = error(
            """{"kind":"photos","title":"t","uris":["content://media/external/images/media/1"],"filters":[{"label":"x","uris":["content://media/external/images/media/2"]}]}""",
        )
        assertTrue(stray, stray.contains("filters"))
    }

    @Test fun emptyFilterIsRejected() {
        val e = error("""{"kind":"photos","title":"t","uris":["content://media/external/images/media/1"],"filters":[{"label":"x","uris":[]}]}""")
        assertTrue(e, e.contains("filters"))
    }

    @Test fun photosAcceptOnlyMediaProviders() {
        for (u in listOf(
            "content://media/external/images/media/1",
            "content://media/picker/0/com.android.providers.media.photopicker/media/1",
            "content://com.android.providers.media.documents/document/image%3A1",
            "content://com.android.providers.media.photopicker/media/1",
        )) {
            ok("""{"kind":"photos","title":"t","uris":["$u"]}""")
        }
        assertTrue(error("""{"kind":"photos","title":"t","uris":["content://com.evil.provider/x"]}""").contains("uris"))
        // Other MediaStore collections are not photos.
        for (u in listOf(
            "content://media/external/file/12",
            "content://media/external/downloads/12",
            "content://media/external/audio/media/12",
            "content://media/external/video/media/12",
            "content://media/external/images/media/12/../../file/3",
            "content://media/external/images/media/",
            "content://media/1",
            "content://com.android.providers.media.documents/document/video%3A1",
            "content://media/external/images/media/12?x=1",
        )) {
            assertFalse(u, isPhotoUri(u))
            assertTrue(u, error("""{"kind":"photos","title":"t","uris":["$u"]}""").contains("uris"))
        }
        assertTrue(isPhotoUri("content://media/external_primary/images/media/7"))
        assertTrue(isPhotoUri("content://com.android.providers.media.documents/document/image:7"))
    }

    @Test fun listsAndStringsAreCapped() {
        val legs = (1..21).joinToString(",") { """{"at":"18:00","kind":"walk","label":"l"}""" }
        assertTrue(error("""{"kind":"route","depart_at":"18:00","arrive_at":"18:40","legs":[$legs],"dest":{"name":"d"}}""").contains("legs"))
        val items = (1..21).joinToString(",") { """{"text":"t"}""" }
        assertTrue(error("""{"kind":"digest","groups":[{"label":"g","tone":"info","items":[$items]}]}""").contains("items"))
        val body = "가".repeat(2001)
        assertTrue(error("""{"kind":"draft","channel":"sms","body":"$body"}""").contains("2000"))
        ok("""{"kind":"draft","channel":"sms","body":"${"가".repeat(2000)}"}""")
        val many = (1..60).joinToString(",") { "\"content://media/external/images/media/$it\"" }
        ok("""{"kind":"photos","title":"t","uris":[$many]}""")
    }

    @Test fun storedCardsDecodeWithoutRevalidation() {
        // A card that a later, stricter rule would reject still shows from history.
        val stored = """[{"kind":"places","title":"t","items":[]}]"""
        assertEquals(listOf(PlacesCard("t", null, emptyList())), CardJson.decodeList(stored))
    }

    @Test fun otherKindsValidateTheirFields() {
        assertTrue(error("""{"kind":"route","depart_at":"25:00","arrive_at":"18:52","summary":"s","legs":[],"dest":{"name":"d"}}""").contains("depart_at"))
        assertTrue(error("""{"kind":"event","title":"t","start":"내일","end":"2026-11-10T16:00"}""").contains("start"))
        assertTrue(error("""{"kind":"event","title":"t","start":"2026-11-10T16:00","end":"2026-11-10T15:00"}""").contains("end"))
        assertTrue(error("""{"kind":"draft","channel":"fax","body":"b"}""").contains("channel"))
        assertTrue(error("""{"kind":"draft","channel":"sms","body":" "}""").contains("body"))
        assertTrue(error("""{"kind":"digest","groups":[{"label":"g","tone":"loud","items":[]}]}""").contains("tone"))
        assertTrue(error("""{"kind":"results","title":"t","items":[{"title":"a","url":"javascript:alert(1)"}]}""").contains("url"))
        assertTrue(error("""{"kind":"route","depart_at":"18:00","arrive_at":"18:52","summary":"s","legs":[{"at":"18:00","kind":"boat","label":"l","detail":""}],"dest":{"name":"d"}}""").contains("kind"))
    }
}
