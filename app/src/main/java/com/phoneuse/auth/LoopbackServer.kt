package com.phoneuse.auth

import com.phoneuse.i18n.Lang
import com.phoneuse.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One-shot HTTP server on 127.0.0.1 that waits for the OAuth redirect to [path] (default `/auth/callback`).
 * Other paths (e.g. favicon) get a 404 and are ignored. [port] 0 picks a free port; read it from [localPort]
 * after [open].
 */
class LoopbackServer(
    private val port: Int,
    private val timeoutMs: Long = 300_000,
    private val path: String = "/auth/callback",
) {
    @Volatile private var socket: ServerSocket? = null

    /** The bound port once [open] ran (the chosen one for port 0), else the requested port. */
    val localPort: Int
        get() = socket?.localPort ?: port

    /** Binds the port now so the browser can be opened afterwards. Called by [awaitCode] if needed. */
    fun open() {
        if (socket != null) return
        socket = try {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) {
            throw AuthException(tr("Couldn't open the sign-in server", "로그인 서버를 열 수 없어요"))
        }
    }

    fun close() {
        socket?.close()
        socket = null
    }

    /** Returns the authorization code. The socket is always closed afterwards (also on cancel). */
    suspend fun awaitCode(expectedState: String): String = awaitCallback(expectedState).getValue("code")

    /**
     * Returns the valid callback's query parameters (`code` always present; e.g. an issued `client_id`). The socket
     * is always closed afterwards (also on cancel).
     */
    suspend fun awaitCallback(expectedState: String): Map<String, String> {
        open()
        val server = socket!!
        try {
            return withContext(Dispatchers.IO) { acceptLoop(server, expectedState) }
        } finally {
            close()
        }
    }

    private suspend fun acceptLoop(server: ServerSocket, expectedState: String): Map<String, String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw AuthException(tr("Sign-in timed out", "로그인 시간이 지났어요"))
            // Short accept slices so cancellation is noticed promptly.
            server.soTimeout = remaining.coerceAtMost(POLL_MS).toInt().coerceAtLeast(1)
            val client = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive() // A failure here would outrank the cancel and hide the real error.
                throw AuthException(tr("The sign-in server ran into a problem", "로그인 서버에 문제가 생겼어요"))
            }
            client.use { handle(it, expectedState)?.let { params -> return params } }
        }
    }

    /** Returns the parameters of a valid callback, null for unrelated requests; throws on a bad callback. */
    private fun handle(client: Socket, expectedState: String): Map<String, String>? {
        client.soTimeout = READ_TIMEOUT_MS
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = try { input.readLine() } catch (e: IOException) { null } ?: return null
        // Drain headers so the browser sees a clean response.
        try {
            while (true) { val l = input.readLine(); if (l.isNullOrEmpty()) break }
        } catch (e: IOException) { /* ignore */ }

        val target = requestLine.split(' ').getOrNull(1) ?: return null
        val url = "http://127.0.0.1$target".toHttpUrlOrNull()
        if (url == null || url.encodedPath != path) {
            respond(client, 404, "Not found")
            return null
        }
        // Any local app or web page can hit this port: a callback without our state (error ones included) is
        // ignored, so it can neither end nor answer the user's sign-in.
        if (url.queryParameter("state") != expectedState) {
            respond(client, 404, "Not found")
            return null
        }
        val error = url.queryParameter("error")
        if (error != null) {
            respond(client, 400, page(tr("Sign-in failed. Please try again in the app", "로그인에 실패했어요. 앱에서 다시 시도해 주세요")))
            val reason = error.lowercase().filter { it in 'a'..'z' || it == '_' }.take(40)
            throw AuthException(tr("Sign-in was cancelled or failed ($reason)", "로그인이 취소되었거나 실패했어요 ($reason)"), code = reason)
        }
        val code = url.queryParameter("code")
        if (code.isNullOrEmpty()) {
            respond(client, 400, page(tr("No sign-in code received. Please try again in the app", "로그인 코드가 없어요. 앱에서 다시 시도해 주세요")))
            throw AuthException(tr("Didn't receive a sign-in code", "로그인 코드를 받지 못했어요"))
        }
        respond(client, 200, page(tr("Signed in. You can go back to the app", "로그인 완료. 앱으로 돌아가세요")))
        return url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }
    }

    private fun page(message: String) =
        "<!doctype html><html lang=\"${Lang.tag}\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>OpenPhoneUse</title></head>" +
            "<body style=\"font-family:sans-serif;text-align:center;padding:48px 16px\"><h2>$message</h2></body></html>"

    private fun respond(client: Socket, status: Int, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 404 -> "Not Found"; else -> "Bad Request" }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        try {
            client.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(body)
                flush()
            }
        } catch (e: IOException) { /* browser went away; nothing to do */ }
    }

    private companion object {
        const val POLL_MS = 250L
        const val READ_TIMEOUT_MS = 5_000
    }
}
