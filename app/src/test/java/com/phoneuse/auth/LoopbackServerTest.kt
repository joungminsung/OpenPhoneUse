package com.phoneuse.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

class LoopbackServerTest {
    private val http = OkHttpClient()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Retries until the server is listening. */
    private suspend fun get(port: Int, pathAndQuery: String): Response = withTimeout(10_000) {
        var last: IOException? = null
        repeat(100) {
            try {
                return@withTimeout http.newCall(
                    Request.Builder().url("http://127.0.0.1:$port$pathAndQuery").build()
                ).execute()
            } catch (e: IOException) {
                last = e
                delay(50)
            }
        }
        throw IllegalStateException("server never listened", last)
    }

    @Test fun returnsCodeOnCallback() = runBlocking {
        val port = freePort()
        val code = async(Dispatchers.IO) { LoopbackServer(port, 5_000).awaitCode("s") }
        get(port, "/auth/callback?code=c1&state=s").use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.string().contains("로그인 완료. 앱으로 돌아가세요"))
        }
        assertEquals("c1", code.await())
    }

    @Test fun ignoresOtherPathsUntilCallback() = runBlocking {
        val port = freePort()
        val code = async(Dispatchers.IO) { LoopbackServer(port, 5_000).awaitCode("s") }
        get(port, "/favicon.ico").use { assertEquals(404, it.code) }
        get(port, "/auth/callback?code=c2&state=s").close()
        assertEquals("c2", code.await())
    }

    @Test fun stateMismatchIsIgnored() = runBlocking {
        val port = freePort()
        val code = async(Dispatchers.IO) { LoopbackServer(port, 5_000).awaitCode("s") }
        get(port, "/auth/callback?code=c1&state=other").use { assertEquals(404, it.code) }
        get(port, "/auth/callback?error=access_denied&state=other").use { assertEquals(404, it.code) }
        get(port, "/auth/callback?error=access_denied").use { assertEquals(404, it.code) }
        get(port, "/auth/callback?code=c3&state=s").close()
        assertEquals("c3", code.await())
    }

    @Test fun errorParamFails() = runBlocking {
        val port = freePort()
        val code = async(Dispatchers.IO) { runCatching { LoopbackServer(port, 5_000).awaitCode("s") } }
        get(port, "/auth/callback?error=access_denied&state=s").close()
        val e = code.await().exceptionOrNull()
        assertTrue(e is AuthException)
        assertTrue(e!!.message!!.contains("access_denied"))
        assertEquals("access_denied", (e as AuthException).code)
    }

    @Test fun errorParamIsSanitizedInMessage() = runBlocking {
        val port = freePort()
        val code = async(Dispatchers.IO) { runCatching { LoopbackServer(port, 5_000).awaitCode("s") } }
        val evil = "bad<script>" + "x".repeat(100)
        get(port, "/auth/callback?error=$evil&state=s").close()
        val msg = code.await().exceptionOrNull()!!.message!!
        val shown = msg.substringAfter('(').substringBefore(')')
        assertTrue(shown, shown.matches(Regex("[a-z_]{0,40}")))
        assertTrue(!msg.contains("<"))
    }

    @Test fun loopbackTimesOut() = runBlocking {
        try {
            LoopbackServer(freePort(), 200).awaitCode("s")
            fail("expected timeout")
        } catch (e: AuthException) {
            assertEquals("로그인 시간이 지났어요", e.message)
        }
    }

    @Test fun loopbackPortBusyFails() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { busy ->
            try {
                LoopbackServer(busy.localPort, 2_000).awaitCode("s")
                fail("expected port busy")
            } catch (e: AuthException) {
                assertEquals("로그인 서버를 열 수 없어요", e.message)
            }
        }
    }

    @Test fun cancellationClosesSocket() = runBlocking {
        val port = freePort()
        val job = async(Dispatchers.IO) { LoopbackServer(port, 60_000).awaitCode("s") }
        get(port, "/favicon.ico").close() // ensure it is listening
        job.cancel()
        runCatching { job.await() }
        // The port is free again.
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun closingUnderACancelledWaitStaysACancellation() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        repeat(5) {
            val server = LoopbackServer(0).also { it.open() }
            val wait = scope.async { server.awaitCallback("state") }
            delay(50) // into accept()
            wait.cancel()
            server.close()
            wait.join()
            assertTrue(wait.getCompletionExceptionOrNull() is CancellationException)
        }
        scope.cancel()
    }
}
