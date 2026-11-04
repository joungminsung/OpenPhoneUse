package com.phoneuse.auth

import com.phoneuse.app.SettingsStore
import com.phoneuse.i18n.tr
import com.phoneuse.model.ModelAuth
import com.phoneuse.model.ModelException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/*
 * Sign in with ChatGPT, ChatGPT plan usage (OpenAI's open-source flow, developers.openai.com/siwc): each ChatGPT account
 * registers its own OAuth client on its first sign-in (client_id dynamic_agent_client -> an issued oaiapp_... id), and
 * the access token then calls the public Responses API at api.openai.com on the user's ChatGPT plan.
 */

object PlanConfig {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE_URL = "$ISSUER/api/accounts/authorize"
    const val TOKEN_URL = "$ISSUER/api/accounts/oauth/token"
    const val JWKS_URL = "$ISSUER/.well-known/jwks.json"
    /** OpenAI's OpenID configuration; the revocation endpoint is read from it. */
    const val DISCOVERY_URL = "$ISSUER/.well-known/openid-configuration"
    const val RESOURCE = "https://api.openai.com/v1"
    const val BASE_URL = "$RESOURCE/"
    const val DYNAMIC_CLIENT = "dynamic_agent_client"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    /** The scope that lets the token spend the plan; without it the sign-in is only an identity. */
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    const val AGENT_NAME = "OpenPhoneUse"
    /** The loopback port of the sign-in, the one the SIWC sign-in page shows; only the port may vary between sign-ins. */
    const val LOOPBACK_PORT = 1455
    /** Where the user sees and manages the plan usage this app spends. */
    const val MANAGE_USAGE_URL = "https://chatgpt.com/settings/usage"

    /**
     * The authorize URL. An account's first sign-in registers it ([clientId] null: dynamic_agent_client with the app's
     * name); later ones reuse its issued id, [idTokenHint] skips the account picker and [loginHint] (a saved email)
     * preselects the account. [consent] asks for the plan permission again, after an earlier decline.
     */
    fun authorizeUrl(clientId: String?, hostId: String, redirectUri: String, pkce: PkceCodes, state: String, nonce: String, idTokenHint: String? = null, loginHint: String? = null, consent: Boolean = false): String =
        AUTHORIZE_URL.toHttpUrl().newBuilder().apply {
            addQueryParameter("client_id", clientId ?: DYNAMIC_CLIENT)
            if (clientId == null) addQueryParameter("agent_name_hint", AGENT_NAME)
            addQueryParameter("ext_agent_host_id", hostId)
            if (clientId != null && idTokenHint != null) addQueryParameter("id_token_hint", idTokenHint)
            if (clientId != null && loginHint != null) addQueryParameter("login_hint", loginHint)
            if (consent) addQueryParameter("prompt", "consent")
            addQueryParameter("response_type", "code")
            addQueryParameter("redirect_uri", redirectUri)
            addQueryParameter("scope", SCOPE)
            addQueryParameter("resource", RESOURCE)
            addQueryParameter("state", state)
            addQueryParameter("nonce", nonce)
            addQueryParameter("code_challenge_method", "S256")
            addQueryParameter("code_challenge", pkce.challenge)
        }.build().toString()
}

/** A plan sign-in. [expiresAt] is epoch seconds; [scopes] are the granted ones from the token response; [refreshToken] is empty if none was granted. */
@Serializable
data class PlanTokens(
    val clientId: String,
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val scopes: List<String>,
    val subject: String? = null,
    val email: String? = null,
) {
    val canUsePlan: Boolean get() = PlanConfig.PLAN_SCOPE in scopes

    /** Never print token values. */
    override fun toString() = "PlanTokens(clientId=$clientId, email=$email, scopes=$scopes, tokens=<redacted>)"
}

/**
 * One ChatGPT account's registration on this install: an issued client id is bound to the user and workspace it was
 * registered with. [subject] is the validated ID token's sub, null until a sign-in under this id has been validated.
 */
@Serializable
data class PlanRegistration(val clientId: String, val subject: String? = null, val email: String? = null) {
    /** What the account picker shows; emails can repeat across registrations, so those get a piece of the id. */
    fun label(all: List<PlanRegistration>): String {
        val name = email ?: tr("ChatGPT account", "ChatGPT 계정")
        return if (all.count { it.email == email } > 1) "$name · ${clientId.takeLast(6)}" else name
    }
}

