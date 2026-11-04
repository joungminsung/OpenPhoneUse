package com.phoneuse.connectors

import com.phoneuse.auth.AuthException
import com.phoneuse.auth.OAuthTokenSet
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoogleAuthTest {
    private lateinit var server: MockWebServer
    private val secrets = HashMap<String, String>()
    private val store = ConnectorStore(read = { secrets[it] }, write = { k, v -> if (v == null) secrets.remove(k) else secrets[k] = v })

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun auth(now: Long = 1_000L) = GoogleAuth(
        OkHttpClient(), store,
        tokenUrl = server.url("/token").toString(),
        now = { now },
        revokeUrl = server.url("/revoke").toString(),
    )

    private suspend fun connected(expiresAt: Long) =
        store.updateGoogle { GoogleConfig("cid.apps.googleusercontent.com", "sec", OAuthTokenSet("old-at", "rt", expiresAt), "a@b.c") }

    private fun form(body: String) = ("http://x/?$body").toHttpUrl()

    @Test fun freshTokensAreUsedAsTheyAre() = runBlocking {
        connected(expiresAt = 5_000)
        assertEquals("old-at", auth().accessToken())
        assertEquals(0, server.requestCount)
    }

    @Test fun expiringTokensAreRefreshedAndSaved() = runBlocking {
        connected(expiresAt = 1_030)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"access_token":"new-at","expires_in":3600}"""))
        assertEquals("new-at", auth().accessToken())
        val f = form(server.takeRequest().body.readUtf8())
        assertEquals("refresh_token", f.queryParameter("grant_type"))
        assertEquals("rt", f.queryParameter("refresh_token"))
        assertEquals("cid.apps.googleusercontent.com", f.queryParameter("client_id"))
        assertEquals("sec", f.queryParameter("client_secret"))
        val saved = store.google.value!!.tokens!!
        assertEquals("new-at", saved.accessToken)
        assertEquals("rt", saved.refreshToken)
        assertEquals(4_600L, saved.expiresAt)
    }

    @Test fun aRejectedRefreshDisconnectsWithoutRevoking() = runBlocking {
        connected(expiresAt = 900)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        val e = runCatching { auth().accessToken() }.exceptionOrNull()
        assertTrue(e is AuthException)
        assertTrue(e!!.message!!.contains("다시 연결"))
        assertFalse(store.google.value!!.connected)
        assertEquals("cid.apps.googleusercontent.com", store.google.value!!.clientId)
        assertEquals(1, server.requestCount)
    }

    @Test fun aNetworkErrorKeepsTheConnection() = runBlocking {
        connected(expiresAt = 900)
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { auth().accessToken() }.isFailure)
        assertTrue(store.google.value!!.connected)
    }

    @Test fun disconnectRevokesBestEffort() = runBlocking {
        connected(expiresAt = 5_000)
        server.enqueue(MockResponse().setResponseCode(200))
        auth().disconnect()
        val revoke = server.takeRequest()
        assertEquals("/revoke", revoke.requestUrl!!.encodedPath)
        assertEquals("rt", form(revoke.body.readUtf8()).queryParameter("token"))
        assertNull(store.google.value!!.tokens)
        assertNull(store.google.value!!.email)

        // A failing revoke still disconnects.
        connected(expiresAt = 5_000)
        server.enqueue(MockResponse().setResponseCode(500))
        auth().disconnect()
        assertFalse(store.google.value!!.connected)
    }
}
