package com.phoneuse.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client(token: String? = null) =
        McpClient(OkHttpClient(), server.url("/mcp").toString(), token = { token })

    private fun json(body: String, session: String? = null) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
        .also { r -> session?.let { r.setHeader("Mcp-Session-Id", it) } }

    private fun initResult(session: String? = "s1", id: Int = 1) =
        json("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"t","version":"1"}}}""", session)

    private fun accepted() = MockResponse().setResponseCode(202)

    private fun RecordedRequest.rpc(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

    @Test fun initializesAndListsTools() = runBlocking {
        server.enqueue(initResult())
        server.enqueue(accepted())
        server.enqueue(
            json(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[
                  {"name":"search","description":"Search pages","inputSchema":{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}},
                  {"name":"ping"}
                ]}}""",
            ),
        )
        val tools = client().listTools()

        val init = server.takeRequest()
        assertEquals("POST", init.method)
        assertEquals("/mcp", init.path)
        assertEquals("application/json, text/event-stream", init.getHeader("Accept"))
        val initBody = init.rpc()
        assertEquals("2.0", initBody["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("initialize", initBody["method"]!!.jsonPrimitive.content)
        assertEquals("2025-06-18", initBody["params"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)

        val initialized = server.takeRequest().rpc()
        assertEquals("notifications/initialized", initialized["method"]!!.jsonPrimitive.content)
        assertNull(initialized["id"])

        val list = server.takeRequest().rpc()
        assertEquals("tools/list", list["method"]!!.jsonPrimitive.content)
        assertEquals(2, list["id"]!!.jsonPrimitive.int)

        assertEquals(listOf("search", "ping"), tools.map { it.name })
        assertEquals("Search pages", tools[0].description)
        assertEquals("object", tools[1].inputSchema["type"]!!.jsonPrimitive.content)
    }

    @Test fun keepsSessionHeader() = runBlocking {
        server.enqueue(initResult("sess-42"))
        server.enqueue(accepted())
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"hi"}]}}"""))
        val c = client(token = "tok")
        c.listTools()
        val result = c.callTool("ping", JsonObject(emptyMap()))
        assertEquals("hi", result.text)
        assertFalse(result.isError)

        val init = server.takeRequest()
        assertNull(init.getHeader("Mcp-Session-Id"))
        assertEquals("Bearer tok", init.getHeader("Authorization"))
        repeat(3) {
            val r = server.takeRequest()
            assertEquals("sess-42", r.getHeader("Mcp-Session-Id"))
            assertEquals("Bearer tok", r.getHeader("Authorization"))
            assertEquals("2025-06-18", r.getHeader("MCP-Protocol-Version"))
        }
    }

    @Test fun parsesSseResponse() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: message\n" +
                    "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}\n\n",
            ).setHeader("Mcp-Session-Id", "s1"),
        )
        server.enqueue(accepted())
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                ": keep-alive\n\n" +
                    "event: message\n" +
                    "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progress\":1}}\n\n" +
                    "event: message\n" +
                    "data: {\"jsonrpc\":\"2.0\",\"id\":2,\n" +
                    "data: \"result\":{\"content\":[{\"type\":\"text\",\"text\":\"line one\"},{\"type\":\"text\",\"text\":\"line two\"}],\"isError\":true}}\n\n",
            ),
        )
        val result = client().callTool("search", JsonObject(mapOf("q" to JsonPrimitive("x"))))
        assertEquals("line one\nline two", result.text)
        assertTrue(result.isError)

        server.takeRequest()
        server.takeRequest()
        val call = server.takeRequest().rpc()
        assertEquals("tools/call", call["method"]!!.jsonPrimitive.content)
        val params = call["params"]!!.jsonObject
        assertEquals("search", params["name"]!!.jsonPrimitive.content)
        assertEquals("x", params["arguments"]!!.jsonObject["q"]!!.jsonPrimitive.content)
    }

    @Test fun truncatesLongResults() = runBlocking {
        val long = "가".repeat(McpClient.MAX_RESULT_CHARS + 5_000)
        server.enqueue(initResult())
        server.enqueue(accepted())
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"$long"}]}}"""))
        val result = client().callTool("big", JsonObject(emptyMap()))
        assertTrue(result.text.startsWith("가".repeat(McpClient.MAX_RESULT_CHARS)))
        assertFalse(result.text.contains("가".repeat(McpClient.MAX_RESULT_CHARS + 1)))
        assertTrue(result.text.length < McpClient.MAX_RESULT_CHARS + 100)
        assertEquals(20_000, McpClient.MAX_RESULT_CHARS)
    }

    @Test fun reportsRpcErrorsAndAuthFailures() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer resource_metadata=\"https://x/.well-known/oauth-protected-resource\""))
        val e = runCatching { client().listTools() }.exceptionOrNull()
        assertTrue(e is McpException)
        assertEquals(401, (e as McpException).status)
        assertEquals("https://x/.well-known/oauth-protected-resource", e.resourceMetadata)

        server.enqueue(initResult())
        server.enqueue(accepted())
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"Unknown tool"}}"""))
        val err = runCatching { client().callTool("nope", JsonObject(emptyMap())) }.exceptionOrNull()
        assertTrue(err is McpException)
        assertTrue(err!!.message!!.contains("Unknown tool"))
    }

    @Test fun reinitializesWhenTheSessionExpires() = runBlocking {
        server.enqueue(initResult("old"))
        server.enqueue(accepted())
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(initResult("new", id = 4))
        server.enqueue(accepted())
        server.enqueue(json("""{"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"text","text":"ok"}]}}"""))
        val c = client()
        c.listTools()
        assertEquals("ok", c.callTool("ping", JsonObject(emptyMap())).text)
        repeat(6) { server.takeRequest() }
        assertEquals("new", server.takeRequest().getHeader("Mcp-Session-Id"))
    }

    @Test fun ignoresRepliesToOtherRequests() = runBlocking {
        server.enqueue(initResult())
        server.enqueue(accepted())
        // A reply without an id, or with another id, is not the answer to tools/list.
        server.enqueue(json("""{"jsonrpc":"2.0","result":{"tools":[{"name":"evil"}]}}"""))
        assertTrue(runCatching { client().listTools() }.exceptionOrNull() is McpException)
        server.enqueue(initResult())
        server.enqueue(accepted())
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{\"tools\":[{\"name\":\"evil\"}]}}\n\n" +
                    "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"good\"}]}}\n\n",
            ),
        )
        assertEquals(listOf("good"), client().listTools().map { it.name })
    }

    @Test fun capsTheResponseBody() = runBlocking {
        server.enqueue(initResult())
        server.enqueue(accepted())
        val huge = "x".repeat((McpClient.MAX_BODY_BYTES + 10).toInt())
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"$huge"}]}}"""))
        val e = runCatching { client().callTool("big", JsonObject(emptyMap())) }.exceptionOrNull()
        assertTrue(e.toString(), e is McpException && e.message!!.contains("너무 커요"))
    }

    @Test fun readsToolAnnotations() = runBlocking {
        server.enqueue(initResult())
        server.enqueue(accepted())
        server.enqueue(
            json(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[
                  {"name":"wipe","annotations":{"destructiveHint":true}},{"name":"look","annotations":{"readOnlyHint":true}},{"name":"x"}]}}""",
            ),
        )
        val tools = client().listTools()
        assertEquals(true, tools[0].destructive)
        assertEquals(true, tools[1].readOnly)
        assertNull(tools[2].readOnly)
    }

    @Test fun aHangingServerIsCancelledByATimeout() = runBlocking {
        server.enqueue(initResult().setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS))
        val started = System.nanoTime()
        val r = kotlinx.coroutines.withTimeoutOrNull(300) { client().listTools() }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertNull(r)
        assertTrue("took $ms ms", ms < 1_500)
    }

    @Test fun endsTheSessionWithDelete() = runBlocking {
        server.enqueue(initResult("s9"))
        server.enqueue(accepted())
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        val c = client()
        c.listTools()
        c.close()
        repeat(3) { server.takeRequest() }
        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("s9", del.getHeader("Mcp-Session-Id"))
    }
}