interface PlanStore {
    suspend fun load(): PlanTokens?
    /** null clears the sign-in (the registrations and host id stay, see [registrations] and [hostId]). */
    suspend fun save(t: PlanTokens?)
    /** Every account's registration, newest first, kept across sign-outs so an account does not register twice. */
    suspend fun registrations(): List<PlanRegistration>
    /** Adds [r], or replaces the record of its client id. */
    suspend fun saveRegistration(r: PlanRegistration)
    suspend fun forgetRegistration(clientId: String)
    /** This install's opaque ext_agent_host_id (urn:uuid), made once and kept. */
    suspend fun hostId(): String
}

/** Keeps the sign-in, the registrations and the host id in the encrypted settings secrets. */
class SettingsPlanStore(private val settings: SettingsStore) : PlanStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): PlanTokens? {
        val raw = settings.readSecret(SettingsStore.KEY_PLAN_TOKENS) ?: return null
        return runCatching { json.decodeFromString(PlanTokens.serializer(), raw) }.getOrNull()
    }

    override suspend fun save(t: PlanTokens?) {
        // Signed out or another account: the pause after a usage limit was about the one before (PlanPause).
        if (t == null || t.subject != load()?.subject) settings.setPlanPausedUntil(0)
        settings.writeSecret(SettingsStore.KEY_PLAN_TOKENS, t?.let { json.encodeToString(PlanTokens.serializer(), it) })
    }

    override suspend fun registrations(): List<PlanRegistration> = REGISTRY_MUTEX.withLock { readRegistrations() }

    override suspend fun saveRegistration(r: PlanRegistration) {
        REGISTRY_MUTEX.withLock { writeRegistrations(listOf(r) + readRegistrations().filter { it.clientId != r.clientId }) }
    }

    override suspend fun forgetRegistration(clientId: String) {
        REGISTRY_MUTEX.withLock { writeRegistrations(readRegistrations().filter { it.clientId != clientId }) }
    }

    private suspend fun readRegistrations(): List<PlanRegistration> {
        val saved = settings.readSecret(SettingsStore.KEY_PLAN_REGISTRATIONS)
            ?.let { raw -> runCatching { json.decodeFromString(REGISTRATIONS, raw) }.getOrNull() }
            .orEmpty()
        // Earlier builds kept one client id per install; whose it is shows only while its sign-in is still here.
        val older = settings.readSecret(SettingsStore.KEY_PLAN_CLIENT)?.ifBlank { null } ?: return saved
        val signedIn = load()?.takeIf { it.clientId == older }
        val merged = if (saved.any { it.clientId == older }) saved else saved + PlanRegistration(older, signedIn?.subject, signedIn?.email)
        if (writeRegistrations(merged)) settings.writeSecret(SettingsStore.KEY_PLAN_CLIENT, null)
        return merged
    }

    /** False when it wrote nothing: a read during Keystore trouble looks empty, and must not replace the real list. */
    private suspend fun writeRegistrations(all: List<PlanRegistration>): Boolean {
        if (settings.secretInTrouble(SettingsStore.KEY_PLAN_REGISTRATIONS)) return false
        settings.writeSecret(SettingsStore.KEY_PLAN_REGISTRATIONS, json.encodeToString(REGISTRATIONS, all))
        return true
    }

    override suspend fun hostId(): String = HOST_MUTEX.withLock {
        settings.readSecret(SettingsStore.KEY_PLAN_HOST)?.ifBlank { null }
            ?: ("urn:uuid:" + UUID.randomUUID()).also { settings.writeSecret(SettingsStore.KEY_PLAN_HOST, it) }
    }

    private companion object {
        val HOST_MUTEX = Mutex()
        val REGISTRY_MUTEX = Mutex()
        val REGISTRATIONS = ListSerializer(PlanRegistration.serializer())
    }
}

