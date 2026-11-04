package com.phoneuse.mcp

import com.phoneuse.i18n.tr
import com.phoneuse.model.SseParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One tool an MCP server offers. [inputSchema] is the server's JSON Schema for the arguments. [readOnly] and
 * [destructive] are the server's `annotations.readOnlyHint` / `destructiveHint` (null when not given).
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean? = null,
    val destructive: Boolean? = null,
)

/** A tools/call result: the text parts joined (cut at [McpClient.MAX_RESULT_CHARS]); [isError] from the server. */
data class McpCallResult(val text: String, val isError: Boolean)

/**
 * A failed MCP request. [status] is the HTTP code (null for network or protocol errors). [resourceMetadata] is the
 * RFC 9728 metadata URL from a 401's WWW-Authenticate header, when the server gave one. Messages never contain tokens.
 */
class McpException(message: String, val status: Int? = null, val resourceMetadata: String? = null) : Exception(message)

/**
 * MCP client over Streamable HTTP (protocol 2025-06-18): every message is a JSON-RPC 2.0 POST to [url] with
 * `Accept: application/json, text/event-stream`; the answer is plain JSON or an SSE stream. The session id the
 * server hands out on initialize is sent back on every later request; a 404 for that session starts a new one
 * once. [token] supplies the optional bearer token (read fresh for each request, so a refreshed token is used).
 */
