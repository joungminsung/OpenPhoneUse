package com.phoneuse.model

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.phoneuse.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAiResponsesClient(
    private val auth: ModelAuth,
    private val options: ModelOptions,
    private val http: OkHttpClient,
) : ModelClient {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The same client (and connection pool) without a total call timeout: a long research turn may stream for
     * minutes, so only the read timeout (idle time between bytes, reset by every SSE event) limits a call.
     */
    private val streamHttp: OkHttpClient = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()

    override suspend fun next(
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
    ): ModelTurn = next(instructions, input, tools, onTextDelta) {}

    override suspend fun next(
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
        onSearch: (WebSearchCall) -> Unit,
    ): ModelTurn {
        var verbosity = options.verbosity?.takeIf { verbosityKey() !in noVerbosity }
        var carry = verbosityKey() !in noReasoningCarry
        var droppedCarry = false
        while (true) {
            try {
                val turn = send(body(instructions, input, tools, verbosity, carry), onTextDelta, onSearch)
                // Remembered only once the request without it went through: a 400 that merely mentioned it (and
                // fails anyway) must not turn reasoning carry off for every later run.
                if (droppedCarry) noReasoningCarry += verbosityKey()
                return turn
            } catch (e: ModelException) {
                // Some backends or models reject text.verbosity or encrypted reasoning: drop that one, for the rest
                // of the session, and try again.
                if (e.status != 400) throw e
                // The ChatGPT plan route names a field it does not take (error.param): leave it out from now on.
                // Rejected tools are sent again grouped in a namespace, the form that route documents.
                if (e.code == UNSUPPORTED_CAPABILITY) {
                    val field = droppable(errorField(e.message.orEmpty(), "param"))
                    if (field != null && rejected.add(verbosityKey() + "|" + field)) {
                        if (field == "text") verbosity = null
                        if (field == "include") carry = false
                        continue
                    }
                    throw ModelException(400, PLAN_UNSUPPORTED, code = e.code, retryable = false)
                }
                when {
                    verbosity != null && rejectsVerbosity(e.message) -> {
                        noVerbosity += verbosityKey()
                        verbosity = null
                    }
                    carry && rejectsReasoning(e.message) -> {
                        carry = false
                        droppedCarry = true
                    }
                    else -> throw e
                }
            }
        }
    }

    /**
     * The request. [instructions] and [tools] must stay byte-identical across the steps of a conversation, and
     * prompt_cache_key names the conversation, so each step reuses the cached prefix. Within a run they are fixed;
     * across messages they change only with settings (memory, auto-remember, web search) or a connector that
     * failed to load, which costs that one message its cache hit.
     */
    private fun body(
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        verbosity: String?,
        carryReasoning: Boolean,
    ): String =
        buildJsonObject {
            put("model", options.model)
            put("instructions", instructions)
            put("input", buildJsonArray {
                // Reasoning from another provider (the user switched mid-conversation) means nothing here.
                input.forEach { if (it !is InputItem.Reasoning || (carryReasoning && it.raw.isOpenAiReasoning())) add(it.toJson()) }
            })
            if (carryReasoning && !off("include")) put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
            put("tools", buildJsonArray {
                if (off("tools")) {
                    if (tools.isNotEmpty()) add(buildJsonObject {
                        put("type", "namespace")
                        put("name", NAMESPACE)
                        put("description", "The assistant's tools.")
                        put("tools", buildJsonArray { tools.forEach { add(it.toJson()) } })
                    })
                } else {
                    tools.forEach { add(it.toJson()) }
                }
                if (options.webSearch) add(WebSearch.TOOL)
            })
            put("store", false)
            put("stream", true)
            // Lets update_plan and data lookups go along with an action; the agent loop runs at most one phone action per turn.
            if (!off("parallel_tool_calls")) put("parallel_tool_calls", true)
            if (!off("reasoning")) put("reasoning", buildJsonObject { put("effort", options.effort) })
            if (!off("prompt_cache_key")) options.promptCacheKey?.let { put("prompt_cache_key", it) }
            if (!off("text")) verbosity?.let { v -> put("text", buildJsonObject { put("verbosity", v) }) }
            if (options.fast && !off("service_tier")) put("service_tier", "priority")
        }.toString()

    private fun verbosityKey() = auth.baseUrl + "|" + options.model

    /** [field] was rejected by this backend and model (see [rejected]). */
    private fun off(field: String) = (verbosityKey() + "|" + field) in rejected

    /** The request field an unsupported-capability error's param names, when it is one this client can leave out. */
    private fun droppable(param: String?): String? {
        val p = param?.trim()?.lowercase() ?: return null
        if ("service" in p && "tier" in p) return "service_tier"
        return p.substringBefore('.').substringBefore('[').takeIf { it in DROPPABLE }
    }

    /** Only an error that names verbosity: other 400s that mention some "text" field are real errors. */
    private fun rejectsVerbosity(message: String?): Boolean = message?.contains("verbosity", ignoreCase = true) == true

    /**
     * Only an error about the include parameter or encrypted reasoning itself: a 400 about reasoning.effort or an
     * input item merely mentions "reasoning" and is a real error.
     */
    private fun rejectsReasoning(message: String?): Boolean {
        if (message == null) return false
        if (message.contains("reasoning.encrypted_content", ignoreCase = true)) return true
        return errorField(message, "param") == "include"
    }

    /** [field] of the error object in a JSON error body (`{"error":{...}}` or the root), or null. */
    private fun errorField(body: String, field: String): String? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val err = root["error"] as? JsonObject ?: root
        (err[field] as? JsonPrimitive)?.contentOrNull
    } catch (e: Exception) {
        null
    }

    private suspend fun send(
        body: String,
        onTextDelta: (String) -> Unit,
        onSearch: (WebSearchCall) -> Unit,
    ): ModelTurn {
        var refreshed = false
        while (true) {
            val builder = Request.Builder()
                .url(auth.baseUrl + "responses")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
            val sent = auth.headers()
            sent.forEach { (k, v) -> builder.header(k, v) }
            try {
                return withContext(Dispatchers.IO) { cancellable(builder.build(), Listener(onTextDelta, onSearch)) }
            } catch (e: ModelException) {
                if (auth.planUsage) planFailure(e)?.let {
                    if (it.code == ModelException.PLAN_USAGE_LIMIT) auth.onUsageLimit()
                    throw it
                }
                if (e.status == 403) throw ModelException(403, auth.unauthorizedMessage)
                if (e.status != 401) throw e
                if (refreshed || !auth.onUnauthorized(sent)) throw ModelException(401, auth.unauthorizedMessage)
                refreshed = true
            }
        }
    }

    /** Per-request callbacks; reports each `web_search_call` item once. Also notes when output first came. */
    private class Listener(val onTextDelta: (String) -> Unit, private val onSearch: (WebSearchCall) -> Unit) {
        private val reported = HashSet<String>()
        /** Items from `output_item.done`, in order: a stream may complete with an empty `output`. */
        val doneItems = ArrayList<JsonElement>()
        val startedNs = System.nanoTime()
        var firstByteNs = 0L
        var firstOutputNs = 0L

        fun output() {
            if (firstOutputNs == 0L) firstOutputNs = System.nanoTime()
        }

        fun ms(ns: Long): Long? = if (ns == 0L) null else (ns - startedNs) / 1_000_000

        /** [done] is true for `output_item.done`: an item that still has no queries is reported then. */
        fun search(call: WebSearchCall, done: Boolean) {
            val id = call.id
            // Without an id the added and done events cannot be matched, so only done reports it.
            if (id == null && !done) return
            if (call.queries.isEmpty() && !done) return
            if (id != null && !reported.add(id)) return
            onSearch(call)
        }
    }

    private suspend fun cancellable(request: Request, listener: Listener): ModelTurn = coroutineScope {
        val call = streamHttp.newCall(request)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            stream(call, listener)
        } finally {
            watcher.cancel()
        }
    }

    /** Blocking; run on an IO dispatcher. Cancelling the coroutine cancels the HTTP call. */
    private suspend fun stream(call: Call, listener: Listener): ModelTurn {
        try {
            call.execute().use { response ->
                listener.firstByteNs = System.nanoTime()
                if (!response.isSuccessful) {
                    val text = try { response.body?.string().orEmpty() } catch (e: IOException) { "" }
                    val code = errorField(text, "type")?.takeIf { it in KNOWN_CODES } ?: errorField(text, "code") ?: errorField(text, "type")
                    throw ModelException(
                        response.code, text.take(ERROR_BODY_LIMIT),
                        retryAfterMs = retryAfterMs { response.header(it) }, code = code,
                    )
                }
                val source = response.body?.source() ?: throw ModelException(null, "stream ended early")
                val parser = SseParser()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    val event = parser.feed(line) ?: continue
                    handleEvent(event, listener)?.let { return timed(it, listener) }
                }
                parser.feed("")?.let { handleEvent(it, listener)?.let { turn -> return timed(turn, listener) } }
                throw ModelException(null, "stream ended early", afterOutput = listener.firstOutputNs != 0L)
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // a cancelled call surfaces as IOException
            throw ModelException(null, "Network error: ${e.message}", afterOutput = listener.firstOutputNs != 0L)
        }
    }

    private fun timed(turn: ModelTurn, listener: Listener) =
        turn.copy(firstByteMs = listener.ms(listener.firstByteNs), firstOutputMs = listener.ms(listener.firstOutputNs))

    /** Returns the final turn on `response.completed`, throws on failure events, else null. */
    private fun handleEvent(event: SseEvent, listener: Listener): ModelTurn? {
        val type = event.event ?: runCatching {
            json.parseToJsonElement(event.data).jsonObject["type"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        when (type) {
            "response.output_text.delta" -> {
                val delta = runCatching {
                    json.parseToJsonElement(event.data).jsonObject["delta"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                listener.output()
                if (!delta.isNullOrEmpty()) listener.onTextDelta(delta)
            }
            "response.output_item.added", "response.output_item.done" -> {
                listener.output()
                val item = runCatching {
                    json.parseToJsonElement(event.data).jsonObject["item"] as? JsonObject
                }.getOrNull()
                item?.let(WebSearch::parseCall)?.let { listener.search(it, done = type == "response.output_item.done") }
                if (item != null && type == "response.output_item.done") listener.doneItems += item
            }
            "response.completed" -> return parse(event.data, listener.doneItems)
            "response.incomplete" -> return incomplete(event.data, listener.doneItems)
            "response.failed", "error" -> {
                val err = failureError(event.data)
                val message = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: event.data.take(ERROR_BODY_LIMIT)
                val code = (err?.get("code") as? JsonPrimitive)?.contentOrNull ?: (err?.get("type") as? JsonPrimitive)?.contentOrNull
                throw ModelException(null, message, code = code, afterOutput = listener.firstOutputNs != 0L)
            }
        }
        return null
    }

    private fun failureError(data: String): JsonObject? = try {
        val root = json.parseToJsonElement(data).jsonObject
        (root["response"] as? JsonObject)?.get("error") as? JsonObject ?: root["error"] as? JsonObject ?: root
    } catch (e: Exception) {
        null
    }

    /**
     * A response that stopped early (`response.incomplete`). Cut at max_output_tokens, what it finished is returned;
     * any other reason (a content filter, ...) or nothing usable is a final error: the same request would stop the
     * same way, so it is not retried.
     */
    private fun incomplete(data: String, streamed: List<JsonElement>): ModelTurn {
        val reason = try {
            val response = json.parseToJsonElement(data).jsonObject["response"] as? JsonObject
            ((response?.get("incomplete_details") as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
        } catch (e: Exception) {
            null
        } ?: "unknown"
        if (reason == "max_output_tokens") {
            val turn = try { parse(data, streamed) } catch (e: ModelException) { null }
            if (turn != null && (turn.text != null || turn.calls.isNotEmpty())) return turn
        }
        throw ModelException(200, "The model stopped early ($reason)", code = "incomplete:$reason", retryable = false)
    }

    /** [data] is the `response.completed` event payload; [streamed] stands in when its `output` is empty. */
    private fun parse(data: String, streamed: List<JsonElement>): ModelTurn {
        try {
            val root = json.parseToJsonElement(data).jsonObject.getValue("response").jsonObject
            val text = StringBuilder()
            var hasText = false
            val calls = mutableListOf<InputItem.FunctionCall>()
            val reasoning = mutableListOf<InputItem.Reasoning>()
            val output = root["output"]?.jsonArray?.takeIf { it.isNotEmpty() } ?: JsonArray(streamed)
            for (item in output) {
                val obj = item.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "message" -> for (part in obj["content"]?.jsonArray.orEmpty()) {
                        val p = part.jsonObject
                        if (p["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                            p["text"]?.jsonPrimitive?.contentOrNull?.let { text.append(it); hasText = true }
                        }
                    }
                    // Kept (without the id, which names nothing on an unstored response) only when encrypted.
                    "reasoning" -> if (obj["encrypted_content"] != null) reasoning += InputItem.Reasoning(JsonObject(obj - "id" - "status"))
                    "function_call" -> calls += InputItem.FunctionCall(
                        callId = obj.getValue("call_id").jsonPrimitive.content,
                        name = obj.getValue("name").jsonPrimitive.content,
                        argumentsJson = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                        // A call from a namespace goes back with it, or the next request does not match it.
                        extra = (obj["namespace"] as? JsonPrimitive)?.contentOrNull?.let { ns -> buildJsonObject { put("namespace", ns) } },
                    )
                }
            }
            val usage = (root["usage"] as? JsonObject)?.let { u ->
                val inTok = u["input_tokens"]?.jsonPrimitive?.intOrNull
                val outTok = u["output_tokens"]?.jsonPrimitive?.intOrNull
                val cached = (u["input_tokens_details"] as? JsonObject)?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
                if (inTok != null && outTok != null) Usage(inTok, outTok, cached) else null
            }
            return ModelTurn(if (hasText) text.toString() else null, calls, usage, WebSearch.citations(output), reasoning = reasoning)
        } catch (e: Exception) {
            throw ModelException(200, "Failed to parse response: ${e.message}")
        }
    }

    private fun InputItem.toJson(): JsonElement = when (this) {
        is InputItem.UserText -> if (images.isEmpty()) message("user", "input_text", "text", text) else userWithImages(text, images)
        is InputItem.UserImage -> message("user", "input_image", "image_url", "data:$mime;base64,$base64")
        is InputItem.AssistantText -> message("assistant", "output_text", "text", text)
        is InputItem.DeveloperText -> message("developer", "input_text", "text", text)
        is InputItem.FunctionCall -> buildJsonObject {
            put("type", "function_call")
            put("call_id", callId)
            put("name", name)
            (extra?.get("namespace") as? JsonPrimitive)?.let { put("namespace", it) }
            put("arguments", argumentsJson)
        }
        is InputItem.Reasoning -> raw
        is InputItem.FunctionOutput -> buildJsonObject {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", output)
        }
    }

    /** One user message: the text part (left out when empty), then one input_image part per image. */
    private fun userWithImages(text: String, images: List<InputItem.UserImage>) = buildJsonObject {
        put("role", "user")
        put("content", buildJsonArray {
            if (text.isNotEmpty()) {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })
            }
            for (image in images) {
                add(buildJsonObject {
                    put("type", "input_image")
                    put("image_url", "data:${image.mime};base64,${image.base64}")
                })
            }
        })
    }

    private fun message(role: String, partType: String, field: String, value: String) = buildJsonObject {
        put("role", role)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", partType)
                put(field, value)
            })
        })
    }

    private fun JsonObject.isOpenAiReasoning() = (this["type"] as? JsonPrimitive)?.contentOrNull == "reasoning"

    private fun ToolSpec.toJson() = buildJsonObject {
        put("type", "function")
        put("name", name)
        put("description", description)
        put("parameters", parameters)
        // Our schemas are not strict-mode compatible (optional properties, free-form batch args).
        put("strict", false)
    }

    internal companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** Error types that name the case better than the code beside them. */
        private val KNOWN_CODES = setOf("usage_limit_reached", "usage_not_included", "insufficient_quota")

        /**
         * The wait a 429 or 5xx asks for, in ms: retry-after-ms, Retry-After (seconds or an HTTP date), else the
         * longer of the x-ratelimit-reset-* headers ("1s", "6m0s", "250ms"). Null when none is given.
         */
        fun retryAfterMs(header: (String) -> String?): Long? {
            header("retry-after-ms")?.trim()?.toDoubleOrNull()?.let { return it.toLong().coerceAtLeast(0) }
            header("retry-after")?.trim()?.let { v ->
                v.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(0) }
                try {
                    val at = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    return (at.toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
                } catch (e: Exception) {
                    // neither seconds nor a date
                }
            }
            return listOfNotNull(header("x-ratelimit-reset-requests"), header("x-ratelimit-reset-tokens"))
                .mapNotNull { duration(it) }.maxOrNull()
        }

        private val DURATION_PART = Regex("([0-9]+(?:\\.[0-9]+)?)(ms|h|m|s)")

        /** "6m0s", "1.5s", "250ms" or plain seconds, in ms; null when it is not such a duration. */
        fun duration(text: String): Long? {
            val t = text.trim()
            if (t.isEmpty()) return null
            t.toDoubleOrNull()?.let { return (it * 1000).toLong() }
            val parts = DURATION_PART.findAll(t).toList()
            if (parts.isEmpty() || parts.joinToString("") { it.value } != t) return null
            return parts.sumOf { m ->
                val n = m.groupValues[1].toDouble()
                when (m.groupValues[2]) {
                    "h" -> n * 3_600_000
                    "m" -> n * 60_000
                    "s" -> n * 1000
                    else -> n
                }
            }.toLong()
        }
        const val ERROR_BODY_LIMIT = 500

        const val UNSUPPORTED_CAPABILITY = "subscription_sharing_unsupported_capability"

        /** The namespace the tools go in when a backend takes no top-level function tools (the ChatGPT plan route). */
        const val NAMESPACE = "phone"

        /** Request fields that may be left out when a backend rejects them. */
        private val DROPPABLE = setOf("prompt_cache_key", "service_tier", "parallel_tool_calls", "include", "text", "tools", "reasoning")

        /** baseUrl|model|field entries a backend rejected as unsupported; not sent to it again this session. */
        val rejected: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        private val PLAN_UNSUPPORTED get() = tr(
            "This request isn't supported on the ChatGPT plan. Try another model or an API key",
            "ChatGPT 요금제로는 이 요청을 쓸 수 없어요. 다른 모델이나 API 키로 시도해 주세요",
        )

        /**
         * A ChatGPT plan error said in words the user can act on (errors-and-recovery page), or null to handle [e] as
         * usual. Plan limits and eligibility are not retried: the same request would fail the same way.
         */
        fun planFailure(e: ModelException): ModelException? {
            val manage = "chatgpt.com/settings/usage"
            return when (e.code) {
                ModelException.PLAN_USAGE_LIMIT -> ModelException(
                    // The limit may be the plan's or just this app's, so neither is claimed; no reset time is known.
                    429, tr("Usage limit reached for your ChatGPT plan or this app. See $manage", "ChatGPT 요금제 또는 이 앱의 사용량 한도에 도달했어요. $manage 에서 확인해 주세요"),
                    code = e.code, retryable = false,
                )
                "subscription_sharing_user_not_eligible" -> ModelException(
                    403, tr("This ChatGPT account or workspace can't use its plan in apps", "이 ChatGPT 계정이나 워크스페이스는 앱에서 요금제를 쓸 수 없어요"),
                    code = e.code, retryable = false,
                )
                "subscription_sharing_route_not_supported", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> ModelException(
                    403, tr("The ChatGPT plan sign-in isn't set up right (${e.code}). Please sign in again", "ChatGPT 요금제 로그인 설정에 문제가 있어요 (${e.code}). 다시 로그인해 주세요"),
                    code = e.code, retryable = false,
                )
                "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> ModelException(
                    503, tr("ChatGPT plan usage is unavailable right now. Try again shortly", "지금은 ChatGPT 요금제 사용이 잠시 안 돼요. 잠시 후 다시 시도해 주세요"),
                    code = e.code,
                )
                else -> if (e.status == 403 && e.code == null) ModelException(
                    403, tr("The ChatGPT plan can't be used for this request (account, region or policy)", "이 요청에는 ChatGPT 요금제를 쓸 수 없어요 (계정·지역·정책)"),
                    retryable = false,
                ) else null
            }
        }

        /** baseUrl|model pairs whose backend rejected text.verbosity; it is not sent to them again this session. */
        val noVerbosity: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** baseUrl|model pairs whose backend rejected encrypted reasoning; this session runs those without it. */
        val noReasoningCarry: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}
