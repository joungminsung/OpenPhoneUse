package com.phoneuse.app

import com.phoneuse.i18n.tr
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.phoneuse.agent.AgentEvent
import com.phoneuse.agent.AgentLoop
import com.phoneuse.agent.SteeringQueue
import com.phoneuse.agent.ToolExecutor
import com.phoneuse.BuildConfig
import com.phoneuse.agent.AgentPrompt
import com.phoneuse.agent.RoutineAccess
import com.phoneuse.routine.RoutineRun
import com.phoneuse.routine.RoutineSystem
import com.phoneuse.chat.ConversationSearch
import com.phoneuse.connectors.Connectors
import com.phoneuse.chat.ForgottenMemory
import com.phoneuse.chat.MemoryRef
import com.phoneuse.memory.AppTipStore
import com.phoneuse.memory.AppTips
import com.phoneuse.memory.MemoryRanker
import com.phoneuse.memory.MemoryStore
import com.phoneuse.cards.CardSpec
import com.phoneuse.cards.ResultsCard
import com.phoneuse.cards.SourceLink
import com.phoneuse.chat.ReplyExtras
import com.phoneuse.usage.RequestKind
import com.phoneuse.usage.UsageStore
import java.util.concurrent.atomic.AtomicInteger
import com.phoneuse.chat.Attachment
import com.phoneuse.chat.ChatRepository
import com.phoneuse.chat.HistoryBuilder
import com.phoneuse.chat.ImageBudget
import com.phoneuse.chat.MessageRole
import com.phoneuse.chat.StepLog
import com.phoneuse.files.AndroidExifReader
import com.phoneuse.files.AttachmentStore
import com.phoneuse.files.FileTools
import com.phoneuse.model.WebSearch
import com.phoneuse.model.ModelAuth
import com.phoneuse.model.ModelException
import com.phoneuse.phone.AndroidMessaging
import com.phoneuse.phone.AndroidPhone
import com.phoneuse.phone.RunTrigger
import com.phoneuse.model.ModelOptions
import com.phoneuse.model.OpenAiResponsesClient
import com.phoneuse.service.PhoneUseAccessibilityService
import com.phoneuse.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.cancelAndJoin
import com.phoneuse.notify.NeedsOk
import com.phoneuse.notify.Notifier
import com.phoneuse.notify.UpdateThrottle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a message came from. ASSIST (side-button sheet) looks at the screen first and shows the overlay from the start.
 * CARD is a result-card button: its text was not written by the user, so it never counts as user authorization to send.
 * ROUTINE is a routine that fired by itself: like CARD, it sends only after the confirm card unless the user turned
 * on "확인 없이 보내기" for that routine ([RoutineRun.trustSend]); it never pulls the user back into the app.
 */
enum class Source {
    APP, ASSIST, CARD, ROUTINE;

    /** Who started a run with [text], for writes confirmed on a card (ConfirmGate). */
    fun trigger(text: String, suggested: Boolean = false): RunTrigger = if (suggested) RunTrigger.Other else when (this) {
        APP -> RunTrigger.Typed
        CARD -> RunTrigger.CardButton(text)
        ASSIST, ROUTINE -> RunTrigger.Other
    }

    /** The attachments a message from this source may carry: none for a card button or a routine. */
    fun allowed(attachments: List<Attachment>): List<Attachment> = if (this == CARD || this == ROUTINE) emptyList() else attachments

    /**
     * Whether the user message itself carries text the user did not write: a routine quoting a notification or
     * an event, a side-button suggestion written from the screen, or attachments. The conversation is
     * flagged together with that message, so a run that ends early still leaves it tainted.
     */
    fun messageIsThirdParty(routine: RoutineRun?, suggested: Boolean, attachments: List<Attachment>): Boolean =
        routine?.thirdParty == true || suggested || allowed(attachments).isNotEmpty()

    /** Whether runs from this source get the conversation's history; a routine run starts from its prompt alone. */
    fun usesHistory(): Boolean = this != ROUTINE

    /** The history taint a run from this source carries, given the conversation's third-party flag. */
    fun historyTainted(conversationFlag: Boolean): Boolean = usesHistory() && conversationFlag

    /** Whether the model's user_authorized may skip the SEND confirmation in a run from this source. */
    fun trustsUserAuthorized(routine: RoutineRun?): Boolean = when (this) {
        CARD -> false
        ROUTINE -> routine?.trustSend == true
        APP, ASSIST -> true
    }
}

/**
 * Runs one message of a conversation at a time and stores the user message plus one assistant reply.
 * The overlay (and touch blocking) only comes up once the agent is about to touch the device, or from the
 * start for [Source.ASSIST]; once up, it is always cleaned up.
 */
