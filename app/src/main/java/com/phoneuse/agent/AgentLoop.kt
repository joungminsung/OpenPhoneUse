package com.phoneuse.agent

import com.phoneuse.BuildConfig
import com.phoneuse.i18n.tr
import com.phoneuse.i18n.Lang
import com.phoneuse.cards.CardSpec
import com.phoneuse.chat.ForgottenMemory
import com.phoneuse.chat.MemoryRef
import com.phoneuse.model.Citation
import com.phoneuse.model.InputItem
import com.phoneuse.model.ModelClient
import com.phoneuse.model.ModelException
import com.phoneuse.model.ModelTurn
import com.phoneuse.model.WebSearchCall
import com.phoneuse.screen.CompressedScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

sealed interface AgentEvent {
    data object Thinking : AgentEvent
    data class Acting(val description: String) : AgentEvent
    /**
     * The text of a turn that also made tool calls. That text has already been streamed as [TextDelta]s, so
     * consumers must not render both.
     */
    data class Log(val text: String) : AgentEvent

    /**
     * A streamed fragment of the model's reply text. When a turn has both text and tool calls, the text arrives
     * as deltas AND again as [Log]; consumers must not render both.
     */
    data class TextDelta(val text: String) : AgentEvent

    /**
     * The model call is being retried: discard the [TextDelta]s and [Searching]s received since the last
     * [Thinking] or reset.
     */
    data object TextReset : AgentEvent

    /** Sent once per run, just before the first tool that acts on or reads the phone screen. */
    data object DeviceUse : AgentEvent
    /** A result card from show_card; it belongs to this run's reply. */
    data class Card(val card: CardSpec) : AgentEvent

    /**
     * The model ran one hosted web search; [queries] may be empty (e.g. it opened a page). [id] is the
     * response item id when known; count searches by it.
     */
    data class Searching(val queries: List<String>, val id: String? = null) : AgentEvent

    /** Web sources a turn's text cites, sent right after that turn's text; they belong to this run's reply. */
    data class Sources(val sources: List<Citation>) : AgentEvent

    /** remember saved a memory; the reply shows it with an undo button. */
    data class Remembered(val memory: MemoryRef) : AgentEvent

    /** forget removed a memory; the reply shows it with a button that puts it back. */
    data class Forgot(val memory: ForgottenMemory) : AgentEvent

    /**
     * Sent just before [Finished] when a device task ended with a successful finish, only in runs that keep app
     * tips (an [AgentLoop] with appTips): the app it mostly worked in, the steps taken there (never typed text)
     * and finish's [summary].
     */
    data class DeviceTaskDone(val packageName: String, val actions: List<String>, val summary: String = "") : AgentEvent
    data class Usage(val step: Int, val inputTokens: Int, val outputTokens: Int, val millis: Long, val cachedTokens: Int = 0) : AgentEvent
    /** [stayInApp]: the model's finish said the result is the other app on screen (see ReturnPolicy). */
    data class Finished(val summary: String, val stayInApp: Boolean = false) : AgentEvent
    /** [code]: the model error's code, when a model call is what failed. */
    data class Failed(val message: String, val code: String? = null) : AgentEvent
    data object Cancelled : AgentEvent
}

/** Thread-safe mailbox for mid-task user instructions. */
class SteeringQueue {
    private val queue = ConcurrentLinkedQueue<String>()

    fun post(text: String) {
        queue.add(text)
    }

    fun drain(): List<String> {
        val out = ArrayList<String>()
        while (true) out += queue.poll() ?: break
        return out
    }

    /** Whether an instruction is waiting (it stays queued). */
    fun pending(): Boolean = queue.isNotEmpty()
}

/**
 * [instructions] is this run's system prompt (personalization included, see [AgentPrompt.build]); it and the tools
 * stay the same for every step so the prompt cache hits, and per-message context goes in [run]'s note instead.
 * [appTips] returns a note of earlier successful ways to work in an app, or null; it is asked once per app, the
 * first time the run sees that app's screen, and the note is added to the input right after.
 */
