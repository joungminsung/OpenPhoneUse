package com.phoneuse.model

import kotlinx.serialization.json.JsonObject

sealed interface InputItem {
    /** A user message; [images] are sent as image parts after the text (attached photos). */
    data class UserText(val text: String, val images: List<UserImage> = emptyList()) : InputItem
    data class UserImage(val base64: String, val mime: String = "image/jpeg") : InputItem
    data class AssistantText(val text: String) : InputItem

    /**
     * App context for this turn (e.g. the memories that match the message), sent as a developer message. It goes
     * in the input, not the instructions, so the instructions stay byte-identical and the prompt cache hits.
     */
    data class DeveloperText(val text: String) : InputItem

    /**
     * [extra] is provider data that must come back with the call on the next request (Gemini's
     * `extra_content.google.thought_signature`); null for every other provider.
     */
    data class FunctionCall(
        val callId: String,
        val name: String,
        val argumentsJson: String,
        val extra: JsonObject? = null,
    ) : InputItem
    data class FunctionOutput(val callId: String, val output: String) : InputItem

    /**
     * The model's own reasoning from an earlier step of this run, opaque and encrypted ([raw] is the output item
     * without its id). Sent back so the model keeps its plan and what it has tried from step to step; requests are
     * not stored on the server, so without it every step starts from scratch. Each client sends back only its own
     * provider's kind (OpenAI `reasoning` items; Claude's whole assistant turn, see [ClaudeClient]) and skips the rest.
     */
    data class Reasoning(val raw: JsonObject) : InputItem
}

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

/** [cachedTokens]: the part of the input served from the prompt cache. */
data class Usage(val inputTokens: Int, val outputTokens: Int, val cachedTokens: Int = 0)

/**
 * [citations] are the web sources the turn's text cites (hosted web search). [firstByteMs] (response headers) and
 * [firstOutputMs] (first output item or text) are measured from the request start, for the step timing log.
 */
data class ModelTurn(
    val text: String?,
    val calls: List<InputItem.FunctionCall>,
    val usage: Usage?,
    val citations: List<Citation> = emptyList(),
    val firstByteMs: Long? = null,
    val firstOutputMs: Long? = null,
    /** This turn's reasoning items, to put back in the input before its calls on the next step. */
    val reasoning: List<InputItem.Reasoning> = emptyList(),
)

/**
 * [status] is the HTTP code, or null for an IO error. [retryAfterMs] is the wait the server asked for (Retry-After or
 * a rate-limit reset header), when it named one. [code] is the error's type or code from its JSON body, when known
 * (e.g. "usage_limit_reached", "context_length_exceeded"). [afterOutput]: the stream had already sent output when it
 * failed (a retry would run the same long turn again). [retryable] false: trying the same request again cannot help.
 */
class ModelException(
    val status: Int?,
    message: String,
    val retryAfterMs: Long? = null,
    val code: String? = null,
    val afterOutput: Boolean = false,
    val retryable: Boolean = true,
) : Exception(message) {
    companion object {
        /** The ChatGPT plan's usage limit ([code]); the chat offers "Manage usage" on a reply that failed with it. */
        const val PLAN_USAGE_LIMIT = "subscription_sharing_usage_limit_exceeded"
    }
}

/**
 * [effort] is "low", "medium" or "high". [fast] adds the priority service tier. [webSearch] adds the
 * model-hosted web search tool. [promptCacheKey] (one per conversation) routes the steps of a conversation to the
 * same prompt cache. [verbosity] ("low", "medium", "high") is sent as text.verbosity when the backend takes it.
 */
data class ModelOptions(
    val model: String,
    val fast: Boolean,
    val effort: String,
    val webSearch: Boolean = false,
    val promptCacheKey: String? = null,
    val verbosity: String? = null,
)

interface ModelAuth {
    val baseUrl: String
    /** Shown to the user when the backend answers 401/403. */
    val unauthorizedMessage: String
    suspend fun headers(): Map<String, String>
    /** Called on a 401; returns true if credentials were refreshed and the request may be retried once. */
    suspend fun onUnauthorized(): Boolean
    /** The same, knowing the [rejected] headers: credentials that changed since they were sent need no new refresh. */
    suspend fun onUnauthorized(rejected: Map<String, String>): Boolean = onUnauthorized()
    /** Sign in with ChatGPT plan usage: the public Responses API with that route's limits (see OpenAiResponsesClient). */
    val planUsage: Boolean get() = false
    /** The plan route refused a request over its usage limit ([ModelException.PLAN_USAGE_LIMIT]). */
    suspend fun onUsageLimit() = Unit
}
