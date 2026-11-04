package com.phoneuse.auth

import com.phoneuse.model.ModelException
import com.phoneuse.model.ModelOptions
import com.phoneuse.model.OpenAiResponsesClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private class MemoryPlanStore(var tokens: PlanTokens? = null, vararg saved: PlanRegistration) : PlanStore {
    val registered = saved.toMutableList()
    override suspend fun load() = tokens
    override suspend fun save(t: PlanTokens?) { tokens = t }
    override suspend fun registrations() = registered.toList()
    override suspend fun saveRegistration(r: PlanRegistration) { registered.removeAll { it.clientId == r.clientId }; registered.add(0, r) }
    override suspend fun forgetRegistration(clientId: String) { registered.removeAll { it.clientId == clientId } }
    override suspend fun hostId() = "urn:uuid:00000000-0000-4000-8000-000000000000"
}

class PlanAuthTest {
    private lateinit var server: MockWebServer
    /** The issuer's published documents and revocation endpoint. */
    private lateinit var issuer: MockWebServer
    private val http = OkHttpClient()
    private val now = 1_800_000_000L
    /** What /revoke answers, in order; then 200. */
    private val revokeAnswers = CopyOnWriteArrayList<MockResponse>()
    private val revoked = CopyOnWriteArrayList<String>()
    @Volatile private var jwksAnswer: MockResponse? = null
    @Volatile private var revokeDelayMs = 0L

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        issuer = MockWebServer()
        issuer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/jwks" -> jwksAnswer ?: MockResponse().setBody(jwksOf("k1" to KEYS))
                "/discovery" -> MockResponse().setBody("""{"revocation_endpoint":"${issuer.url("/revoke")}"}""")
                "/revoke" -> {
                    revoked += request.body.readUtf8()
                    Thread.sleep(revokeDelayMs)
                    if (revokeAnswers.isEmpty()) MockResponse() else revokeAnswers.removeAt(0)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        issuer.start()
    }

    @After fun tearDown() {
        server.shutdown()
        issuer.shutdown()
    }

    private fun tokenClient() = PlanTokenClient(
        http, server.url("/token").toString(), issuer.url("/jwks").toString(), issuer.url("/discovery").toString(), listOf(0, 0),
    ) { now }

    private fun claims(nonce: String, aud: String = "oaiapp_1", iss: String = "https://auth.openai.com", sub: String = "user-1") =
        """{"iss":"$iss","aud":"$aud","exp":${now + 3600},"nonce":"$nonce","sub":"$sub","email":"a@b.c"}"""

    private fun id(nonce: String, aud: String = "oaiapp_1", iss: String = "https://auth.openai.com", sub: String = "user-1") =
        signedJwt(claims(nonce, aud, iss, sub), KEYS.private)

    private fun answerWith(idToken: String, scope: String = PlanConfig.SCOPE) = MockResponse().setBody(
        """{"access_token":"AT","refresh_token":"RT","id_token":"$idToken","token_type":"Bearer","expires_in":3600,"scope":"$scope"}""",
    )

    private fun tokenAnswer(nonce: String, scope: String = PlanConfig.SCOPE, aud: String = "oaiapp_1", sub: String = "user-1") =
        answerWith(id(nonce, aud, sub = sub), scope)

    private fun query(url: String) = url.toHttpUrl().let { u -> u.queryParameterNames.associateWith { u.queryParameter(it) } }

    @Test fun firstSignInRegistersTheAppAndLaterOnesReuseTheIssuedId() {
        val first = query(PlanConfig.authorizeUrl(null, "urn:uuid:h", "http://127.0.0.1:1455/auth/callback", Pkce.generate(), "s", "n"))
        assertTrue(PlanConfig.authorizeUrl(null, "h", "r", Pkce.generate(), "s", "n").startsWith("https://auth.openai.com/api/accounts/authorize?"))
        assertEquals("dynamic_agent_client", first["client_id"])
        assertEquals("OpenPhoneUse", first["agent_name_hint"])
        assertEquals("urn:uuid:h", first["ext_agent_host_id"])
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", first["scope"])
        assertEquals("https://api.openai.com/v1", first["resource"])
        assertEquals("S256", first["code_challenge_method"])
        assertEquals("n", first["nonce"])
        assertEquals("http://127.0.0.1:1455/auth/callback", first["redirect_uri"])

        val again = query(PlanConfig.authorizeUrl("oaiapp_1", "urn:uuid:h", "r", Pkce.generate(), "s", "n", idTokenHint = "ID"))
        assertEquals("oaiapp_1", again["client_id"])
        assertNull(again["agent_name_hint"])
        assertEquals("ID", again["id_token_hint"])
    }

    @Test fun exchangeSendsTheIssuedClientAndChecksTheIdToken() = runBlocking {
        server.enqueue(tokenAnswer("N1"))
        val t = tokenClient().exchange("oaiapp_1", "CODE", "VERIFIER", "http://127.0.0.1:1455/auth/callback", "N1")
        val form = server.takeRequest().body.readUtf8()
        for (f in listOf("grant_type=authorization_code", "client_id=oaiapp_1", "code=CODE", "code_verifier=VERIFIER", "redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback", "resource=https%3A%2F%2Fapi.openai.com%2Fv1")) {
            assertTrue(f, form.contains(f))
        }
        assertEquals("AT", t.accessToken)
        assertEquals("RT", t.refreshToken)
        assertEquals(id("N1"), t.idToken)
        assertEquals("oaiapp_1", t.clientId)
        assertEquals(now + 3600, t.expiresAt)
        assertTrue(t.canUsePlan)
        assertEquals("user-1", t.subject)
        assertEquals("a@b.c", t.email)
        for (secret in listOf("AT", "RT", id("N1"))) assertFalse(t.toString().contains(secret))

        // A wrong nonce, audience or issuer is refused.
        for ((nonce, body) in listOf("N1" to tokenAnswer("OTHER"), "N1" to MockResponse().setBody("""{"access_token":"A","refresh_token":"R","id_token":"${id("N1", aud = "oaiapp_2")}"}"""))) {
            server.enqueue(body)
            try {
                tokenClient().exchange("oaiapp_1", "C", "V", "r", nonce)
                fail("expected AuthException")
            } catch (e: AuthException) {
                // expected
            }
        }
    }

