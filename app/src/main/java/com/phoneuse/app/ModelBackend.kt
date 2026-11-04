package com.phoneuse.app

import com.phoneuse.auth.ApiKeyAuth
import com.phoneuse.auth.SettingsPlanStore
import com.phoneuse.model.ChatCompletionsClient
import com.phoneuse.model.ClaudeClient
import com.phoneuse.model.ModelAuth
import com.phoneuse.model.ModelClient
import com.phoneuse.model.ModelOptions
import com.phoneuse.model.OpenAiResponsesClient
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/** Where a run's model calls go, as the settings say (see [AgentRunner.resolveBackend]); [client] builds one. */
sealed interface ModelBackend {
    val mode: AuthMode

    /** A client for [options]. */
    fun client(options: ModelOptions, http: OkHttpClient): ModelClient

    /** The quick model for side calls (helper, suggestions, briefs); null = this provider has none, use the chosen one. */
    val lightModel: String? get() = ModelProviders.lightModel(mode)

    class OpenAi(override val mode: AuthMode, val auth: ModelAuth) : ModelBackend {
        override fun client(options: ModelOptions, http: OkHttpClient): ModelClient =
            OpenAiResponsesClient(auth, options, http)
    }

    class Claude(private val apiKey: String) : ModelBackend {
        override val mode get() = AuthMode.CLAUDE

        // Fast mode is OpenAI's priority tier.
        override fun client(options: ModelOptions, http: OkHttpClient): ModelClient =
            ClaudeClient(ClaudeClient.sdkClient(apiKey), options.copy(fast = false))
    }

    class Chat(override val mode: AuthMode, private val endpoint: ChatCompletionsClient.Endpoint, private val apiKey: String) : ModelBackend {
        override fun client(options: ModelOptions, http: OkHttpClient): ModelClient =
            ChatCompletionsClient(endpoint, apiKey, options.copy(fast = false, webSearch = false), http)
    }
}

/**
 * The provider and sign-in these settings choose, or null when it has nothing configured (signed out, no key).
 * [planAuth] is the caller's one plan sign-in.
 */
internal suspend fun SettingsStore.chosenBackend(planAuth: ModelAuth): ModelBackend? = when (val mode = authMode.first()) {
    AuthMode.CHATGPT -> if (SettingsPlanStore(this).load() != null) ModelBackend.OpenAi(mode, planAuth) else null
    AuthMode.API_KEY -> apiKey.first().takeIf { it.isNotBlank() }?.let { ModelBackend.OpenAi(mode, ApiKeyAuth(it)) }
    AuthMode.CLAUDE -> providerKey(mode).first().takeIf { it.isNotBlank() }?.let { ModelBackend.Claude(it) }
    AuthMode.GROK, AuthMode.GEMINI -> providerKey(mode).first().takeIf { it.isNotBlank() }?.let { key ->
        ModelBackend.Chat(mode, ModelProviders.endpoint(mode)!!, key)
    }
}
