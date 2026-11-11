package com.phoneuse.app

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phoneuse.i18n.tr
import com.phoneuse.model.ChatCompletionsClient
import com.phoneuse.model.ClaudeClient

/** What each [AuthMode] (the model provider) brings: where its key and chosen model are kept, its model ids. */
object ModelProviders {
    const val KEY_CLAUDE = "claude_api_key"
    const val KEY_XAI = "xai_api_key"
    const val KEY_GEMINI = "gemini_api_key"

    /** Fallbacks until the user picks from the provider's own list. */
    const val GROK_DEFAULT = "grok-4"
    const val GEMINI_DEFAULT = "gemini-flash-latest"

    const val XAI_BASE_URL = "https://api.x.ai/v1/"
    const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"

    /** The Chat Completions backend of Grok and Gemini; null for the others. */
    fun endpoint(mode: AuthMode): ChatCompletionsClient.Endpoint? = when (mode) {
        AuthMode.GROK -> ChatCompletionsClient.Endpoint(XAI_BASE_URL, tr("Check your xAI API key", "xAI API 키를 확인해 주세요"))
        AuthMode.GEMINI -> ChatCompletionsClient.Endpoint(GEMINI_BASE_URL, tr("Check your Gemini API key", "Gemini API 키를 확인해 주세요"))
        else -> null
    }

    /** The encrypted secret that holds this provider's API key; null for the ChatGPT sign-in. */
    fun secretName(mode: AuthMode): String? = when (mode) {
        AuthMode.CHATGPT -> null
        AuthMode.API_KEY -> SettingsStore.KEY_API
        AuthMode.CLAUDE -> KEY_CLAUDE
        AuthMode.GROK -> KEY_XAI
        AuthMode.GEMINI -> KEY_GEMINI
    }

    /** Both OpenAI modes share one chosen model; each other provider keeps its own. */
    fun modelPref(mode: AuthMode): Preferences.Key<String> = when (mode) {
        AuthMode.CHATGPT, AuthMode.API_KEY -> MODEL_OPENAI
        AuthMode.CLAUDE -> MODEL_CLAUDE
        AuthMode.GROK -> MODEL_GROK
        AuthMode.GEMINI -> MODEL_GEMINI
    }

    fun defaultModel(mode: AuthMode): String = when (mode) {
        AuthMode.CHATGPT, AuthMode.API_KEY -> SettingsStore.DEFAULT_MODEL
        AuthMode.CLAUDE -> ClaudeClient.DEFAULT_MODEL
        AuthMode.GROK -> GROK_DEFAULT
        AuthMode.GEMINI -> GEMINI_DEFAULT
    }

    /** [defaultModel], or the newer one of its family the provider's own list showed last (see [ModelUpgrade]). */
    fun defaultModel(prefs: Preferences, mode: AuthMode): String =
        prefs[autoPref(mode)]?.takeIf { it.isNotBlank() } ?: defaultModel(mode)

    /** The quick model for the helper, routines and side calls; null = the chosen model (no reliable light id). */
    fun lightModel(mode: AuthMode): String? = when (mode) {
        AuthMode.CHATGPT, AuthMode.API_KEY -> SettingsStore.ROUTINE_LIGHT_MODEL
        AuthMode.CLAUDE -> ClaudeClient.LIGHT_MODEL
        AuthMode.GROK, AuthMode.GEMINI -> null
    }

    /** [lightModel], or the newer one of its family the provider's own list showed last. */
    fun lightModel(prefs: Preferences, mode: AuthMode): String? =
        lightModel(mode)?.let { prefs[autoLightPref(mode)]?.takeIf { id -> id.isNotBlank() } ?: it }

    /** The newest default and light model [listed] holds for [mode]; null where nothing is newer than the built-in. */
    fun upgrades(mode: AuthMode, listed: List<String>): Pair<String?, String?> =
        ModelUpgrade.newest(defaultModel(mode), listed) to lightModel(mode)?.let { ModelUpgrade.newest(it, listed) }

    /** Ids offered before (and beside) the provider's own list. */
    fun presets(mode: AuthMode): List<String> = when (mode) {
        AuthMode.CHATGPT, AuthMode.API_KEY -> MODEL_PRESETS
        AuthMode.CLAUDE -> ClaudeClient.MODELS
        AuthMode.GROK, AuthMode.GEMINI -> emptyList()
    }