    @Test fun aSignInWithoutThePlanScopeCannotSpendThePlan() = runBlocking {
        server.enqueue(tokenAnswer("N", scope = "openid profile email offline_access"))
        val t = tokenClient().exchange("oaiapp_1", "C", "V", "r", "N")
        assertFalse(t.canUsePlan)
        val auth = PlanAuth(MemoryPlanStore(t), tokenClient()) { now }
        try {
            auth.headers()
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(403, e.status)
            assertFalse(e.retryable)
        }
    }

    private fun signedIn(expiresAt: Long) = PlanTokens("oaiapp_1", "ID", "OLD", "RT1", expiresAt, PlanConfig.SCOPE.split(' '))

    @Test fun refreshRotatesTheRefreshTokenNearExpiry() = runBlocking {
        val store = MemoryPlanStore(signedIn(now + 60))
        server.enqueue(MockResponse().setBody("""{"access_token":"NEW","refresh_token":"RT2","expires_in":3600}"""))
        val headers = PlanAuth(store, tokenClient()) { now }.headers()
        assertEquals("Bearer NEW", headers["Authorization"])
        assertEquals(1, headers.size)
        val form = server.takeRequest().body.readUtf8()
        assertTrue(form.contains("grant_type=refresh_token") && form.contains("refresh_token=RT1") && form.contains("client_id=oaiapp_1"))
        assertEquals("RT2", store.tokens!!.refreshToken)
        assertEquals("ID", store.tokens!!.idToken)

        // Far from expiry: no request.
        assertEquals("Bearer NEW", PlanAuth(store, tokenClient()) { now }.headers()["Authorization"])
        assertEquals(1, server.requestCount)
    }

