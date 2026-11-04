package com.phoneuse.app

import android.content.Context
import com.phoneuse.chat.ChatRepository
import com.phoneuse.chat.Retention
import com.phoneuse.service.SettleSpeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import com.phoneuse.chat.SqliteChatRepository
import com.phoneuse.chat.SqliteConversationSearch
import com.phoneuse.connectors.ConnectorStore
import com.phoneuse.connectors.Connectors
import com.phoneuse.files.AttachmentStore
import com.phoneuse.memory.SqliteMemoryStore
import com.phoneuse.usage.UsageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Process-wide singletons, initialised once from PhoneUseApp.onCreate. */
object AppGraph {
    lateinit var settings: SettingsStore
        private set
    lateinit var http: OkHttpClient
        private set
    lateinit var chats: ChatRepository
        private set
    lateinit var runner: AgentRunner
        private set
    lateinit var usage: UsageStore
        private set

    /** MCP servers and the Google connector; settings sealed in SettingsStore's encrypted secrets. */
    lateinit var connectors: Connectors
        private set

    /** User memories and app tips, in chat.db. */
    lateinit var memories: SqliteMemoryStore
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The chosen theme, read off the main thread at process start; null until the first read finishes.
     * The activity holds its first frame until it is known, and the overlay draws with it from the start.
     */
    lateinit var themeMode: StateFlow<ThemeMode?>
        private set

    /** The chosen text size, read like [themeMode]: null until known, so the first frame never jumps size. */
    lateinit var fontSize: StateFlow<FontSize?>
        private set

    /** True while MainActivity is started; the runner only posts its result notification when false. */
    val uiVisible = MutableStateFlow(false)

    fun init(context: Context) {
        val app = context.applicationContext
        settings = SettingsStore(app)
        // Runs at every start and does nothing once they are gone; a failure just tries again next start.
        scope.launch { runCatching { settings.dropRetiredSecrets() } }
        scope.launch { runCatching { settings.dropRetiredPrefs() } }
        // A failed read falls back to the system theme, and so does a read that takes too long: the activity holds
        // its first frame until this is known, so it must never stay null.
        val theme = MutableStateFlow<ThemeMode?>(null)
        themeMode = theme
        scope.launch {
            settings.themeMode
                .catch { e ->
                    android.util.Log.w("AppGraph", "reading the theme failed", e)
                    emit(ThemeMode.SYSTEM)
                }
                .collect { theme.value = it }
        }
        val size = MutableStateFlow<FontSize?>(null)
        fontSize = size
        scope.launch {
            settings.fontSize.catch { emit(FontSize.DEFAULT) }.collect { size.value = it }
        }
        scope.launch {
            delay(THEME_WAIT_MS)
            theme.compareAndSet(null, ThemeMode.SYSTEM)
            size.compareAndSet(null, FontSize.DEFAULT)
        }
        voiceLanguage = settings.voiceLanguage.catch { emit(VoiceLanguage.KOREAN) }
            .stateIn(scope, SharingStarted.Eagerly, VoiceLanguage.KOREAN)
        // Settings "조작 속도": every settle of the agent reads it.
        scope.launch {
            settings.controlSpeed.catch { emit(ControlSpeed.NORMAL) }.collect { SettleSpeed.scale = it.waitScale }
        }
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
        val sqliteChats = SqliteChatRepository(app)
        chats = sqliteChats
        // Attachment images no message refers to (lost drafts, failed starts) are removed once a day old.
        scope.launch {
            try {
                AttachmentStore(app).sweep(sqliteChats.attachmentFiles())
            } catch (e: Exception) {
                android.util.Log.w("AppGraph", "attachment sweep failed", e)
            }
        }
        // Notification history past its 7 days goes even when no notification arrives (access revoked).
        scope.launch {
            try {
                com.phoneuse.phone.NotificationStore.get(app).prune()
            } catch (e: Exception) {
                android.util.Log.w("AppGraph", "notification prune failed", e)
            }
        }
        usage = UsageStore(app)
        memories = SqliteMemoryStore(app)
        connectors = Connectors(
            http,
            ConnectorStore(read = { settings.readSecret(it) }, write = { k, v -> settings.writeSecret(k, v) }),
            log = { android.util.Log.w("Connectors", it) },
        )
        runner = AgentRunner(app, settings, http, chats, uiVisible, usage, memories, memories, SqliteConversationSearch(app), connectors)
        // A run the previous process died in left its question unanswered: say so in that conversation.
        val startedAt = System.currentTimeMillis()
        scope.launch {
            try {
                ConversationStart.markInterrupted(
                    sqliteChats, startedAt,
                    com.phoneuse.i18n.tr(
                        "This request was interrupted (the app stopped) and may not have finished.",
                        "앱이 멈춰서 이 요청이 중간에 끊겼어요. 끝나지 않았을 수 있어요.",
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AppGraph", "marking interrupted runs failed", e)
            }
        }
        // Routines: alarms, geofences and deferred runs; needs the runner.
        com.phoneuse.routine.RoutineSystem.get(app)
        // Home-screen widgets follow the runner, routines, tiles and memories from here.
        com.phoneuse.widget.WidgetSync.start(app)
        // "알아서 챙기기": the 15-minute check starts here (and again after a reboot, which starts the process).
        com.phoneuse.proactive.ProactiveSystem.get(app)
        // Settings "대화 기록 보관": prune now and then once a day while the process lives, and again on a change.
        scope.launch {
            settings.retentionDays.catch { emit(0) }.distinctUntilChanged().collectLatest { days ->
                while (true) {
                    pruneConversations(app, days)
                    delay(DAY_MS)
                }
            }
        }
        // Settings "오늘 요약 받기": (re)set the daily alarm at every start and on every change.
        scope.launch {
            settings.summaryMinute.catch { emit(-1) }.distinctUntilChanged().collect { minute ->
                try {
                    DailySummary.schedule(app, minute)
                } catch (e: Exception) {
                    android.util.Log.w("AppGraph", "scheduling the daily summary failed", e)
                }
            }
        }
    }

    /** Settings "음성으로 말하기", read at once by the voice input buttons. */
    lateinit var voiceLanguage: StateFlow<VoiceLanguage>
        private set

    private const val THEME_WAIT_MS = 1_500L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** The conversation the chat screen shows (null: none); retention never deletes it from under the user. */
    @Volatile var shownConversation: Long? = null

    private fun keptConversations(): Set<Long> =
        setOfNotNull(runner.activeConversation.value.takeIf { runner.running.value }, shownConversation)

    /**
     * Settings "모든 데이터 지우기": stops any run and waits for it to end (so it cannot write memories, routines or
     * usage after the wipe), then wipes. Runs in this process scope, so leaving the screen never cuts it short.
     */
    suspend fun wipeAll(context: Context): List<String> {
        val app = context.applicationContext
        return scope.async {
            runner.stopAndWait()
            FullWipe.run(app)
        }.await()
    }

    /** How many conversations a retention of [days] would delete now (Settings confirms a shorter period). */
    suspend fun expiredConversations(days: Int): Int =
        Retention.expired(chats, days, System.currentTimeMillis(), keptConversations()).size

    /** Deletes conversations past the retention, never the one a run is using. */
    private suspend fun pruneConversations(app: Context, days: Int) {
        try {
            val pruned = Retention.prune(chats, days, System.currentTimeMillis(), keptConversations())
            if (pruned.isNotEmpty()) com.phoneuse.proactive.ProactiveSystem.get(app).conversationsGone(pruned)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AppGraph", "pruning conversations failed", e)
        }
    }
}
