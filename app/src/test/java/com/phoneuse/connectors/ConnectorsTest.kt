package com.phoneuse.connectors

import com.phoneuse.agent.FakeUserGate
import com.phoneuse.auth.OAuthTokenSet
import com.phoneuse.mcp.McpException
import com.phoneuse.mcp.McpOAuthState
import com.phoneuse.mcp.McpServer
import com.phoneuse.risk.RiskCategory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

class ConnectorsTest {
    private lateinit var server: MockWebServer
    private val secrets = HashMap<String, String>()
    private val methods: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var tokenStatus = 200

    private fun store() = ConnectorStore(read = { secrets[it] }, write = { k, v -> if (v == null) secrets.remove(k) else secrets[k] = v })

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl!!.encodedPath == "/token") {
                    return MockResponse().setResponseCode(tokenStatus).setBody("""{"error":"invalid_grant"}""")
                }
                val body = request.body.readUtf8()
                val method = Regex(""""method":"([^"]+)"""").find(body)?.groupValues?.get(1) ?: request.method!!
                methods += method
                val id = Regex(""""id":(\d+)""").find(body)?.groupValues?.get(1)
                val json = { result: String ->
                    MockResponse().setHeader("Content-Type", "application/json").setHeader("Mcp-Session-Id", "s1")
                        .setBody("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                }
                return when (method) {
                    "initialize" -> json("""{"protocolVersion":"2025-06-18","capabilities":{}}""")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> json(
                        """{"tools":[{"name":"send_message","description":"Send a chat message"},
                           {"name":"search","description":"Find pages"}]}""",
                    )
                    "tools/call" -> json("""{"content":[{"type":"text","text":"done"}]}""")
                    else -> MockResponse().setResponseCode(204)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private suspend fun connectorsWith(server: McpServer, now: Long = 1_000L): Connectors {
        val s = store()
        s.updateServers { listOf(server) }
        return Connectors(OkHttpClient(), s, now = { now })
    }

    private fun mcp(oauth: McpOAuthState? = null) = McpServer("id1", "srv", server.url("/mcp").toString(), oauth = oauth)

    @Test fun riskyMcpToolsAskAndADeclineSendsNoCall() = runBlocking {
        val tools = connectorsWith(mcp()).forRun()!!
        assertTrue(tools.handles("mcp__srv__send_message"))
        val gate = FakeUserGate().apply { confirmResult = false }

        val declined = tools.call("mcp__srv__send_message", JsonObject(mapOf("text" to JsonPrimitive("hi\nthere"))), gate)
        assertTrue(declined.output, declined.output.startsWith("CANCELLED"))
        val (category, summary) = gate.confirms.single()
        assertEquals(RiskCategory.SEND, category)
        assertTrue(summary, summary.startsWith("srv · send_message: "))
        assertFalse(summary.contains('\n'))
        assertFalse("no tools/call after a decline", "tools/call" in methods)

        gate.confirmResult = true
        assertEquals("done", tools.call("mcp__srv__send_message", JsonObject(emptyMap()), gate).output)
        assertEquals(1, methods.count { it == "tools/call" })

        // A plain reader goes straight through.
        val before = gate.confirms.size
        assertEquals("done", tools.call("mcp__srv__search", JsonObject(emptyMap()), gate).output)
        assertEquals(before, gate.confirms.size)
    }

    @Test fun urlsMustBeHttps() {
        assertTrue(Connectors.isAllowedUrl("https://mcp.notion.com/mcp"))
        assertTrue(Connectors.isAllowedUrl("  https://example.com:8443/x "))
        assertFalse(Connectors.isAllowedUrl("http://127.0.0.1:8080/mcp"))
        assertFalse(Connectors.isAllowedUrl("http://localhost.evil.com/"))
        assertFalse(Connectors.isAllowedUrl("http://127.0.0.1@evil.com/mcp"))
        assertFalse(Connectors.isAllowedUrl("https://user:pw@evil.com/mcp"))
        assertFalse(Connectors.isAllowedUrl("ftp://x"))
        assertFalse(Connectors.isAllowedUrl("mcp.notion.com"))
    }

    @Test fun aServerThatFailsToAddIsRolledBack() = runBlocking {
        val s = store()
        val c = Connectors(OkHttpClient(), s)
        val e = runCatching { c.addServer("https://127.0.0.1:1/mcp", null) }.exceptionOrNull()
        assertTrue(e.toString(), e is McpException)
        assertTrue(s.servers.value.isEmpty())
        assertTrue(runCatching { c.addServer("http://127.0.0.1:1/mcp", null) }.exceptionOrNull() is McpException)
        assertTrue(s.servers.value.isEmpty())
    }

    @Test fun removingAServerEndsItsSession() = runBlocking {
        val c = connectorsWith(mcp())
        c.forRun()
        c.removeServer("id1")
        assertEquals("DELETE", methods.last())
        assertTrue(c.store.servers.value.isEmpty())
    }

    @Test fun aRejectedRefreshAsksToLogInAgainInsteadOfUsingTheOldToken() = runBlocking {
        tokenStatus = 400
        val expired = McpOAuthState("cid", null, server.url("/token").toString(), OAuthTokenSet("old-at", "rt", expiresAt = 500L))
        val c = connectorsWith(mcp(expired), now = 1_000L)
        val errors = ArrayList<String>()
        val tools = c.registry.tools(c.store.servers.value) { _, e -> errors += e.message.orEmpty() }
        assertTrue(tools.isEmpty())
        assertTrue(errors.toString(), errors.single().contains("다시 로그인"))
        // The MCP server was never called with the expired token.
        assertTrue(methods.isEmpty())
    }

    @Test fun storeRoundTripsThroughTheSecrets() = runBlocking {
        val a = store()
        a.updateServers { listOf(McpServer("1", "notion", "https://mcp.notion.com/mcp", preset = "notion", token = "t0k3n")) }
        a.updateGoogle { GoogleConfig("cid.apps.googleusercontent.com", "s3cr3t", OAuthTokenSet("at", "rt", 99), "a@b.c") }
        // Stored as opaque JSON values under two keys; a new store reads the same.
        assertEquals(setOf("mcp_servers", "google_connector"), secrets.keys)
        val b = store()
        b.load()
        assertEquals("t0k3n", b.servers.value.single().token)
        assertEquals("notion", b.servers.value.single().preset)
        assertEquals("rt", b.google.value!!.tokens!!.refreshToken)
        assertEquals("a@b.c", b.google.value!!.email)
        // toString never shows secrets.
        assertFalse(b.servers.value.single().toString().contains("t0k3n"))
        assertFalse(b.google.value.toString().contains("s3cr3t"))

        b.updateServers { emptyList() }
        b.updateGoogle { null }
        assertTrue(secrets.isEmpty())
        val c = store()
        c.load()
        assertTrue(c.servers.value.isEmpty())
        assertNull(c.google.value)
        assertNotNull(c)
    }

    /** Every argument name is on the card, even one that would sit past the cut. */
    @Test fun confirmCardListsEveryArgumentKey() {
        val args = JsonObject(
            mapOf(
                "text" to JsonPrimitive("x".repeat(Connectors.ARGS_PREVIEW + 500)),
                "bcc" to JsonPrimitive("attacker@example.com"),
            ),
        )
        val shown = Connectors.argsPreview(args)
        assertTrue(shown, shown.startsWith("[항목: text, bcc]") || shown.startsWith("[fields: text, bcc]"))
        assertTrue(shown.contains("500") || shown.contains("5"))
        assertTrue(shown.length < Connectors.ARGS_PREVIEW + 200)
        val short = Connectors.argsPreview(JsonObject(mapOf("to" to JsonPrimitive("a"), "body" to JsonPrimitive("hi there, this whole text is shown"))))
        assertTrue(short, short.contains("hi there, this whole text is shown"))
    }
}
