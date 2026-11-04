package com.phoneuse.model

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.jsonMapper
import com.anthropic.core.http.AsyncStreamResponse
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.ErrorType
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.WebSearchTool20260209
import com.anthropic.models.models.ModelListParams
import com.phoneuse.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Optional
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Claude through the Anthropic Messages API (official Java SDK), streamed. Function tools, images, hosted web search
 * (`web_search_20260209`) and prompt caching (system, tools and the conversation so far).
 *
 * Claude's assistant turn is one message: thinking blocks (signed, sent back unchanged), any web search blocks, text
 * and tool_use blocks, in the order the model produced them. The whole turn goes back as one [InputItem.Reasoning]
 * ([TURN_TYPE]); the agent loop's [InputItem.AssistantText] and [InputItem.FunctionCall] items of that turn are then
 * already in it. All tool results of a turn go back in one user message, results first, then images and text.
 */
class ClaudeClient(
    private val client: AnthropicClient,
    private val options: ModelOptions,
    private val unauthorizedMessage: String = UNAUTHORIZED,
) : ModelClient {

    private val mapper = jsonMapper()
    private val json = Json { ignoreUnknownKeys = true }

    /** Which response each call came from, for calls whose turn did not come back as a [TURN_TYPE] item. */
    private val callTurn = ConcurrentHashMap<String, Int>()
    private val turns = AtomicInteger()

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
        val messages = messages(input)
        var turn: ModelTurn? = null
        var content = JsonArray(emptyList())
        // A server-side search loop that hit its limit pauses; sending the turn back resumes it.
        repeat(MAX_CONTINUATIONS + 1) {
            val resumed = if (content.isEmpty()) messages else messages + assistantMessage(content)
            val (part, paused) = send(instructions, resumed, tools, onTextDelta, onSearch)
            content = JsonArray(content + part.content)
            turn = merge(turn, part.turn)
            if (!paused) return finish(turn!!, content)
        }
        return finish(turn!!, content)
    }

    private fun merge(a: ModelTurn?, b: ModelTurn): ModelTurn {
        if (a == null) return b
        val text = listOfNotNull(a.text, b.text).joinToString("").ifEmpty { null }
        val usage = if (a.usage == null || b.usage == null) a.usage ?: b.usage else Usage(
            a.usage.inputTokens + b.usage.inputTokens, a.usage.outputTokens + b.usage.outputTokens,
            a.usage.cachedTokens + b.usage.cachedTokens,
        )
        return a.copy(text = text, calls = a.calls + b.calls, usage = usage, citations = (a.citations + b.citations).distinctBy { it.url })
    }

    private fun finish(turn: ModelTurn, content: JsonArray): ModelTurn {
        val n = turns.incrementAndGet()
        turn.calls.forEach { callTurn[it.callId] = n }
        val raw = buildJsonObject {
            put("type", TURN_TYPE)
            put("content", content)
        }
        return turn.copy(reasoning = if (turn.calls.isEmpty()) emptyList() else listOf(InputItem.Reasoning(raw)))
    }

    // ---- request ---------------------------------------------------------------------------------------------

    /** The input as Messages API messages (JSON): alternating roles, starting with the user. */
    internal fun messages(input: List<InputItem>): List<JsonObject> {
        val grouped = Conversation.group(input, keepReasoning = { it.string("type") == TURN_TYPE }) { turn, call ->
            call.callId in turnIds(turn) || turn.calls.last().callId.let { callTurn[it] != null && callTurn[it] == callTurn[call.callId] }
        }
        val out = ArrayList<Pair<String, MutableList<JsonObject>>>()
        for (message in grouped) {
            val (role, blocks) = when (message) {
                is Conversation.Assistant -> "assistant" to assistantBlocks(message)
                is Conversation.User -> "user" to userBlocks(message)
            }
            if (blocks.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.first == role) last.second += blocks else out += role to blocks.toMutableList()
        }
        if (out.firstOrNull()?.first == "assistant") out.add(0, "user" to mutableListOf(text("…")))
        return out.map { (role, blocks) ->
            // Merged user messages still put every tool_result first.
            val ordered = if (role == "user") blocks.sortedBy { if (it.string("type") == "tool_result") 0 else 1 } else blocks
            buildJsonObject {
                put("role", role)
                put("content", JsonArray(ordered))
            }
        }
    }

    private fun turnIds(turn: Conversation.Assistant): Set<String> = turn.reasoning.flatMapTo(HashSet()) { raw ->
        (raw["content"] as? JsonArray).orEmpty().mapNotNull { b ->
            (b as? JsonObject)?.takeIf { it.string("type") == "tool_use" }?.string("id")
        }
    }

    private fun assistantBlocks(message: Conversation.Assistant): List<JsonObject> {
        val raw = message.reasoning.flatMap { (it["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
        if (raw.isNotEmpty()) {
            // The turn as the model produced it; only calls it does not hold (none, normally) are added.
            val ids = raw.mapNotNullTo(HashSet()) { it.takeIf { b -> b.string("type") == "tool_use" }?.string("id") }
            return raw + message.calls.filter { it.callId !in ids }.map(::toolUse)
        }
        return message.texts.map(::text) + message.calls.map(::toolUse)
    }

    private fun userBlocks(message: Conversation.User): List<JsonObject> {
        val blocks = ArrayList<JsonObject>()
        for (r in message.results) {
            blocks += buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", r.callId)
                if (r.output.isNotEmpty()) put("content", r.output)
            }
        }
        for (part in message.parts) {
            blocks += when (part) {
                is Conversation.Part.Text -> text(part.text)
                is Conversation.Part.Context -> text(Conversation.context(part.text))
                is Conversation.Part.Image -> buildJsonObject {
                    put("type", "image")
                    put("source", buildJsonObject {
                        put("type", "base64")
                        put("media_type", part.image.mime)
                        put("data", part.image.base64)
                    })
                }
            }
        }
        return blocks
    }

    private fun text(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun toolUse(call: InputItem.FunctionCall) = buildJsonObject {
        put("type", "tool_use")
        put("id", call.callId)
        put("name", call.name)
        put("input", runCatching { json.parseToJsonElement(call.argumentsJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap()))
    }

    private fun assistantMessage(content: JsonArray) = buildJsonObject {
        put("role", "assistant")
        put("content", content)
    }

    /**
     * [instructions] and [tools] are byte-identical across the steps of a run, so the system block and the last
     * tool carry cache breakpoints, and the top-level one caches the conversation up to its newest block.
     */
    internal fun params(instructions: String, messages: List<JsonObject>, tools: List<ToolSpec>): MessageCreateParams {
        val cache = CacheControlEphemeral.builder().build()
        val builder = MessageCreateParams.builder()
            .model(options.model)
            .maxTokens(MAX_TOKENS)
            .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(instructions).cacheControl(cache).build()))
            .messages(messages.map { mapper.readValue(it.toString(), MessageParam::class.java) })
            .cacheControl(cache)
        if (modelKey() !in noThinking) builder.thinking(ThinkingConfigAdaptive.builder().build())
        if (modelKey() !in noEffort) builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(options.effort)).build())
        tools.forEachIndexed { i, spec ->
            val last = i == tools.lastIndex && !options.webSearch
            builder.addTool(
                Tool.builder()
                    .name(spec.name)
                    .description(spec.description)
                    .inputSchema(mapper.readValue<Tool.InputSchema>(spec.parameters.toString(), Tool.InputSchema::class.java))
                    .apply { if (last) cacheControl(cache) }
                    .build(),
            )
        }
        if (options.webSearch) builder.addTool(WebSearchTool20260209.builder().cacheControl(cache).build())
        return builder.build()
    }

    private fun modelKey() = options.model

    // ---- response --------------------------------------------------------------------------------------------

    private class Part(val turn: ModelTurn, val content: JsonArray)

    private suspend fun send(
        instructions: String,
        messages: List<JsonObject>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
        onSearch: (WebSearchCall) -> Unit,
    ): Pair<Part, Boolean> {
        while (true) {
            try {
                return stream(params(instructions, messages, tools), onTextDelta, onSearch)
            } catch (e: ModelException) {
                // Models that take no adaptive thinking or no effort: drop it for the session and try again.
                if (e.status != 400) throw e
                val message = e.message.orEmpty()
                when {
                    modelKey() !in noEffort && message.contains("effort", ignoreCase = true) -> noEffort += modelKey()
                    modelKey() !in noThinking && message.contains("thinking", ignoreCase = true) &&
                        !message.contains("signature", ignoreCase = true) -> noThinking += modelKey()
                    else -> throw e
                }
            }
        }
    }

    private suspend fun stream(
        params: MessageCreateParams,
        onTextDelta: (String) -> Unit,
        onSearch: (WebSearchCall) -> Unit,
    ): Pair<Part, Boolean> {
        val listener = Listener(onTextDelta, onSearch)
        val response = withContext(Dispatchers.IO) { client.async().messages().createStreaming(params) }
        var done = false
        try {
            response.subscribe(listener).onCompleteFuture().await()
            done = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            done = true
            throw toModelException(e)
        } finally {
            // Stop pressed: closing ends the HTTP call, so the request does not run on in the background.
            if (!done) response.close()
        }
        listener.failure?.let { throw toModelException(it) }
        return listener.result()
    }

    /** Per-request stream handling (on the SDK's stream thread): text deltas, searches, timings. */
    private inner class Listener(
        private val onTextDelta: (String) -> Unit,
        private val onSearch: (WebSearchCall) -> Unit,
    ) : AsyncStreamResponse.Handler<RawMessageStreamEvent> {
        private val accumulator = com.anthropic.helpers.MessageAccumulator.create()
        private val searchIds = HashMap<Long, String?>()
        private val searchInput = HashMap<Long, StringBuilder>()
        private val startedNs = System.nanoTime()
        private var firstByteNs = 0L
        private var firstOutputNs = 0L
        @Volatile var failure: Throwable? = null
            private set

        override fun onNext(event: RawMessageStreamEvent) {
            if (failure != null) return
            try {
                if (firstByteNs == 0L) firstByteNs = System.nanoTime()
                accumulator.accumulate(event)
                event.contentBlockStart().ifPresent { start ->
                    if (firstOutputNs == 0L) firstOutputNs = System.nanoTime()
                    start.contentBlock().serverToolUse().ifPresent { use ->
                        if (use.name().asString() == "web_search") {
                            searchIds[start.index()] = use.id()
                            searchInput[start.index()] = StringBuilder()
                        }
                    }
                }
                event.contentBlockDelta().ifPresent { d ->
                    d.delta().text().ifPresent { t -> if (t.text().isNotEmpty()) onTextDelta(t.text()) }
                    d.delta().inputJson().ifPresent { j -> searchInput[d.index()]?.append(j.partialJson()) }
                }
                event.contentBlockStop().ifPresent { stop ->
                    val index = stop.index()
                    if (index in searchIds) {
                        val query = searchInput[index]?.toString()?.let { s ->
                            runCatching { (json.parseToJsonElement(s) as? JsonObject)?.string("query") }.getOrNull()
                        }?.trim()?.takeIf { it.isNotEmpty() }
                        onSearch(WebSearchCall(searchIds[index], listOfNotNull(query)))
                    }
                }
            } catch (e: Exception) {
                failure = e
            }
        }

        override fun onComplete(error: Optional<Throwable>) {
            error.ifPresent { if (failure == null) failure = it }
        }

        private fun ms(ns: Long): Long? = if (ns == 0L) null else (ns - startedNs) / 1_000_000

        fun result(): Pair<Part, Boolean> {
            val message = try {
                accumulator.message()
            } catch (e: Exception) {
                throw ModelException(null, "stream ended early")
            }
            val content = json.parseToJsonElement(mapper.writeValueAsString(message.content().map { it.toParam() })) as JsonArray
            val parsed = parse(json.parseToJsonElement(mapper.writeValueAsString(message)).jsonObject)
            val turn = parsed.first.copy(firstByteMs = ms(firstByteNs), firstOutputMs = ms(firstOutputNs))
            return Part(turn, content) to (parsed.second == "pause_turn")
        }
    }

    /** The turn in a final message (Messages API JSON), and its stop reason. */
    internal fun parse(message: JsonObject): Pair<ModelTurn, String?> {
        val text = StringBuilder()
        var hasText = false
        val calls = ArrayList<InputItem.FunctionCall>()
        val cited = LinkedHashMap<String, Citation>()
        for (element in (message["content"] as? JsonArray).orEmpty()) {
            val block = element as? JsonObject ?: continue
            when (block.string("type")) {
                "text" -> {
                    block.string("text")?.let { text.append(it); hasText = true }
                    for (c in (block["citations"] as? JsonArray).orEmpty()) {
                        val citation = c as? JsonObject ?: continue
                        if (citation.string("type") != "web_search_result_location") continue
                        val url = citation.string("url")?.trim() ?: continue
                        if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                        cited.getOrPut(url) { Citation(citation.string("title")?.trim()?.takeIf(String::isNotEmpty) ?: url, url) }
                    }
                }
                "tool_use" -> calls += InputItem.FunctionCall(
                    callId = block.string("id") ?: continue,
                    name = block.string("name") ?: continue,
                    argumentsJson = (block["input"] as? JsonObject)?.toString() ?: "{}",
                )
            }
        }
        val stop = message.string("stop_reason")
        val usage = (message["usage"] as? JsonObject)?.let { u ->
            val input = u.int("input_tokens") ?: return@let null
            val output = u.int("output_tokens") ?: return@let null
            val read = u.int("cache_read_input_tokens") ?: 0
            val written = u.int("cache_creation_input_tokens") ?: 0
            Usage(input + read + written, output, read)
        }
        // A refused turn may stop mid-call: none of its calls run.
        if (stop == "refusal") {
            val reply = if (hasText) text.toString() else tr("Claude declined this request.", "Claude가 이 요청을 거절했어요.")
            return ModelTurn(reply, emptyList(), usage) to stop
        }
        return ModelTurn(if (hasText) text.toString() else null, calls, usage, cited.values.toList()) to stop
    }

    private fun toModelException(e: Throwable): ModelException {
        val cause = if ((e is CompletionException || e is ExecutionException) && e.cause != null) e.cause!! else e
        return when (cause) {
            is ModelException -> cause
            is AnthropicServiceException -> {
                val status = cause.statusCode().takeIf { it >= 400 } ?: statusOf(cause.errorType().orElse(null))
                if (status == 401 || status == 403) {
                    ModelException(status, unauthorizedMessage)
                } else {
                    ModelException(status, errorMessage(cause))
                }
            }
            is AnthropicIoException -> ModelException(null, "Network error: ${cause.message}")
            else -> ModelException(null, cause.message ?: cause.javaClass.simpleName)
        }
    }

    private fun errorMessage(e: AnthropicServiceException): String {
        val body = runCatching { json.parseToJsonElement(mapper.writeValueAsString(e.body())) as? JsonObject }.getOrNull()
        val message = (body?.get("error") as? JsonObject)?.string("message")
        return (message ?: e.message ?: "HTTP ${e.statusCode()}").take(ERROR_BODY_LIMIT)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    companion object {
        /** The [InputItem.Reasoning.raw] type that holds a whole Claude assistant turn (`content`: its blocks). */
        const val TURN_TYPE = "anthropic_turn"
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val LIGHT_MODEL = "claude-haiku-5-5"
        val MODELS = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1")

        /** Thinking counts toward it; Opus 5.5 thinks on every request. */
        private const val MAX_TOKENS = 32_000L
        private const val MAX_CONTINUATIONS = 3
        private const val ERROR_BODY_LIMIT = 500

        private val UNAUTHORIZED get() = tr("Check your Claude API key", "Claude API 키를 확인해 주세요")

        /** Models that rejected output_config.effort / adaptive thinking; not sent to them again this session. */
        private val noEffort: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val noThinking: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** A status for an error that arrived inside the stream (its HTTP status was 200). */
        private fun statusOf(type: ErrorType?): Int? = when (type) {
            ErrorType.INVALID_REQUEST_ERROR -> 400
            ErrorType.AUTHENTICATION_ERROR -> 401
            ErrorType.BILLING_ERROR -> 402
            ErrorType.PERMISSION_ERROR -> 403
            ErrorType.NOT_FOUND_ERROR -> 404
            ErrorType.RATE_LIMIT_ERROR -> 429
            ErrorType.TIMEOUT_ERROR -> 504
            ErrorType.OVERLOADED_ERROR -> 529
            ErrorType.API_ERROR -> 500
            else -> null
        }

        @Volatile private var shared: Pair<String, AnthropicClient>? = null

        /**
         * The SDK client for [apiKey], kept while the key stays the same (it owns a connection pool and threads).
         * No SDK retries: the agent loop retries with its own backoff.
         */
        @Synchronized
        fun sdkClient(apiKey: String): AnthropicClient {
            shared?.let { (key, client) -> if (key == apiKey) return client }
            val client = build(apiKey)
            shared?.second?.let { old -> runCatching { old.close() } }
            shared = apiKey to client
            return client
        }

        fun build(apiKey: String, baseUrl: String? = null): AnthropicClient =
            AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .maxRetries(0)
                .apply { baseUrl?.let { baseUrl(it) } }
                .build()

        /** The Claude model ids the key can use, newest first. Blocking. */
        fun listModels(client: AnthropicClient): List<String> = try {
            client.models().list(ModelListParams.builder().limit(100L).build()).data().map { it.id() }.filter { it.startsWith("claude") }
        } catch (e: AnthropicServiceException) {
            val status = e.statusCode()
            throw ModelException(status, if (status == 401 || status == 403) UNAUTHORIZED else (e.message ?: "HTTP $status").take(ERROR_BODY_LIMIT))
        } catch (e: AnthropicIoException) {
            throw ModelException(null, "Network error: ${e.message}")
        }
    }
}
