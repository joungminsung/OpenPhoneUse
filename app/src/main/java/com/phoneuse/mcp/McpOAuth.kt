package com.phoneuse.mcp

import com.phoneuse.i18n.tr
import com.phoneuse.auth.AuthException
import com.phoneuse.auth.LoopbackLogin
import com.phoneuse.auth.OAuthTokenEndpoint
import com.phoneuse.auth.OAuthTokenSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** An MCP server's OAuth client and tokens (stored encrypted with the server). */
@Serializable
data class McpOAuthState(
    val clientId: String,
    val clientSecret: String? = null,
    val tokenEndpoint: String,
    val tokens: OAuthTokenSet,
) {
    override fun toString() = "McpOAuthState(clientId=$clientId, tokens=<redacted>)"
}

/** Where to log in, from the server's metadata. */
data class McpAuthEndpoints(
    val authorization: String,
    val token: String,
    val registration: String?,
    val scopes: List<String>,
)

/**
 * MCP authorization (spec 2025-06-18): RFC 9728 protected resource metadata names the authorization server,
 * RFC 8414 metadata gives its endpoints, RFC 7591 dynamic registration makes a public client for this app, then
 * the usual PKCE login through a loopback redirect, with the RFC 8707 `resource` parameter.
 */
class McpOAuth(
    private val http: OkHttpClient,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Every URL from server metadata must be https. Only tests (MockWebServer) turn this off. */
    private val requireHttps: Boolean = true,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun login(serverUrl: String, resourceMetadata: String?, openBrowser: (String) -> Unit): McpOAuthState {
        val endpoints = discover(serverUrl, resourceMetadata)
        var client: Pair<String, String?>? = null
        val tokens = LoopbackLogin.run(
            openBrowser,
            authorizeUrl = { redirect, challenge, state ->
                val registration = endpoints.registration ?: throw AuthException(tr("This server doesn't support app registration. Connect with a token instead", "이 서버는 앱 등록을 지원하지 않아요. 토큰으로 연결해 주세요"))
                val registered = register(registration, redirect)
                client = registered
                authorizeUrl(endpoints, registered.first, redirect, challenge, state, resource(serverUrl))
            },
            exchange = { code, verifier, redirect ->
                val (id, secret) = client!!
                OAuthTokenEndpoint.post(
                    http,
                    endpoints.token,
                    mapOf(
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to redirect,
                        "client_id" to id,
                        "client_secret" to secret,
                        "code_verifier" to verifier,
                        "resource" to resource(serverUrl),
                    ),
                    now = now,
                )
            },
            path = "/callback",
        )
        val (id, secret) = client!!
        return McpOAuthState(id, secret, endpoints.token, tokens)
    }

    suspend fun refresh(serverUrl: String, state: McpOAuthState): McpOAuthState {
        val refresh = state.tokens.refreshToken ?: throw AuthException(tr("You need to sign in again", "다시 로그인해야 해요"))
        val tokens = OAuthTokenEndpoint.post(
            http,
            state.tokenEndpoint,
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to state.clientId,
                "client_secret" to state.clientSecret,
                "resource" to resource(serverUrl),
            ),
            previousRefresh = refresh,
            now = now,
        )
        return state.copy(tokens = tokens)
    }

    /**
     * Endpoints for [serverUrl]. URLs the server names (metadata, issuer, endpoints) must be https. The resource
     * metadata's `resource` must be the server URL or a prefix of it on the same origin (RFC 9728 3.3), and the
     * authorization server metadata's `issuer` must be the issuer asked for (RFC 8414 3.3); otherwise login stops.
     */
    suspend fun discover(serverUrl: String, resourceMetadata: String?): McpAuthEndpoints {
        val server = serverUrl.toHttpUrlOrNull() ?: throw AuthException(tr("The server address is invalid", "서버 주소가 올바르지 않아요"))
        val origin = origin(server)
        val path = server.encodedPath.trimEnd('/')
        val resourceCandidates = listOfNotNull(
            resourceMetadata?.let { checked(it) },
            "$origin/.well-known/oauth-protected-resource$path".takeIf { path.isNotEmpty() },
            "$origin/.well-known/oauth-protected-resource",
        )
        val prm = resourceCandidates.firstNotNullOfOrNull { getJson(it) }
        prm?.str("resource")?.let { declared ->
            val wanted = resource(serverUrl)
            val d = declared.trimEnd('/')
            val sameOrigin = d.toHttpUrlOrNull()?.let { origin(it) == origin } == true
            if (!sameOrigin || !(wanted == d || wanted.startsWith("$d/"))) throw AuthException(tr("The server's auth info doesn't match its address", "서버의 인증 정보가 주소와 맞지 않아요"))
        }
        val issuer = (prm?.get("authorization_servers") as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.let { checked(it) } ?: origin
        val scopes = (prm?.get("scopes_supported") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val iss = issuer.toHttpUrlOrNull() ?: throw AuthException(tr("The auth server address is invalid", "인증 서버 주소가 올바르지 않아요"))
        val issOrigin = origin(iss)
        val issPath = iss.encodedPath.trimEnd('/')
        val metadata = listOf(
            "$issOrigin/.well-known/oauth-authorization-server$issPath",
            "$issOrigin/.well-known/openid-configuration$issPath",
            "$issOrigin$issPath/.well-known/openid-configuration",
        ).distinct().firstNotNullOfOrNull { getJson(it) }
        if (metadata != null) {
            val declared = metadata.str("issuer")?.trimEnd('/')
            if (declared != null && declared != issuer.trimEnd('/')) throw AuthException(tr("The auth server info doesn't match", "인증 서버 정보가 맞지 않아요"))
        }
        // Without any metadata, the 2025-03-26 default paths on the issuer's origin.
        val authorization = checked(metadata?.str("authorization_endpoint") ?: "$issOrigin/authorize")
        val token = checked(metadata?.str("token_endpoint") ?: "$issOrigin/token")
        val registration = (metadata?.str("registration_endpoint") ?: if (metadata == null) "$issOrigin/register" else null)?.let { checked(it) }
        return McpAuthEndpoints(authorization, token, registration, scopes)
    }

    /** [url] if it is https (or any http(s) when [requireHttps] is off); else login stops. */
    private fun checked(url: String): String {
        val u = url.toHttpUrlOrNull() ?: throw AuthException(tr("The auth server address is invalid", "인증 서버 주소가 올바르지 않아요"))
        if (requireHttps && !u.isHttps) throw AuthException(tr("The auth server address must be https", "인증 서버 주소는 https여야 해요"))
        return url
    }

    private fun origin(u: okhttp3.HttpUrl) = "${u.scheme}://${u.host}${if (u.port != defaultPort(u.scheme)) ":${u.port}" else ""}"

    /** RFC 7591 registration of a public client (PKCE, no secret unless the server insists). */
    suspend fun register(endpoint: String, redirectUri: String): Pair<String, String?> {
        val body = buildJsonObject {
            put("client_name", "OpenPhoneUse")
            putJsonArray("redirect_uris") { add(redirectUri) }
            putJsonArray("grant_types") {
                add("authorization_code")
                add("refresh_token")
            }
            putJsonArray("response_types") { add("code") }
            put("token_endpoint_auth_method", "none")
        }
        val reply = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint).post(body.toString().toRequestBody(JSON_TYPE)).header("Accept", "application/json").build()
            try {
                http.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) throw AuthException(tr("App registration failed (HTTP ${r.code})", "앱 등록에 실패했어요 (HTTP ${r.code})"), r.code)
                    json.parseToJsonElement(r.readCapped(METADATA_MAX_BYTES)).jsonObject
                }
            } catch (e: IOException) {
                throw AuthException(tr("Couldn't register the app because of a network error", "네트워크 오류로 앱을 등록하지 못했어요"))
            } catch (e: IllegalArgumentException) {
                throw AuthException(tr("Couldn't read the app registration response", "앱 등록 응답을 읽을 수 없어요"))
            }
        }
        val id = reply.str("client_id") ?: throw AuthException(tr("The app registration response has no client_id", "앱 등록 응답에 client_id가 없어요"))
        return id to reply.str("client_secret")
    }

    fun authorizeUrl(
        endpoints: McpAuthEndpoints,
        clientId: String,
        redirectUri: String,
        challenge: String,
        state: String,
        resource: String,
    ): String = endpoints.authorization.toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("code_challenge", challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("state", state)
        .addQueryParameter("resource", resource)
        .apply { if (endpoints.scopes.isNotEmpty()) addQueryParameter("scope", endpoints.scopes.joinToString(" ")) }
        .build()
        .toString()

    private suspend fun getJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        try {
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                json.parseToJsonElement(r.readCapped(METADATA_MAX_BYTES)) as? JsonObject
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()
        private const val METADATA_MAX_BYTES = 256L * 1024

        /** The canonical resource URI (RFC 8707): the server URL without fragment or query. */
        fun resource(serverUrl: String): String =
            serverUrl.toHttpUrlOrNull()?.newBuilder()?.query(null)?.fragment(null)?.build()?.toString()?.trimEnd('/') ?: serverUrl
    }
}
