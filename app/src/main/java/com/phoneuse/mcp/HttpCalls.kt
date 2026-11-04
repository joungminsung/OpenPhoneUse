package com.phoneuse.mcp

import com.phoneuse.i18n.tr
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException

/** A response larger than the caller's cap. */
class TooLargeException(message: String) : IOException(message)

/**
 * Runs [request] on the IO dispatcher and hands the open response to [block]. Cancelling the calling coroutine
 * (e.g. a withTimeout) cancels the OkHttp call, which also aborts a blocking body read. [block] gets an `active`
 * check for loops over a stream.
 */
suspend fun <T> OkHttpClient.callCancellable(request: Request, block: (Response, active: () -> Boolean) -> T): T = coroutineScope {
    val call = newCall(request)
    // Cancelled together with this scope: then it cancels the call, unblocking execute() or a body read.
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) {
            val scope = this
            call.execute().use { block(it) { scope.isActive } }
        }
    } catch (e: Exception) {
        // The call failed because this coroutine was cancelled (a timeout): report the cancellation, not an IO error.
        ensureActive()
        throw e
    } finally {
        watcher.cancel()
    }
}

/** The body as UTF-8, failing with [TooLargeException] past [maxBytes] (never buffering more than that). */
fun Response.readCapped(maxBytes: Long): String {
    val source = body?.source() ?: return ""
    if (source.request(maxBytes + 1)) throw TooLargeException(tr("The response is too large", "응답이 너무 커요"))
    return source.buffer.readUtf8()
}

/** The first [maxBytes] of the body as UTF-8 (a cut multi-byte character at the end is dropped); the rest is not read. */
fun Response.readPrefix(maxBytes: Long): String {
    val source = body?.source() ?: return ""
    source.request(maxBytes)
    val n = minOf(maxBytes, source.buffer.size)
    val bytes = source.buffer.readByteArray(n)
    var end = bytes.size
    // Step back over an incomplete UTF-8 sequence at the cut.
    if (n == maxBytes) {
        var i = end - 1
        while (i >= 0 && end - i <= 4 && (bytes[i].toInt() and 0xC0) == 0x80) i--
        if (i >= 0) {
            val lead = bytes[i].toInt() and 0xFF
            val need = when {
                lead >= 0xF0 -> 4
                lead >= 0xE0 -> 3
                lead >= 0xC0 -> 2
                else -> 1
            }
            if (end - i < need) end = i
        }
    }
    return String(bytes, 0, end, Charsets.UTF_8)
}

/**
 * Reads one line (without its terminator, `\r` stripped) of at most [maxBytes]; null at the end of the stream.
 * A longer line fails with [TooLargeException].
 */
fun BufferedSource.readLineCapped(maxBytes: Long): String? {
    val idx = indexOf('\n'.code.toByte(), 0, maxBytes + 1)
    val line = if (idx == -1L) {
        if (request(maxBytes + 1)) throw TooLargeException(tr("The response line is too long", "응답 줄이 너무 길어요"))
        if (exhausted()) return null
        readUtf8()
    } else {
        readUtf8(idx).also { skip(1) }
    }
    return line.removeSuffix("\r")
}
