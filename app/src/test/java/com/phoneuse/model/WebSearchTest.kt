package com.phoneuse.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebSearchTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun extractsQueriesFromWebSearchCall() {
        val single = WebSearch.parseCall(obj("""{"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","query":"주말 전시"}}"""))
        assertEquals(WebSearchCall("ws_1", listOf("주말 전시")), single)
        val many = WebSearch.parseCall(obj("""{"type":"web_search_call","id":"ws_2","action":{"type":"search","query":"a","queries":["a","b"]}}"""))
        assertEquals(listOf("a", "b"), many!!.queries)
        val pending = WebSearch.parseCall(obj("""{"type":"web_search_call","id":"ws_3","status":"in_progress"}"""))
        assertEquals(WebSearchCall("ws_3", emptyList()), pending)
        assertNull(WebSearch.parseCall(obj("""{"type":"message","id":"m"}""")))
    }

    @Test fun collectsUrlCitations() {
        val output = Json.parseToJsonElement(
            """[
              {"type":"web_search_call","id":"ws_1","action":{"type":"search","query":"x"}},
              {"type":"message","content":[{"type":"output_text","text":"…","annotations":[
                {"type":"url_citation","url":"https://museum.example/a","title":"시립 미술관","start_index":0,"end_index":3},
                {"type":"url_citation","url":"https://museum.example/a","title":"dup","start_index":5,"end_index":6},
                {"type":"file_citation","file_id":"f"},
                {"type":"url_citation","url":"https://blog.example.com/post","title":""},
                {"type":"url_citation","url":"javascript:alert(1)","title":"bad"}
              ]}]}
            ]""",
        ).jsonArray
        assertEquals(
            listOf(Citation("시립 미술관", "https://museum.example/a"), Citation("blog.example.com", "https://blog.example.com/post")),
            WebSearch.citations(output),
        )
    }

    @Test fun stripsInlineCitationMarks() {
        val raw = "분산해도 손실을 막을 수는 없습니다.\n\uE200cite\uE202turn853051search1\uE202turn853051search4\uE201\n\n토스에서 확인했어요."
        assertEquals("분산해도 손실을 막을 수는 없습니다.\n\n토스에서 확인했어요.", WebSearch.stripMarks(raw))
        assertEquals("맑아요 12도.", WebSearch.stripMarks("맑아요 12도. \uE200cite\uE202turn0search0\uE201"))
        // Mid-stream, a mark not yet closed is hidden.
        assertEquals("맑아요", WebSearch.stripMarks("맑아요 \uE200cite\uE202tur"))
        assertEquals("그대로 둬요 (cite)", WebSearch.stripMarks("그대로 둬요 (cite)"))
    }
}