    /** Fast mode is OpenAI's priority tier: the ChatGPT plan and an API key can ask for it, other providers have none. */
    fun supportsFast(mode: AuthMode): Boolean = mode == AuthMode.CHATGPT || mode == AuthMode.API_KEY

    /** Hosted web search is OpenAI's and Claude's. */
    fun supportsWebSearch(mode: AuthMode): Boolean = mode != AuthMode.GROK && mode != AuthMode.GEMINI

    fun chosenModel(prefs: Preferences, mode: AuthMode): String =
        prefs[modelPref(mode)]?.takeIf { it.isNotBlank() } ?: defaultModel(prefs, mode)

    fun routineModel(prefs: Preferences, mode: AuthMode, light: Boolean): String =
        if (light) lightModel(prefs, mode) ?: chosenModel(prefs, mode) else chosenModel(prefs, mode)

    /** What the key field shows while empty: the start every key of that provider has. */
    fun keyHint(mode: AuthMode): String = when (mode) {
        AuthMode.CLAUDE -> "sk-ant-…"
        AuthMode.GROK -> "xai-…"
        AuthMode.GEMINI -> "AIza…"
        else -> "sk-…"
    }

    fun label(mode: AuthMode): String = when (mode) {
        AuthMode.CHATGPT -> tr("ChatGPT subscription", "ChatGPT 구독")
        AuthMode.API_KEY -> tr("OpenAI API key", "OpenAI API 키")
        AuthMode.CLAUDE -> "Claude"
        AuthMode.GROK -> "Grok"
        AuthMode.GEMINI -> "Gemini"
    }

    /**
     * The model ids [mode]'s own models endpoint lists for [apiKey], chat models only; empty for OpenAI (its
     * presets are the list). Throws [com.phoneuse.model.ModelException] on a bad key or a network error.
     */
    suspend fun listModels(mode: AuthMode, apiKey: String, http: okhttp3.OkHttpClient): List<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (mode) {
                AuthMode.CLAUDE -> ClaudeClient.listModels(ClaudeClient.sdkClient(apiKey))
                AuthMode.GROK, AuthMode.GEMINI -> ChatCompletionsClient.listModels(endpoint(mode)!!, apiKey, http).filter(::chatCapable)
                // [apiKey] is the plan sign-in's access token: the account's own catalog, in the server's order.
                AuthMode.CHATGPT -> com.phoneuse.auth.PlanModels.list(http, apiKey)
                AuthMode.API_KEY -> com.phoneuse.auth.PlanModels.list(http, apiKey).filter(::openAiChatModel).sorted()
            }
        }

    /** Model ids a provider's list holds that cannot drive the agent (embeddings, image and speech models). */
    fun chatCapable(id: String): Boolean {
        val lower = id.lowercase()
        return NOT_CHAT.none { it in lower }
    }

    /** An OpenAI API model that can drive the agent: the gpt, chatgpt and o families, without speech or search ones. */
    fun openAiChatModel(id: String): Boolean {
        val lower = id.lowercase()
        val family = lower.startsWith("gpt-") || lower.startsWith("chatgpt-") || OPENAI_O.containsMatchIn(lower)
        return family && chatCapable(lower) && OPENAI_NOT_CHAT.none { it in lower }
    }

    private val OPENAI_O = Regex("""^o\d""")
    private val OPENAI_NOT_CHAT = listOf("transcribe", "search", "realtime", "moderation", "instruct")

    private val NOT_CHAT = listOf("embedding", "imagen", "imagine", "-image", "veo", "tts", "aqa", "audio", "live", "lyria", "robotics")

    private val MODEL_OPENAI = stringPreferencesKey("model")
    private val MODEL_CLAUDE = stringPreferencesKey("model_claude")
    private val MODEL_GROK = stringPreferencesKey("model_grok")
    private val MODEL_GEMINI = stringPreferencesKey("model_gemini")

    /** Where the newer default and light model found in [mode]'s own list are kept. */
    fun autoPref(mode: AuthMode): Preferences.Key<String> = stringPreferencesKey("auto_model_" + mode.name.lowercase())
    fun autoLightPref(mode: AuthMode): Preferences.Key<String> = stringPreferencesKey("auto_light_model_" + mode.name.lowercase())
}
