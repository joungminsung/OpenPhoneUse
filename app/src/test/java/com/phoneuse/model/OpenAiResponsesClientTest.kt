package com.phoneuse.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiResponsesClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private class FakeAuth(
        override val baseUrl: String,
        var token: String = "t1",
        val refreshTo: String? = null,
    ) : ModelAuth {
        override val unauthorizedMessage = "auth expired"
        var refreshCalls = 0
        override suspend fun headers() = mapOf("Authorization" to "Bearer $token", "x-test-auth" to "acc")
        override suspend fun onUnauthorized(): Boolean {
            refreshCalls++
            val n = refreshTo ?: return false
            token = n
            return true
        }
    }

    private fun auth(base: String = "/v1/", refreshTo: String? = null) =
        FakeAuth(server.url(base).toString(), refreshTo = refreshTo)

    private fun client(
        auth: ModelAuth = auth(),
        fast: Boolean = false,
        effort: String = "low",
        webSearch: Boolean = false,
    ) = OpenAiResponsesClient(auth, ModelOptions("gpt-5-mini", fast, effort, webSearch), http)

    private val tool = ToolSpec("tap", "Tap a node", buildJsonObject { put("type", "object") })

    private fun sse(vararg events: Pair<String, String>) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" })

    private fun completed(response: String) =
        "response.completed" to """{"type":"response.completed","response":$response}"""

    private fun ok(response: String = """{"output":[]}""") = sse(completed(response))

    private fun bodyOf(req: okhttp3.mockwebserver.RecordedRequest) =
        Json.parseToJsonElement(req.body.readUtf8()).jsonObject

    @Test fun sendsRequestShape() = runBlocking {
        server.enqueue(ok())
        client().next(
            "be helpful",
            listOf(
                InputItem.UserImage("QUJD"),
                InputItem.FunctionOutput("c1", "ok"),
                InputItem.UserText("hi"),
                InputItem.AssistantText("hello"),
                InputItem.FunctionCall("c2", "tap", "{\"id\":1}"),
            ),
            listOf(tool),
        )
        val req = server.takeRequest()
        assertEquals("/v1/responses", req.path)
        assertEquals("POST", req.method)
        assertEquals("Bearer t1", req.getHeader("Authorization"))
        val body = bodyOf(req)
        assertEquals("gpt-5-mini", body["model"]!!.jsonPrimitive.content)
        assertEquals("be helpful", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("true", body["parallel_tool_calls"]!!.jsonPrimitive.content)
        val input = body["input"]!!.jsonArray
        assertEquals("function_call_output", input[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("c1", input[1].jsonObject["call_id"]!!.jsonPrimitive.content)
        val img = input[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("input_image", img["type"]!!.jsonPrimitive.content)
        assertTrue(img["image_url"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertEquals("input_text", input[2].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        val asst = input[3].jsonObject
        assertEquals("assistant", asst["role"]!!.jsonPrimitive.content)
        assertEquals("output_text", asst["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        val call = input[4].jsonObject
        assertEquals("function_call", call["type"]!!.jsonPrimitive.content)
        assertEquals("{\"id\":1}", call["arguments"]!!.jsonPrimitive.content)
        val t = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", t["type"]!!.jsonPrimitive.content)
        assertEquals("tap", t["name"]!!.jsonPrimitive.content)
        assertEquals("Tap a node", t["description"]!!.jsonPrimitive.content)
        assertTrue(t["parameters"] is JsonObject)
        assertEquals("false", t["strict"]!!.jsonPrimitive.content)
    }

    @Test fun serializesInputImage() = runBlocking {
        server.enqueue(ok())
        client().next(
            "i",
            listOf(
                InputItem.UserText("잎이 왜 노래져?", listOf(InputItem.UserImage("QUJD"), InputItem.UserImage("REVG"))),
                InputItem.UserText("", listOf(InputItem.UserImage("R0hJ"))),
            ),
            emptyList(),
        )
        val input = bodyOf(server.takeRequest())["input"]!!.jsonArray
        assertEquals(2, input.size)
        val first = input[0].jsonObject
        assertEquals("user", first["role"]!!.jsonPrimitive.content)
        val parts = first["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("input_text", "input_image", "input_image"), parts.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("잎이 왜 노래져?", parts[0]["text"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,QUJD", parts[1]["image_url"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,REVG", parts[2]["image_url"]!!.jsonPrimitive.content)
        // A photo sent without words has no empty text part.
        val second = input[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("input_image"), second.map { it["type"]!!.jsonPrimitive.content })
    }

    @Test fun streamsTextDeltasAndParsesCompleted() = runBlocking {
        server.enqueue(sse(
            "response.created" to """{"type":"response.created"}""",
            "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"Hel"}""",
            "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"lo"}""",
            completed("""{"output":[
              {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]},
              {"type":"function_call","call_id":"call_1","name":"tap","arguments":"{\"id\":3}"}
            ],"usage":{"input_tokens":120,"output_tokens":45}}""".replace("\n", "")),
        ))
        val deltas = mutableListOf<String>()
        val turn = client().next("i", listOf(InputItem.UserText("go")), listOf(tool)) { deltas += it }
        assertEquals(listOf("Hel", "lo"), deltas)
        assertEquals("Hello", turn.text)
        assertEquals(listOf(InputItem.FunctionCall("call_1", "tap", "{\"id\":3}")), turn.calls)
        assertEquals(Usage(120, 45), turn.usage)
    }

    /** A stream may carry its items in output_item.done and complete with an empty output. */
    @Test fun itemsFromOutputItemDoneWhenCompletedOutputIsEmpty() = runBlocking {
        val message = """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}"""
        val call = """{"type":"function_call","call_id":"call_1","name":"tap","arguments":"{\"id\":3}"}"""
        server.enqueue(sse(
            "response.output_item.done" to """{"type":"response.output_item.done","item":$message}""",
            "response.output_item.done" to """{"type":"response.output_item.done","item":$call}""",
            completed("""{"output":[],"usage":{"input_tokens":10,"output_tokens":5}}"""),
        ))
        val turn = client().next("i", listOf(InputItem.UserText("go")), listOf(tool))
        assertEquals("Hello", turn.text)
        assertEquals(listOf(InputItem.FunctionCall("call_1", "tap", "{\"id\":3}")), turn.calls)
        assertEquals(Usage(10, 5), turn.usage)
    }

    @Test fun completedOutputWinsOverStreamedItems() = runBlocking {
        val message = """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}"""
        server.enqueue(sse(
            "response.output_item.done" to """{"type":"response.output_item.done","item":$message}""",
            completed("""{"output":[$message]}"""),
        ))
        assertEquals("Hello", client().next("i", emptyList(), emptyList()).text)
    }

    @Test fun missingUsageAndTextAreNull() = runBlocking {
        server.enqueue(ok("""{"output":[{"type":"message","content":[]}]}"""))
        val turn = client().next("i", emptyList(), emptyList())
        assertNull(turn.text)
        assertNull(turn.usage)
        assertTrue(turn.calls.isEmpty())
    }

    @Test fun fastModeAddsPriorityTier() = runBlocking {
        server.enqueue(ok())
        client(fast = true).next("i", emptyList(), emptyList())
        assertEquals("priority", bodyOf(server.takeRequest())["service_tier"]!!.jsonPrimitive.content)
    }

    @Test fun fastModeOffOmitsTier() = runBlocking {
        server.enqueue(ok())
        client(fast = false).next("i", emptyList(), emptyList())
        assertFalse(bodyOf(server.takeRequest()).containsKey("service_tier"))
    }

    @Test fun sendsReasoningEffortAndStream() = runBlocking {
        server.enqueue(ok())
        client(effort = "medium").next("i", emptyList(), emptyList())
        val body = bodyOf(server.takeRequest())
        assertEquals("medium", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
    }

    @Test fun streamEndingWithoutCompletedIsIoError() = runBlocking {
        server.enqueue(sse("response.output_text.delta" to """{"type":"response.output_text.delta","delta":"x"}"""))
        try {
            client().next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertNull(e.status)
            assertTrue(e.message!!.contains("stream ended early"))
        }
    }

    @Test fun responseFailedEventThrows() = runBlocking {
        server.enqueue(sse("response.failed" to """{"type":"response.failed","response":{"error":{"code":"server_error","message":"boom"}}}"""))
        try {
            client().next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertTrue(e.message!!.contains("boom"))
        }
    }

    @Test fun errorEventThrows() = runBlocking {
        server.enqueue(sse("error" to """{"type":"error","code":"rate_limit","message":"slow down"}"""))
        try {
            client().next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertTrue(e.message!!.contains("slow down"))
        }
    }

    @Test fun non2xxThrowsWithStatus() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"oops"}"""))
        try {
            client().next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(500, e.status)
            assertTrue(e.message!!.contains("oops"))
        }
    }

    @Test fun malformedCompletedThrowsParseError() = runBlocking {
        server.enqueue(sse("response.completed" to "not json"))
        try {
            client().next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertTrue(e.message!!.contains("parse", ignoreCase = true))
        }
    }

    @Test fun retriesOnceAfterSuccessfulRefresh() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        server.enqueue(ok())
        val a = auth(refreshTo = "t2")
        client(a).next("i", emptyList(), emptyList())
        assertEquals(2, server.requestCount)
        assertEquals("Bearer t1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer t2", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, a.refreshCalls)
    }

    @Test fun secondUnauthorizedAfterRefreshFailsWithoutThirdRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            client(auth(refreshTo = "t2")).next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(401, e.status)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun unauthorizedWithoutRefreshFails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        try {
            client().next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(401, e.status)
            assertEquals("auth expired", e.message)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun forbiddenUsesAuthMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"raw"}"""))
        val a = auth(refreshTo = "t2")
        try {
            client(a).next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(403, e.status)
            assertEquals("auth expired", e.message)
        }
        assertEquals(1, server.requestCount)
        assertEquals(0, a.refreshCalls)
    }

    @Test fun eventWithoutEventLineUsesJsonType() = runBlocking {
        val resp = """{"type":"response.completed","response":{"output":[]}}"""
        val delta = """{"type":"response.output_text.delta","delta":"A"}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: $delta\n\ndata: $resp\n\n"))
        val deltas = mutableListOf<String>()
        client().next("i", emptyList(), emptyList()) { deltas += it }
        assertEquals(listOf("A"), deltas)
    }

    @Test fun finalEventWithoutTrailingBlankLineIsHandled() = runBlocking {
        val resp = """{"type":"response.completed","response":{"output":[]}}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("event: response.completed\ndata: $resp"))
        val turn = client().next("i", emptyList(), emptyList())
        assertTrue(turn.calls.isEmpty())
    }

    @Test fun usesAuthBaseUrlAndHeaders() = runBlocking {
        server.enqueue(ok())
        client(auth("/custom/v1/")).next("i", emptyList(), emptyList())
        val req = server.takeRequest()
        assertEquals("/custom/v1/responses", req.path)
        assertEquals("acc", req.getHeader("x-test-auth"))
    }

    @Test fun ioErrorHasNullStatus() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try {
            client().next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertNull(e.status)
        }
    }

    @Test fun cancellationAbortsPendingCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            client().next("i", emptyList(), emptyList())
        }
        server.takeRequest()
        job.cancel()
        val start = System.nanoTime()
        try {
            withTimeout(3000) { job.await() }
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
    }

    @Test fun includesWebSearchToolWhenEnabled() = runBlocking {
        server.enqueue(ok())
        server.enqueue(ok())
        client(webSearch = true).next("i", emptyList(), listOf(tool))
        val on = bodyOf(server.takeRequest())["tools"]!!.jsonArray
        assertEquals(2, on.size)
        val ws = on.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "web_search" }
        assertEquals("true", ws["external_web_access"]!!.jsonPrimitive.content)
        assertEquals(2, ws.size)
        client(webSearch = false).next("i", emptyList(), listOf(tool))
        val off = bodyOf(server.takeRequest())["tools"]!!.jsonArray
        assertTrue(off.none { it.jsonObject["type"]!!.jsonPrimitive.content == "web_search" })
    }

    @Test fun reportsSearchesAndCitations() = runBlocking {
        val item = """{"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","query":"전시"}}"""
        server.enqueue(sse(
            "response.output_item.added" to """{"type":"response.output_item.added","item":{"type":"web_search_call","id":"ws_1","status":"in_progress"}}""",
            "response.output_item.done" to """{"type":"response.output_item.done","item":$item}""",
            "response.output_item.added" to """{"type":"response.output_item.added","item":{"type":"web_search_call","id":"ws_2","action":{"type":"search","query":"후기"}}}""",
            "response.output_item.done" to """{"type":"response.output_item.done","item":{"type":"web_search_call","id":"ws_2","action":{"type":"search","query":"후기"}}}""",
            completed("""{"output":[$item,{"type":"message","content":[{"type":"output_text","text":"답","annotations":[{"type":"url_citation","url":"https://a.example/x","title":"A"}]}]}]}"""),
        ))
        val searches = mutableListOf<WebSearchCall>()
        val turn = client().next("i", emptyList(), emptyList(), {}, { searches += it })
        assertEquals(listOf(WebSearchCall("ws_1", listOf("전시")), WebSearchCall("ws_2", listOf("후기"))), searches)
        assertEquals("답", turn.text)
        assertEquals(listOf(Citation("A", "https://a.example/x")), turn.citations)
    }

    @Test fun searchWithoutIdIsReportedOnceOnDone() = runBlocking {
        val item = """{"type":"web_search_call","action":{"type":"search","query":"q"}}"""
        server.enqueue(sse(
            "response.output_item.added" to """{"type":"response.output_item.added","item":$item}""",
            "response.output_item.done" to """{"type":"response.output_item.done","item":$item}""",
            completed("""{"output":[]}"""),
        ))
        val searches = mutableListOf<WebSearchCall>()
        client().next("i", emptyList(), emptyList(), {}, { searches += it })
        assertEquals(listOf(WebSearchCall(null, listOf("q"))), searches)
    }

    // ---- prompt cache, verbosity, developer note, timings ----------------------------------------

    private fun optionsClient(options: ModelOptions, auth: ModelAuth = auth()) = OpenAiResponsesClient(auth, options, http)

    @Test fun sendsPromptCacheKeyAndVerbosity() = runBlocking {
        server.enqueue(ok())
        optionsClient(ModelOptions("m", false, "low", promptCacheKey = "phoneuse-conversation-7", verbosity = "medium"))
            .next("i", emptyList(), emptyList())
        val body = bodyOf(server.takeRequest())
        assertEquals("phoneuse-conversation-7", body["prompt_cache_key"]!!.jsonPrimitive.content)
        assertEquals("medium", body["text"]!!.jsonObject["verbosity"]!!.jsonPrimitive.content)
    }

    @Test fun omitsCacheKeyAndVerbosityWhenUnset() = runBlocking {
        server.enqueue(ok())
        client().next("i", emptyList(), emptyList())
        val body = bodyOf(server.takeRequest())
        assertFalse(body.containsKey("prompt_cache_key"))
        assertFalse(body.containsKey("text"))
    }

    @Test fun verbosityRejectedIsRetriedWithoutAndRemembered() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Unsupported parameter: 'text.verbosity'"}"""))
        server.enqueue(ok("""{"output":[{"type":"message","content":[{"type":"output_text","text":"hi"}]}]}"""))
        server.enqueue(ok())
        val a = auth("/vb/")
        val c = optionsClient(ModelOptions("m-verbosity", false, "low", verbosity = "high"), a)
        assertEquals("hi", c.next("i", emptyList(), emptyList()).text)
        assertEquals("high", bodyOf(server.takeRequest())["text"]!!.jsonObject["verbosity"]!!.jsonPrimitive.content)
        assertFalse(bodyOf(server.takeRequest()).containsKey("text"))
        // Later requests (even from a new client of the same backend and model) leave it out.
        optionsClient(ModelOptions("m-verbosity", false, "low", verbosity = "high"), a).next("i", emptyList(), emptyList())
        assertFalse(bodyOf(server.takeRequest()).containsKey("text"))
    }

    @Test fun otherBadRequestIsNotRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Unsupported model"}"""))
        try {
            optionsClient(ModelOptions("m-other", false, "low", verbosity = "low")).next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(400, e.status)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun badRequestAboutSomeTextFieldIsNotTakenForVerbosity() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Invalid 'input[3].content[0].text': string too long"}"""))
        try {
            optionsClient(ModelOptions("m-text", false, "low", verbosity = "low")).next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(400, e.status)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun reasoningIsAskedForParsedAndSentBackWithoutItsId() = runBlocking {
        val reasoning = """{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"enc-abc"}"""
        val call = """{"type":"function_call","call_id":"call_1","name":"tap","arguments":"{}"}"""
        server.enqueue(ok("""{"output":[$reasoning,$call]}"""))
        server.enqueue(ok())
        val c = optionsClient(ModelOptions("m-reason", false, "low"))
        val turn = c.next("i", listOf(InputItem.UserText("go")), emptyList())
        assertEquals(1, turn.reasoning.size)
        assertFalse(turn.reasoning[0].raw.containsKey("id"))
        assertEquals("reasoning.encrypted_content", bodyOf(server.takeRequest())["include"]!!.jsonArray[0].jsonPrimitive.content)
        c.next("i", listOf(InputItem.UserText("go")) + turn.reasoning + turn.calls, emptyList())
        val sent = bodyOf(server.takeRequest())["input"]!!.jsonArray
        assertEquals("reasoning", sent[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("enc-abc", sent[1].jsonObject["encrypted_content"]!!.jsonPrimitive.content)
        assertEquals("function_call", sent[2].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun reasoningRejectedIsRetriedWithoutAndRemembered() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Unsupported value: 'include' contains reasoning.encrypted_content"}"""))
        server.enqueue(ok())
        server.enqueue(ok())
        val a = auth("/rs/")
        val stale = InputItem.Reasoning(kotlinx.serialization.json.JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("reasoning"))))
        optionsClient(ModelOptions("m-noreason", false, "low"), a).next("i", listOf(InputItem.UserText("go"), stale), emptyList())
        assertTrue(bodyOf(server.takeRequest()).containsKey("include"))
        val retried = bodyOf(server.takeRequest())
        assertFalse(retried.containsKey("include"))
        assertEquals(1, retried["input"]!!.jsonArray.size)
        optionsClient(ModelOptions("m-noreason", false, "low"), a).next("i", emptyList(), emptyList())
        assertFalse(bodyOf(server.takeRequest()).containsKey("include"))
    }

    @Test fun aBadRequestThatOnlyMentionsReasoningKeepsReasoningCarry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unsupported value: 'reasoning.effort' does not support 'minimal'","param":"reasoning.effort"}}"""))
        server.enqueue(ok())
        val a = auth("/rs2/")
        try {
            optionsClient(ModelOptions("m-effort", false, "minimal"), a).next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(400, e.status)
        }
        assertEquals(1, server.requestCount)
        server.takeRequest()
        optionsClient(ModelOptions("m-effort", false, "low"), a).next("i", emptyList(), emptyList())
        assertTrue(bodyOf(server.takeRequest()).containsKey("include"))
    }

    @Test fun reasoningCarryIsNotDroppedWhenTheRetryFailsToo() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"bad include","param":"include"}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unsupported model"}}"""))
        server.enqueue(ok())
        val a = auth("/rs3/")
        try {
            optionsClient(ModelOptions("m-both", false, "low"), a).next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(400, e.status)
        }
        assertTrue(bodyOf(server.takeRequest()).containsKey("include"))
        assertFalse(bodyOf(server.takeRequest()).containsKey("include"))
        optionsClient(ModelOptions("m-both", false, "low"), a).next("i", emptyList(), emptyList())
        assertTrue(bodyOf(server.takeRequest()).containsKey("include"))
    }

    @Test fun incompleteResponseIsAFinalErrorWithItsReason() = runBlocking {
        server.enqueue(sse("response.incomplete" to """{"type":"response.incomplete","response":{"output":[],"incomplete_details":{"reason":"content_filter"}}}"""))
        try {
            client().next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertFalse(e.retryable)
            assertTrue(e.message!!, e.message!!.contains("content_filter"))
        }
    }

    @Test fun incompleteAtMaxOutputTokensReturnsWhatItFinished() = runBlocking {
        server.enqueue(sse("response.incomplete" to """{"type":"response.incomplete","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"part"}]}],"incomplete_details":{"reason":"max_output_tokens"}}}"""))
        assertEquals("part", client().next("i", emptyList(), emptyList()).text)
    }

    @Test fun rateLimitCarriesRetryAfterAndUsageLimitItsCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody("""{"error":{"code":"rate_limit_exceeded"}}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"type":"usage_limit_reached","message":"The usage limit has been reached"}}"""))
        try {
            client().next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(7_000L, e.retryAfterMs)
            assertEquals("rate_limit_exceeded", e.code)
        }
        try {
            client().next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals("usage_limit_reached", e.code)
        }
    }

    @Test fun retryAfterHeadersAreRead() {
        fun of(vararg h: Pair<String, String>) = OpenAiResponsesClient.retryAfterMs { name -> h.toMap()[name] }
        assertEquals(1500L, of("retry-after-ms" to "1500"))
        assertEquals(3000L, of("retry-after" to "3"))
        assertEquals(360_000L, of("x-ratelimit-reset-tokens" to "6m0s", "x-ratelimit-reset-requests" to "1s"))
        assertEquals(250L, of("x-ratelimit-reset-requests" to "250ms"))
        assertNull(of())
        assertNull(of("x-ratelimit-reset-tokens" to "soon"))
    }

    @Test fun aStreamLongerThanTheCallTimeoutIsNotCut() = runBlocking {
        val slow = sse(
            "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"x"}""",
            completed("""{"output":[{"type":"message","content":[{"type":"output_text","text":"done"}]}]}"""),
        ).throttleBody(64, 300, java.util.concurrent.TimeUnit.MILLISECONDS)
        server.enqueue(slow)
        val shortCall = OkHttpClient.Builder().callTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val c = OpenAiResponsesClient(auth(), ModelOptions("gpt-5-mini", false, "low"), shortCall)
        assertEquals("done", c.next("i", emptyList(), emptyList()).text)
    }

    @Test fun aNetworkErrorAfterOutputSaysSo() = runBlocking {
        server.enqueue(sse("response.output_text.delta" to """{"type":"response.output_text.delta","delta":"x"}"""))
        try {
            client().next("i", emptyList(), emptyList())
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertNull(e.status)
            assertTrue(e.afterOutput)
        }
    }

    @Test fun serializesDeveloperNote() = runBlocking {
        server.enqueue(ok())
        client().next("i", listOf(InputItem.DeveloperText("기억: x"), InputItem.UserText("hi")), emptyList())
        val input = bodyOf(server.takeRequest())["input"]!!.jsonArray
        val dev = input[0].jsonObject
        assertEquals("developer", dev["role"]!!.jsonPrimitive.content)
        val part = dev["content"]!!.jsonArray[0].jsonObject
        assertEquals("input_text", part["type"]!!.jsonPrimitive.content)
        assertEquals("기억: x", part["text"]!!.jsonPrimitive.content)
    }

    @Test fun reportsFirstByteAndFirstOutputTimes() = runBlocking {
        server.enqueue(sse(
            "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"x"}""",
            completed("""{"output":[]}"""),
        ))
        val turn = client().next("i", emptyList(), emptyList())
        assertTrue(turn.firstByteMs != null && turn.firstByteMs!! >= 0)
        assertTrue(turn.firstOutputMs != null && turn.firstOutputMs!! >= turn.firstByteMs!!)
    }
}
