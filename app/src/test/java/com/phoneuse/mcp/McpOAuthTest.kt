package com.phoneuse.mcp

import com.phoneuse.auth.AuthException
import com.phoneuse.auth.OAuthTokenSet
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpOAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private var issuer: () -> String = { "$base/as" }
    private var resource: () -> String = { "$base/mcp" }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val json = { body: String -> MockResponse().setHeader("Content-Type", "application/json").setBody(body) }
                return when (request.requestUrl!!.encodedPath) {
                    "/.well-known/oauth-protected-resource/mcp" ->
                        json("""{"resource":"${resource()}","authorization_servers":["$base/as"],"scopes_supported":["read","write"]}""")
                    "/.well-known/oauth-authorization-server/as" ->
                        json("""{"issuer":"${issuer()}","authorization_endpoint":"$base/as/authorize","token_endpoint":"$base/as/token","registration_endpoint":"$base/as/register"}""")
                    "/as/register" -> json("""{"client_id":"dyn-1"}""")
                    "/as/token" -> json("""{"access_token":"new-at","expires_in":3600}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun discoversEndpointsFromResourceMetadata() = runBlocking {
        val e = McpOAuth(OkHttpClient(), requireHttps = false).discover("$base/mcp", null)
        assertEquals("$base/as/authorize", e.authorization)
        assertEquals("$base/as/token", e.token)
        assertEquals("$base/as/register", e.registration)
        assertEquals(listOf("read", "write"), e.scopes)

        val url = McpOAuth(OkHttpClient(), requireHttps = false).authorizeUrl(e, "dyn-1", "http://127.0.0.1:4000/callback", "chal", "st", McpOAuth.resource("$base/mcp")).toHttpUrl()
        assertEquals("dyn-1", url.queryParameter("client_id"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("$base/mcp", url.queryParameter("resource"))
        assertEquals("read write", url.queryParameter("scope"))
    }

    @Test fun requiresHttpsForServerNamedUrls() = runBlocking {
        val e = runCatching { McpOAuth(OkHttpClient()).discover("$base/mcp", null) }.exceptionOrNull()
        assertTrue(e is AuthException)
        assertTrue(e!!.message!!.contains("https"))
        val meta = runCatching { McpOAuth(OkHttpClient()).discover("$base/mcp", "http://evil.example/meta") }.exceptionOrNull()
        assertTrue(meta is AuthException)
    }

    @Test fun rejectsMismatchedIssuerAndResource() = runBlocking {
        issuer = { "https://evil.example/as" }
        assertTrue(runCatching { McpOAuth(OkHttpClient(), requireHttps = false).discover("$base/mcp", null) }.exceptionOrNull() is AuthException)
        issuer = { "$base/as" }
        resource = { "https://other.example/mcp" }
        assertTrue(runCatching { McpOAuth(OkHttpClient(), requireHttps = false).discover("$base/mcp", null) }.exceptionOrNull() is AuthException)
        // A resource that is the server's origin (a prefix of its URL) is fine.
        resource = { base }
        assertEquals("$base/as/token", McpOAuth(OkHttpClient(), requireHttps = false).discover("$base/mcp", null).token)
    }

    @Test fun registersAPublicClient() = runBlocking {
        val (id, secret) = McpOAuth(OkHttpClient(), requireHttps = false).register("$base/as/register", "http://127.0.0.1:4000/callback")
        assertEquals("dyn-1", id)
        assertNull(secret)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("http://127.0.0.1:4000/callback", body["redirect_uris"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("none", body["token_endpoint_auth_method"]!!.jsonPrimitive.content)
    }

    @Test fun refreshKeepsTheRefreshTokenAndSendsTheResource() = runBlocking {
        val state = McpOAuthState("dyn-1", null, "$base/as/token", OAuthTokenSet("old", "rt", 0))
        val fresh = McpOAuth(OkHttpClient(), now = { 1000L }, requireHttps = false).refresh("$base/mcp", state)
        assertEquals("new-at", fresh.tokens.accessToken)
        assertEquals("rt", fresh.tokens.refreshToken)
        assertEquals(4600L, fresh.tokens.expiresAt)
        val form = ("http://x/?" + server.takeRequest().body.readUtf8()).toHttpUrl()
        assertEquals("refresh_token", form.queryParameter("grant_type"))
        assertEquals("rt", form.queryParameter("refresh_token"))
        assertEquals("$base/mcp", form.queryParameter("resource"))
    }
}
