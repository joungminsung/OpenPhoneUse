package com.phoneuse.connectors

import com.phoneuse.i18n.tr
import com.phoneuse.auth.AuthException
import com.phoneuse.auth.LoopbackLogin
import com.phoneuse.auth.OAuthTokenEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Google's installed-app OAuth endpoints and the connector's scopes. */
object GoogleOAuth {
    const val AUTHORIZE = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN = "https://oauth2.googleapis.com/token"
    const val REVOKE = "https://oauth2.googleapis.com/revoke"
    val SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.compose",
        "https://www.googleapis.com/auth/drive.readonly",
    )

    /** Shown on the connectors screen next to the client ID field. */
    val GUIDE: String get() = tr(
        "1. In the Google Cloud console (console.cloud.google.com), create a project and turn on the Gmail API and the Google Drive API.\n" +
            "2. Set up the OAuth consent screen as External in testing mode and add your Google account as a test user.\n" +
            "3. Under Credentials › Create OAuth client ID, choose the type Desktop app.\n" +
            "4. Paste the client ID and client secret below and tap [Connect Google].\n" +
            "The values are stored encrypted on this phone only. Sending mail always asks you first, even when connected.",
        "1. Google Cloud 콘솔(console.cloud.google.com)에서 프로젝트를 만들고 Gmail API와 Google Drive API를 켜요.\n" +
            "2. ‘OAuth 동의 화면’을 외부·테스트 모드로 만들고 내 Google 계정을 테스트 사용자로 넣어요.\n" +
            "3. ‘사용자 인증 정보 › OAuth 클라이언트 ID 만들기’에서 유형을 ‘데스크톱 앱’으로 골라요.\n" +
            "4. 만들어진 클라이언트 ID와 클라이언트 보안 비밀번호를 아래에 붙여 넣고 [Google로 연결]을 눌러요.\n" +
            "값은 이 폰에만 암호화해서 저장해요. 메일 보내기는 연결해도 항상 확인받아요.",
    )

    fun authorizeUrl(clientId: String, redirectUri: String, challenge: String, state: String): String =
        AUTHORIZE.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", SCOPES.joinToString(" "))
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent")
            .build()
            .toString()

    /** A desktop client ID looks like "<numbers>-<id>.apps.googleusercontent.com". */
    fun looksLikeClientId(id: String): Boolean = id.trim().endsWith(".apps.googleusercontent.com") && !id.trim().contains(' ')
}

/**
 * The Google connector's sign-in: PKCE + loopback redirect to 127.0.0.1 on a free port, with the user's own
 * desktop client. Tokens live encrypted in [store]; [accessToken] refreshes them when they are about to expire.
 */
class GoogleAuth(
    private val http: OkHttpClient,
    private val store: ConnectorStore,
    private val tokenUrl: String = GoogleOAuth.TOKEN,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val revokeUrl: String = GoogleOAuth.REVOKE,
) {
    private val refreshLock = Mutex()

    /**
     * Signs in through the browser with the given client, then saves client, tokens and the account's address.
     * Nothing is saved before the token exchange succeeds, so a cancelled or failed reconnect keeps the old connection.
     */
    suspend fun connect(clientId: String, clientSecret: String?, openBrowser: (String) -> Unit, profileEmail: suspend (String) -> String?) {
        val id = clientId.trim()
        store.load()
        // A reconnect with the secret field left empty keeps the saved secret of the same client.
        val secret = clientSecret?.trim()?.takeIf { it.isNotEmpty() } ?: store.google.value?.takeIf { it.clientId == id }?.clientSecret
        if (!GoogleOAuth.looksLikeClientId(id)) throw AuthException(tr("The client ID must end with '…apps.googleusercontent.com'", "클라이언트 ID는 ‘…apps.googleusercontent.com’으로 끝나야 해요"))
        val tokens = LoopbackLogin.run(
            openBrowser,
            authorizeUrl = { redirect, challenge, state -> GoogleOAuth.authorizeUrl(id, redirect, challenge, state) },
            exchange = { code, verifier, redirect ->
                OAuthTokenEndpoint.post(
                    http,
                    tokenUrl,
                    mapOf(
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "client_id" to id,
                        "client_secret" to secret,
                        "redirect_uri" to redirect,
                        "code_verifier" to verifier,
                    ),
                    now = now,
                )
            },
        )
        val email = try {
            profileEmail(tokens.accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        store.updateGoogle { GoogleConfig(id, secret, tokens, email) }
    }

    /**
     * Forgets the tokens (the client ID stays for the next connect) and, with [revoke], asks Google to revoke the
     * grant. Revoking is best effort: the tokens are gone from the phone either way.
     */
    suspend fun disconnect(revoke: Boolean = true) {
        store.load()
        val old = store.google.value?.tokens
        store.updateGoogle { it?.copy(tokens = null, email = null) }
        if (!revoke || old == null) return
        try {
            OAuthTokenEndpoint.revoke(http, revokeUrl, old.refreshToken ?: old.accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or already revoked: nothing more to do.
        }
    }

    /** A valid access token, refreshed when needed; throws when not connected or the refresh was rejected. */
    suspend fun accessToken(): String {
        store.load()
        val config = store.google.value ?: throw AuthException(tr("Google isn't connected", "Google이 연결되지 않았어요"))
        val tokens = config.tokens ?: throw AuthException(tr("Google isn't connected", "Google이 연결되지 않았어요"))
        if (!tokens.expiresSoon(now())) return tokens.accessToken
        return refreshLock.withLock {
            val current = store.google.value?.tokens ?: throw AuthException(tr("Google isn't connected", "Google이 연결되지 않았어요"))
            if (!current.expiresSoon(now())) return@withLock current.accessToken
            val refresh = current.refreshToken ?: throw AuthException(tr("The Google connection expired. Reconnect it under Connectors", "Google 연결이 만료됐어요. 커넥터에서 다시 연결해 주세요"))
            val fresh = try {
                OAuthTokenEndpoint.post(
                    http,
                    tokenUrl,
                    mapOf(
                        "grant_type" to "refresh_token",
                        "refresh_token" to refresh,
                        "client_id" to config.clientId,
                        "client_secret" to config.clientSecret,
                    ),
                    previousRefresh = refresh,
                    now = now,
                )
            } catch (e: AuthException) {
                if (e.status == 400 || e.status == 401) {
                    disconnect(revoke = false)
                    throw AuthException(tr("The Google connection expired. Reconnect it under Connectors", "Google 연결이 만료됐어요. 커넥터에서 다시 연결해 주세요"))
                }
                throw e
            }
            store.updateGoogle { it?.copy(tokens = fresh) }
            fresh.accessToken
        }
    }

}
