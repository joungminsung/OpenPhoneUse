package com.phoneuse.auth

import com.phoneuse.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Tokens from a standard OAuth token endpoint. [expiresAt] is epoch seconds, null when the server gave no lifetime. */
@Serializable
data class OAuthTokenSet(val accessToken: String, val refreshToken: String? = null, val expiresAt: Long? = null) {
    override fun toString() = "OAuthTokenSet(expiresAt=$expiresAt, tokens=<redacted>)"

    fun expiresSoon(now: Long, marginS: Long = 60): Boolean = expiresAt != null && expiresAt - now <= marginS
}

/** Form posts to an RFC 6749 token endpoint (authorization_code and refresh_token grants). Errors never include tokens. */
object OAuthTokenEndpoint {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun post(
        http: OkHttpClient,
        url: String,
        fields: Map<String, String?>,
        previousRefresh: String? = null,
        now: () -> Long = { System.currentTimeMillis() / 1000 },
    ): OAuthTokenSet = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
        fields.forEach { (k, v) -> if (v != null) form.add(k, v) }
        val request = Request.Builder().url(url).post(form.build()).header("Accept", "application/json").build()
        val body = try {
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val code = errorCode(text)
                    throw AuthException(tr("Couldn't get a token (HTTP ${r.code}${code?.let { ", $it" }.orEmpty()})", "토큰 발급에 실패했어요 (HTTP ${r.code}${code?.let { ", $it" }.orEmpty()})"), r.code)
                }
                try {
                    json.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    throw AuthException(tr("Couldn't read the token response", "토큰 응답을 읽을 수 없어요"), r.code)
                }
            }
        } catch (e: IOException) {
            throw AuthException(tr("Couldn't get a token because of a network error", "네트워크 오류로 토큰을 받지 못했어요"))
        }
        val access = body.str("access_token") ?: throw AuthException(tr("The token response has no access_token", "토큰 응답에 access_token이 없어요"))
        val expiresIn = (body["expires_in"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
        OAuthTokenSet(access, body.str("refresh_token") ?: previousRefresh, expiresIn?.let { now() + it })
    }

    /** RFC 7009 revocation of [token] (form `token=`); throws on a non-2xx answer or a network error. */
    suspend fun revoke(http: OkHttpClient, url: String, token: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).post(FormBody.Builder().add("token", token).build()).build()
        try {
            http.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw AuthException(tr("Couldn't revoke the token (HTTP ${r.code})", "토큰을 취소하지 못했어요 (HTTP ${r.code})"), r.code)
            }
        } catch (e: IOException) {
            throw AuthException(tr("Couldn't revoke the token because of a network error", "네트워크 오류로 토큰을 취소하지 못했어요"))
        }
    }

    /** The RFC 6749 `error` code only (letters and underscores), never the description. */
    private fun errorCode(text: String): String? = try {
        (json.parseToJsonElement(text).jsonObject["error"] as? JsonPrimitive)?.contentOrNull
            ?.filter { it.isLetter() || it == '_' }?.take(40)?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
}

/**
 * Installed-app login (RFC 8252): PKCE + state, a loopback server on a free port, the browser, then the code.
 * [authorizeUrl] builds the browser URL from (redirectUri, challenge, state); [exchange] trades (code, verifier,
 * redirectUri) for tokens.
 */
object LoopbackLogin {
    suspend fun <T> run(
        openBrowser: (String) -> Unit,
        authorizeUrl: suspend (redirectUri: String, challenge: String, state: String) -> String,
        exchange: suspend (code: String, verifier: String, redirectUri: String) -> T,
        path: String = "/",
    ): T = coroutineScope {
        val pkce = Pkce.generate()
        val state = Pkce.state()
        val server = LoopbackServer(port = 0, path = path)
        server.open()
        try {
            val redirect = "http://127.0.0.1:${server.localPort}$path"
            val code = async(Dispatchers.IO) { server.awaitCode(state) }
            openBrowser(authorizeUrl(redirect, pkce.challenge, state))
            exchange(code.await(), pkce.verifier, redirect)
        } finally {
            server.close()
        }
    }
}
