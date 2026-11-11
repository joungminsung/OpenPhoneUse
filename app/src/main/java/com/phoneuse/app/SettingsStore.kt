package com.phoneuse.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phoneuse.agent.AnswerLength
import com.phoneuse.agent.Personalization
import com.phoneuse.security.KeystoreSecretBox
import com.phoneuse.security.SecretBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class AuthMode { CHATGPT, API_KEY, CLAUDE, GROK, GEMINI }

enum class ReasoningEffort { LOW, MEDIUM, HIGH }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Settings "측면 버튼 길게 누르기": suggestions for the screen underneath, or only the input. */
enum class AssistAction { SUGGEST, TYPE }

/** Settings "조작 속도": every settle wait of the agent is multiplied by [waitScale]. */
enum class ControlSpeed(val waitScale: Double) { FAST(0.7), NORMAL(1.0), SLOW(1.4) }

/** Settings "글자 크기": multiplies the system font scale inside the app. */
enum class FontSize(val scale: Float) { DEFAULT(1f), LARGE(1.15f), LARGEST(1.3f) }

/** Settings "펼친 화면에서": the result pane beside the chat, or the chat alone. */
enum class UnfoldedLayout { SIDE_BY_SIDE, CHAT_ONLY }

/** Settings "모델에 보내는 화면 내용": the screen tree and screenshots, or the tree (text) only. */
enum class ScreenContent { ALL, TEXT_ONLY }

/** Settings "음성으로 말하기": the speech recognizer's language; [tag] null = the system's. */
enum class VoiceLanguage(val tag: String?) { KOREAN("ko-KR"), ENGLISH("en-US"), SYSTEM(null) }

/**
 * Settings "방해하지 않는 시간": minutes of the day; the span may cross midnight (23:00–07:00).
 * While inside it, OpenPhoneUse's own notifications arrive silently.
 */
data class QuietHours(val on: Boolean = true, val startMin: Int = 23 * 60, val endMin: Int = 7 * 60) {
    fun contains(minuteOfDay: Int): Boolean = when {
        !on || startMin == endMin -> false
        startMin < endMin -> minuteOfDay in startMin until endMin
        else -> minuteOfDay >= startMin || minuteOfDay < endMin
    }
}

val MODEL_PRESETS = listOf("gpt-6.1-terra", "gpt-6.1-sol", "gpt-6-astra", "gpt-6-luna", "gpt-5.6-terra")

