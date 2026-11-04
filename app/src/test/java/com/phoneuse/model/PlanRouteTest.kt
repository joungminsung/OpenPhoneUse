package com.phoneuse.model

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The ChatGPT plan route (Sign in with ChatGPT plan usage): what it rejects is left out, tools fall back to a namespace. */
class PlanRouteTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private var limitsHit = 0

    private inner class PlanFake : ModelAuth {
        override val baseUrl = server.url("/v1/").toString()
        override val unauthorizedMessage = "expired"
        override val planUsage = true
        override suspend fun headers() = mapOf("Authorization" to "Bearer t")
        override suspend fun onUnauthorized() = false
        override suspend fun onUsageLimit() { limitsHit++ }
    }

    private fun client() = OpenAiResponsesClient(PlanFake(), ModelOptions("gpt-6-astra", fast = true, effort = "low", promptCacheKey = "conv-1"), http)

    private val tool = ToolSpec("tap", "Tap a node", buildJsonObject { put("type", "object") })

    private fun completed(output: String = "[]") = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"output\":$output}}\n\n")

    private fun unsupported(param: String) = MockResponse().setResponseCode(400)
        .setBody("""{"error":{"code":"subscription_sharing_unsupported_capability","param":"$param","message":"x"}}""")

    private fun body() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test fun aRejectedFieldIsLeftOutFromThenOn() = runBlocking {
        server.enqueue(unsupported("service_tier"))
        server.enqueue(unsupported("prompt_cache_key"))
        server.enqueue(completed())
        server.enqueue(completed())
        val c = client()
        c.next("i", listOf(InputItem.UserText("go")), listOf(tool)) {}
        assertTrue(body().containsKey("service_tier"))
        val second = body()
        assertFalse(second.containsKey("service_tier"))
        assertTrue(second.containsKey("prompt_cache_key"))
        val third = body()
        assertFalse(third.containsKey("service_tier") || third.containsKey("prompt_cache_key"))
        assertEquals(false, third["store"]!!.jsonPrimitive.content.toBoolean())
        // The next turn starts without them.
        c.next("i", listOf(InputItem.UserText("go")), listOf(tool)) {}
        assertFalse(body().containsKey("service_tier"))
    }

    @Test fun fastModeAsksForThePriorityTierOnThePlanRoute() = runBlocking {
        server.enqueue(completed())
        client().next("i", listOf(InputItem.UserText("go")), listOf(tool)) {}
        assertEquals("priority", body()["service_tier"]!!.jsonPrimitive.content)
        assertEquals(1, server.requestCount)
    }

    @Test fun aRefusedTierIsRetriedOnceWithoutItAndNotSentAgainThisSession() = runBlocking {
        server.enqueue(unsupported("service_tier"))
        server.enqueue(completed())
        server.enqueue(completed())
        client().next("i", listOf(InputItem.UserText("go")), listOf(tool)) {}
        assertEquals("priority", body()["service_tier"]!!.jsonPrimitive.content)
        assertFalse(body().containsKey("service_tier"))
        assertEquals(2, server.requestCount)
        // A later client for this backend and model (the next run) starts without it.
        client().next("i", listOf(InputItem.UserText("go")), listOf(tool)) {}
        assertFalse(body().containsKey("service_tier"))
        assertEquals(3, server.requestCount)
    }

    @Test fun rejectedTopLevelToolsGoInANamespaceAndCallsComeBackWithIt() = runBlocking {
        server.enqueue(unsupported("tools[0]"))
        server.enqueue(completed("""[{"type":"function_call","call_id":"c1","name":"tap","namespace":"phone","arguments":"{\"index\":2}"}]"""))
        server.enqueue(completed())
        val c = client()
        val turn = c.next("i", listOf(InputItem.UserText("go")), listOf(tool)) {}
        assertEquals("function", body()["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        val ns = body()["tools"]!!.jsonArray[0].jsonObject
        assertEquals("namespace", ns["type"]!!.jsonPrimitive.content)
        assertEquals("phone", ns["name"]!!.jsonPrimitive.content)
        assertEquals("tap", ns["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)

        val call = turn.calls.single()
        assertEquals("tap", call.name)
        c.next("i", listOf(InputItem.UserText("go"), call, InputItem.FunctionOutput("c1", "OK")), listOf(tool)) {}
        val replayed = body()["input"]!!.jsonArray.map { it.jsonObject }.first { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals("phone", replayed["namespace"]!!.jsonPrimitive.content)
    }

    @Test fun anUnknownUnsupportedCapabilityStopsWithoutRetrying() = runBlocking {
        server.enqueue(unsupported("input[3].content"))
        try {
            client().next("i", listOf(InputItem.UserText("go")), listOf(tool)) {}
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertFalse(e.retryable)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun theUsageLimitIsReportedWithWhereToManageItAndNotRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"x"}}"""))
        try {
            client().next("i", listOf(InputItem.UserText("go")), listOf(tool)) {}
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(429, e.status)
            assertEquals(ModelException.PLAN_USAGE_LIMIT, e.code)
            assertFalse(e.retryable)
            assertTrue(e.message!!.contains("chatgpt.com/settings/usage"))
            assertEquals(1, limitsHit)
        }
    }

    @Test fun onlyTheUsageLimitReportsAUsageLimit() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"subscription_sharing_user_not_eligible","message":"x"}}"""))
        try {
            client().next("i", listOf(InputItem.UserText("go")), listOf(tool)) {}
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(0, limitsHit)
        }
    }
}