/** Code exchange, refresh and revocation at the plan endpoints (form-encoded, no client secret). Errors never carry tokens. */
class PlanTokenClient(
    private val http: OkHttpClient,
    private val tokenUrl: String = PlanConfig.TOKEN_URL,
    private val jwksUrl: String = PlanConfig.JWKS_URL,
    private val discoveryUrl: String = PlanConfig.DISCOVERY_URL,
    /** The waits before each repeat of a revocation that met a network failure or a 5xx. */
    private val revokeBackoffMs: List<Long> = listOf(1_000, 3_000),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Exchanges [code]; checks the ID token's signature, issuer, audience, expiry and [nonce], and that it names a subject. */
    suspend fun exchange(clientId: String, code: String, verifier: String, redirectUri: String, nonce: String): PlanTokens {
        val res = post(
            "grant_type" to "authorization_code", "client_id" to clientId, "code" to code,
            "code_verifier" to verifier, "redirect_uri" to redirectUri, "resource" to PlanConfig.RESOURCE,
        )
        val idToken = res.str("id_token") ?: throw AuthException(tr("The sign-in response has no id_token", "로그인 응답에 id_token이 없어요"))
        val claims = checkIdToken(idToken, clientId, nonce)
        val scopes = res.str("scope")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
        // Only a grant with offline_access brings one; a sign-in without the plan is still retained, as disabled.
        val refreshToken = res.str("refresh_token")
        if (refreshToken == null && ("offline_access" in scopes || PlanConfig.PLAN_SCOPE in scopes)) {
            throw AuthException(tr("The sign-in response has no refresh_token", "로그인 응답에 refresh_token이 없어요"))
        }
        return PlanTokens(
            clientId = clientId,
            idToken = idToken,
            accessToken = res.str("access_token") ?: throw AuthException(tr("The sign-in response has no access_token", "로그인 응답에 access_token이 없어요")),
            refreshToken = refreshToken.orEmpty(),
            expiresAt = now() + (res.long("expires_in") ?: DEFAULT_LIFETIME_S),
            scopes = scopes,
            subject = claims.str("sub"),
            email = claims.str("email"),
        )
    }

    /** A refresh; the refresh token rotates, and fields the answer leaves out keep [current]'s values. */
    suspend fun refresh(current: PlanTokens): PlanTokens {
        val res = post(
            "grant_type" to "refresh_token", "client_id" to current.clientId,
            "refresh_token" to current.refreshToken, "resource" to PlanConfig.RESOURCE,
        )
        return current.copy(
            accessToken = res.str("access_token") ?: throw AuthException(tr("The refresh response has no access_token", "갱신 응답에 access_token이 없어요")),
            refreshToken = res.str("refresh_token") ?: current.refreshToken,
            idToken = res.str("id_token") ?: current.idToken,
            expiresAt = now() + (res.long("expires_in") ?: DEFAULT_LIFETIME_S),
            scopes = res.str("scope")?.split(' ')?.filter { it.isNotBlank() } ?: current.scopes,
        )
    }

    /**
     * Ends the renewable session at the revocation endpoint of OpenAI's OpenID configuration. True only on the server's
     * HTTP 200. A network failure or 5xx is tried again, and neither is thrown: signing out goes on without it.
     */
    suspend fun revoke(tokens: PlanTokens): Boolean {
        val quick = http.newBuilder().callTimeout(REVOKE_TIMEOUT_S, TimeUnit.SECONDS).build()
        for (wait in listOf(0L) + revokeBackoffMs) {
            delay(wait)
            val status = revokeOnce(quick, tokens)
            if (status == 200) return true
            if (status != null && status !in 500..599) return false
        }
        return false
    }

    /** The revocation's HTTP status, or null when it never got one. */
    private suspend fun revokeOnce(client: OkHttpClient, tokens: PlanTokens): Int? = withContext(Dispatchers.IO) {
        try {
            val endpoint = fetch(client, get(discoveryUrl)).str("revocation_endpoint") ?: return@withContext null
            val form = FormBody.Builder()
                .add("token", tokens.refreshToken)
                .add("token_type_hint", "refresh_token")
                .add("client_id", tokens.clientId)
                .build()
            client.newCall(Request.Builder().url(endpoint).post(form).build()).execute().use { it.code }
        } catch (e: AuthException) {
            null
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private suspend fun checkIdToken(idToken: String, clientId: String, nonce: String): JsonObject {
        // The keys are read at each sign-in, so a rotated one is always known.
        if (!JwtSignature.verifies(idToken, fetch(http, get(jwksUrl)))) throw AuthException(ID_TOKEN_REFUSED)
        val c = JwtClaims.claims(idToken) ?: throw AuthException(tr("Couldn't read the sign-in token", "로그인 토큰을 읽을 수 없어요"))
        val aud = c["aud"]
        val audOk = (aud as? JsonPrimitive)?.contentOrNull == clientId || (aud as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == clientId } == true
        val exp = (c["exp"] as? JsonPrimitive)?.longOrNull
        if (c.str("iss") != PlanConfig.ISSUER || !audOk || exp == null || exp < now() - CLOCK_SKEW_S || c.str("nonce") != nonce || c.str("sub") == null) {
            throw AuthException(ID_TOKEN_REFUSED)
        }
        return c
    }

    private suspend fun post(vararg fields: Pair<String, String>): JsonObject {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return fetch(http, Request.Builder().url(tokenUrl).post(form).header("Accept", "application/json").build())
    }

    private fun get(url: String) = Request.Builder().url(url).header("Accept", "application/json").build()

    private suspend fun fetch(client: OkHttpClient, request: Request): JsonObject = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val code = errorCode(text)
                    throw AuthException(tr("Sign-in server error (HTTP ${r.code}${code?.let { ", $it" }.orEmpty()})", "로그인 서버 오류 (HTTP ${r.code}${code?.let { ", $it" }.orEmpty()})"), r.code, code)
                }
                try {
                    json.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    throw AuthException(tr("Couldn't read the sign-in server response", "로그인 서버 응답을 읽을 수 없어요"), r.code)
                }
            }
        } catch (e: IOException) {
            throw AuthException(tr("Couldn't reach the sign-in server because of a network error", "네트워크 오류로 로그인 서버에 연결할 수 없어요"))
        }
    }

    /** The error code only (`{"error":"x"}`, `{"error":{"code":"x"}}` or `{"code":"x"}`), letters and underscores. */
    private fun errorCode(text: String): String? = try {
        val root = json.parseToJsonElement(text).jsonObject
        val e = root["error"]
        ((e as? JsonPrimitive)?.contentOrNull ?: (e as? JsonObject)?.str("code") ?: root.str("code"))
            ?.filter { it.isLetter() || it == '_' }?.take(60)?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val DEFAULT_LIFETIME_S = 3600L
        const val CLOCK_SKEW_S = 300L
        const val REVOKE_TIMEOUT_S = 8L
        val ID_TOKEN_REFUSED get() = tr("The sign-in token didn't check out. Please try again", "로그인 토큰을 확인하지 못했어요. 다시 시도해 주세요")
    }
}