class McpClient(
    http: OkHttpClient,
    val url: String,
    private val token: suspend () -> String? = { null },
    private val clientVersion: String = "1.0",
    listTimeoutMs: Long = LIST_TIMEOUT_MS,
    callTimeoutMs: Long = CALL_TIMEOUT_MS,
) {
    /** initialize, tools/list and session DELETE: short; tools/call may run longer. Both share [http]'s pool. */
    private val listHttp = http.newBuilder().callTimeout(listTimeoutMs, TimeUnit.MILLISECONDS).build()
    private val callHttp = http.newBuilder().callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicLong(0)
    private val initLock = Mutex()

    @Volatile private var sessionId: String? = null

    @Volatile private var initialized = false

    suspend fun listTools(limit: Int = McpRegistry.MAX_TOOLS_PER_SERVER): List<McpTool> {
        val tools = ArrayList<McpTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = request("tools/list", params)
            (result["tools"] as? JsonArray)?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return@forEach
                val hints = o["annotations"] as? JsonObject
                tools += McpTool(
                    name,
                    o.str("description").orEmpty(),
                    o["inputSchema"] as? JsonObject ?: EMPTY_SCHEMA,
                    readOnly = (hints?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull,
                    destructive = (hints?.get("destructiveHint") as? JsonPrimitive)?.booleanOrNull,
                )
            }
            cursor = result.str("nextCursor")?.takeIf { it.isNotEmpty() }
        } while (cursor != null && tools.size < limit && ++pages < MAX_PAGES)
        return tools.take(limit)
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        val result = request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
        )
        return McpCallResult(truncate(resultText(result)), (result["isError"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    /** Forgets the session; the next request initializes again. */
    fun reset() {
        sessionId = null
        initialized = false
    }

    /** Ends the session on the server (HTTP DELETE with its id), best effort; the client may be reused afterwards. */
    suspend fun close() {
        val session = sessionId ?: return
        reset()
        try {
            val builder = Request.Builder().url(url).delete().header("Mcp-Session-Id", session).header("MCP-Protocol-Version", PROTOCOL_VERSION)
            token()?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
            listHttp.callCancellable(builder.build()) { _, _ -> }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 405 or no network: the server drops the session on its own.
        }
    }

    private suspend fun ensureInitialized() = initLock.withLock {
        if (initialized) return@withLock
        sessionId = null
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "PhoneUse")
                put("version", clientVersion)
            }
        }
        val (result, session) = post(rpc("initialize", params, ids.incrementAndGet()), expectReply = true, sessionHeader = null)
        if (result == null) throw McpException(tr("The MCP server didn't answer initialize", "MCP 서버가 initialize에 답하지 않았어요"))
        // The spec allows only visible ASCII; anything else could not be sent back as a header.
        if (session != null && (session.isEmpty() || session.any { it.code !in 0x21..0x7E })) throw McpException(tr("The MCP server's session id is invalid", "MCP 서버의 세션 id가 올바르지 않아요"))
        sessionId = session
        post(notification("notifications/initialized"), expectReply = false, sessionHeader = session)
        initialized = true
    }

    /** Sends one request; on a 404 for the current session, starts a new session and retries once. */
    private suspend fun request(method: String, params: JsonObject): JsonObject {
        ensureInitialized()
        val session = sessionId
        try {
            return post(rpc(method, params, ids.incrementAndGet()), expectReply = true, sessionHeader = session).first!!
        } catch (e: McpException) {
            if (e.status != 404 || session == null) throw e
        }
        reset()
        ensureInitialized()
        return post(rpc(method, params, ids.incrementAndGet()), expectReply = true, sessionHeader = sessionId).first!!
    }

    private fun rpc(method: String, params: JsonObject, id: Long) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }

    private fun notification(method: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
    }

    /** Returns the matching `result` (null when [expectReply] is false) and the response's session id header. */
    private suspend fun post(message: JsonObject, expectReply: Boolean, sessionHeader: String?): Pair<JsonObject?, String?> {
        val method = (message["method"] as? JsonPrimitive)?.content
        val request = try {
            val builder = Request.Builder()
                .url(url)
                .post(message.toString().toRequestBody(JSON_TYPE))
                .header("Accept", "application/json, text/event-stream")
            sessionHeader?.let { builder.header("Mcp-Session-Id", it) }
            if (method != "initialize") builder.header("MCP-Protocol-Version", PROTOCOL_VERSION)
            token()?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
            builder.build()
        } catch (e: IllegalArgumentException) {
            // A malformed address or a header value OkHttp refuses; its message could quote the value, so it is not kept.
            throw McpException(tr("The MCP server address or credentials are invalid", "MCP 서버 주소나 인증 값이 올바르지 않아요"))
        }
        val client = if (method == "tools/call") callHttp else listHttp
        return try {
            client.callCancellable(request) { r, active ->
                if (!r.isSuccessful) throw httpError(r)
                val session = r.header("Mcp-Session-Id") ?: sessionHeader
                if (!expectReply) return@callCancellable null to session
                val id = (message["id"] as? JsonPrimitive)?.longOrNull
                val reply = if (r.header("Content-Type").orEmpty().startsWith("text/event-stream")) readSse(r, id, active) else readJson(r, id)
                unwrap(reply) to session
            }
        } catch (e: TooLargeException) {
            throw McpException(tr("The MCP server response is too large", "MCP 서버 응답이 너무 커요"))
        } catch (e: InterruptedIOException) {
            throw McpException(tr("The MCP server didn't answer in time", "MCP 서버가 제때 답하지 않았어요"))
        } catch (e: IOException) {
            throw McpException(tr("Couldn't connect to the MCP server", "MCP 서버에 연결할 수 없어요"))
        }
    }

    private fun httpError(r: Response): McpException {
        val metadata = r.header("WWW-Authenticate")?.let { RESOURCE_METADATA.find(it)?.groupValues?.get(1) }
        val message = when (r.code) {
            401 -> tr("The MCP server needs authentication", "MCP 서버 인증이 필요해요")
            403 -> tr("The MCP server denied access", "MCP 서버가 접근을 거부했어요")
            404 -> tr("Couldn't find the MCP server address or session", "MCP 서버 주소나 세션을 찾을 수 없어요")
            else -> tr("MCP server error (HTTP ${r.code})", "MCP 서버 오류 (HTTP ${r.code})")
        }
        return McpException(message, r.code, metadata)
    }

    private fun readJson(r: Response, id: Long?): JsonObject {
        val text = r.readCapped(MAX_BODY_BYTES)
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw McpException(tr("Couldn't read the MCP server response", "MCP 서버 응답을 읽을 수 없어요"))
        }
        return when (element) {
            is JsonObject -> element.takeIf { isReplyTo(it, id) }
            is JsonArray -> element.filterIsInstance<JsonObject>().firstOrNull { isReplyTo(it, id) }
            else -> null
        } ?: throw McpException(tr("Couldn't read the MCP server response", "MCP 서버 응답을 읽을 수 없어요"))
    }

    /** Reads events until the reply to [id]; notifications and server requests on the stream are skipped. */
    private fun readSse(r: Response, id: Long?, active: () -> Boolean): JsonObject {
        val source = r.body?.source() ?: throw McpException(tr("The MCP server response was empty", "MCP 서버 응답이 비었어요"))
        val parser = SseParser()
        var total = 0L
        while (true) {
            if (!active()) throw CancellationException("cancelled")
            val line = source.readLineCapped(MAX_LINE_BYTES)
            total += (line?.length ?: 0) + 1
            if (total > MAX_BODY_BYTES) throw TooLargeException(tr("The response is too large", "응답이 너무 커요"))
            val event = if (line == null) parser.feed("") else parser.feed(line)
            if (event != null) {
                val element = try {
                    json.parseToJsonElement(event.data)
                } catch (e: Exception) {
                    null
                }
                val candidates = when (element) {
                    is JsonObject -> listOf(element)
                    is JsonArray -> element.filterIsInstance<JsonObject>()
                    else -> emptyList()
                }
                candidates.firstOrNull { isReplyTo(it, id) }?.let { return it }
            }
            if (line == null) throw McpException(tr("The MCP server disconnected before answering", "MCP 서버가 답하기 전에 연결을 끊었어요"))
        }
    }

    private fun isReplyTo(o: JsonObject, id: Long?): Boolean {
        if ("result" !in o && "error" !in o) return false
        val got = (o["id"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
        // A request with an id only takes the reply carrying that same id.
        return id == null || got == id
    }

    private fun unwrap(reply: JsonObject): JsonObject {
        (reply["error"] as? JsonObject)?.let { err ->
            val msg = err.str("message")?.take(300) ?: tr("Unknown error", "알 수 없는 오류")
            throw McpException(tr("MCP error: $msg", "MCP 오류: $msg"))
        }
        return reply["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun resultText(result: JsonObject): String {
        val parts = (result["content"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            when (o.str("type")) {
                "text" -> o.str("text")
                "image" -> "[이미지]"
                "audio" -> "[오디오]"
                "resource" -> (o["resource"] as? JsonObject)?.let { it.str("text") ?: "[리소스 ${it.str("uri").orEmpty()}]" }
                "resource_link" -> "[링크 ${o.str("name").orEmpty()} ${o.str("uri").orEmpty()}]".replace("  ", " ")
                else -> null
            }
        }
        if (parts.isNotEmpty()) return parts.joinToString("\n")
        return (result["structuredContent"] as? JsonElement)?.toString() ?: ""
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val MAX_RESULT_CHARS = 20_000
        const val LIST_TIMEOUT_MS = 10_000L
        const val CALL_TIMEOUT_MS = 120_000L

        /** Largest reply body (or SSE stream) read from a server; a bigger one is an error, never buffered. */
        const val MAX_BODY_BYTES = 2L * 1024 * 1024
        private const val MAX_LINE_BYTES = 1024L * 1024
        private const val MAX_PAGES = 5
        private val JSON_TYPE = "application/json".toMediaType()
        private val EMPTY_SCHEMA = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap())))
        private val RESOURCE_METADATA = Regex("""resource_metadata="([^"]+)"""")

        /** [text] cut to [MAX_RESULT_CHARS] characters (never splitting a surrogate pair), with a note when cut. */
        fun truncate(text: String, max: Int = MAX_RESULT_CHARS): String {
            if (text.length <= max) return text
            var end = max
            if (Character.isHighSurrogate(text[end - 1])) end--
            return text.substring(0, end) + "\n[결과가 길어 ${max}자에서 잘랐어요]"
        }
    }
}