class SettingsStore internal constructor(
    private val store: DataStore<Preferences>,
    private val secrets: SecretBox,
) {
    constructor(context: Context, secrets: SecretBox = KeystoreSecretBox()) : this(context.applicationContext.dataStore, secrets)

    val authMode: Flow<AuthMode> = store.data.map { prefs ->
        prefs[AUTH_MODE]?.let { s -> AuthMode.entries.firstOrNull { it.name == s } } ?: AuthMode.CHATGPT
    }

    val apiKey: Flow<String> = store.data
        .onStart { migratePlaintextApiKey() }
        .map { prefs -> prefs[secretKey(KEY_API)]?.let { openSecret(it) } ?: "" }

    /** The chosen model of the chosen provider (each provider keeps its own, see [ModelProviders]). */
    val model: Flow<String> = store.data.map { ModelProviders.chosenModel(it, modeOf(it)) }
    /** Fast mode (priority tier); on until the user turns it off. */
    val fastMode: Flow<Boolean> = store.data.map { it[FAST_MODE] ?: DEFAULT_FAST_MODE }
    val reasoningEffort: Flow<ReasoningEffort> = store.data.map { prefs ->
        prefs[REASONING]?.let { s -> ReasoningEffort.entries.firstOrNull { it.name == s } } ?: ReasoningEffort.MEDIUM
    }

    /** The model-hosted web search tool; on by default. */
    val webSearch: Flow<Boolean> = store.data.map { it[WEB_SEARCH] ?: true }

    /** Board Usage: routines run on [ROUTINE_LIGHT_MODEL] instead of the chosen model; off by default. */
    val routineLightModel: Flow<Boolean> = store.data.map { it[ROUTINE_LIGHT] ?: false }

    /**
     * Board Usage "빠른 도우미": the main model may hand mechanical phone work (scrolling a list, collecting items)
     * to [HELPER_MODEL] while it keeps every decision; on by default.
     */
    val helperModel: Flow<Boolean> = store.data.map { it[HELPER] ?: true }

    /** The model a routine run should use. */
    val routineModel: Flow<String> = store.data.map { prefs ->
        ModelProviders.routineModel(prefs, modeOf(prefs), light = prefs[ROUTINE_LIGHT] == true)
    }

    /** The API key of [mode] (Claude, Grok, Gemini or OpenAI); empty when none is saved. */
    fun providerKey(mode: AuthMode): Flow<String> {
        val name = ModelProviders.secretName(mode) ?: return flowOf("")
        return if (name == KEY_API) apiKey else store.data.map { prefs -> prefs[secretKey(name)]?.let { secrets.open(it) } ?: "" }
    }

    suspend fun setProviderKey(mode: AuthMode, v: String) {
        val name = ModelProviders.secretName(mode) ?: return
        if (name == KEY_API) setApiKey(v) else writeSecret(name, v.trim().ifEmpty { null })
    }

    /** Board Memory "기억 끄기": memories are neither put into the prompt nor saved; on by default. */
    val memoryEnabled: Flow<Boolean> = store.data.map { it[MEMORY_ON] ?: true }

    /** Settings "대화에서 자동으로 기억": the prompt asks the model to remember new facts; on by default. */
    val autoRemember: Flow<Boolean> = store.data.map { it[AUTO_REMEMBER] ?: true }

    /** Settings "말투와 호칭" and "나에 대해 알려주기". */
    val personalization: Flow<Personalization> = store.data.map { prefs ->
        Personalization(
            callName = prefs[CALL_NAME] ?: "",
            polite = prefs[POLITE] ?: true,
            length = prefs[ANSWER_LENGTH]?.let { s -> AnswerLength.entries.firstOrNull { it.name == s } } ?: AnswerLength.NORMAL,
            about = prefs[ABOUT_ME] ?: "",
        )
    }

    val themeMode: Flow<ThemeMode> = store.data.map { prefs ->
        prefs[THEME]?.let { s -> ThemeMode.entries.firstOrNull { it.name == s } } ?: ThemeMode.SYSTEM
    }

    /** Settings "대화 기록 보관": days to keep a conversation after its last message; 0 = forever (the default). */
    val retentionDays: Flow<Int> = store.data.map { prefs -> prefs[RETENTION]?.takeIf { it in RETENTION_OPTIONS } ?: 0 }

    /**
     * Settings "메시지 보내기 전 확인": SEND (messages, mail, posts) and CHANGE (alarm, wallpaper) ask first; on by
     * default. MONEY, SECURITY and DELETE always ask, and send_sms always asks, whatever this says.
     */
    val confirmSend: Flow<Boolean> = store.data.map { it[CONFIRM_SEND] ?: true }

    /**
     * Settings "AI 안전 검사": the acting model's own declaration of what a tap or typing will do counts on top of the
     * keyword checks (see agent/RiskJudge.kt); read at run start; on by default. Off = keywords only.
     */
    val riskJudge: Flow<Boolean> = store.data.map { it[RISK_JUDGE] ?: true }

    val assistAction: Flow<AssistAction> = store.data.map { prefs -> enumOf(prefs[ASSIST_ACTION], AssistAction.SUGGEST) }
    val controlSpeed: Flow<ControlSpeed> = store.data.map { prefs -> enumOf(prefs[CONTROL_SPEED], ControlSpeed.NORMAL) }

    /** Settings "오늘 요약 받기": minute of the day for the daily digest notification; -1 = off. */
    val summaryMinute: Flow<Int> = store.data.map { prefs -> prefs[SUMMARY_MINUTE]?.takeIf { it in -1 until DAY_MINUTES } ?: DEFAULT_SUMMARY_MINUTE }

    val quietHours: Flow<QuietHours> = store.data.map { prefs ->
        QuietHours(
            on = prefs[QUIET_ON] ?: true,
            startMin = prefs[QUIET_START]?.takeIf { it in 0 until DAY_MINUTES } ?: QuietHours().startMin,
            endMin = prefs[QUIET_END]?.takeIf { it in 0 until DAY_MINUTES } ?: QuietHours().endMin,
        )
    }

    val fontSize: Flow<FontSize> = store.data.map { prefs -> enumOf(prefs[FONT_SIZE], FontSize.DEFAULT) }
    val unfoldedLayout: Flow<UnfoldedLayout> = store.data.map { prefs -> enumOf(prefs[UNFOLDED], UnfoldedLayout.SIDE_BY_SIDE) }
    val voiceLanguage: Flow<VoiceLanguage> = store.data.map { prefs -> enumOf(prefs[VOICE_LANG], VoiceLanguage.KOREAN) }

    /** Settings "답 소리 내어 읽기": replies are read aloud when a run ends; off by default. */
    val readAloud: Flow<Boolean> = store.data.map { it[READ_ALOUD] ?: false }
    val screenContent: Flow<ScreenContent> = store.data.map { prefs -> enumOf(prefs[SCREEN_CONTENT], ScreenContent.ALL) }

    val notificationsAsked: Flow<Boolean> = store.data.map { it[NOTIF_ASKED] ?: false }

    suspend fun markNotificationsAsked() {
        store.edit { it[NOTIF_ASKED] = true }
    }

    /** True the first time only: the one-time "You're using your ChatGPT plan" confirmation is due. */
    suspend fun claimPlanWelcome(): Boolean {
        var first = false
        store.edit {
            first = it[PLAN_WELCOME] != true
            it[PLAN_WELCOME] = true
        }
        return first
    }

    /** When automatic requests on the ChatGPT plan may go again, in epoch ms; 0 when they aren't paused. See [PlanPause]. */
    val planPausedUntil: Flow<Long> = store.data.map { it[PLAN_PAUSED_UNTIL] ?: 0L }

    suspend fun setPlanPausedUntil(v: Long) {
        store.edit { it[PLAN_PAUSED_UNTIL] = v }
    }

    suspend fun readSecret(key: String): String? {
        if (key == KEY_API) migratePlaintextApiKey()
        val sealed = store.data.first()[secretKey(key)] ?: return null
        return openSecret(sealed)
    }

    /**
     * The stored value can't be read now, and neither can one sealed this moment: the Keystore is in trouble for now and
     * the value isn't lost, so it shouldn't be replaced. An old value alone unreadable is gone for good (a key reset).
     */
    suspend fun secretInTrouble(key: String): Boolean {
        val sealed = store.data.first()[secretKey(key)] ?: return false
        if (openSecret(sealed) != null) return false
        return runCatching { openSecret(sealSecret("probe")) }.getOrNull() == null
    }

    /** null deletes the stored value. */
    suspend fun writeSecret(key: String, value: String?) {
        if (value == null) {
            store.edit { it.remove(secretKey(key)) }
        } else {
            val sealed = sealSecret(value)
            store.edit { it[secretKey(key)] = sealed }
        }
    }

    /**
     * Deletes the stored values of sign-ins the app no longer has, so no unused credentials stay on the device. They
     * are removed without being opened: a value this install can't decrypt goes too, and nothing can reach a log.
     */
    suspend fun dropRetiredSecrets() {
        if (RETIRED_SECRETS.none { it in store.data.first() }) return
        store.edit { prefs -> RETIRED_SECRETS.forEach { prefs.remove(it) } }
    }

    /** Deletes the stored values of settings the app no longer has. */
    suspend fun dropRetiredPrefs() {
        if (RETIRED_PREFS.none { it in store.data.first() }) return
        store.edit { prefs -> RETIRED_PREFS.forEach { prefs.remove(it) } }
    }

    suspend fun setAuthMode(v: AuthMode) {
        store.edit { it[AUTH_MODE] = v.name }
    }

    suspend fun setApiKey(v: String) {
        val key = v.trim()
        writeSecret(KEY_API, key.ifEmpty { null })
        store.edit { it.remove(LEGACY_API_KEY) }
    }

    /** Keeps [v] as the chosen model, also when it is today's newest; empty = back to [setModelAuto]. */
    suspend fun setModel(v: String) {
        store.edit { prefs ->
            val key = ModelProviders.modelPref(modeOf(prefs))
            val id = v.trim()
            if (id.isEmpty()) prefs.remove(key) else prefs[key] = id
        }
    }

    /** "자동": no model is kept, so the newest of the default's family is used (see [ModelUpgrade]). */
    suspend fun setModelAuto() {
        store.edit { prefs -> prefs.remove(ModelProviders.modelPref(modeOf(prefs))) }
    }

    /** True while no model is chosen and the newest is followed. */
    val modelAuto: Flow<Boolean> = store.data.map { it[ModelProviders.modelPref(modeOf(it))].isNullOrBlank() }

    /** Keeps the newest default and light model of [mode] that its own model list [listed] holds. */
    suspend fun rememberListedModels(mode: AuthMode, listed: List<String>) {
        if (listed.isEmpty()) return
        val (default, light) = ModelProviders.upgrades(mode, listed)
        store.edit { prefs ->
            if (default != null) prefs[ModelProviders.autoPref(mode)] = default else prefs.remove(ModelProviders.autoPref(mode))
            if (light != null) prefs[ModelProviders.autoLightPref(mode)] = light else prefs.remove(ModelProviders.autoLightPref(mode))
        }
    }

    /** The quick model of the chosen provider, following its newest; null = it has none. */
    val lightModel: Flow<String?> = store.data.map { ModelProviders.lightModel(it, modeOf(it)) }

    private fun modeOf(prefs: Preferences): AuthMode =
        prefs[AUTH_MODE]?.let { s -> AuthMode.entries.firstOrNull { it.name == s } } ?: AuthMode.CHATGPT

    suspend fun setFastMode(v: Boolean) {
        store.edit { it[FAST_MODE] = v }
    }

    suspend fun setReasoningEffort(v: ReasoningEffort) {
        store.edit { it[REASONING] = v.name }
    }

    suspend fun setWebSearch(v: Boolean) {
        store.edit { it[WEB_SEARCH] = v }
    }

    suspend fun setRoutineLightModel(v: Boolean) {
        store.edit { it[ROUTINE_LIGHT] = v }
    }

    suspend fun setHelperModel(v: Boolean) {
        store.edit { it[HELPER] = v }
    }

    suspend fun setRiskJudge(v: Boolean) {
        store.edit { it[RISK_JUDGE] = v }
    }

    suspend fun setMemoryEnabled(v: Boolean) {
        store.edit { it[MEMORY_ON] = v }
    }

    suspend fun setAutoRemember(v: Boolean) {
        store.edit { it[AUTO_REMEMBER] = v }
    }

    suspend fun setPersonalization(v: Personalization) {
        store.edit {
            it[CALL_NAME] = v.callName.trim().take(30)
            it[POLITE] = v.polite
            it[ANSWER_LENGTH] = v.length.name
            it[ABOUT_ME] = v.about.trim().take(Personalization.ABOUT_MAX)
        }
    }

    suspend fun setThemeMode(v: ThemeMode) {
        store.edit { it[THEME] = v.name }
    }

    suspend fun setRetentionDays(v: Int) {
        require(v in RETENTION_OPTIONS)
        store.edit { it[RETENTION] = v }
    }

    suspend fun setConfirmSend(v: Boolean) {
        store.edit { it[CONFIRM_SEND] = v }
    }

    suspend fun setAssistAction(v: AssistAction) {
        store.edit { it[ASSIST_ACTION] = v.name }
    }

    suspend fun setControlSpeed(v: ControlSpeed) {
        store.edit { it[CONTROL_SPEED] = v.name }
    }

    suspend fun setSummaryMinute(v: Int) {
        store.edit { it[SUMMARY_MINUTE] = v.coerceIn(-1, DAY_MINUTES - 1) }
    }

    suspend fun setQuietHours(v: QuietHours) {
        store.edit {
            it[QUIET_ON] = v.on
            it[QUIET_START] = v.startMin.coerceIn(0, DAY_MINUTES - 1)
            it[QUIET_END] = v.endMin.coerceIn(0, DAY_MINUTES - 1)
        }
    }

    suspend fun setFontSize(v: FontSize) {
        store.edit { it[FONT_SIZE] = v.name }
    }

    suspend fun setUnfoldedLayout(v: UnfoldedLayout) {
        store.edit { it[UNFOLDED] = v.name }
    }

    suspend fun setVoiceLanguage(v: VoiceLanguage) {
        store.edit { it[VOICE_LANG] = v.name }
    }

    suspend fun setReadAloud(v: Boolean) {
        store.edit { it[READ_ALOUD] = v }
    }

    suspend fun setScreenContent(v: ScreenContent) {
        store.edit { it[SCREEN_CONTENT] = v.name }
    }

    // Keystore AES-GCM is a Binder call (keystore2 / StrongBox): never on the caller's thread, which may be Main.
    private suspend fun openSecret(sealed: String): String? = withContext(Dispatchers.IO) { secrets.open(sealed) }

    private suspend fun sealSecret(plain: String): String = withContext(Dispatchers.IO) { secrets.seal(plain) }

    /** Older versions stored the key in plaintext; move it into encrypted storage and drop the plaintext. */
    private suspend fun migratePlaintextApiKey() {
        val legacy = store.data.first()[LEGACY_API_KEY] ?: return
        val sealed = if (legacy.isBlank()) null else sealSecret(legacy)
        store.edit { prefs ->
            if (sealed != null && prefs[secretKey(KEY_API)] == null) prefs[secretKey(KEY_API)] = sealed
            prefs.remove(LEGACY_API_KEY)
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-6.1-terra"
        const val DEFAULT_FAST_MODE = true
        /** The lighter preset routines use when "루틴은 가벼운 모델로" is on. */
        const val ROUTINE_LIGHT_MODEL = "gpt-6-luna"
        /** The fast helper's model: quick, with a large quota. */
        const val HELPER_MODEL = ROUTINE_LIGHT_MODEL
        const val KEY_API = "api_key"
        /** Sign in with ChatGPT plan usage: the sign-in, each account's registration and this install's host id. */
        const val KEY_PLAN_TOKENS = "chatgpt_plan_tokens"
        const val KEY_PLAN_REGISTRATIONS = "chatgpt_plan_registrations"
        /** The one client id per install of earlier builds; becomes a registration when next read. */
        const val KEY_PLAN_CLIENT = "chatgpt_plan_client"
        const val KEY_PLAN_HOST = "chatgpt_plan_host"

        private val LEGACY_API_KEY = stringPreferencesKey("api_key")
        /** The older ChatGPT sign-in's tokens; see [dropRetiredSecrets]. */
        private val RETIRED_SECRETS = listOf(stringPreferencesKey("secret_chatgpt_tokens"))
        /** The usage limit's alert and fast-mode cut switches; see [dropRetiredPrefs]. */
        private val RETIRED_PREFS = listOf(booleanPreferencesKey("usage_alert_80"), booleanPreferencesKey("usage_auto_fast_off"))
        private val NOTIF_ASKED = booleanPreferencesKey("notifications_asked")
        private val PLAN_WELCOME = booleanPreferencesKey("plan_welcome_shown")
        private val PLAN_PAUSED_UNTIL = longPreferencesKey("plan_paused_until")
        private val AUTH_MODE = stringPreferencesKey("auth_mode")
        private val FAST_MODE = booleanPreferencesKey("fast_mode")
        private val REASONING = stringPreferencesKey("reasoning_effort")
        private val THEME = stringPreferencesKey("theme_mode")
        private val WEB_SEARCH = booleanPreferencesKey("web_search")
        private val ROUTINE_LIGHT = booleanPreferencesKey("routine_light_model")
        private val HELPER = booleanPreferencesKey("helper_model")
        private val MEMORY_ON = booleanPreferencesKey("memory_enabled")
        private val AUTO_REMEMBER = booleanPreferencesKey("auto_remember")
        private val CALL_NAME = stringPreferencesKey("call_name")
        private val POLITE = booleanPreferencesKey("polite")
        private val ANSWER_LENGTH = stringPreferencesKey("answer_length")
        private val ABOUT_ME = stringPreferencesKey("about_me")

        /** "대화 기록 보관" choices in days; 0 = forever. */
        val RETENTION_OPTIONS = listOf(30, 90, 365, 0)
        // 20:00, an hour before the DELIVERY nudge's 21:00 spending total, so the two never arrive together.
        const val DEFAULT_SUMMARY_MINUTE = 20 * 60
        const val DAY_MINUTES = 24 * 60

        private val RETENTION = intPreferencesKey("retention_days")
        private val CONFIRM_SEND = booleanPreferencesKey("confirm_send")
        private val RISK_JUDGE = booleanPreferencesKey("risk_judge")
        private val ASSIST_ACTION = stringPreferencesKey("assist_action")
        private val CONTROL_SPEED = stringPreferencesKey("control_speed")
        private val SUMMARY_MINUTE = intPreferencesKey("summary_minute")
        private val QUIET_ON = booleanPreferencesKey("quiet_on")
        private val QUIET_START = intPreferencesKey("quiet_start")
        private val QUIET_END = intPreferencesKey("quiet_end")
        private val FONT_SIZE = stringPreferencesKey("font_size")
        private val UNFOLDED = stringPreferencesKey("unfolded_layout")
        private val VOICE_LANG = stringPreferencesKey("voice_language")
        private val READ_ALOUD = booleanPreferencesKey("read_aloud")
        private val SCREEN_CONTENT = stringPreferencesKey("screen_content")

        private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

        private fun secretKey(name: String) = stringPreferencesKey("secret_$name")
    }
}