private const val INVALID_CLIENT = "invalid_client"

/** Every change of the stored tokens goes under this process-wide lock: refresh, sign-out and a finished sign-in. */
private val TOKENS_LOCK = Mutex()

/** Where a replaced sign-in's revocation runs: past the end of the sign-in that replaced it, and of its screen. */
private val REPLACED_SESSIONS = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

/** ModelAuth for the public Responses API on the user's ChatGPT plan. [now] returns epoch seconds. */
class PlanAuth(
    private val store: PlanStore,
    private val client: PlanTokenClient,
    /** Told when a request met the plan's usage limit. */
    private val usageLimitHit: suspend () -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : ModelAuth {
    override val baseUrl = PlanConfig.BASE_URL
    override val planUsage = true
    override val unauthorizedMessage = tr("Your ChatGPT sign-in expired. Please sign in again", "ChatGPT 로그인이 만료됐어요. 다시 로그인해 주세요")

    /** Noted even if the caller was just stopped, and never in place of the failure it comes with. */
    override suspend fun onUsageLimit() {
        withContext(NonCancellable) { runCatching { usageLimitHit() } }
    }

    override suspend fun headers(): Map<String, String> {
        var tokens = store.load() ?: throw ModelException(401, unauthorizedMessage)
        if (!tokens.canUsePlan) throw ModelException(403, NOT_ENABLED, retryable = false)
        if (tokens.expiresAt - now() <= REFRESH_MARGIN_S) tokens = refreshed(tokens.accessToken, force = false)
        return mapOf("Authorization" to "Bearer ${tokens.accessToken}")
    }

    override suspend fun onUnauthorized(): Boolean = retryAfter(store.load()?.accessToken ?: return false)

    override suspend fun onUnauthorized(rejected: Map<String, String>): Boolean {
        val stale = rejected["Authorization"]?.removePrefix("Bearer ") ?: return onUnauthorized()
        return retryAfter(stale)
    }

    /** A token other than [stale] is already in the store: the request can go again without spending a refresh. */
    private suspend fun retryAfter(stale: String): Boolean = try {
        refreshed(stale, force = true)
        true
    } catch (e: ModelException) {
        if (e.status == 401) false else throw e
    }

    /**
     * Ends the sign-in: revokes the renewable session, then clears the tokens whatever came of that (the sign-out page
     * has them cleared after the revocation). The registration and host id stay. Returns whether the server confirmed
     * the revocation; if not, the user should hear it. Under the tokens lock, so a refresh that is out finishes first and
     * its replacement token is the one revoked. Runs to the end even if the caller goes away. That can take a while:
     * first any refresh that is out (up to the HTTP client's call timeout), then offline about 52 s (three tries of two
     * calls at 8 s each, and 4 s of waits between them).
     */
    suspend fun signOut(): Boolean = withContext(NonCancellable) {
        TOKENS_LOCK.withLock {
            val current = store.load() ?: return@withLock true
            try {
                // A sign-in granted no refresh token has no renewable session to end.
                current.refreshToken.isEmpty() || client.revoke(current)
            } finally {
                store.save(null)
            }
        }
    }

    /** Fresh tokens under the tokens lock (the refresh token rotates, so it must never be used twice). */
    private suspend fun refreshed(stale: String, force: Boolean): PlanTokens = TOKENS_LOCK.withLock {
        val current = store.load() ?: throw ModelException(401, unauthorizedMessage)
        val fresh = current.expiresAt - now() > REFRESH_MARGIN_S
        if (current.accessToken != stale && fresh) return@withLock current
        if (!force && fresh) return@withLock current
        // Cancelling the caller doesn't stop the request and the server rotates anyway: its answer is stored regardless,
        // or the next refresh spends the old token and ends the sign-in as refresh_token_reused.
        try {
            withContext(NonCancellable) { rotate(current) }
        } catch (e: ModelException) {
            // A caller that was stopped ends as stopped, not with an error about its sign-in.
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    private suspend fun rotate(current: PlanTokens): PlanTokens = try {
        val next = client.refresh(current)
        // Changed by something outside the lock while the request was out: never bring the old sign-in back.
        val latest = store.load()
        if (latest == null || latest.refreshToken != current.refreshToken) {
            latest ?: throw ModelException(401, unauthorizedMessage)
        } else {
            store.save(next)
            next
        }
    } catch (e: AuthException) {
        if (e.code in TERMINAL) {
            if (store.load()?.refreshToken == current.refreshToken) store.save(null)
            // The server no longer knows this client: the account's next sign-in registers it again.
            if (e.code == INVALID_CLIENT) store.forgetRegistration(current.clientId)
            throw ModelException(401, unauthorizedMessage)
        }
        // Network or server trouble: keep the sign-in and let the call fail as retryable.
        throw ModelException(null, e.message ?: unauthorizedMessage)
    }

    companion object {
        private const val REFRESH_MARGIN_S = 300L

        /** Refresh answers that mean the sign-in is gone (from the SIWC errors page, which goes by these codes alone). */
        private val TERMINAL = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused", INVALID_CLIENT,
        )

        val NOT_ENABLED = tr(
            "ChatGPT plan usage isn't allowed for this sign-in. Sign in again and allow it",
            "이 로그인은 ChatGPT 요금제 사용이 허용되지 않았어요. 다시 로그인해서 허용해 주세요",
        )
    }
}

/** Browser sign-in: host id, PKCE, state and nonce -> loopback on 127.0.0.1 -> code (+ issued client id) -> tokens. */
class PlanLogin(
    private val http: OkHttpClient,
    private val store: PlanStore,
    private val tokens: PlanTokenClient = PlanTokenClient(http),
    /** The loopback port; 0 picks a free one. A saved account moves to a free one if this is taken. */
    private val port: Int = PlanConfig.LOOPBACK_PORT,
    private val replacedSessions: CoroutineScope = REPLACED_SESSIONS,
) {
    /**
     * Signs in to the saved [account], or with null registers a new one: another ChatGPT account has its own client id.
     * [consent] asks for the plan permission again. Nothing stored changes until the new ID token has been validated.
     * An issued id the server no longer knows (invalid_client) is forgotten and the sign-in registers again, once: the
     * user goes through the browser a second time, and the result must still be the [account] they chose.
     * A sign-in it replaces keeps working until then; see [endsWith] for when its session is revoked afterwards.
     */
    suspend fun login(account: PlanRegistration? = null, consent: Boolean = false, idTokenHint: String? = null, openBrowser: (String) -> Unit): PlanTokens = try {
        attempt(account, consent, idTokenHint, account?.subject, openBrowser)
    } catch (e: AuthException) {
        if (account == null || e.code != INVALID_CLIENT) throw e
        store.forgetRegistration(account.clientId)
        attempt(null, false, null, account.subject, openBrowser)
    }

    /** Binds the loopback. Only the port may vary, and only for a later sign-in: a first registration stays on [port]. */
    private fun openLoopback(returning: Boolean): LoopbackServer = try {
        LoopbackServer(port).also { it.open() }
    } catch (e: AuthException) {
        if (returning) {
            LoopbackServer(0).also { it.open() }
        } else {
            throw AuthException(tr("The first sign-in needs port $port, and another app may be using it. Close that app and try again", "첫 로그인에는 포트 $port 이(가) 필요한데 다른 앱이 쓰고 있는 것 같아요. 그 앱을 닫고 다시 시도해 주세요"))
        }
    }

    private suspend fun attempt(account: PlanRegistration?, consent: Boolean, idTokenHint: String?, expectedSubject: String?, openBrowser: (String) -> Unit): PlanTokens = coroutineScope {
        val hostId = store.hostId()
        val saved = account?.clientId
        val pkce = Pkce.generate()
        val state = Pkce.state()
        val nonce = Pkce.state()
        val server = openLoopback(returning = account != null)
        val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
        val callback = async(Dispatchers.IO) { server.awaitCallback(state) }
        try {
            openBrowser(PlanConfig.authorizeUrl(saved, hostId, redirect, pkce, state, nonce, idTokenHint, account?.email, consent))
            val params = callback.await()
            val issued = params["client_id"]?.takeIf { it.isNotBlank() && it != PlanConfig.DYNAMIC_CLIENT }
            val clientId = when {
                saved == null -> issued ?: throw AuthException(tr("The app registration didn't finish. Please try again", "앱 등록이 끝나지 않았어요. 다시 시도해 주세요"))
                issued != null && issued != saved -> throw AuthException(tr("The sign-in came back for another app registration", "다른 앱 등록으로 로그인 응답이 왔어요"))
                else -> saved
            }
            // Kept at once: a code that fails later must not cost a second registration.
            if (saved == null) store.saveRegistration(PlanRegistration(clientId))
            val t = tokens.exchange(clientId, params.getValue("code"), pkce.verifier, redirect, nonce)
            if (expectedSubject != null && t.subject != expectedSubject) {
                // A client registered just now is that other account's own: it stays, known by who it is.
                if (saved == null) store.saveRegistration(PlanRegistration(clientId, t.subject, t.email))
                throw AuthException(tr("That's a different ChatGPT account than the one you chose", "선택한 계정과 다른 ChatGPT 계정이에요"))
            }
            withContext(NonCancellable) {
                TOKENS_LOCK.withLock {
                    // Read under the lock: a refresh that was out has finished, and this is its replacement token.
                    val replaced = store.load()
                    store.saveRegistration(PlanRegistration(clientId, t.subject, t.email))
                    store.save(t)
                    // Off the store now, so nothing else can spend this token. In the background: offline it takes
                    // about 52 s (see signOut), and the new sign-in is done. If it fails, the old session only stays open.
                    if (replaced != null && endsWith(replaced, t)) {
                        replacedSessions.launch { runCatching { tokens.revoke(replaced) } }
                    }
                }
            }
            t
        } finally {
            // Cancelled first: closing the socket under a live wait would report that instead of the real failure.
            callback.cancel()
            server.close()
        }
    }

    /**
     * Whether [next] replacing [old] should end [old]'s session: another account's, or another registration's, whose
     * revocation can't reach [next]. Under the same registration it stays: ending it might end [next]'s too.
     */
    private fun endsWith(old: PlanTokens, next: PlanTokens): Boolean =
        old.refreshToken.isNotEmpty() && (old.clientId != next.clientId || (old.subject != null && old.subject != next.subject))
}

/** The signed-in account's model catalog (GET /v1/models with the plan token): slugs to show, server order kept. */
object PlanModels {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(http: OkHttpClient, accessToken: String, baseUrl: String = PlanConfig.BASE_URL): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl + "models").header("Authorization", "Bearer $accessToken").build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            parse(r.body?.string().orEmpty())
        }
    }

    /** `{"models":[{slug, visibility}]}` keeps visibility "list" (or none given); an API-key `{"data":[{id}]}` keeps every id. */
    fun parse(body: String): List<String> {
        val root = json.parseToJsonElement(body).jsonObject
        (root["models"] as? JsonArray)?.let { models ->
            return models.mapNotNull { m ->
                val o = m as? JsonObject ?: return@mapNotNull null
                val vis = o.str("visibility")
                o.str("slug")?.takeIf { vis == null || vis == "list" }
            }
        }
        return (root["data"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("id") }.orEmpty()
    }
}