class AgentLoop(
    private val model: ModelClient,
    private val executor: ToolExecutor,
    private val steering: SteeringQueue,
    private val maxSteps: Int = 150,
    private val instructions: String = AgentPrompt.INSTRUCTIONS,
    private val appTips: (suspend (packageName: String) -> String?)? = null,
    /** Receives each step's [StepTimings] (logged to logcat by the app); null = not measured. */
    private val onTimings: ((StepTimings) -> Unit)? = null,
    /** The fast helper model behind `delegate`; null = the tool is not offered and the main model does everything. */
    helperModel: ModelClient? = null,
    /**
     * Input size (characters of text and reasoning) above which older parts of the run are cut down (see
     * [compact]); about a third to half of the model's window, so a long task never reaches the limit.
     */
    private val inputBudgetChars: Int = INPUT_BUDGET_CHARS,
    /** Jitter for retry waits (tests pass a seeded one). */
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) {
    private val helper = helperModel?.let { Helper(it, executor) }

    /**
     * The tools offered to the main model: delegate whenever there is a helper. It stays listed after the helper
     * fails (it then answers with an error), so the tool list, and with it the prompt cache, never changes mid-run.
     */
    private val tools = executor.specs.filter { it.name != "delegate" || helper != null }

    /**
     * Runs one user [message] on top of the earlier conversation [history]. With [observeFirst] the current
     * screen is added up front (side-button sheet); otherwise the model starts without one and the screen is
     * only read when a tool needs it. [images] are the photos attached to [message]; they go with the first
     * model call only. [attached] says the message carries attachments (photos or file text): content the user
     * did not write, so the run is marked as having seen third-party text (remember then saves only a guess).
     * [note] is per-message app context (the matching memories), sent as a developer message just before
     * [message] so the instructions stay the same and the prompt cache hits.
     *
     * Events are emitted synchronously: the collector has handled one (e.g. [AgentEvent.DeviceUse]) before the
     * loop moves on. Cancelling the collector cancels any in-flight model call or tool; the flow ends with
     * CancellationException.
     */
    fun run(
        history: List<InputItem>,
        message: String,
        observeFirst: Boolean,
        images: List<InputItem.UserImage> = emptyList(),
        attached: Boolean = images.isNotEmpty(),
        note: String? = null,
    ): Flow<AgentEvent> = flow {
        try {
            if (attached) executor.markThirdParty()
            // The user's own words, which a send's user_authorized is checked against once the run read other text.
            // A message with attachments may carry file text the user did not write, so it does not count.
            if (!attached) executor.userSaid(message)
            loop(history, message, observeFirst, images, note)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServiceUnavailableException) {
            emit(AgentEvent.Failed(tr("Accessibility service was turned off", "접근성 서비스가 꺼졌어요")))
        } catch (e: Fail) {
            emit(AgentEvent.Failed(e.message, e.code))
        } catch (e: Exception) {
            emit(AgentEvent.Failed(tr("Error: ${e.message}", "오류: ${e.message}")))
        }
    }

    private class Fail(override val message: String, val code: String? = null) : Exception(message)

    private suspend fun FlowCollector<AgentEvent>.loop(
        history: List<InputItem>,
        message: String,
        observeFirst: Boolean,
        images: List<InputItem.UserImage>,
        note: String?,
    ) {
        val input = ArrayList<InputItem>(history)
        note?.takeIf { it.isNotBlank() }?.let { input += InputItem.DeveloperText(it) }
        input += InputItem.UserText(message, images)
        // Where the newest full screen tree is in the input and that screen; older ones are cut to a one-line digest.
        // Only results that carry a screen (ToolResult.screen) count, never text that merely looks like one.
        var screenAt = -1
        var shownScreen: CompressedScreen? = null
        fun addOutput(item: InputItem, screen: CompressedScreen?) {
            if (screen != null) {
                shownScreen?.let { if (screenAt >= 0) input[screenAt] = elideScreen(input[screenAt], it) }
                screenAt = input.size
                shownScreen = screen
            }
            input += item
        }
        // Where the run has been: a revisited screen says so (facts for the model to judge, no rules).
        val journal = ScreenJournal()
        fun annotate(output: String, step: Int, action: String, screen: CompressedScreen?): String {
            val seen = screen?.let { journal.revisit(step, action, it) } ?: return output
            return ScreenJournal.withNote(output, seen)
        }
        // The latest screen the model has seen; null until it has seen one.
        var current: CompressedScreen? = null
        var deviceUsed = false
        // The run worked inside an app, instead of only opening one (see stayHonoured).
        var worked = false
        // Apps whose tips were already looked up, and the device actions taken in each app (for a new tip).
        val tipped = HashSet<String>()
        val actions = LinkedHashMap<String, MutableList<String>>()
        if (observeFirst) {
            emit(AgentEvent.DeviceUse)
            deviceUsed = true
            val observed = executor.observe()
            current = observed.screen ?: throw Fail(tr("Error: couldn't read the screen", "오류: 화면을 읽지 못했어요"))
            journal.revisit(0, "start", observed.screen)
            addOutput(InputItem.UserText(observed.output), observed.screen)
            observed.screenshot?.let { addImage(input, it) }
            addTips(input, observed.screen, tipped)
        }

        // Where each step's items start in the input (for compaction: the last few steps are kept whole).
        val stepStarts = ArrayList<Int>()

        /**
         * Cuts down everything before the last [keepSteps] steps: the model's older reasoning is dropped, and older
         * tool results other than screens and the latest plan are cut to their first lines. It runs rarely and in
         * one large cut (only when the input passed the budget, down to well below it), so the cached prompt
         * prefix stays the same between cuts. Returns whether anything changed.
         */
        fun compact(keepSteps: Int): Boolean {
            val keepFrom = stepStarts.getOrNull(stepStarts.size - keepSteps) ?: return false
            val names = input.filterIsInstance<InputItem.FunctionCall>().associate { it.callId to it.name }
            val lastPlan = input.indexOfLast { it is InputItem.FunctionOutput && names[it.callId] == PlanModule.NAME }
            val out = ArrayList<InputItem>(input.size)
            val moved = IntArray(input.size + 1)
            var changed = false
            for ((i, item) in input.withIndex()) {
                moved[i] = out.size
                if (i >= keepFrom || i == lastPlan || i == screenAt) {
                    out += item
                    continue
                }
                when {
                    item is InputItem.Reasoning -> changed = true
                    item is InputItem.FunctionOutput && item.output.length > TRIM_HEAD_CHARS + TRIMMED.length -> {
                        out += item.copy(output = head(item.output, TRIM_HEAD_CHARS) + TRIMMED)
                        changed = true
                    }
                    else -> out += item
                }
            }
            moved[input.size] = out.size
            if (!changed) return false
            if (screenAt >= 0) screenAt = moved[screenAt]
            for (k in stepStarts.indices) stepStarts[k] = moved[stepStarts[k]]
            input.clear()
            input.addAll(out)
            return true
        }

        /** When the input passed the budget: compacts it to well below, keeping as many recent steps as that allows. */
        fun trimToBudget() {
            if (inputSize(input) <= inputBudgetChars) return
            var keep = KEEP_STEPS
            while (keep >= MIN_KEEP_STEPS) {
                compact(keep)
                if (inputSize(input) <= inputBudgetChars * TRIM_TARGET) return
                keep /= 2
            }
        }

        var step = 0
        // The screen of the last step may still change (see Speculation); the next model call watches it.
        var watch: ScreenWatch? = null
        var overflowRetried = false
        while (true) {
            addSteering(input)
            if (step >= maxSteps) throw Fail(tr("Step limit exceeded", "단계 한도 초과"))
            step++
            emit(AgentEvent.Thinking)
            trimToBudget()

            val started = System.nanoTime()
            var restarts = 0
            var turn: ModelTurn
            while (true) {
                val next = try {
                    streamTurn(input, watch.takeIf { restarts < Speculation.MAX_RESTARTS })
                } catch (e: Overflow) {
                    // Too long for the model after all: cut down to the last couple of steps and try once more.
                    if (overflowRetried || !compact(MIN_KEEP_STEPS)) throw Fail(ModelRetry.message(e.error))
                    overflowRetried = true
                    continue
                }
                if (next != null) {
                    turn = next
                    break
                }
                // The screen changed after the model was sent it: show the new one and ask again.
                restarts++
                val observed = executor.observe()
                observed.screen?.let { current = it }
                addOutput(InputItem.UserText(SCREEN_CHANGED + observed.output.removePrefix("# Screen\n")), observed.screen)
                watch = executor.takeWatch()
            }
            watch = null
            // Attached photos go with the first model call only; later steps carry the model's own reading of them.
            if (step == 1) dropAttachedImages(input)
            val millis = (System.nanoTime() - started) / 1_000_000
            turn.usage?.let { emit(AgentEvent.Usage(step, it.inputTokens, it.outputTokens, millis, it.cachedTokens)) }
            val timing = StepTimings(
                step, millis, turn.firstByteMs, turn.firstOutputMs, turn.usage?.inputTokens,
                instructions.length + textSize(input), restarts = restarts,
            )
            executor.clock.reset()
            val toolsStarted = System.nanoTime()
            val toolNames = ArrayList<String>()
            fun reportTimings() {
                val report = onTimings ?: return
                val c = executor.clock
                report(
                    timing.copy(
                        tools = toolNames, toolMs = (System.nanoTime() - toolsStarted) / 1_000_000,
                        settleMs = c.settleMs, settle = c.settle, readMs = c.readMs, shotMs = c.shotMs,
                        screenChars = c.screenChars,
                    ),
                )
            }
            if (turn.citations.isNotEmpty()) {
                executor.markThirdParty()
                emit(AgentEvent.Sources(turn.citations))
            }

            stepStarts += input.size
            if (turn.calls.isEmpty()) {
                reportTimings()
                // An empty reply is a fault (a response we failed to read), never a silent success.
                val text = turn.text ?: throw Fail(tr("Error: the model's reply was empty. Please try again", "오류: 모델 답이 비어 있어요. 다시 시도해 주세요"))
                // The user wrote while this answer was being written: it goes on with the new instruction.
                if (steering.pending()) {
                    input += turn.reasoning
                    emit(AgentEvent.Log(text))
                    input += InputItem.AssistantText(text)
                    continue
                }
                emit(AgentEvent.Finished(text))
                return
            }
            // The model's own reasoning goes back in, so the next step remembers its plan and what it tried.
            input += turn.reasoning
            if (turn.text != null) {
                emit(AgentEvent.Log(turn.text))
                input += InputItem.AssistantText(turn.text)
            }
            val runs = runnable(turn.calls)
            var ended = false
            for (call in turn.calls) {
                // Parallel calls exist so a plan update or data lookups can ride along with the action; any other
                // screen action after the first is sent back unrun, since it was chosen on a screen that is gone.
                // After a finish that the user's new instruction overrode, the rest of the turn is not run either.
                if (ended || runs.none { it === call }) {
                    input += call
                    addOutput(InputItem.FunctionOutput(call.callId, if (ended) AFTER_FINISH else ONE_ACTION), null)
                    continue
                }
                if (!deviceUsed && (call.name in ToolExecutor.DEVICE_TOOLS || call.name == "delegate")) {
                    // Before the helper's first look as well: the touch blocker and the service check come first.
                    emit(AgentEvent.DeviceUse)
                    deviceUsed = true
                }
                if (call.name in WORK_TOOLS) worked = true
                // finish is reported once, by the Finished event below; a card shows itself.
                val description = describe(call, current)
                val tipDescription = if (Lang.korean) description else describe(call, current, korean = true)
                if (call.name !in QUIET_TOOLS) emit(AgentEvent.Acting(description))

                val screenBefore = current
                val before = current?.packageName
                toolNames += call.name
                if (call.name == "delegate" && helper != null) {
                    input += call
                    if (!helper.available) {
                        addOutput(InputItem.FunctionOutput(call.callId, HELPER_DOWN), null)
                        continue
                    }
                    val r = runHelper(helper, call, step)
                    addOutput(InputItem.FunctionOutput(call.callId, annotate(Helper.output(r), step, description, r.screen)), r.screen)
                    r.screen?.let { current = it }
                    continue
                }
                val result = executor.execute(call, current)
                // Debug builds only: arguments and outputs carry typed text, messages and screen contents.
                if (BuildConfig.DEBUG) {
                    TRACE.info("step=$step call=${call.name} args=${call.argumentsJson.take(300)} -> ${result.output.take(1500).replace('\n', '|')}")
                }
                // The user wrote while the run was finishing: it goes on with the new instruction instead.
                val overridden = result.finishedSummary != null && steering.pending()
                val output = if (overridden) NOT_FINISHED else result.output
                input += call
                addOutput(InputItem.FunctionOutput(call.callId, annotate(output, step, description, result.screen)), result.screen)
                result.screenshot?.let { addImage(input, it) }
                result.screen?.let { current = it }
                result.card?.let { emit(AgentEvent.Card(it)) }
                result.remembered?.let { emit(AgentEvent.Remembered(it)) }
                result.forgotten?.let { emit(AgentEvent.Forgot(it)) }
                if (appTips != null && call.name in TIP_ACTIONS &&
                    !result.output.startsWith("ERROR") && !result.output.startsWith("CANCELLED")
                ) {
                    // Opening an app counts for the app it opened; other actions for the app they acted in.
                    val pkg = if (call.name == "open_app" || call.name == "open_uri") current?.packageName else before
                    val stepText = tipStep(call, screenBefore, tipDescription)
                    if (pkg != null && pkg !in NO_TIP_PACKAGES && stepText != null) actions.getOrPut(pkg) { ArrayList() } += stepText
                }
                addTips(input, result.screen, tipped)
                if (appTips != null && result.finishedSummary != null && result.finishedOk && !overridden) {
                    // The app with the most actions; the later one on a tie.
                    actions.entries.toList().asReversed().maxWithOrNull(compareBy { it.value.size })
                        ?.let { (pkg, list) -> emit(AgentEvent.DeviceTaskDone(pkg, list.toList(), result.finishedSummary)) }
                }
                if (result.finishedSummary != null) {
                    if (overridden) {
                        ended = true
                        continue
                    }
                    reportTimings()
                    // The model's stay_in_app is not taken on its word: a game it finished or a form it filled is
                    // not something the user stays to watch, so after working in an app only live media or a call keeps it.
                    val stayHonoured = result.stayInApp && (!worked || executor.playingOrInCall())
                    emit(AgentEvent.Finished(result.finishedSummary, stayHonoured))
                    return
                }
            }
            watch = executor.takeWatch()
            reportTimings()
        }
    }

    /** Adds the user's instructions sent while the run works, as user messages; they count as the user's words. */
    private fun addSteering(input: MutableList<InputItem>) {
        for (text in steering.drain()) {
            executor.userSaid(text)
            input += InputItem.UserText("사용자 추가 지시: $text")
        }
    }

    /** Characters of text and reasoning in [input] (images not counted), for the trim budget. */
    private fun inputSize(input: List<InputItem>): Int = textSize(input) + input.sumOf {
        if (it is InputItem.Reasoning) ((it.raw["encrypted_content"] as? JsonPrimitive)?.content?.length ?: it.raw.toString().length) else 0
    }

    /** The first [max] characters of [s], never ending in half of a surrogate pair. */
    private fun head(s: String, max: Int): String {
        val cut = s.take(max)
        return if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    /** Runs [call] (a delegate) on [helper], showing each helper action as a progress line. */
    private suspend fun FlowCollector<AgentEvent>.runHelper(helper: Helper, call: InputItem.FunctionCall, step: Int): Helper.Result {
        val args = try {
            Json.parseToJsonElement(call.argumentsJson) as? JsonObject
        } catch (e: Exception) {
            null
        }
        val task = (args?.get("task") as? JsonPrimitive)?.content
        if (task.isNullOrBlank()) return Helper.Result(false, "ERROR: task required", null, 0, 0)
        val doneWhen = (args["done_when"] as? JsonPrimitive)?.content
        val steps = ((args["max_steps"] as? JsonPrimitive)?.content?.toIntOrNull() ?: Helper.DEFAULT_STEPS)
            .coerceIn(1, Helper.MAX_STEPS)
        emit(AgentEvent.Acting(tr("Quick helper: ${task.take(60)}", "빠른 도우미: ${task.take(60)}")))
        return helper.run(
            task, doneWhen, steps,
            onAction = { c, screen -> emit(AgentEvent.Acting("⚡ " + describe(c, screen))) },
            onUsage = { u -> emit(AgentEvent.Usage(step, u.inputTokens, u.outputTokens, 0)) },
            interrupted = { steering.pending() },
            random = random,
        )
    }

    /** The text of an item that can carry a screen tree (a tool result or the first observation). */
    private fun screenText(item: InputItem): String? = when (item) {
        is InputItem.FunctionOutput -> item.output
        is InputItem.UserText -> item.text
        else -> null
    }

    /** [item] with [screen]'s tree replaced by its one-line digest; the result line before it stays. */
    private fun elideScreen(item: InputItem, screen: CompressedScreen): InputItem {
        val text = screenText(item) ?: return item
        val tree = screen.text
        if (tree.isEmpty() || !text.endsWith(tree)) return item
        val cut = text.dropLast(tree.length) + ScreenJournal.digest(screen)
        return when (item) {
            is InputItem.FunctionOutput -> item.copy(output = cut)
            is InputItem.UserText -> item.copy(text = cut)
            else -> item
        }
    }

    /** Characters of text in [input] (images not counted), for the timing log. */
    private fun textSize(input: List<InputItem>): Int = input.sumOf {
        when (it) {
            is InputItem.UserText -> it.text.length
            is InputItem.AssistantText -> it.text.length
            is InputItem.DeveloperText -> it.text.length
            is InputItem.FunctionCall -> it.argumentsJson.length
            is InputItem.FunctionOutput -> it.output.length
            is InputItem.UserImage -> 0
            is InputItem.Reasoning -> 0
        }
    }

    /**
     * Calls the model while forwarding its text deltas as [AgentEvent.TextDelta]. The callback may run on another
     * thread and cannot emit, so deltas (and [AgentEvent.Searching]) go through a channel that this coroutine
     * drains; all of a turn's deltas are emitted before the turn is returned. A retry after deltas were sent
     * emits [AgentEvent.TextReset], in order.
     */
    /**
     * One model call, streaming its text and searches as events. With [watch], the live screen is compared with the
     * one the model was sent for [ScreenWatch.windowMs]; if it changes within that time, the call is cancelled and this
     * returns null (the caller shows the new screen and asks again).
     */
    private suspend fun FlowCollector<AgentEvent>.streamTurn(input: List<InputItem>, watch: ScreenWatch?): ModelTurn? = coroutineScope {
        val events = Channel<AgentEvent>(Channel.UNLIMITED)
        val streamed = AtomicBoolean(false)
        val changed = AtomicBoolean(false)
        val watching = AtomicBoolean(watch != null)
        val turn = async {
            try {
                callModel(
                    input,
                    onTextDelta = {
                        streamed.set(true)
                        events.trySend(AgentEvent.TextDelta(it))
                    },
                    onSearch = {
                        // Search results are text the user did not write (see ToolExecutor.markThirdParty).
                        executor.markThirdParty()
                        streamed.set(true)
                        events.trySend(AgentEvent.Searching(it.queries, it.id))
                    },
                    onRetry = { if (streamed.getAndSet(false)) events.trySend(AgentEvent.TextReset) },
                )
            } finally {
                events.close()
            }
        }
        // The live text's hash last found to match the shown screen apart from digits (a ticking clock).
        var same = watch?.hash
        /** Whether the live screen differs from the one shown, digits aside; null when it cannot be read. */
        suspend fun differs(w: ScreenWatch): Boolean? {
            val live = executor.liveScreenHash() ?: return null
            if (live == w.hash || live == same) return false
            val print = w.print ?: return true
            if (executor.liveFingerprint() != print) return true
            same = live
            return false
        }
        val watcher = watch?.let { w ->
            launch {
                withTimeoutOrNull(w.windowMs) {
                    while (true) {
                        delay(Speculation.POLL_MS)
                        val d = differs(w) ?: break
                        if (d) {
                            changed.set(true)
                            turn.cancel()
                            break
                        }
                    }
                }
                watching.set(false)
            }
        }
        for (event in events) emit(event)
        val result = try {
            turn.await()
        } catch (e: CancellationException) {
            // Only the watcher's own cancel is absorbed; a Stop that lands at the same moment still stops the run.
            if (!changed.get() || !isActive) throw e
            null
        } finally {
            watcher?.cancel()
        }
        // Still inside the window: one last look before acting on what the model decided.
        if (result != null && watch != null && watching.get()) {
            if (differs(watch) == true) changed.set(true)
        }
        if (changed.get()) {
            if (streamed.get()) emit(AgentEvent.TextReset)
            null
        } else {
            result
        }
    }


    private suspend fun callModel(
        input: List<InputItem>,
        onTextDelta: (String) -> Unit,
        onSearch: (WebSearchCall) -> Unit,
        onRetry: () -> Unit,
    ): ModelTurn = try {
        ModelRetry.call(onRetry, random) { model.next(instructions, input, tools, onTextDelta, onSearch) }
    } catch (e: ModelException) {
        val status = e.status
        when {
            status == 401 || status == 403 -> throw Fail(e.message ?: tr("Authentication failed", "인증에 실패했어요"))
            ModelRetry.contextOverflow(e) -> throw Overflow(e)
            else -> throw Fail(ModelRetry.message(e), e.code)
        }
    }

    /** The input no longer fits the model's window; the loop compacts it and tries once more. */
    private class Overflow(val error: ModelException) : Exception(error.message)

    /** Adds the tip note for [screen]'s app the first time the run sees that app. */
    private suspend fun addTips(input: MutableList<InputItem>, screen: CompressedScreen?, tipped: MutableSet<String>) {
        val lookup = appTips ?: return
        val pkg = screen?.packageName ?: return
        if (pkg in NO_TIP_PACKAGES || !tipped.add(pkg)) return
        val note = try {
            lookup(pkg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        input += InputItem.UserText(note)
    }

    /** Replaces the image parts of user messages with one "[사진]" mark each, keeping the text. */
    private fun dropAttachedImages(input: MutableList<InputItem>) {
        for (i in input.indices) {
            val item = input[i]
            if (item is InputItem.UserText && item.images.isNotEmpty()) {
                val marks = List(item.images.size) { PHOTO_MARK }.joinToString(" ")
                input[i] = InputItem.UserText(if (item.text.isEmpty()) marks else "$marks ${item.text}")
            }
        }
    }

    private fun addImage(input: MutableList<InputItem>, bytes: ByteArray) {
        for (i in input.indices) {
            if (input[i] is InputItem.UserImage) input[i] = InputItem.UserText(OMITTED_SCREENSHOT)
        }
        input += InputItem.UserImage(Base64.getEncoder().encodeToString(bytes), "image/jpeg")
    }

    /**
     * How an action is kept in an app tip. Typed text never is: type_text becomes "입력" plus the field's hint or
     * name, and a field that looks like a code or secret (OTP, PIN, 인증번호, ...) is left out. A link keeps only
     * its scheme and host. Null = leave the step out.
     */
    private fun tipStep(call: InputItem.FunctionCall, screen: CompressedScreen?, description: String): String? {
        val args = try {
            Json.parseToJsonElement(call.argumentsJson) as? JsonObject
        } catch (e: Exception) {
            null
        }
        return when (call.name) {
            "type_text" -> {
                val node = (args?.get("index") as? JsonPrimitive)?.intOrNull?.let { screen?.elements?.get(it) }
                val hay = listOfNotNull(node?.hint, node?.contentDescription, node?.viewId).joinToString(" ")
                if (node?.password == true || SECRET_FIELD.containsMatchIn(hay)) return null
                val label = listOfNotNull(node?.hint, node?.contentDescription).firstOrNull { it.isNotBlank() }
                    ?: node?.viewId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                if (label != null) "입력: ${label.trim().take(30)}" else "입력"
            }
            "open_uri" -> {
                val uri = (args?.get("uri") as? JsonPrimitive)?.content.orEmpty()
                val head = Regex("^[A-Za-z][A-Za-z0-9+.-]*:(//[^/?#]*)?").find(uri)?.value
                if (head != null) "링크 열기: $head" else "링크 열기"
            }
            // A batch may type; keep only that it was one.
            "batch" -> VERBS["batch"]?.second
            else -> description
        }
    }

    private fun describe(call: InputItem.FunctionCall, current: CompressedScreen?, korean: Boolean = Lang.korean): String {
        val verb = VERBS[call.name]?.let { if (korean) it.second else it.first } ?: call.name
        val args = try {
            Json.parseToJsonElement(call.argumentsJson) as? JsonObject
        } catch (e: Exception) {
            null
        }
        val target = args?.let { a ->
            (a["index"] as? JsonPrimitive)?.intOrNull?.let { current?.labels?.get(it) }
                ?: TARGET_KEYS.firstNotNullOfOrNull { k -> (a[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } }
        }
        if (call.name == "update_plan") return PlanModule.current(args)?.let { "$verb: $it" } ?: verb
        return if (target != null) "$verb: ${target.removePrefix("/storage/emulated/0/")}" else verb
    }

    private companion object {
        const val ONE_ACTION = "NOT RUN: one phone action per turn (update_plan and data tools may go along with it). Call it again if it is still needed on the screen above."
        const val AFTER_FINISH = "NOT RUN: the user sent a new instruction before the task finished."
        const val NOT_FINISHED = "NOT FINISHED: the user sent a new instruction (below) while you were finishing. Follow it, then finish again."
        const val HELPER_DOWN = "ERROR: the helper is unavailable for the rest of this task; do this yourself."

        /**
         * Calls that act on the phone or end the turn's work: only the first of them in a turn runs, since the
         * others were chosen on a screen it may change (or after a question not yet answered).
         */
        private val SINGLE: Set<String> = ToolExecutor.DEVICE_TOOLS + setOf("delegate", "ask_user", "hand_to_user", "finish")

        /**
         * The calls of a turn that run: every update_plan and data tool (in order), and the first call that acts on
         * the phone or ends the task ([SINGLE]).
         */
        fun runnable(calls: List<InputItem.FunctionCall>): List<InputItem.FunctionCall> {
            val action = calls.firstOrNull { it.name in SINGLE }
            return calls.filter { it.name !in SINGLE || it === action }
        }

        /** Default [inputBudgetChars]. */
        const val INPUT_BUDGET_CHARS = 300_000

        /** A trim goes down to this share of the budget, so the next one is many steps away. */
        const val TRIM_TARGET = 0.6

        /** Steps kept whole by a trim (halved while the input is still too big), and the least kept. */
        const val KEEP_STEPS = 12
        const val MIN_KEEP_STEPS = 2

        /** How much of an older tool result a trim keeps. */
        const val TRIM_HEAD_CHARS = 400
        const val TRIMMED = "\n[…trimmed]"

        /** Starts the note sent when the screen changed while the model was deciding (see [Speculation]). */
        const val SCREEN_CHANGED = "# Screen changed while you were deciding (act on this one)\n"

        val QUIET_TOOLS = setOf("finish", "show_card", "remember", "forget", "delegate")

        /** Actions that make up an app tip: the ones that change the screen. */
        val TIP_ACTIONS = setOf("tap", "long_press", "type_text", "scroll", "swipe", "pinch", "press_key", "open_app", "open_uri", "batch")

        /** Actions that work inside an app, as opposed to only opening one. */
        val WORK_TOOLS = TIP_ACTIONS - setOf("open_app", "open_uri") + "delegate"

        /** Fields whose input must never be kept, even as a label. */
        val SECRET_FIELD = Regex("(?i)(?<![a-z])(otp|pin|passcode|password|passwd|cvc|cvv|code)(?![a-z])|인증|비밀번호|보안|코드")

        /** Never tipped: this app and the system UI. */
        val NO_TIP_PACKAGES = setOf("com.phoneuse", "com.android.systemui")
        const val OMITTED_SCREENSHOT = "[이전 스크린샷 생략]"
        const val PHOTO_MARK = "[사진]"
        val TARGET_KEYS = listOf("package", "name", "uri", "key", "text", "path", "to_dir", "from")
        /** English and Korean verb for each tool; the Korean one is also what app tips store. */
        /** Tool calls and results in logcat (tag PU_TRACE), for debugging runs; debug builds only. */
        private val TRACE: java.util.logging.Logger = java.util.logging.Logger.getLogger("PU_TRACE")

        val VERBS: Map<String, Pair<String, String>> = mapOf(
            "update_plan" to ("Plan" to "계획"),
            "tap" to ("Tap" to "탭"), "long_press" to ("Long press" to "길게 누르기"), "type_text" to ("Type" to "입력"),
            "scroll" to ("Scroll" to "스크롤"), "swipe" to ("Swipe" to "스와이프"), "pinch" to ("Zoom" to "확대/축소"), "press_key" to ("Key" to "키"),
            "open_app" to ("Open app" to "앱 열기"), "open_uri" to ("Open link" to "링크 열기"),
            "list_apps" to ("App list" to "앱 목록"), "screenshot" to ("Screenshot" to "스크린샷"), "wait" to ("Wait" to "대기"),
            "batch" to ("Action sequence" to "연속 동작"), "ask_user" to ("Question" to "질문"),
            "hand_to_user" to ("Hand over to you" to "사용자에게 넘김"), "finish" to ("Done" to "완료"),
            "remember" to ("Remember" to "기억"), "forget" to ("Forget" to "기억 지우기"), "recall" to ("Recall" to "기억 찾기"),
            "search_conversations" to ("Search past chats" to "지난 대화 찾기"),
            "get_location" to ("Check my location" to "내 위치 확인"), "geocode" to ("Find place" to "장소 찾기"),
            "open_route" to ("Open directions" to "길찾기 열기"), "remind_at" to ("Schedule reminder" to "알림 예약"),
            "make_routine" to ("Create routine" to "루틴 만들기"),
            "list_dir" to ("Look in folder" to "폴더 보기"), "search_files" to ("Find files" to "파일 찾기"),
            "file_info" to ("File details" to "파일 정보"), "mkdir" to ("Make folder" to "폴더 만들기"),
            "move_file" to ("Move file" to "파일 옮기기"), "move_files" to ("Move files" to "파일 여러 개 옮기기"),
            "copy_file" to ("Copy file" to "파일 복사"), "rename_file" to ("Rename file" to "이름 바꾸기"),
            "trash_files" to ("Move to trash" to "휴지통으로 옮기기"),
        )
    }
}