class AgentRunner(
    private val context: Context,
    private val settings: SettingsStore,
    private val http: OkHttpClient,
    private val chats: ChatRepository,
    private val uiVisible: StateFlow<Boolean>,
    private val usage: UsageStore,
    private val memories: MemoryStore,
    private val tips: AppTipStore,
    private val conversationSearch: ConversationSearch,
    /** Connected MCP servers and Google; their tools are added to each run. */
    private val connectors: Connectors? = null,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "uncaught runner error", e) },
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runSlot = RunSlot()
    val running: StateFlow<Boolean> = runSlot.running

    private val _activeConversation = MutableStateFlow<Long?>(null)

    /** The conversation of the current run, or of the last run once it ended. */
    val activeConversation: StateFlow<Long?> = _activeConversation.asStateFlow()

    private val _streamingText = MutableStateFlow("")

    // The current turn's text as received; [streamingText] shows it without citation marks.
    private var rawStream = ""

    /** The current model turn's reply text while it streams; empty between turns and once the reply is saved. */
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _liveSteps = MutableStateFlow<List<StepLog>>(emptyList())

    /** The current run's step lines so far; cleared together with [streamingText] once the reply is saved. */
    val liveSteps: StateFlow<List<StepLog>> = _liveSteps.asStateFlow()

    private val _liveSearches = MutableStateFlow(0)

    /** Web searches the current run has made so far ("웹 n곳 확인"); reset with [streamingText]. */
    val liveSearches: StateFlow<Int> = _liveSearches.asStateFlow()

    private val _currentTask = MutableStateFlow("")

    /** The message of the current run, or of the last run once it ended (the home-screen task widget's title). */
    val currentTask: StateFlow<String> = _currentTask.asStateFlow()

    /** Settings "방해하지 않는 시간", kept current; result notifications inside it arrive silently. */
    @Volatile private var quietHours = QuietHours()

    private fun quietNow(): Boolean = quietHours.contains(java.time.LocalTime.now().let { it.hour * 60 + it.minute })

    /** Cards for the chat screen; requests go here until the overlay comes up. */
    val chatGate = ChatUserGate { _activeConversation.value }
    private val attachmentStore = AttachmentStore(context)

    /** Where the current (or last) run came from; a routine's questions are announced even with the app open. */
    @Volatile private var runSource: Source? = null

    /** The screen-on lock of a run that uses the device. */
    private val awake = ScreenAwake(context)

    init {
        scope.launch {
            try {
                settings.quietHours.collect { quietHours = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reading quiet hours failed", e)
            }
        }
        // A card left from a previous process can no longer be answered.
        guarded("needs-ok cleanup") { Notifier.cancelNeedsOk(context) }
        scope.launch { announceCards() }
        // The screen stays on from a run's first use of the device (engage) to the run's end; chat-only runs
        // never hold it.
        scope.launch { running.collect { if (!it) awake.release() } }
    }

    /**
     * A card waiting in the chat while the app is not in front (or any card of a routine run, which nobody may be
     * watching) becomes a "needs your OK" notification; it goes away once the card is answered or given up.
     */
    private suspend fun announceCards() {
        var shown: Long? = null
        combine(chatGate.card, uiVisible) { card, visible -> card to visible }.collect { (card, visible) ->
            try {
                if (card == null) {
                    if (shown != null) Notifier.cancelNeedsOk(context)
                    shown = null
                } else if (card.id != shown && (!visible || runSource == Source.ROUTINE)) {
                    val id = _activeConversation.value
                    val title = id?.let { c -> chats.conversations().first().firstOrNull { it.id == c }?.title }
                    if (Notifier.needsOk(context, card, id, title, quietNow())) shown = card.id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "needs-ok notification failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * A "needs your OK" notification's button: answers the waiting card through the same [ChatUserGate.answer] as
     * the card's own buttons, only when it is still the card the notification showed and the button may answer it
     * ([NeedsOk.answer]). False when nothing was answered.
     */
    fun answerFromNotification(key: String, button: NeedsOk.Button): Boolean {
        val card = chatGate.card.value ?: return false
        val answer = NeedsOk.answer(card, key, button) ?: return false
        chatGate.answer(card.id, answer)
        return true
    }

    @Volatile private var job: Job? = null
    @Volatile private var finishing = false
    @Volatile private var steering: SteeringQueue? = null
    @Volatile private var session: OverlaySession? = null
    @Volatile private var steps: MutableList<StepLog>? = null

    /**
     * Sends [text] to [conversationId], or to a new conversation titled with its first 30 characters when null.
     * Returns true only when a run was actually launched. [attachments] go with the user message (never for
     * [Source.CARD]). [suggested]: [text] is a side-button suggestion the model wrote from the screen, so it is
     * never taken as the user's authorization to send (like a card button).
     */
    fun send(
        conversationId: Long?,
        text: String,
        source: Source,
        attachments: List<Attachment> = emptyList(),
        routine: RoutineRun? = null,
        suggested: Boolean = false,
        /** No "already running" toast: a routine firing in the background defers its run by itself. */
        quiet: Boolean = source == Source.ROUTINE,
    ): Boolean {
        if (source == Source.ASSIST && PhoneUseAccessibilityService.instance?.overlay == null) {
            if (!quiet) toast(if (running.value) tr("Already running", "이미 실행 중이에요") else NO_SERVICE)
            return false
        }
        val files = source.allowed(attachments)
        var overlay: OverlaySession? = null
        // Atomic: the slot is taken and the job published together, and an older run's completion frees the slot
        // only for its own job, so it can never free (or reset) this run while it is starting.
        val launched = runSlot.claim {
            finishing = false
            runSource = source
            _currentTask.value = text.trim()
            val o = OverlaySession(onStop = { stop() }, onSteer = { steer(it) })
            overlay = o
            session = o
            // Lazy: the job is published before it can run, so stop() can never miss it.
            scope.launch(start = CoroutineStart.LAZY) { run(conversationId, text, source, o, files, routine, suggested) }
                .also { job = it }
        }
        if (launched == null) {
            if (!quiet) toast(tr("Already running", "이미 실행 중이에요"))
            return false
        }
        // A new run silences a reply still being read aloud (not a refused one, e.g. a routine firing while busy).
        ReadAloud.stop()
        val started = overlay!!
        // The run's end, or a job cancelled before its body ever ran: reset state, release the overlay, free the slot.
        launched.invokeOnCompletion {
            runSlot.release(launched) {
                started.releaseNow()
                rawStream = ""
                _streamingText.value = ""
                _liveSteps.value = emptyList()
                _liveSearches.value = 0
            }
        }
        launched.start()
        return true
    }

    fun stop() {
        job?.cancel()
    }

    /** Stops the run and waits until it has ended: its reply, memories and tips are written (or dropped) by then. */
    suspend fun stopAndWait() {
        job?.cancelAndJoin()
    }

    fun steer(text: String) {
        if (finishing) return
        val queue = steering ?: return
        queue.post(text)
        steps?.let { publishStep(it, tr("Me: $text", "나: $text")) }
        session?.addLine(tr("Me: $text", "나: $text"))
    }

    private suspend fun run(
        conversationId: Long?,
        text: String,
        source: Source,
        overlay: OverlaySession,
        attachments: List<Attachment> = emptyList(),
        routine: RoutineRun? = null,
        suggested: Boolean = false,
    ) {
        // The run's state lives in [RunState] and its body in [runBody]: the end below stays in a small method.
        // A Stop used to abort the process here (ART: 'divide by zero' with a pending JobCancellationException)
        // while this cleanup sat at the end of the very large body, so nothing was saved, the chat never came
        // back and the running-task notification stayed.
        val st = RunState()
        try {
            runBody(st, conversationId, text, source, overlay, attachments, routine, suggested)
        } finally {
            st.watcher?.cancel()
            withContext(NonCancellable) {
                // The run slot is freed by the job's completion (RunSlot, see send()), never here.
                endRun(
                    EndOfRun(
                        overlay, st.progress, st.conversation, st.reply, st.sources, st.searchCount(),
                        st.usedMemories, st.remembered.toList(), st.forgotten.toList(), st.runSteps.toList(), st.runCards.toList(),
                        st.runExecutor?.thirdPartySeen == true, st.cancelled, source, st.learned, st.tipsOn, st.modelCalls.get(),
                        st.deviceUsed, st.stayInApp, st.outcome, st.runTitle, routine, st.serviceLost.get(), st.failureCode,
                    ),
                )
            }
        }
    }

    private suspend fun runBody(
        st: RunState,
        conversationId: Long?,
        text: String,
        source: Source,
        overlay: OverlaySession,
        attachments: List<Attachment>,
        routine: RoutineRun?,
        suggested: Boolean,
    ) {
        st.runJob = currentCoroutineContext()[Job]
        // Read once: getters in this very large method trip a D8 register bug (VerifyError on device).
        val msg = RunMessages()

        // Shows the overlay; from then on, losing the accessibility service cancels the run as a failure.
        suspend fun engage(): Boolean {
            if (overlay.active) return true
            if (!overlay.engage(tr("Start: $text", "시작: $text"))) return false
            // A routine never turns the screen on by itself (it may fire at 3 a.m.); it only keeps a lit one on.
            awake.hold(wakeUp = source != Source.ROUTINE)
            st.watcher = scope.launch {
                PhoneUseAccessibilityService.connected.first { !it }
                st.serviceLost.set(true)
                st.runJob?.cancel()
            }
            return true
        }

        try {
            val firstId = conversationId ?: chats.createConversation(Attachment.title(text, attachments, TITLE_CHARS))
            st.conversation = firstId
            _activeConversation.value = firstId
            // One image budget per request: this message's photos first, then the newest earlier ones
            // (re-read from app storage).
            val budget = ImageBudget()
            val message = withContext(Dispatchers.IO) {
                HistoryBuilder.userItem(text, attachments, withContent = true, loadImage = budget.load(attachmentStore::loadImage))
            }
            // A routine run starts only from its saved prompt; earlier runs stay in the chat for display.
            val history = if (!source.usesHistory()) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) {
                    HistoryBuilder.build(chats.messages(firstId).first(), loadImage = budget.load(attachmentStore::loadImage))
                }
            }
            // A conversation deleted meanwhile (retention, another screen): the run moves to a new one.
            val id = storeUserMessage(firstId, text, attachments, source.messageIsThirdParty(routine, suggested, attachments))
            st.conversation = id
            if (source == Source.ASSIST && !engage()) {
                st.reply = msg.noService
                toast(msg.noService)
                return
            }

            val backend = resolveBackend()
            if (backend == null) {
                finishing = true
                st.reply = msg.notSignedIn
                overlay.update(AgentEvent.Failed(msg.notSignedIn))
                return
            }
            // Memory for this message: the prompt's "기억" block, and the chips of the ones that match it.
            val memoryOn = settings.memoryEnabled.first()
            val policy = RunMemoryPolicy.of(memoryOn, settings.autoRemember.first(), source)
            st.tipsOn = policy.appTips
            val chosen = if (policy.inject) {
                try {
                    // A secret saved before the memory filter existed never reaches the model.
                    MemoryRanker.top(text, memories.candidates(text).filter { com.phoneuse.memory.MemorySecrets.refusal(it.text) == null })
                } catch (e: Exception) {
                    Log.e(TAG, "reading memories failed", e)
                    emptyList()
                }
            } else {
                emptyList()
            }
            st.usedMemories = chosen.filter { it.score > 0 }.map { MemoryRef(it.memory.id, it.memory.text) }
            val personalization = settings.personalization.first()
            // The instructions stay the same from message to message (prompt cache); the matching memories
            // go in a developer message just before this one.
            // Built from settings only (not from where the message came from): a card run's instructions and
            // tools match a typed run's, and remember is refused there instead. Toggling memory, web search
            // or a connector failing still changes them, and costs that message its cache hit.
            val instructions = AgentPrompt.build(personalization, policy.inject, policy.offerRemember, settings.webSearch.first())
            val memoryNote = AgentPrompt.memoryNote(chosen, policy.inject)
            val title = chats.conversations().first().firstOrNull { it.id == id }?.title
            st.runTitle = title
            st.progress = watchProgress(id, title ?: text)
            val queue = SteeringQueue()
            // Tools of connected services; a connector that fails is skipped, never the whole run.
            val connectorTools = try {
                connectors?.forRun()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "connectors unavailable: ${e.javaClass.simpleName}")
                null
            }
            val options = ModelOptions(
                // Routines use the light model when "루틴은 가벼운 모델로" is on.
                if (source == Source.ROUTINE) settings.routineModel.first() else settings.model.first(),
                settings.fastMode.first(),
                settings.reasoningEffort.first().name.lowercase(),
                webSearch = settings.webSearch.first(),
                promptCacheKey = "phoneuse-conversation-$id",
                verbosity = personalization.length.verbosity,
            )
            val model = backend.client(options, http)
            // The fast helper (delegate): the provider's light model at low effort, unless the run already uses it
            // (a provider without a light model has no helper).
            val light = backend.lightModel
            val helperModel = if (settings.helperModel.first() && light != null && options.model != light) {
                backend.client(
                    options.copy(
                        model = light, effort = "low", webSearch = false,
                        promptCacheKey = options.promptCacheKey?.let { "$it-helper" }, verbosity = null,
                    ),
                    http,
                )
            } else {
                null
            }
            // photos_look's sub-call: same sign-in and usage, but no hosted web search (an image must not start one).
            val vision = backend.client(options.copy(webSearch = false, promptCacheKey = null), http)
            // An earlier run here read text the user did not write; the history may carry it.
            val historyTainted = source.historyTainted(
                source.usesHistory() && try {
                    chats.thirdPartySeen(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "reading the third-party flag failed", e)
                    true
                },
            )
            val loop = AgentLoop(
                model,
                ToolExecutor(
                    LiveDeviceController,
                    RoutingUserGate(overlay.gate, chatGate) { overlay.active }.let { gate ->
                        // Nobody may be watching a routine: say a question is waiting, and give up after a while.
                        if (source != Source.ROUTINE) {
                            gate
                        } else {
                            // The waiting card is announced by announceCards (with Approve / Deny where allowed).
                            TimedUserGate(gate, TimedUserGate.ROUTINE_TIMEOUT_MS)
                        }
                    },
                    if (Environment.isExternalStorageManager()) {
                        FileTools(Environment.getExternalStorageDirectory(), AndroidExifReader())
                    } else {
                        null
                    },
                    trustUserAuthorized = source.trustsUserAuthorized(routine) && !suggested,
                    memory = policy.access(memories, conversationSearch, id, title),
                    // A side-button suggestion's text was written from the screen: it never counts as typed.
                    phone = AndroidPhone.access(context, http, vision, source.trigger(text, suggested)) {
                        chats.messages(id).first().flatMap { it.cards }
                    },
                    messaging = AndroidMessaging.access(context),
                    connectors = connectorTools,
                    routines = routineAccess(),
                    historyTainted = historyTainted,
                    // The switch never widens a routine run: nobody wrote this request just now.
                    confirmSend = settings.confirmSend.first() || source == Source.ROUTINE,
                    screenshots = settings.screenContent.first() == ScreenContent.ALL,
                    fixedSend = routine?.fixedSend,
                    appContext = context,
                    inlineRisk = settings.riskJudge.first(),
                ).also {
                    // A suggestion's prompt came from screen text, so the run is third-party from its first step;
                    // so is a run with MCP tools, whose descriptions the server wrote (and may change any time).
                    if (routine?.thirdParty == true || suggested || hasMcpTools(connectorTools)) it.markThirdParty()
                    st.runExecutor = it
                },
                queue,
                instructions = instructions,
                onTimings = { Log.i(TAG, "timing ${it.format()}") },
                helperModel = helperModel,
                appTips = if (policy.appTips) {
                    { pkg -> AppTips.note(pkg, tips.top(pkg, text, TIPS_PER_APP)) }
                } else {
                    null
                },
            )
            steps = st.runSteps
            steering = queue
            loop.run(history, message.text, observeFirst = source == Source.ASSIST, images = message.images, attached = attachments.isNotEmpty(), note = memoryNote).collect { event ->
                when (event) {
                    AgentEvent.Thinking -> {
                        st.modelCalls.incrementAndGet()
                        st.earlierSearches += st.turnSearches.size
                        st.turnSearches.clear()
                        rawStream = ""
                        _streamingText.value = ""
                    }
                    AgentEvent.TextReset -> {
                        st.turnSearches.clear()
                        _liveSearches.value = st.searchCount()
                        rawStream = ""
                        _streamingText.value = ""
                    }
                    is AgentEvent.TextDelta -> {
                        rawStream += event.text
                        _streamingText.value = WebSearch.stripMarks(rawStream)
                    }
                    // Handled before the loop touches the device, so touches are blocked in time.
                    AgentEvent.DeviceUse -> {
                        st.deviceUsed = true
                        if (!engage()) throw NoServiceException()
                    }
                    is AgentEvent.Acting -> publishStep(st.runSteps, event.description)
                    // This text was also streamed as deltas; it is kept once, as a step.
                    is AgentEvent.Log -> publishStep(st.runSteps, event.text)
                    is AgentEvent.Card -> st.runCards += event.card
                    is AgentEvent.Remembered -> st.remembered += event.memory
                    is AgentEvent.Forgot -> st.forgotten += event.memory
                    is AgentEvent.DeviceTaskDone -> st.learned = event
                    is AgentEvent.Searching -> {
                        st.turnSearches += event.id ?: "#${st.turnSearches.size}"
                        _liveSearches.value = st.searchCount()
                    }
                    is AgentEvent.Sources -> synchronized(st.sources) {
                        for (s in event.sources) st.sources.putIfAbsent(s.url, SourceLink(s.title, s.url))
                    }
                    is AgentEvent.Usage ->
                        Log.i(TAG, "step=${event.step} in=${event.inputTokens} cached=${event.cachedTokens} out=${event.outputTokens} ms=${event.millis}")
                    is AgentEvent.Finished -> {
                        finishing = true
                        st.reply = WebSearch.stripMarks(event.summary)
                        st.outcome = RunOutcome.FINISHED
                        st.stayInApp = event.stayInApp
                    }
                    is AgentEvent.Failed -> {
                        finishing = true
                        st.reply = event.message
                        st.failureCode = event.code
                    }
                    AgentEvent.Cancelled -> Unit
                }
                overlay.update(event)
                flagThirdPartyEarly(st.runExecutor, st.conversation, st.flaggedThirdParty)
            }
        } catch (e: NoServiceException) {
            finishing = true
            st.reply = msg.noService
        } catch (e: CancellationException) {
            st.cancelled = true
            finishing = true
            val lost = st.serviceLost.get()
            st.reply = if (lost) msg.serviceLost else msg.cancelled
            st.outcome = if (lost) RunOutcome.SERVICE_LOST else RunOutcome.CANCELLED
            overlay.update(if (lost) AgentEvent.Failed(msg.serviceLost) else AgentEvent.Cancelled)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "agent run failed", e)
            finishing = true
            st.reply = msg.error
            overlay.update(AgentEvent.Failed(msg.error))
        }
    }

    private fun hasMcpTools(tools: com.phoneuse.agent.ConnectorTools?): Boolean =
        tools?.specs?.any { com.phoneuse.mcp.McpRegistry.isMcpTool(it.name) } == true

    /**
     * The conversation's third-party flag, written as soon as the run has read text the user did not write (not
     * only at the end): a process death mid-run must still leave the next run history-tainted.
     */
    private fun flagThirdPartyEarly(executor: ToolExecutor?, conversationId: Long?, done: AtomicBoolean) {
        if (conversationId == null || executor?.thirdPartySeen != true || !done.compareAndSet(false, true)) return
        scope.launch {
            try {
                chats.markThirdPartySeen(conversationId)
            } catch (e: Exception) {
                Log.e(TAG, "saving the third-party flag failed", e)
            }
        }
    }

    /** Adds a step line and publishes the list; the run thread and steer() both go through the list's lock. */
    private fun publishStep(list: MutableList<StepLog>, line: String) {
        synchronized(list) {
            list += StepLog(line)
            _liveSteps.value = list.toList()
        }
    }

    /** Stores the run's user message; a conversation gone meanwhile is replaced by a new one, which the run follows. */
    private suspend fun storeUserMessage(conversationId: Long, text: String, attachments: List<Attachment>, thirdParty: Boolean): Long {
        val stored = ConversationStart.storeUserMessage(
            chats, conversationId, Attachment.title(text, attachments, TITLE_CHARS), text, attachments, thirdParty,
        )
        if (stored.movedToNew) {
            Log.w(TAG, "conversation gone; the run continues in a new one")
            _activeConversation.value = stored.conversationId
        }
        return stored.conversationId
    }

    /** One run's state, filled in by [runBody] and read by run()'s end; fields, not locals of the very large body. */
    private class RunState {
        @Volatile var cancelled = false
        @Volatile var conversation: Long? = null
        // The assistant reply stored when the run ends; null stores nothing.
        @Volatile var reply: String? = null
        val runSteps: MutableList<StepLog> = Collections.synchronizedList(ArrayList())
        // Cards shown during this run; they are stored with its reply, in order.
        val runCards: MutableList<CardSpec> = Collections.synchronizedList(ArrayList())
        val serviceLost = AtomicBoolean(false)
        // Web use of this run: searches made and cited sources (first title per URL).
        // Searches are counted by item id; the current turn's are dropped when its model call is retried.
        @Volatile var earlierSearches = 0
        val turnSearches = HashSet<String>()
        fun searchCount() = earlierSearches + turnSearches.size
        val sources = LinkedHashMap<String, SourceLink>()
        val modelCalls = AtomicInteger(0)
        @Volatile var deviceUsed = false
        // How the run ended and finish's stay_in_app; they decide whether the chat comes back (ReturnPolicy).
        @Volatile var outcome = RunOutcome.FAILED
        @Volatile var failureCode: String? = null
        @Volatile var stayInApp = false
        @Volatile var runJob: Job? = null
        @Volatile var watcher: Job? = null
        // The running-task notification, and the conversation's title for it and the result's.
        @Volatile var progress: Job? = null
        @Volatile var runTitle: String? = null
        // Memories the prompt matched (chips above the reply), memories saved, and a device task to keep as a tip.
        @Volatile var usedMemories: List<MemoryRef> = emptyList()
        val remembered: MutableList<MemoryRef> = Collections.synchronizedList(ArrayList())
        val forgotten: MutableList<ForgottenMemory> = Collections.synchronizedList(ArrayList())
        @Volatile var learned: AgentEvent.DeviceTaskDone? = null
        // This run's executor: whether it read third-party text decides the conversation's flag.
        @Volatile var runExecutor: ToolExecutor? = null
        val flaggedThirdParty = AtomicBoolean(false)
        @Volatile var tipsOn = false
    }

    /** What a run leaves for [endRun]; kept out of run() itself, whose size already trips the verifier on device. */
    private class EndOfRun(
        val overlay: OverlaySession,
        val progress: Job?,
        val conversation: Long?,
        val reply: String?,
        val sources: LinkedHashMap<String, SourceLink>,
        val searches: Int,
        val usedMemories: List<MemoryRef>,
        val remembered: List<MemoryRef>,
        val forgotten: List<ForgottenMemory>,
        val runSteps: List<StepLog>,
        val runCards: List<CardSpec>,
        val thirdPartySeen: Boolean,
        val cancelled: Boolean,
        val source: Source,
        val learned: AgentEvent.DeviceTaskDone?,
        val tipsOn: Boolean,
        val modelCalls: Int,
        val deviceUsed: Boolean,
        val stayInApp: Boolean,
        val outcome: RunOutcome,
        val runTitle: String?,
        val routine: RoutineRun?,
        val serviceLost: Boolean,
        val failureCode: String?,
    )

    /** The end of a run: touches back, the reply stored, usage and tips recorded, and the user told. */
    private suspend fun endRun(e: EndOfRun) {
        steering = null
        steps = null
        // Touches come back first, before any storage work.
        e.overlay.releaseTouches()
        endProgress(e.progress)
        val id = e.conversation
        val answer = e.reply
        // Not on the "Stopped" reply of a Stop that came just after the failure.
        val usageLimit = !e.cancelled && e.failureCode == ModelException.PLAN_USAGE_LIMIT
        if (e.outcome == RunOutcome.FINISHED) liftPlanPause()
        if (id != null && answer != null) {
            try {
                val cited = synchronized(e.sources) { e.sources.values.toList() }
                val extras = ReplyExtras(
                    e.searches, cited, usedMemories = e.usedMemories, remembered = e.remembered, forgotten = e.forgotten,
                    planUsageLimit = usageLimit,
                )
                // A run that read third-party text flags the conversation in the same write as its reply.
                chats.addMessage(
                    id, MessageRole.ASSISTANT, answer, e.runSteps, withSources(e.runCards, cited), extras,
                    thirdParty = e.thirdPartySeen,
                )
            } catch (x: Exception) {
                Log.e(TAG, "saving the reply failed", x)
            }
            // Settings "답 소리 내어 읽기": the finished reply, never a routine's (nobody may be listening).
            if (!e.cancelled && e.source != Source.ROUTINE) {
                guarded("read aloud") {
                    if (settings.readAloud.first()) ReadAloud.speak(context, answer, settings.voiceLanguage.first().tag)
                }
            }
        }
        // Also when no reply was stored (or storing it failed): the next run must start tainted.
        if (id != null && e.thirdPartySeen) {
            try {
                chats.markThirdPartySeen(id)
            } catch (x: Exception) {
                Log.e(TAG, "saving the third-party flag failed", x)
            }
        }
        // Only in runs that keep tips (memory on); the task is finish's summary, never the message.
        e.learned?.let { tip ->
            val task = AppTips.task(tip.summary)
            if (e.tipsOn && answer != null && !e.cancelled && task != null && tip.actions.isNotEmpty()) {
                guarded("app tip") { tips.add(tip.packageName, task, AppTips.steps(tip.actions)) }
            }
        }
        val calls = e.modelCalls
        if (calls > 0) {
            val kind = when {
                e.source == Source.ROUTINE -> RequestKind.ROUTINE
                e.deviceUsed -> RequestKind.DEVICE
                e.searches > 0 -> RequestKind.SEARCH
                else -> RequestKind.CHAT
            }
            guarded("usage log") { usage.record(kind, count = calls) }
        }
        rawStream = ""
        _streamingText.value = ""
        _liveSteps.value = emptyList()
        _liveSearches.value = 0
        // A run from the chat that operated another app brings the chat back, showing the reply.
        val usedDevice = e.deviceUsed && !uiVisible.value
        var returned = false
        if (id != null && ReturnPolicy.shouldReturn(e.source, usedDevice, e.stayInApp, e.outcome)) {
            val launchedAt = SystemClock.elapsedRealtime()
            guarded("return to chat") {
                MainActivity.open(PhoneUseAccessibilityService.instance ?: context, id)
                returned = true
            }
            // Fallback for a launch the system blocked: the chat never resumed after it, so notify.
            if (returned) {
                val text = answer ?: ReturnPolicy.fallbackText(null)
                scope.launch {
                    var waited = 0L
                    for (check in ReturnPolicy.FALLBACK_CHECKS_MS) {
                        delay(check - waited)
                        waited = check
                        if (!ReturnPolicy.needsFallback(launchedAt, MainActivity.lastResumedAt)) return@launch
                    }
                    notifyResult(id, e.runTitle, text, usageLimit)
                }
            }
        }
        // Stop (or going back to the chat) hides at once; other endings leave the last line up briefly.
        val last = e.overlay.release(if (e.cancelled || returned) 0 else RESULT_LINGER_MS)
        // The whole reply, not the overlay's last line: the notification shows its first line and expands.
        val summary = answer ?: last
        if (e.source == Source.ROUTINE) {
            // Routine results always come as a notification that opens the routine's conversation.
            if (summary != null) notifyRoutineResult(id, e.routine, summary, usageLimit)
            guarded("routine result") { e.routine?.onDone?.invoke(if (e.cancelled) null else answer) }
        } else if (summary != null && !returned && !uiVisible.value && !(e.cancelled && !e.serviceLost)) {
            // Not after the user's own Stop: they know, and a "Stopped" notification is noise.
            notifyResult(id, e.runTitle, summary, usageLimit)
        }
    }

    /** A run on the plan went through, so the limit isn't in the way: automatic requests may go again ([PlanPause]). */
    private suspend fun liftPlanPause() {
        guarded("plan pause") { if (settings.authMode.first() == AuthMode.CHATGPT) settings.setPlanPausedUntil(0) }
    }

    /** Results cards without their own sources show the reply's citations. */
    private fun withSources(cards: List<CardSpec>, cited: List<SourceLink>): List<CardSpec> {
        if (cited.isEmpty()) return cards
        return cards.map { if (it is ResultsCard && it.sources.isEmpty()) it.copy(sources = cited) else it }
    }

    /**
     * The one ChatGPT sign-in of the process: runs, suggestions, proactive checks and widget briefs all refresh
     * through it, so a rotated refresh token is never spent twice.
     */
    private val planAuth by lazy {
        com.phoneuse.auth.PlanAuth(com.phoneuse.auth.SettingsPlanStore(settings), com.phoneuse.auth.PlanTokenClient(http), usageLimitHit = {
            settings.setPlanPausedUntil(PlanPause.until(System.currentTimeMillis()))
        })
    }

    /** The provider and sign-in the settings choose, or null when it has nothing configured (signed out, no key). */
    internal suspend fun resolveBackend(): ModelBackend? = settings.chosenBackend(planAuth)

    /** Whether automatic requests stay off the server for now, after the plan's usage limit. */
    internal suspend fun planPaused(): Boolean =
        PlanPause.holds(settings.authMode.first() == AuthMode.CHATGPT, settings.planPausedUntil.first(), System.currentTimeMillis())

    /** [resolveBackend] for what nobody asked for just now (side-button suggestions, briefs, proactive checks): also null during [planPaused]. */
    internal suspend fun automaticBackend(): ModelBackend? = if (planPaused()) null else resolveBackend()

    /** Ends collection when the device is needed but the accessibility service is off. */
    /** The run's user-facing failure texts, in the app language at the start of the run. */
    private class RunMessages(
        val serviceLost: String = SERVICE_LOST,
        val noService: String = NO_SERVICE,
        val notSignedIn: String = NOT_SIGNED_IN,
        val cancelled: String = CANCELLED,
        val error: String = ERROR,
    )

    private class NoServiceException : CancellationException("accessibility service not connected")

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "cleanup step failed: $what", e)
        }
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private fun routineTitle(routine: RoutineRun?): String? = routine?.let { tr("Routine · ${it.title}", "루틴 · ${it.title}") }

    /** make_routine: place names from memories or a geocode; Play services decides whether places work at all. */
    private fun routineAccess(): RoutineAccess {
        val system = RoutineSystem.get(context)
        return RoutineAccess(
            places = AndroidPhone.geocoder(http),
            memories = memories,
            permissionFor = { system.missingPermission(it) },
            placesSupported = system.geofences.available(),
        )
    }

    /**
     * The running-task notification while the app is not in front: the latest progress line (the overlay's), the
     * step count and Stop. Throttled ([UpdateThrottle]): never for a quick answer, at most once per
     * [PROGRESS_EVERY_MS], and taken away while the app is in front; the run's end cancels it.
     */
    private fun watchProgress(conversationId: Long, title: String): Job = scope.launch {
        val throttle = UpdateThrottle(SystemClock.elapsedRealtime(), PROGRESS_FIRST_MS, PROGRESS_EVERY_MS)
        val startedAt = System.currentTimeMillis()
        val writing = _streamingText.map { it.isNotEmpty() }.distinctUntilChanged()
        combine(_liveSteps, writing, uiVisible) { steps, typing, visible ->
            if (visible) {
                null
            } else {
                val line = steps.lastOrNull()?.text ?: if (typing) tr("Writing the answer…", "답을 쓰는 중…") else tr("Thinking…", "생각하는 중…")
                ProgressLine(line, steps.size)
            }
        }.collectLatest { line ->
            try {
                if (line == null) {
                    Notifier.cancelProgress(context)
                    throttle.cleared()
                    return@collectLatest
                }
                val wait = throttle.waitFor(line, SystemClock.elapsedRealtime()) ?: return@collectLatest
                delay(wait)
                if (Notifier.progress(context, conversationId, title, line.text, line.steps, startedAt)) {
                    throttle.posted(line, SystemClock.elapsedRealtime())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "progress notification failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private data class ProgressLine(val text: String, val steps: Int)

    // Kept out of run(): that method is at the size where more code trips D8 (see RunMessages).

    /** Stops the running-task notification's updates, then takes it away. */
    private suspend fun endProgress(progress: Job?) {
        progress?.cancelAndJoin()
        guarded("progress notification") { Notifier.cancelProgress(context) }
    }

    private fun notifyResult(conversationId: Long?, title: String?, answer: String, usageLimit: Boolean) {
        guarded("notify") { Notifier.result(context, conversationId, title, answer, quietNow(), planUsageLimit = usageLimit) }
    }

    /** A routine's result has its own id per routine, so it never replaces an ordinary result (or another routine's). */
    private fun notifyRoutineResult(conversationId: Long?, routine: RoutineRun?, answer: String, usageLimit: Boolean) {
        guarded("notify") {
            Notifier.result(
                context, conversationId, routineTitle(routine), answer, quietNow(),
                Notifier.TAG_ROUTINE_RESULT, routine?.routineId?.toInt() ?: conversationId?.toInt() ?: 0,
                planUsageLimit = usageLimit,
            )
        }
    }

    private companion object {
        const val TAG = "PhoneUse"
        const val PROGRESS_FIRST_MS = 2_500L
        const val PROGRESS_EVERY_MS = 1_000L
        const val RESULT_LINGER_MS = 2000L
        const val TITLE_CHARS = 30
        const val TIPS_PER_APP = 3
        val SERVICE_LOST: String get() = tr("Accessibility service was turned off", "접근성 서비스가 꺼졌어요")
        val NO_SERVICE: String get() = tr("Please turn on the accessibility service", "접근성 서비스를 켜주세요")
        val NOT_SIGNED_IN: String get() = tr("Sign in or add an API key in Settings", "설정에서 로그인하거나 API 키를 넣어 주세요")
        val CANCELLED: String get() = tr("Stopped", "중단됨")
        val ERROR: String get() = tr("Something went wrong", "오류가 발생했어요")
    }
}