    @Test fun aRejectedRefreshSignsOutButAServerErrorKeepsTheSignIn() = runBlocking {
        val store = MemoryPlanStore(signedIn(now + 10))
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            PlanAuth(store, tokenClient()) { now }.headers()
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertNull(e.status)
        }
        assertTrue(store.tokens != null)

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"refresh_token_reused"}"""))
        try {
            PlanAuth(store, tokenClient()) { now }.headers()
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(401, e.status)
        }
        assertNull(store.tokens)
    }

    private fun refreshAnswer(access: String, delayMs: Long = 0) = MockResponse()
        .setBody("""{"access_token":"$access","refresh_token":"RT2","expires_in":3600}""")
        .also { if (delayMs > 0) it.setHeadersDelay(delayMs, TimeUnit.MILLISECONDS) }

    @Test fun noSignInAsksToSignIn() = runBlocking {
        val auth = PlanAuth(MemoryPlanStore(), tokenClient()) { now }
        try {
            auth.headers()
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(401, e.status)
            assertEquals(auth.unauthorizedMessage, e.message)
        }
        assertFalse(auth.onUnauthorized())
        assertEquals(0, server.requestCount)
    }

    /** Through the real client: the public API path, the refreshed token, and no other client's headers. */
    @Test fun a401RefreshesOnceAndRetriesWithOnlyTheBearerToken() = runBlocking {
        val api = MockWebServer().also { it.start() }
        try {
            server.enqueue(refreshAnswer("NEW"))
            api.enqueue(MockResponse().setResponseCode(401))
            api.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n",
            ))
            val plan = PlanAuth(MemoryPlanStore(signedIn(now + 3600)), tokenClient()) { now }
            val auth = object : com.phoneuse.model.ModelAuth by plan {
                override val baseUrl = api.url("/v1/").toString()
            }
            OpenAiResponsesClient(auth, ModelOptions("m", false, "low"), http).next("i", emptyList(), emptyList()) {}
            assertEquals("Bearer OLD", api.takeRequest().getHeader("Authorization"))
            val second = api.takeRequest()
            assertEquals("/v1/responses", second.path)
            assertEquals("Bearer NEW", second.getHeader("Authorization"))
            assertNull(second.getHeader("originator"))
            assertNull(second.getHeader("chatgpt-account-id"))
            assertEquals(1, server.requestCount)
        } finally {
            api.shutdown()
        }
    }

    @Test fun networkFailureOrRateLimitDuringRefreshKeepsTheSignIn() = runBlocking {
        val store = MemoryPlanStore(signedIn(now + 3600))
        server.enqueue(MockResponse().setResponseCode(429))
        try {
            PlanAuth(store, tokenClient()) { now }.onUnauthorized()
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertNull(e.status)
        }
        assertTrue(store.tokens != null)

        server.shutdown() // connection refused
        try {
            PlanAuth(store, tokenClient()) { now }.onUnauthorized()
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertNull(e.status)
        }
        assertTrue(store.tokens != null)
    }

    /** A rotated refresh token is never spent twice, whichever instance asks. */
    @Test fun concurrentCallersRefreshOnce() = runBlocking {
        server.enqueue(refreshAnswer("NEW", delayMs = 300))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"refresh_token_reused"}"""))
        val store = MemoryPlanStore(signedIn(now + 10))
        val results = (1..4).map { async(Dispatchers.IO) { PlanAuth(store, tokenClient()) { now }.headers()["Authorization"] } }.awaitAll()
        assertEquals(List(4) { "Bearer NEW" }, results)
        assertEquals(1, server.requestCount)
    }

    /** The store emptied by something outside the lock while the refresh was out: the fresh tokens are not saved back. */
    @Test fun aStoreEmptiedDuringARefreshStaysEmpty() = runBlocking {
        server.enqueue(refreshAnswer("NEW"))
        val store = object : PlanStore {
            var t: PlanTokens? = signedIn(now + 3600)
            var loads = 0
            override suspend fun load(): PlanTokens? {
                // The third load (after onUnauthorized and the refresh's own) is the re-check after the request.
                if (++loads == 3) t = null
                return t
            }
            override suspend fun save(t: PlanTokens?) { this.t = t }
            override suspend fun registrations() = emptyList<PlanRegistration>()
            override suspend fun saveRegistration(r: PlanRegistration) = Unit
            override suspend fun forgetRegistration(clientId: String) = Unit
            override suspend fun hostId() = "urn:uuid:h"
        }
        assertFalse(PlanAuth(store, tokenClient()) { now }.onUnauthorized())
        assertNull(store.t)
    }

    @Test fun loginRegistersOnceKeepsTheIssuedIdAndSavesTheSignIn() = runBlocking {
        val store = MemoryPlanStore()
        // The browser: the issuer redirects to the loopback callback with the code and the issued client id.
        val tokens = PlanLogin(http, store, tokenClient(), port = 0).login { url ->
            val q = query(url)
            assertEquals("dynamic_agent_client", q["client_id"])
            server.enqueue(tokenAnswer(q.getValue("nonce")!!))
            val back = q.getValue("redirect_uri")!!.toHttpUrl().newBuilder()
                .addQueryParameter("code", "CODE").addQueryParameter("state", q["state"]).addQueryParameter("client_id", "oaiapp_1").build()
            Thread { http.newCall(Request.Builder().url(back).build()).execute().close() }.start()
        }
        assertEquals(tokens, store.tokens)
        assertTrue(server.takeRequest().body.readUtf8().contains("client_id=oaiapp_1"))

        assertEquals(listOf(PlanRegistration("oaiapp_1", "user-1", "a@b.c")), store.registered)

        // Signing in again sends the issued id, and a callback naming another registration is refused.
        try {
            PlanLogin(http, store, tokenClient(), port = 0).login(store.registered.single()) { url ->
                val q = query(url)
                assertEquals("oaiapp_1", q["client_id"])
                assertNull(q["agent_name_hint"])
                assertEquals("a@b.c", q["login_hint"])
                back(q, "C", "oaiapp_9")
            }
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals(listOf(PlanRegistration("oaiapp_1", "user-1", "a@b.c")), store.registered)
        }
    }

    /** The browser's redirect back to the loopback callback. */
    private fun back(q: Map<String, String?>, code: String?, clientId: String?, error: String? = null) {
        val url = q.getValue("redirect_uri")!!.toHttpUrl().newBuilder().apply {
            code?.let { addQueryParameter("code", it) }
            addQueryParameter("state", q["state"])
            clientId?.let { addQueryParameter("client_id", it) }
            error?.let { addQueryParameter("error", it) }
        }.build()
        Thread { http.newCall(Request.Builder().url(url).build()).execute().close() }.start()
    }

    // Sign-out ends the renewable session (accounts and sessions page).

    @Test fun signOutRevokesTheRefreshTokenThenClearsTheTokensAndKeepsTheRegistration() = runBlocking {
        val registration = PlanRegistration("oaiapp_1", "user-1", "a@b.c")
        val store = MemoryPlanStore(signedIn(now + 3600), registration)
        assertTrue(PlanAuth(store, tokenClient()) { now }.signOut())
        assertEquals(listOf("token=RT1&token_type_hint=refresh_token&client_id=oaiapp_1"), revoked.toList())
        assertNull(store.tokens)
        assertEquals(listOf(registration), store.registered)
    }

    @Test fun signOutRetriesServerTroubleThenSignsOutLocallyUnconfirmed() = runBlocking {
        repeat(3) { revokeAnswers += MockResponse().setResponseCode(503) }
        val store = MemoryPlanStore(signedIn(now + 3600))
        assertFalse(PlanAuth(store, tokenClient()) { now }.signOut())
        assertEquals(3, revoked.size)
        assertNull(store.tokens)
    }

    @Test fun signOutRecoversOnARetry() = runBlocking {
        revokeAnswers += MockResponse().setResponseCode(500)
        val store = MemoryPlanStore(signedIn(now + 3600))
        assertTrue(PlanAuth(store, tokenClient()) { now }.signOut())
        assertEquals(2, revoked.size)
        assertNull(store.tokens)
    }

    @Test fun aRefusedRevocationIsNotRetriedAndStillSignsOut() = runBlocking {
        revokeAnswers += MockResponse().setResponseCode(400).setBody("""{"error":"invalid_request"}""")
        val store = MemoryPlanStore(signedIn(now + 3600))
        assertFalse(PlanAuth(store, tokenClient()) { now }.signOut())
        assertEquals(1, revoked.size)
        assertNull(store.tokens)
    }

    @Test fun signOutWithoutANetworkStillSignsOutLocally() = runBlocking {
        val store = MemoryPlanStore(signedIn(now + 3600))
        val client = tokenClient()
        issuer.shutdown()
        assertFalse(PlanAuth(store, client) { now }.signOut())
        assertNull(store.tokens)
    }

    @Test fun signOutWithoutASignInAsksNothing() = runBlocking {
        assertTrue(PlanAuth(MemoryPlanStore(), tokenClient()) { now }.signOut())
        assertTrue(revoked.isEmpty())
    }

    /** A refresh still out when sign-out starts rotates the token: the replacement is the one to revoke. */
    @Test fun signOutWaitsForARefreshAndRevokesTheReplacement() = runBlocking {
        server.enqueue(refreshAnswer("NEW", delayMs = 300))
        val store = MemoryPlanStore(signedIn(now + 10))
        val auth = PlanAuth(store, tokenClient()) { now }
        val refreshing = async(Dispatchers.IO) { auth.headers() }
        server.takeRequest() // the refresh is out, holding the lock
        assertTrue(auth.signOut())
        refreshing.await()
        assertEquals(listOf("token=RT2&token_type_hint=refresh_token&client_id=oaiapp_1"), revoked.toList())
        assertNull(store.tokens)
    }

    /** The stopped caller's request still rotates at the server, so its answer must not be dropped. */
    @Test fun aCancelledRefreshStillKeepsTheRotatedToken() = runBlocking {
        server.enqueue(refreshAnswer("NEW", delayMs = 300))
        val store = MemoryPlanStore(signedIn(now + 10))
        val caller = launch(Dispatchers.IO) { PlanAuth(store, tokenClient()) { now }.headers() }
        server.takeRequest()
        caller.cancelAndJoin()
        assertEquals("RT2", store.tokens!!.refreshToken)
        assertEquals("NEW", store.tokens!!.accessToken)
    }

    @Test fun aCancelledRefreshIsFollowedByTheRotatedTokenNotAReuse() = runBlocking {
        server.enqueue(refreshAnswer("NEW", delayMs = 300))
        val store = MemoryPlanStore(signedIn(now + 10))
        val caller = launch(Dispatchers.IO) { PlanAuth(store, tokenClient()) { now }.onUnauthorized(mapOf("Authorization" to "Bearer OLD")) }
        server.takeRequest()
        caller.cancelAndJoin()
        // The next call does not spend the old refresh token again.
        assertEquals("Bearer NEW", PlanAuth(store, tokenClient()) { now }.headers()["Authorization"])
        assertEquals(1, server.requestCount)
    }

    @Test fun aCancelledRefreshStillObeysATerminalAnswer() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"refresh_token_reused"}""").setHeadersDelay(300, TimeUnit.MILLISECONDS))
        val store = MemoryPlanStore(signedIn(now + 10))
        val caller = launch(Dispatchers.IO) { PlanAuth(store, tokenClient()) { now }.headers() }
        server.takeRequest()
        caller.cancelAndJoin()
        assertNull(store.tokens)
    }

    /** Settings stops the run and signs out straight away: the run's cancelled refresh rotated the token. */
    @Test fun signOutRightAfterACancelledRefreshRevokesTheReplacement() = runBlocking {
        server.enqueue(refreshAnswer("NEW", delayMs = 300))
        val store = MemoryPlanStore(signedIn(now + 10))
        val auth = PlanAuth(store, tokenClient()) { now }
        val run = launch(Dispatchers.IO) { auth.headers() }
        server.takeRequest()
        run.cancel()
        assertTrue(auth.signOut())
        run.join()
        assertEquals(listOf("token=RT2&token_type_hint=refresh_token&client_id=oaiapp_1"), revoked.toList())
        assertNull(store.tokens)
    }

    /** Sign-out is still waiting on the revocation when a new sign-in finishes: its end must not wipe the new tokens. */
    @Test fun aSignOutEndingLaterDoesNotWipeANewSignIn() = runBlocking {
        revokeDelayMs = 1_500
        val store = MemoryPlanStore(signedIn(now + 3600), PlanRegistration("oaiapp_1", "user-1", "a@b.c"))
        val out = async(Dispatchers.IO) { PlanAuth(store, tokenClient()) { now }.signOut() }
        while (revoked.isEmpty()) delay(10)
        val signed = PlanLogin(http, store, tokenClient(), port = 0).login(store.registered.single()) { url ->
            val q = query(url)
            server.enqueue(tokenAnswer(q.getValue("nonce")!!))
            back(q, "CODE", null)
        }
        out.await()
        assertEquals(signed, store.tokens)
    }

    /** A new sign-in finishing while a refresh of the old one is out: the new sign-in stays. */
    @Test fun aNewSignInFinishingDuringARefreshWins() = runBlocking {
        server.enqueue(refreshAnswer("NEW", delayMs = 500))
        val store = MemoryPlanStore(signedIn(now + 10), PlanRegistration("oaiapp_1", "user-1", "a@b.c"))
        val refresh = async(Dispatchers.IO) { PlanAuth(store, tokenClient()) { now }.headers() }
        server.takeRequest()
        val signed = PlanLogin(http, store, tokenClient(), port = 0).login(store.registered.single()) { url ->
            val q = query(url)
            server.enqueue(tokenAnswer(q.getValue("nonce")!!))
            back(q, "CODE", null)
        }
        refresh.await()
        assertEquals(signed, store.tokens)
    }

    // Switching account while signed in.

    private val oldSession = "token=RT1&token_type_hint=refresh_token&client_id=oaiapp_1"
    private val replacedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun signedInAs(subject: String = "user-1", expiresAt: Long = now + 3600) = signedIn(expiresAt).copy(subject = subject, email = "a@b.c")

    /** A sign-in as a new registration: [clientId] and [subject] are what the account picked in the browser comes back as. */
    private suspend fun switchTo(store: PlanStore, clientId: String = "oaiapp_2", subject: String = "user-2") =
        PlanLogin(http, store, tokenClient(), port = 0, replacedSessions = replacedScope).login { url ->
            val q = query(url)
            server.enqueue(tokenAnswer(q.getValue("nonce")!!, aud = clientId, sub = subject))
            back(q, "CODE", clientId)
        }

    private suspend fun revocationsDone() = replacedScope.coroutineContext.job.children.forEach { it.join() }

    @Test fun switchingToAnotherAccountRevokesTheOldSession() = runBlocking {
        val first = PlanRegistration("oaiapp_1", "user-1", "a@b.c")
        val store = MemoryPlanStore(signedInAs(), first)
        val signed = switchTo(store)
        revocationsDone()
        assertEquals(listOf(oldSession), revoked.toList())
        assertEquals(signed, store.tokens)
        assertEquals("user-2", store.tokens!!.subject)
        assertEquals(setOf(first, PlanRegistration("oaiapp_2", "user-2", "a@b.c")), store.registered.toSet())
    }

    @Test fun aFailedSwitchKeepsTheOldSignInUsable() = runBlocking {
        val current = signedInAs()
        val store = MemoryPlanStore(current)
        try {
            PlanLogin(http, store, tokenClient(), port = 0, replacedSessions = replacedScope).login { url ->
                val q = query(url)
                server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
                back(q, "CODE", "oaiapp_2")
            }
            fail("expected AuthException")
        } catch (e: AuthException) {
            revocationsDone()
            assertTrue(revoked.isEmpty())
            assertEquals(current, store.tokens)
            assertEquals(mapOf("Authorization" to "Bearer OLD"), PlanAuth(store, tokenClient()) { now }.headers())
        }
    }

    @Test fun aCancelledSwitchKeepsTheOldSignIn() = runBlocking {
        val current = signedInAs()
        val store = MemoryPlanStore(current)
        val opened = CompletableDeferred<Unit>()
        val login = launch(Dispatchers.IO) {
            PlanLogin(http, store, tokenClient(), port = 0, replacedSessions = replacedScope).login { opened.complete(Unit) }
        }
        opened.await()
        login.cancelAndJoin()
        revocationsDone()
        assertTrue(revoked.isEmpty())
        assertEquals(current, store.tokens)
    }

    @Test fun aRevocationThatFailsDoesNotFailTheSwitch() = runBlocking {
        repeat(3) { revokeAnswers += MockResponse().setResponseCode(500) }
        val store = MemoryPlanStore(signedInAs())
        val signed = switchTo(store)
        revocationsDone()
        assertEquals(3, revoked.size)
        assertEquals(signed, store.tokens)
    }

    @Test fun aRevocationThatCannotBeReachedDoesNotFailTheSwitch() = runBlocking {
        issuer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/jwks" -> MockResponse().setBody(jwksOf("k1" to KEYS))
                else -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
            }
        }
        val store = MemoryPlanStore(signedInAs())
        val signed = switchTo(store)
        revocationsDone()
        assertEquals(signed, store.tokens)
    }

    @Test fun theNewSignInDoesNotWaitForTheRevocation() = runBlocking {
        revokeDelayMs = 1_500
        val store = MemoryPlanStore(signedInAs())
        val signed = switchTo(store)
        assertEquals(signed, store.tokens)
        assertTrue(replacedScope.coroutineContext.job.children.any { it.isActive })
        revocationsDone()
        assertEquals(listOf(oldSession), revoked.toList())
    }

    /** One registration: its revocation might end the new session too, so the old token is left to expire. */
    @Test fun theSameAccountUnderItsRegistrationKeepsTheNewTokensWithoutRevoking() = runBlocking {
        val store = MemoryPlanStore(signedInAs())
        val signed = switchTo(store, clientId = "oaiapp_1", subject = "user-1")
        revocationsDone()
        assertTrue(revoked.isEmpty())
        assertEquals(signed, store.tokens)
    }

    @Test fun theSameAccountUnderANewRegistrationEndsTheOldOne() = runBlocking {
        val store = MemoryPlanStore(signedInAs())
        val signed = switchTo(store, subject = "user-1")
        revocationsDone()
        assertEquals(listOf(oldSession), revoked.toList())
        assertEquals(signed, store.tokens)
    }

    @Test fun anOldSignInWithoutARefreshTokenHasNothingToRevoke() = runBlocking {
        val store = MemoryPlanStore(signedInAs().copy(refreshToken = ""))
        switchTo(store)
        revocationsDone()
        assertTrue(revoked.isEmpty())
    }

    /** A refresh still out when the switch finishes rotates the token: the replacement is the one to revoke, once. */
    @Test fun aSwitchWaitsForARefreshAndRevokesTheReplacement() = runBlocking {
        server.enqueue(refreshAnswer("NEW", delayMs = 300))
        val store = MemoryPlanStore(signedInAs(expiresAt = now + 10))
        val refreshing = async(Dispatchers.IO) { PlanAuth(store, tokenClient()) { now }.headers() }
        server.takeRequest() // the refresh is out, holding the lock
        val signed = switchTo(store)
        refreshing.await()
        revocationsDone()
        assertEquals(listOf("token=RT2&token_type_hint=refresh_token&client_id=oaiapp_1"), revoked.toList())
        assertEquals(signed, store.tokens)
    }

    // The ID token's signature (registration and sign-in page, step 4).

    private suspend fun assertRefused(client: PlanTokenClient, body: MockResponse, nonce: String = "N") {
        server.enqueue(body)
        try {
            client.exchange("oaiapp_1", "C", "V", "r", nonce)
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertFalse(e.message!!.contains("eyJ"))
        }
    }

    @Test fun aTokenSignedByTheIssuersKeyPasses() = runBlocking {
        server.enqueue(tokenAnswer("N"))
        assertEquals("user-1", tokenClient().exchange("oaiapp_1", "C", "V", "r", "N").subject)
    }

    @Test fun aForgedOrUnsignedIdTokenIsRefused() = runBlocking {
        val client = tokenClient()
        assertRefused(client, answerWith(signedJwt(claims("N"), OTHER_KEYS.private)))
        assertRefused(client, answerWith(fakeJwt(claims("N"))))
        assertRefused(client, answerWith(signedJwt(claims("N"), KEYS.private, kid = "unknown")))
        assertRefused(client, answerWith(signedJwt(claims("N"), KEYS.private, alg = "HS256")))
        // The claims changed after signing.
        val parts = id("N").split('.')
        val forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(claims("N", sub = "user-2").toByteArray())
        assertRefused(client, answerWith("${parts[0]}.$forged.${parts[2]}"))
    }

    @Test fun theKeyIsFoundByKidAmongSeveral() = runBlocking {
        jwksAnswer = MockResponse().setBody(jwksOf("old" to OTHER_KEYS, "k1" to KEYS))
        server.enqueue(tokenAnswer("N"))
        assertEquals("user-1", tokenClient().exchange("oaiapp_1", "C", "V", "r", "N").subject)
    }

    @Test fun noPublishedKeysMeansNoSignIn() = runBlocking {
        val client = tokenClient()
        jwksAnswer = MockResponse().setResponseCode(503)
        assertRefused(client, tokenAnswer("N"))
        issuer.shutdown()
        assertRefused(client, tokenAnswer("N"))
    }

    @Test fun theRequiredClaimsMustBeThereAndTheIssuerExact() = runBlocking {
        val client = tokenClient()
        val noExp = """{"iss":"https://auth.openai.com","aud":"oaiapp_1","nonce":"N","sub":"user-1"}"""
        val noSub = """{"iss":"https://auth.openai.com","aud":"oaiapp_1","nonce":"N","exp":${now + 3600}}"""
        assertRefused(client, answerWith(signedJwt(noExp, KEYS.private)))
        assertRefused(client, answerWith(signedJwt(noSub, KEYS.private)))
        assertRefused(client, answerWith(id("N", iss = "https://auth.openai.com/")))
    }

    // One registration per ChatGPT account (accounts and sessions page).

    @Test fun anotherAccountRegistersItsOwnClientAndLeavesTheFirstOneAlone() = runBlocking {
        val first = PlanRegistration("oaiapp_1", "user-1", "a@b.c")
        val store = MemoryPlanStore(null, first)
        val tokens = PlanLogin(http, store, tokenClient(), port = 0).login { url ->
            val q = query(url)
            assertEquals("dynamic_agent_client", q["client_id"])
            assertEquals("OpenPhoneUse", q["agent_name_hint"])
            server.enqueue(tokenAnswer(q.getValue("nonce")!!, aud = "oaiapp_2", sub = "user-2"))
            back(q, "CODE", "oaiapp_2")
        }
        assertEquals("oaiapp_2", tokens.clientId)
        assertEquals("user-2", store.tokens!!.subject)
        assertEquals(setOf(first, PlanRegistration("oaiapp_2", "user-2", "a@b.c")), store.registered.toSet())
    }

    @Test fun aSavedAccountSignsInAgainWithItsOwnClientWithoutClientIdInTheCallback() = runBlocking {
        val store = MemoryPlanStore(null, PlanRegistration("oaiapp_1", "user-1", "a@b.c"), PlanRegistration("oaiapp_2", "user-2", "z@b.c"))
        val tokens = PlanLogin(http, store, tokenClient(), port = 0).login(store.registered[1]) { url ->
            val q = query(url)
            assertEquals("oaiapp_2", q["client_id"])
            assertEquals("z@b.c", q["login_hint"])
            assertNull(q["id_token_hint"])
            server.enqueue(tokenAnswer(q.getValue("nonce")!!, aud = "oaiapp_2", sub = "user-2"))
            back(q, "CODE", null)
        }
        assertEquals("oaiapp_2", tokens.clientId)
        assertEquals(2, store.registered.size)
    }

    @Test fun aDifferentIdentityUnderASavedAccountChangesNothing() = runBlocking {
        val current = signedIn(now + 3600)
        val store = MemoryPlanStore(current, PlanRegistration("oaiapp_1", "user-1", "a@b.c"))
        try {
            PlanLogin(http, store, tokenClient(), port = 0).login(store.registered.single()) { url ->
                val q = query(url)
                server.enqueue(tokenAnswer(q.getValue("nonce")!!, sub = "user-2"))
                back(q, "CODE", null)
            }
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals(current, store.tokens)
            assertEquals("user-1", store.registered.single().subject)
        }
    }

    @Test fun aRegistrationKnownOnlyByItsClientIdLearnsItsAccount() = runBlocking {
        val store = MemoryPlanStore(null, PlanRegistration("oaiapp_1"))
        PlanLogin(http, store, tokenClient(), port = 0).login(store.registered.single()) { url ->
            val q = query(url)
            server.enqueue(tokenAnswer(q.getValue("nonce")!!))
            back(q, "CODE", null)
        }
        assertEquals(listOf(PlanRegistration("oaiapp_1", "user-1", "a@b.c")), store.registered)
    }

    @Test fun labelsStayDistinctWhenEmailsMatch() {
        val a = PlanRegistration("oaiapp_aaaa1111", "user-1", "a@b.c")
        val b = PlanRegistration("oaiapp_bbbb2222", "user-2", "a@b.c")
        val c = PlanRegistration("oaiapp_cccc3333", "user-3", "z@b.c")
        val all = listOf(a, b, c)
        assertEquals("z@b.c", c.label(all))
        assertEquals(3, all.map { it.label(all) }.toSet().size)
        assertTrue(a.label(all).startsWith("a@b.c"))
    }

    @Test fun enablingThePlanLaterAsksForConsentWithTheFullScopes() {
        val q = query(PlanConfig.authorizeUrl("oaiapp_1", "h", "r", Pkce.generate(), "s", "n", idTokenHint = "ID", consent = true))
        assertEquals("consent", q["prompt"])
        assertEquals(PlanConfig.SCOPE, q["scope"])
        assertEquals("ID", q["id_token_hint"])
        assertNull(query(PlanConfig.authorizeUrl("oaiapp_1", "h", "r", Pkce.generate(), "s", "n"))["prompt"])
    }

    @Test fun allowingThePlanRepeatsTheSignInWithConsentAndReplacesTheTokens() = runBlocking {
        val store = MemoryPlanStore(signedIn(now + 3600).copy(scopes = listOf("openid")), PlanRegistration("oaiapp_1", "user-1", "a@b.c"))
        val t = PlanLogin(http, store, tokenClient(), port = 0).login(store.registered.single(), consent = true, idTokenHint = "HINT") { url ->
            val q = query(url)
            assertEquals("oaiapp_1", q["client_id"])
            assertEquals("consent", q["prompt"])
            assertEquals("HINT", q["id_token_hint"])
            assertEquals("a@b.c", q["login_hint"])
            assertEquals(PlanConfig.SCOPE, q["scope"])
            assertNull(q["agent_name_hint"])
            server.enqueue(tokenAnswer(q.getValue("nonce")!!))
            back(q, "CODE", null)
        }
        assertTrue(t.canUsePlan)
        assertEquals(t, store.tokens)
    }

    @Test fun aRegistrationAfterInvalidClientSendsNoHintsOrConsent() = runBlocking {
        val store = MemoryPlanStore(null, PlanRegistration("oaiapp_old", "user-1", "a@b.c"))
        val urls = ArrayList<Map<String, String?>>()
        PlanLogin(http, store, tokenClient(), port = 0).login(store.registered.single(), consent = true, idTokenHint = "HINT") { url ->
            val q = query(url)
            urls += q
            if (q["client_id"] == "oaiapp_old") {
                back(q, null, null, error = "invalid_client")
            } else {
                server.enqueue(tokenAnswer(q.getValue("nonce")!!))
                back(q, "CODE", "oaiapp_1")
            }
        }
        assertEquals("HINT", urls[0]["id_token_hint"])
        assertEquals("dynamic_agent_client", urls[1]["client_id"])
        for (absent in listOf("id_token_hint", "login_hint", "prompt")) assertNull(urls[1][absent])
    }

    /** The user chose user-1, whose client is gone, then registered as user-2: not what they chose, so nothing is replaced. */
    @Test fun aRegistrationAfterInvalidClientMustBeTheChosenAccount() = runBlocking {
        val current = signedIn(now + 3600)
        val store = MemoryPlanStore(current, PlanRegistration("oaiapp_old", "user-1", "a@b.c"))
        try {
            PlanLogin(http, store, tokenClient(), port = 0).login(store.registered.single()) { url ->
                val q = query(url)
                if (q["client_id"] == "oaiapp_old") {
                    back(q, null, null, error = "invalid_client")
                } else {
                    server.enqueue(tokenAnswer(q.getValue("nonce")!!, aud = "oaiapp_2", sub = "user-2"))
                    back(q, "CODE", "oaiapp_2")
                }
            }
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals(current, store.tokens)
            // The new client is the other account's own, known by its validated identity; it is not registered twice.
            assertEquals(listOf(PlanRegistration("oaiapp_2", "user-2", "a@b.c")), store.registered)
        }
    }

    // A stale issued client id.

    @Test fun invalidClientOnRefreshSignsOutAndForgetsTheRegistration() = runBlocking {
        val store = MemoryPlanStore(signedIn(now + 10), PlanRegistration("oaiapp_1", "user-1"), PlanRegistration("oaiapp_2", "user-2"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_client"}"""))
        try {
            PlanAuth(store, tokenClient()) { now }.headers()
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(401, e.status)
        }
        assertNull(store.tokens)
        assertEquals(listOf("oaiapp_2"), store.registered.map { it.clientId })
    }

    @Test fun invalidClientAtTheCodeExchangeRegistersAgain() = runBlocking {
        val store = MemoryPlanStore(null, PlanRegistration("oaiapp_old", "user-1", "a@b.c"))
        val opened = ArrayList<String?>()
        val tokens = PlanLogin(http, store, tokenClient(), port = 0).login(store.registered.single()) { url ->
            val q = query(url)
            opened += q["client_id"]
            if (q["client_id"] == "oaiapp_old") {
                server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_client"}"""))
                back(q, "CODE", null)
            } else {
                server.enqueue(tokenAnswer(q.getValue("nonce")!!))
                back(q, "CODE", "oaiapp_1")
            }
        }
        assertEquals(listOf("oaiapp_old", "dynamic_agent_client"), opened)
        assertEquals("oaiapp_1", tokens.clientId)
        assertEquals(listOf(PlanRegistration("oaiapp_1", "user-1", "a@b.c")), store.registered)
    }

    @Test fun invalidClientInTheCallbackRegistersAgain() = runBlocking {
        val store = MemoryPlanStore(null, PlanRegistration("oaiapp_old"))
        val tokens = PlanLogin(http, store, tokenClient(), port = 0).login(store.registered.single()) { url ->
            val q = query(url)
            if (q["client_id"] == "oaiapp_old") {
                back(q, null, null, error = "invalid_client")
            } else {
                server.enqueue(tokenAnswer(q.getValue("nonce")!!))
                back(q, "CODE", "oaiapp_1")
            }
        }
        assertEquals("oaiapp_1", tokens.clientId)
    }

    @Test fun aSecondInvalidClientStopsInsteadOfLooping() = runBlocking {
        val store = MemoryPlanStore(null, PlanRegistration("oaiapp_old"))
        var opened = 0
        try {
            PlanLogin(http, store, tokenClient(), port = 0).login(store.registered.single()) { url ->
                opened++
                back(query(url), null, null, error = "invalid_client")
            }
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals(2, opened)
        }
    }

    @Test fun anotherSignInFailureKeepsTheRegistration() = runBlocking {
        val store = MemoryPlanStore(null, PlanRegistration("oaiapp_1"))
        try {
            PlanLogin(http, store, tokenClient(), port = 0).login(store.registered.single()) { url ->
                val q = query(url)
                server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
                back(q, "CODE", null)
            }
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals("invalid_grant", e.code)
            assertEquals(listOf("oaiapp_1"), store.registered.map { it.clientId })
        }
    }

    // A 401 around a rotated refresh.

    /** Its request went out with the old token, but another caller already rotated: no second refresh. */
    @Test fun aLate401ForAnOldTokenRetriesWithTheCurrentOne() = runBlocking {
        val store = MemoryPlanStore(signedIn(now + 3600).copy(accessToken = "NEW", refreshToken = "RT2"))
        assertTrue(PlanAuth(store, tokenClient()) { now }.onUnauthorized(mapOf("Authorization" to "Bearer OLD")))
        assertEquals(0, server.requestCount)
        assertEquals("RT2", store.tokens!!.refreshToken)
    }

    @Test fun concurrent401sForOneTokenRefreshOnce() = runBlocking {
        server.enqueue(refreshAnswer("NEW", delayMs = 200))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"refresh_token_reused"}"""))
        val store = MemoryPlanStore(signedIn(now + 3600))
        val results = (1..4).map {
            async(Dispatchers.IO) { PlanAuth(store, tokenClient()) { now }.onUnauthorized(mapOf("Authorization" to "Bearer OLD")) }
        }.awaitAll()
        assertEquals(List(4) { true }, results)
        assertEquals(1, server.requestCount)
        assertEquals("NEW", store.tokens!!.accessToken)
    }

    /** Refreshed and still refused: one refresh, one retry, then an error, and the sign-in stays. */
    @Test fun a401AfterTheRefreshEndsWithoutALoopOrASignOut() = runBlocking {
        val api = MockWebServer().also { it.start() }
        try {
            server.enqueue(refreshAnswer("NEW"))
            repeat(2) { api.enqueue(MockResponse().setResponseCode(401)) }
            val store = MemoryPlanStore(signedIn(now + 3600))
            val plan = PlanAuth(store, tokenClient()) { now }
            val auth = object : com.phoneuse.model.ModelAuth by plan {
                override val baseUrl = api.url("/v1/").toString()
            }
            try {
                OpenAiResponsesClient(auth, ModelOptions("m", false, "low"), http).next("i", emptyList(), emptyList()) {}
                fail("expected ModelException")
            } catch (e: ModelException) {
                assertEquals(401, e.status)
            }
            assertEquals(2, api.requestCount)
            assertEquals(1, server.requestCount)
            assertEquals("NEW", store.tokens!!.accessToken)
        } finally {
            api.shutdown()
        }
    }

    /** Only a machine-readable refresh error ends the sign-in (errors page), not a bare 401 from something on the way. */
    @Test fun aRefreshRefusalWithoutACodeKeepsTheSignIn() = runBlocking {
        for (status in listOf(400, 401)) {
            val store = MemoryPlanStore(signedIn(now + 3600))
            server.enqueue(MockResponse().setResponseCode(status).setBody("<html>gateway</html>"))
            try {
                PlanAuth(store, tokenClient()) { now }.onUnauthorized()
                fail("expected ModelException")
            } catch (e: ModelException) {
                assertNull(e.status)
            }
            assertTrue(store.tokens != null)
        }
    }

    // The loopback port.

    private fun busyPort() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    /** Later sign-ins may use another port (sign-in page, redirect_uri): only the port differs. */
    @Test fun aBusyPortMovesASavedAccountToAnotherPort() = runBlocking {
        busyPort().use { busy ->
            val store = MemoryPlanStore(null, PlanRegistration("oaiapp_1", "user-1", "a@b.c"))
            val t = PlanLogin(http, store, tokenClient(), port = busy.localPort).login(store.registered.single()) { url ->
                val q = query(url)
                val redirect = q.getValue("redirect_uri")!!.toHttpUrl()
                assertNotEquals(busy.localPort, redirect.port)
                assertEquals("127.0.0.1", redirect.host)
                assertEquals("/auth/callback", redirect.encodedPath)
                server.enqueue(tokenAnswer(q.getValue("nonce")!!))
                back(q, "CODE", null)
            }
            assertEquals(t, store.tokens)
        }
    }

    @Test fun aBusyPortStopsAFirstRegistrationAndNamesThePort() = runBlocking {
        busyPort().use { busy ->
            try {
                PlanLogin(http, MemoryPlanStore(), tokenClient(), port = busy.localPort).login { fail("the browser must not open") }
                fail("expected AuthException")
            } catch (e: AuthException) {
                assertTrue(e.message, e.message!!.contains(busy.localPort.toString()))
            }
        }
    }

    /** What Settings throws when no browser can open: the sign-in ends now, not when the loopback times out. */
    @Test fun aBrowserThatWontOpenEndsTheSignInAtOnce() = runBlocking {
        val store = MemoryPlanStore()
        val e = withTimeout(5_000) {
            runCatching { PlanLogin(http, store, tokenClient(), port = 0).login { throw AuthException("no browser") } }.exceptionOrNull()
        }
        assertEquals("no browser", e?.message)
        assertNull(store.tokens)
    }

    // A sign-in without the plan permission is retained (errors page); a refresh token comes only with offline_access (token reference).

    private fun answerWithoutRefresh(nonce: String, scope: String) = MockResponse().setBody(
        """{"access_token":"AT","id_token":"${id(nonce)}","token_type":"Bearer","expires_in":3600,"scope":"$scope"}""",
    )

    @Test fun aSignInWithoutThePlanOrARefreshTokenIsRetainedAsDisabled() = runBlocking {
        server.enqueue(answerWithoutRefresh("N", "openid profile email"))
        val t = tokenClient().exchange("oaiapp_1", "C", "V", "r", "N")
        assertFalse(t.canUsePlan)
        val store = MemoryPlanStore(t)
        val auth = PlanAuth(store, tokenClient()) { now }
        try {
            auth.headers()
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(403, e.status)
        }
        // There is no renewable session to revoke.
        assertTrue(auth.signOut())
        assertTrue(revoked.isEmpty())
        assertNull(store.tokens)
    }

    @Test fun aGrantThatPromisedARefreshTokenStillNeedsOne() = runBlocking {
        for (scope in listOf("openid offline_access", PlanConfig.SCOPE)) {
            server.enqueue(answerWithoutRefresh("N", scope))
            try {
                tokenClient().exchange("oaiapp_1", "C", "V", "r", "N")
                fail("expected AuthException")
            } catch (e: AuthException) {
                assertTrue(e.message!!.contains("refresh_token"))
            }
        }
    }

    @Test fun modelCatalogKeepsListedSlugsInServerOrder() {
        val plan = """{"models":[{"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list"},{"slug":"codex-auto-review","visibility":"hide"},{"slug":"gpt-6-astra","display_name":"GPT-6 Astra","visibility":"list"}]}"""
        assertEquals(listOf("gpt-6.1-sol", "gpt-6-astra"), PlanModels.parse(plan))
        assertEquals(listOf("gpt-6-astra", "text-embedding-3"), PlanModels.parse("""{"data":[{"id":"gpt-6-astra"},{"id":"text-embedding-3"}]}"""))
    }

    private companion object {
        val KEYS = rsaKeys()
        val OTHER_KEYS = rsaKeys()
    }
}
