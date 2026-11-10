package com.phoneuse.agent

import com.phoneuse.i18n.tr
import com.phoneuse.cards.CardSpec
import com.phoneuse.chat.ForgottenMemory
import com.phoneuse.chat.MemoryRef
import com.phoneuse.files.FileTools
import com.phoneuse.model.InputItem
import com.phoneuse.model.ToolSpec
import com.phoneuse.phone.ContactAdder
import com.phoneuse.phone.SmsTool
import com.phoneuse.risk.RiskCategory
import com.phoneuse.risk.RiskGuard
import com.phoneuse.risk.RiskMatch
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.CompressedScreen
import com.phoneuse.screen.TreeCompressor
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.hypot

data class ToolResult(
    val output: String,
    val screen: CompressedScreen?,
    val screenshot: ByteArray?,
    val finishedSummary: String?,
    /** True when the tool acted on or read the phone screen (not a file, ask_user, finish or list_apps call). */
    val deviceTool: Boolean = false,
    /** A valid card from show_card, to be shown with this run's reply. */
    val card: CardSpec? = null,
    /** finish's stay_in_app: the result is the other app on screen, so the run does not return to the chat. */
    val stayInApp: Boolean = false,
    /** finish's success (default true); false when the model reported the task could not be done. */
    val finishedOk: Boolean = true,
    /** A memory remember saved; the reply shows "기억했어요 · <text> [취소]" for it. */
    val remembered: MemoryRef? = null,
    /** A memory forget removed; the reply shows "잊었어요 · <text> [되돌리기]" for it. */
    val forgotten: ForgottenMemory? = null,
)

/** Malformed or missing arguments; reported as "ERROR: invalid arguments: ...". */

/** A step that could not be carried out; reported as "ERROR: ...". */
private class StepFail(message: String) : Exception(message)

/** The element the model chose is no longer on the live screen [fresh]; nothing was done. */
private class StaleTarget(val fresh: CompressedScreen) : Exception("screen changed")

/**
 * [trustUserAuthorized] false (a run started from a card button, whose text the user did not write) makes
 * every SEND ask for confirmation even when the model sets user_authorized.
 */
class ToolExecutor(
    private val device: DeviceController,
    private val gate: UserGate,
    files: FileTools? = null,
    private val trustUserAuthorized: Boolean = true,
    memory: MemoryAccess? = null,
    phone: PhoneAccess? = null,
    /** Tools of connected MCP servers and Google for this run (see [ConnectorTools]); null = none. */
    private val connectors: ConnectorTools? = null,
    /** Notification and text-message tools (see [MessagingAccess]); null = none. */
    messaging: MessagingAccess? = null,
    /** make_routine; null leaves it out. */
    routines: RoutineAccess? = null,
    /**
     * Settings "메시지 보내기 전 확인" (see [ConfirmPolicy]): false lets SEND and CHANGE go without a card. It applies
     * only where the user wrote the request ([trustUserAuthorized]); send_sms, MONEY and SECURITY always ask, and
     * DELETE asks unless the user asked for that deletion ([deleteSkips]).
     */
    confirmSend: Boolean = true,
    /** Settings "모델에 보내는 화면 내용: 글자만": false keeps screenshots out of the run (the screen tree still goes). */
    private val screenshots: Boolean = true,
    /**
     * An earlier run of this conversation read third-party text (ChatRepository.thirdPartySeen): its
     * history may carry that text, so remember only guesses, user_authorized is ignored, the confirm setting
     * does not skip cards and a typed confirmed write is refused. The card's own button still saves.
     */
    private val historyTainted: Boolean = false,
    /** A routine run's saved message: user_authorized then skips SEND only for exactly it (see [FixedSend]). */
    val fixedSend: FixedSend? = null,
    /** The app context, for modules that read the phone themselves (see [ToolDeps.context]); null in unit tests. */
    appContext: android.content.Context? = null,
    /** Settings "AI 안전 검사": the acting model's inline risk declaration counts (see [RiskJudge]); false = keywords only. */
    private val inlineRisk: Boolean = false,
    /** Builds the run's tool modules; tests may add one (see [ToolRegistry] for adding a module for real). */
    registry: (ToolDeps) -> List<ToolModule> = ToolRegistry::modules,
) {
    /**
     * The gate for every confirmation the setting may skip; send_sms keeps [gate] (it always asks). Decided at each
     * confirmation: a card is skipped only while the switch is off, the user wrote the request, the history is clean
     * and this run has not read third-party text so far (a screen, a notification, a mail). A routine run ignores the
     * switch (its only skip is its own [fixedSend] match; the runner also passes confirmSend for every routine).
     */
    private val confirming: UserGate = ConfirmPolicyGate(gate) {
        confirmSend || !trustUserAuthorized || historyTainted || runThirdParty || fixedSend != null
    }
    /** Third-party text entered this run itself (history aside). */
    @Volatile private var runThirdParty = false

    /** True once text the user did not write entered the run, here or in an earlier run (see [markThirdParty]). */
    val thirdPartySeen: Boolean get() = historyTainted || runThirdParty

    /**
     * Records that text the user did not write entered the run (e.g. a hosted web search, reported by the loop);
     * from then on remember only stores guesses. Tool outputs and screens mark it by themselves.
     */
    fun markThirdParty() {
        runThirdParty = true
    }

    /** What the user wrote in this run (the message and any instruction sent while it works), normalized. */
    private val userWords = StringBuilder()

    /**
     * Records [text] as the user's own words for this run. Once the run has read third-party text, user_authorized
     * only counts for a send whose text appears in them verbatim (see [userAuthorized]).
     */
    fun userSaid(text: String) {
        userWords.append(' ').append(norm(text))
    }

    /**
     * Whether the user's own words in this run fix both [to] and exactly [body]: the text appears verbatim and the
     * recipient's number or name does too, as a number or a word of its own ([saysNumber], [saysName]). Such a send
     * was decided by the user, so it needs no card whatever the run read since. Never in a routine or card-started
     * run, and never for the helper.
     */
    fun userFixedSend(to: String, body: String): Boolean {
        if (!trustUserAuthorized || helperCall || fixedSend != null) return false
        val words = userWords.toString()
        val text = norm(body)
        if (text.isEmpty() || !words.contains(text)) return false
        return if (to.none(Char::isLetter)) saysNumber(words, to) else saysName(words, to)
    }

    /**
     * Whether the user's own words in this run ask for a deletion (a delete verb in [userWords]). Deterministic: what
     * the run read on screens never counts. Never in a routine or card-started run, and never for the helper.
     */
    fun userAskedDelete(): Boolean {
        if (!trustUserAuthorized || helperCall || fixedSend != null) return false
        val words = userWords.toString()
        return DELETE_VERBS_CJK.any { it in words } || DELETE_VERBS_EN.containsMatchIn(words)
    }

    /**
     * Whether the user's own words in this run ask to call this recipient: a call word, and either [number] as a
     * number of its own or one of [names] as a word of its own ([saysNumber], [saysName]). Asking about a phone
     * number or the call log, or not to call, is not asking to call. Deterministic like [userAskedDelete].
     */
    fun userAskedCall(names: List<String>, number: String): Boolean {
        if (!trustUserAuthorized || helperCall || fixedSend != null) return false
        var words = userWords.toString()
        NOT_A_CALL.forEach { words = words.replace(it, " ") }
        words = NOT_A_CALL_EN.replace(words, " ")
        if (CALL_WORDS_CJK.none { it in words } && !CALL_WORDS_EN.containsMatchIn(words)) return false
        return saysNumber(words, number) || names.any { saysName(words, it) }
    }

    /**
     * Whether the user's own words in this run ask for a new alarm; asking to stop, remove or look at one does not
     * count. The time and the label are the model's, so this holds only while the run has read nothing third-party
     * that could have chosen them. Deterministic like [userAskedDelete].
     */
    fun userAskedAlarm(): Boolean {
        if (!trustUserAuthorized || helperCall || fixedSend != null || runThirdParty) return false
        val words = NOT_AN_ALARM.fold(userWords.toString()) { left, phrase -> phrase.replace(left, " ") }
        return ALARM_WORDS_CJK.any { it in words } || ALARM_WORDS_EN.containsMatchIn(words)
    }

    /** Whether the user's own words in this run ask to start a timer; as [userAskedAlarm]. */
    fun userAskedTimer(): Boolean {
        if (!trustUserAuthorized || helperCall || fixedSend != null || runThirdParty) return false
        val words = NOT_A_TIMER.fold(userWords.toString()) { left, phrase -> phrase.replace(left, " ") }
        return TIMER_WORDS_CJK.any { it in words } || TIMER_WORDS_EN.containsMatchIn(words)
    }

    /**
     * Whether the user's own words in this run give this contact: [number] (6+ digits) as a number of its own, the
     * [name], and the [email] when there is one. A name or an address the model chose still gets the card.
     */
    fun userGaveContact(name: String, number: String, email: String?): Boolean {
        if (!trustUserAuthorized || helperCall || fixedSend != null) return false
        val words = userWords.toString()
        return number.count(Char::isDigit) >= 6 && saysNumber(words, number) && saysName(words, name) &&
            (email == null || norm(email).let { it.isNotEmpty() && it in words })
    }

    /**
     * A DELETE that may go without a card: the user asked for a deletion ([userAskedDelete]) and the model declares
     * this one is what they named, a single item (or exactly those named) and recoverable, and no permanent or bulk
     * word ([RiskGuard.irreversible]) is on the control or its dialog. No judgment (off, slow, failed): the card.
     */
    private fun deleteSkips(judgment: RiskJudge.Judgment?, vararg texts: String?): Boolean =
        judgment != null && judgment.category == RiskCategory.DELETE && judgment.requested && !judgment.bulk &&
            !judgment.irreversible && userAskedDelete() && !userAskedBulkDelete() &&
            !RiskGuard.irreversible(*texts, judgment.effect)

    /**
     * Whether the user's words ask to delete everything or many items ("전부 삭제", "delete all"). Such a request is
     * carried out one item at a time, and each single delete may look requested and not bulk to the model, so it keeps
     * the card on every step.
     */
    private fun userAskedBulkDelete(): Boolean {
        val words = norm(userWords.toString())
        return BULK_WORDS_CJK.any { it in words } || BULK_WORDS_EN.containsMatchIn(words)
    }

    /** True while the delegate helper's call runs: the helper never saw the user's words (see [userAuthorized]). */
    private var helperCall = false

    /** This run's tool modules, from [ToolRegistry] unless a test passes its own list. */
    private val modules: List<ToolModule> = registry(
        ToolDeps(
            device, gate, confirming, files, memory, phone, messaging, routines, connectors,
            runTainted = { runThirdParty }, historyTainted = historyTainted, screen = ScreenModule(), context = appContext,
            userFixedSend = ::userFixedSend, userAskedDelete = ::userAskedDelete,
            userAskedCall = ::userAskedCall, userAskedAlarm = ::userAskedAlarm, userAskedTimer = ::userAskedTimer,
            userGaveContact = ::userGaveContact,
        ),
    )

    /** Tools whose output is the user's own words or data (see [ToolModule.userText]). */
    private val userText: Set<String> = modules.flatMapTo(HashSet()) { it.userText }

    /** Every module's offered tools, in registry order (the model prompt; see ToolSpecsPinTest). */
    val specs: List<ToolSpec> = modules.flatMap { m -> m.offered.let { offered -> m.specs.filter { it.name in offered } } }

    /** [DeviceController.interruptions] when the screen was last read; a newer count makes indices stale. */
    private var seenInterruptions: Int? = null

    /** The app the last open_app / open_route opened; its settle is short when that app was already in front. */
    private var launchedPackage: String? = null

    /** Settle and screen-read time of the calls since the loop last reset it (step timing log). */
    val clock = ToolClock()

    /** Set by a quick settle (see [Speculation]); the loop takes it after each step. */
    private var watch: ScreenWatch? = null

    /** Apps checked this run (see [isProtected]): true for one that blocks screen capture. */
    private val protectedApps = HashMap<String, Boolean>()

    /** The pending [ScreenWatch] of the last screen read, if that read ended early; clears it. */
    fun takeWatch(): ScreenWatch? = watch.also { watch = null }

    fun playingOrInCall(): Boolean = device.playingOrInCall()

    /** The live screen's hash (see [DeviceController.screenHash]). */
    suspend fun liveScreenHash(): Int? = device.screenHash()

    suspend fun observe(): ToolResult {
        markThirdParty()
        val screen = refresh()
        val shot = look(screen)
        if (isProtected(screen, shot, tried = screenshots)) return ToolResult("# Screen\n${protectedText(screen)}", screen, null, null)
        return ToolResult("# Screen\n${screen.text}", screen, shot, null)
    }

    /**
     * [current] may be null; a tool that needs the screen then observes first. [fromHelper]: the delegate helper
     * made the call; its user_authorized never counts (it did not see what the user wrote).
     */
    suspend fun execute(call: InputItem.FunctionCall, current: CompressedScreen?, fromHelper: Boolean = false): ToolResult {
        helperCall = fromHelper
        return try {
            executeInner(call, current).let {
                // Any output but the user's own answer, a memory tool's echo or the user's own contacts and alarms is text the user did not write.
                if (call.name !in userText) markThirdParty()
                it.copy(deviceTool = call.name in DEVICE_TOOLS)
            }
        } finally {
            helperCall = false
        }
    }

    private suspend fun executeInner(call: InputItem.FunctionCall, current: CompressedScreen?): ToolResult =
        try {
            if (current != null && call.name in SCREEN_TOOLS && interruptedSinceRead()) return interrupted()
            val args = parseArgs(call.argumentsJson)
            if (current == null && call.name in SCREEN_TOOLS && namesSpot(call.name, args)) {
                // An element number or point chosen before any screen was seen (a guess, or one remembered from an
                // earlier run) would hit whatever is there now: show the screen instead.
                return screenResult("STOPPED: $UNSEEN Nothing was done.", observe().screen ?: refresh())
            }
            val screen = current ?: if (call.name in SCREEN_TOOLS) observe().screen else null
            // An app that hides its screen from capture is never operated (see PROTECTED); leaving it is still allowed.
            if (screen != null && call.name in SCREEN_TOOLS && isProtected(screen)) {
                return ToolResult("STOPPED: nothing was done.\n\n# Screen\n${protectedText(screen)}", screen, null, null)
            }
            dispatch(call, args, screen)
        } catch (e: ScreenInterruptedException) {
            interrupted()
        } catch (e: StaleTarget) {
            screenResult("STOPPED: $STALE Nothing was done.", e.fresh)
        } catch (e: ServiceUnavailableException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: ArgException) {
            error("invalid arguments: ${e.message}")
        } catch (e: StepFail) {
            error(e.message ?: "failed")
        } catch (e: Exception) {
            error(e.message ?: e.toString())
        }

    /** Routes the call to the first module that takes it (a module refuses a tool this run does not offer). */
    private suspend fun dispatch(call: InputItem.FunctionCall, args: JsonObject, current: CompressedScreen?): ToolResult {
        val module = modules.firstOrNull { it.handles(call.name) } ?: return error("unknown tool ${call.name}")
        return module.run(call.name, args, CallContext(current))
    }

    private inner class CallContext(override val screen: CompressedScreen?) : ToolContext {
        override suspend fun settled(head: String, afterTool: String?, launched: String?): ToolResult {
            if (afterTool != null) launchedPackage = launched
            return this@ToolExecutor.settled(head, afterTool)
        }
    }

    /**
     * The screen tools. They stay in the executor because they share its screen state: the last read and its
     * interruption count, the element a step acted on, the app last launched and the settle after each step.
     */
    private inner class ScreenModule : ToolModule {
        override val specs: List<ToolSpec> get() = ScreenToolSpecs.all

        override suspend fun run(name: String, args: JsonObject, context: ToolContext): ToolResult {
            val current = context.screen
            return when (name) {
                "list_apps" -> listApps(args.str("query"))
                "screenshot" -> screenshotTool()
                "get_screen" -> observe()
                "batch" -> batch(args, current ?: return error("no screen"))
                in BATCH_TOOLS -> {
                    // The settle compares against the live screen the step acted on, when it read one.
                    if (runStep(name, args, current)) settled("OK", name, actedOn ?: current) else cancelled()
                }
                // delegate is AgentLoop's (its helper model); it never reaches the executor while offered.
                else -> error("unknown tool $name")
            }
        }
    }

    // ---- single steps -------------------------------------------------------------------------

    /**
     * The live screen the last step acted on (read just before it), or null when it read none; the settle after
     * the step compares against it.
     */
    private var actedOn: CompressedScreen? = null

    /** The live screen: [live] when the caller just read it (batch steps after the first), else read now. */
    private suspend fun liveScreen(live: CompressedScreen?): CompressedScreen =
        (live ?: readNow()).also { actedOn = it }

    /**
     * Runs one screen-changing tool. Returns false if the user declined a confirmation. [live] is a screen read
     * just now (batch steps after the first); without it a step that names an element reads the live screen
     * first and acts on the same element there (see [liveIndex]), so every check judges what is there now.
     */
    private suspend fun runStep(
        tool: String,
        args: JsonObject,
        screenOrNull: CompressedScreen?,
        live: CompressedScreen? = null,
    ): Boolean {
        actedOn = null
        when (tool) {
            "tap", "long_press" -> {
                val seen = need(screenOrNull)
                val now = liveScreen(live)
                val t = liveTarget(args, seen, now)
                // A point, or an element with no label, is also judged by what is around it.
                val judgeAround = args.int("index") == null || t.index?.let { now.fullLabels[it] }.isNullOrBlank()
                if (allowed(tool, t.index, now, args, if (judgeAround) t.x to t.y else null) == Gate.DENIED) return false
                // An element is clicked as itself first (works under the touch blocker); a point, or an element
                // that cannot be clicked that way, gets a real touch.
                val long = tool == "long_press"
                val clicked = t.index?.let { now.elements[it] }?.let { device.click(it, long) } == true
                val ok = clicked || if (long) device.longPress(t.x, t.y) else device.tap(t.x, t.y)
                if (!ok) throw StepFail("$tool failed")
            }
            "type_text" -> {
                val seen = need(screenOrNull)
                val asked = args.requireInt("index")
                val now = liveScreen(live)
                val index = liveIndex(asked, seen, now)
                val node = element(index, now)
                if (node.password || isPasswordField(node)) throw StepFail("password field — use hand_to_user")
                if (isSensitiveField(node)) throw StepFail("card or ID number field — use hand_to_user")
                if (!node.editable) throw StepFail("element [$asked] is not editable")
                val text = args.requireStr("text")
                // A field whose label reads as a send ("Send a message") asks with the text on the card, so the user
                // never approves a send without seeing what goes.
                val gated = allowed(tool, index, now, args, typed = text)
                if (gated == Gate.DENIED) return false
                if (args.bool("submit") == true && !userAuthorized(args, now, index, text) &&
                    gated != Gate.CONFIRMED_SEND && !isSearchField(node) &&
                    !confirming.confirm(RiskCategory.SEND, tr("Type and send: \"${preview(text, SEND_PREVIEW_CHARS)}\"", "입력 후 전송: \"${preview(text, SEND_PREVIEW_CHARS)}\""))
                ) {
                    return false
                }
                if (!device.setText(node, text)) throw StepFail("$tool failed")
                if (args.bool("submit") == true && !device.imeEnter(node)) throw StepFail("$tool failed")
            }
            "scroll" -> {
                val direction = args.requireStr("direction")
                if (direction !in DIRECTIONS) throw ArgException("direction must be one of $DIRECTIONS")
                val node = args.int("index")?.let { asked ->
                    val seen = need(screenOrNull)
                    val now = liveScreen(live)
                    element(liveIndex(asked, seen, now), now)
                }
                if (!device.scroll(node, direction)) throw StepFail("$tool failed")
            }
            "swipe" -> {
                need(screenOrNull)
                val duration = (args.int("duration_ms") ?: DEFAULT_SWIPE_MS).toLong()
                val x1 = args.requireInt("x1")
                val y1 = args.requireInt("y1")
                val x2 = args.requireInt("x2")
                val y2 = args.requireInt("y2")
                // A swipe that barely moves lands as a tap and is judged like one; a real swipe may be a slider
                // ("밀어서 결제"), so its start element and container are judged, never the text around it.
                val now = liveScreen(live)
                val asTap = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()) < TAP_SLOP_PX
                if (allowed(tool, pointTarget(x1, y1, now).index, now, args, x1 to y1, swipe = !asTap) == Gate.DENIED) return false
                if (!device.swipe(x1, y1, x2, y2, duration)) throw StepFail("$tool failed")
            }
            "pinch" -> {
                need(screenOrNull)
                val zoomIn = when (args.requireStr("zoom")) {
                    "in" -> true
                    "out" -> false
                    else -> throw ArgException("zoom must be in or out")
                }
                if (!device.pinch(args.requireInt("x"), args.requireInt("y"), zoomIn)) throw StepFail("$tool failed")
            }
            "press_key" -> {
                val action = KEYS[args.requireStr("key")] ?: throw ArgException("unknown key")
                if (!device.globalAction(action)) throw StepFail("$tool failed")
            }
            "open_app" -> {
                val pkg = args.str("package") ?: findPackage(args.str("name") ?: throw ArgException("package or name required"))
                launchedPackage = pkg
                if (!device.launchApp(pkg, fresh = args.bool("fresh") == true)) throw StepFail("$tool failed")
            }
            "open_uri" -> if (!device.openUri(args.requireStr("uri"))) throw StepFail("$tool failed")
            "wait" -> delay(args.requireInt("ms").coerceIn(0, MAX_WAIT_MS).toLong())
            else -> throw StepFail("unknown tool $tool")
        }
        return true
    }

    /**
     * Whether the model's user_authorized counts. In a routine run with a saved message ([fixedSend]) it counts
     * only when the live [screen] is the saved recipient's chat with exactly the saved text in its message field
     * (for a type_text with submit, [typed] about to go into [typedInto]); nothing typed earlier in the run counts.
     */
    private fun userAuthorized(args: JsonObject, screen: CompressedScreen? = null, typedInto: Int? = null, typed: String? = null): Boolean {
        if (args.bool("user_authorized") != true || !trustUserAuthorized || historyTainted || helperCall) return false
        fixedSend?.let { fixed -> return screen != null && fixed.matches(screen, typedInto, typed) }
        if (!runThirdParty) return true
        // Text the user did not write (a chat bubble, a page, a notification) may have asked for this send. The claim
        // then holds only when what is sent is the user's own words from this run.
        val sent = typed ?: screen?.let { composerText(it) } ?: return false
        val want = norm(sent)
        return want.isNotEmpty() && userWords.contains(want)
    }

    /** The text of the one filled message field on [screen] (a hint is not text); null when none or several. */
    private fun composerText(screen: CompressedScreen): String? =
        screen.elements.values.mapNotNull { n -> n.text?.takeIf { n.editable && it.isNotBlank() && it != n.hint } }.singleOrNull()

    /**
     * A field named as a password or PIN (hint, description or view id) that is not flagged as one: a web view, a
     * "show password" toggle or a custom PIN pad. Only the user types into it.
     */
    private fun isPasswordField(node: UiNode): Boolean {
        val hay = listOfNotNull(node.hint, node.contentDescription, node.viewId).joinToString(" ").lowercase()
        return PASSWORD_FIELD.containsMatchIn(hay)
    }

    /** Whether [args] of screen tool [name] point at an element number or a screen position. */
    private fun namesSpot(name: String, args: JsonObject): Boolean = when (name) {
        "swipe", "pinch" -> true
        "batch" -> (args["actions"] as? JsonArray).orEmpty().any { a ->
            val action = a as? JsonObject ?: return@any false
            val tool = (action["tool"] as? JsonPrimitive)?.content
            val step = action["args"] as? JsonObject
            tool == "swipe" || tool == "pinch" || (step != null && SPOT_KEYS.any { it in step })
        }
        else -> SPOT_KEYS.any { it in args }
    }

    private fun need(screen: CompressedScreen?): CompressedScreen = screen ?: throw StepFail("no screen")

    private enum class Gate { DENIED, PASSED, CONFIRMED_SEND, CONFIRMED_OTHER }

    /**
     * A card number, CVC or resident registration number field: only the user types these. Judged by what
     * names the field (hint, description, view id), never by what is typed in it (a draft may mention "카드번호").
     */
    private fun isSensitiveField(node: UiNode): Boolean {
        val hay = listOfNotNull(node.hint, node.contentDescription, node.viewId).joinToString(" ").lowercase()
        return SENSITIVE_FIELD.containsMatchIn(hay)
    }

    private fun isSearchField(node: UiNode): Boolean {
        val hay = listOfNotNull(node.contentDescription, node.hint, node.viewId, node.className)
            .joinToString(" ").lowercase()
        return SEARCH_MARKERS.any { it in hay }
    }

    /** Whitespace collapsed to single spaces, cut to [max] code points so emoji are never split. */
    private fun preview(s: String, max: Int): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.codePointCount(0, t.length) <= max) t else t.substring(0, t.offsetByCodePoints(0, max)).trimEnd()
    }

    private suspend fun findPackage(name: String): String {
        val apps = device.installedApps()
        val hit = apps.firstOrNull { it.label.equals(name, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.contains(name, ignoreCase = true) }
            ?: throw StepFail("app not found: $name")
        return hit.packageName
    }

    private class Target(val x: Int, val y: Int, val index: Int?)

    /**
     * Where to touch on the live screen [now]. An index names the element the model saw on [seen]; it is tapped
     * only if that same element (class, label and bounds) is on [now], else [StaleTarget]. A point is taken as
     * is, and the element under it on [now] is what the risk gate judges.
     */
    private fun liveTarget(args: JsonObject, seen: CompressedScreen, now: CompressedScreen): Target {
        val index = args.int("index")
        if (index != null) {
            val live = liveIndex(index, seen, now)
            val c = now.tapPoint(live)
            return Target(c.first, c.second, live)
        }
        val x = args.int("x")
        val y = args.int("y")
        if (x == null || y == null) throw ArgException("index or x and y required")
        return pointTarget(x, y, now)
    }

    /** The index on [now] of the element the model called [index] on [seen]; [StaleTarget] when it is gone. */
    private fun liveIndex(index: Int, seen: CompressedScreen, now: CompressedScreen): Int {
        element(index, seen)
        return sameElement(index, seen, now) ?: throw StaleTarget(now)
    }

    /**
     * The index on [now] of element [index] of [seen]:
     * - the same app, class, label and bounds: at the same index, else the one such element;
     * - else, when it only moved (a list scrolled a few pixels, a banner pushed it down): the same app, a non-blank
     *   class-and-label pair that is unique on both screens, the same width and height, and visible now.
     * Null otherwise (gone, blank label, duplicates, resized). The caller acts on the live element and the risk
     * gate judges that element, so a match never skips a confirmation.
     */
    private fun sameElement(index: Int, seen: CompressedScreen, now: CompressedScreen): Int? {
        val want = identity(index, seen) ?: return null
        if (identity(index, now) == want) return index
        now.elements.keys.singleOrNull { identity(it, now) == want }?.let { return it }

        if (seen.packageName != now.packageName) return null
        val node = seen.elements.getValue(index)
        val kind = kind(index, seen)
        if (kind.second.isNullOrBlank()) return null
        if (seen.elements.keys.count { kind(it, seen) == kind } != 1) return null
        val moved = now.elements.keys.singleOrNull { kind(it, now) == kind } ?: return null
        val b = now.elements.getValue(moved).bounds
        val sameSize = b.right - b.left == node.bounds.right - node.bounds.left &&
            b.bottom - b.top == node.bounds.bottom - node.bounds.top
        val visible = !b.isEmpty && b.right > 0 && b.bottom > 0 && b.left < now.displayWidth && b.top < now.displayHeight
        return moved.takeIf { sameSize && visible }
    }

    private data class Identity(val packageName: String?, val className: String?, val label: String?, val bounds: Bounds)

    private fun identity(index: Int, screen: CompressedScreen): Identity? {
        val node = screen.elements[index] ?: return null
        return Identity(screen.packageName, node.className, screen.fullLabels[index], node.bounds)
    }

    private fun kind(index: Int, screen: CompressedScreen): Pair<String?, String?> =
        screen.elements[index]?.className to screen.fullLabels[index]

    /** The smallest element containing (x, y), if any. */
    private fun pointTarget(x: Int, y: Int, screen: CompressedScreen): Target {
        val hit = screen.elements.entries
            .filter { (_, n) -> x >= n.bounds.left && x < n.bounds.right && y >= n.bounds.top && y < n.bounds.bottom }
            .minByOrNull { (_, n) -> (n.bounds.right - n.bounds.left).toLong() * (n.bounds.bottom - n.bounds.top) }
        return Target(x, y, hit?.key)
    }

    private fun element(index: Int, screen: CompressedScreen): UiNode =
        screen.elements[index] ?: throw StepFail("no element [$index]")

    /** Risk gate: true if the action may proceed. Classifies the untruncated label. */
    private suspend fun allowed(
        tool: String,
        index: Int?,
        screen: CompressedScreen,
        args: JsonObject,
        /** The touched point when the label alone may not tell (see [PointRisk]). */
        point: Pair<Int, Int>? = null,
        /** [point] starts a swipe: only its element and container are judged. */
        swipe: Boolean = false,
        /** type_text's text: shown on the card, and what user_authorized is checked against. */
        typed: String? = null,
    ): Gate {
        val label = index?.let { screen.fullLabels[it] }
        var (match, context) = classify(tool, index, label, screen)
        var name = label?.takeIf { it.isNotBlank() }?.let { preview(it, LABEL_PREVIEW_CHARS) } ?: index?.let { "[$it]" } ?: "<unlabeled>"
        if (match == null && point != null) {
            PointRisk.judge(screen, point.first, point.second, swipe)?.let {
                match = RiskMatch(it.category, it.strong)
                context = it.context
                name = preview(it.name, LABEL_PREVIEW_CHARS)
            }
        }
        // The model's own declaration may add risk or clear a weak keyword hit; without one the keywords decide alone.
        val judgment = if (inlineRisk && RiskJudge.judges(tool, screen.packageName)) RiskJudge.fromArgs(args) else null
        val cat = RiskCombine.combine(match, judgment) ?: return Gate.PASSED
        val needsConfirm = when (cat) {
            RiskCategory.SEND -> !userAuthorized(args, screen, index.takeIf { typed != null }, typed)
            RiskCategory.DELETE -> !deleteSkips(judgment, label, context, name, screen.packageName)
            else -> true
        }
        if (!needsConfirm) return Gate.PASSED
        return when {
            !confirming.confirm(cat, cardSummary(tool, name, context, typed, judgment?.effect)) -> Gate.DENIED
            cat == RiskCategory.SEND -> Gate.CONFIRMED_SEND
            else -> Gate.CONFIRMED_OTHER
        }
    }

    /**
     * A generic affirmative ("확인", "OK", ...) says nothing by itself, so for a tap on one the
     * non-actionable text of the same window (the dialog's message) decides the category. The
     * second value is that context text when it was what decided, else null.
     */
    private fun classify(tool: String, index: Int?, label: String?, screen: CompressedScreen): Pair<RiskMatch?, String?> {
        val own = RiskGuard.match(label, screen.packageName)
        if (tool == "type_text" || index == null || label == null) return own to null
        if (GENERIC_AFFIRMATIVES.none { it.equals(label.trim(), ignoreCase = true) }) return own to null
        val windowId = screen.elements[index]?.windowId ?: return own to null
        val context = screen.elements.entries
            .filter { (i, n) -> i != index && n.windowId == windowId && !n.isActionable }
            .mapNotNull { (i, _) -> screen.fullLabels[i] }
            .joinToString(" ")
        // The button's word goes along ("Allow" + "...wants to access your account"); the field-clear exemption
        // is for a button's own label only, never for a dialog's text.
        val fromContext = RiskGuard.match("$label $context", screen.packageName, allowFieldClear = false) ?: return own to null
        return fromContext to context
    }

    /** The confirmation card's text: the model's [effect] (what will really happen) over the touched control. */
    private fun cardSummary(tool: String, name: String, context: String?, typed: String?, effect: String?): String {
        val dialog = context?.let { " — \"${preview(it, CONTEXT_PREVIEW_CHARS)}\"" }.orEmpty()
        val text = typed?.let { " — \"${preview(it, SEND_PREVIEW_CHARS)}\"" }.orEmpty()
        val action = "$tool: $name$dialog$text"
        return effect?.let { "$it\n$action" } ?: action
    }

    // ---- batch --------------------------------------------------------------------------------

    private suspend fun batch(args: JsonObject, current: CompressedScreen): ToolResult {
        val actions = args["actions"] as? JsonArray ?: throw ArgException("actions must be an array")
        if (actions.isEmpty()) throw ArgException("actions is empty")
        val lines = ArrayList<String>()
        var working = current
        var finalScreen: CompressedScreen? = null
        // The last step that ran; the UI is settled after it before the next step and before the final read.
        var acted: String? = null
        for ((i, action) in actions.withIndex()) {
            val n = i + 1
            val obj = action as? JsonObject ?: throw ArgException("action $n must be an object")
            val tool = obj.requireStr("tool")
            var stepArgs = obj["args"] as? JsonObject ?: JsonObject(emptyMap())
            if (tool !in BATCH_TOOLS) {
                lines += "$n. $tool: ERROR: not allowed in batch"
                break
            }
            try {
                val target = stepArgs.str("target")?.takeIf { it.isNotBlank() && stepArgs.int("index") == null }
                if (n >= 2 && interruptedSinceRead()) {
                    lines += "$n. $tool: STOPPED: $INTERRUPTED"
                    break
                }
                if (target != null) {
                    // A label on a screen the model has not seen yet: wait for it to appear, then act on it there.
                    // The first step looks on the live screen too: the model's screen may be seconds old, and the
                    // risk gate and the touch must see what is there now.
                    val start = if (n >= 2) refresh(afterTool = acted, baseline = working) else readNow()
                    val (seen, found) = awaitTarget(target, start)
                    working = seen
                    if (found == null) {
                        lines += "$n. $tool: STOPPED: no element \"$target\" appeared"
                        finalScreen = seen
                        break
                    }
                    stepArgs = JsonObject(stepArgs + ("index" to JsonPrimitive(found)))
                } else if (n >= 2) {
                    val fresh = refresh(afterTool = acted, baseline = working)
                    val index = stepArgs.int("index")
                    if (index != null) {
                        // Indices refer to the screen the model saw; find that same element now.
                        val now = if (fresh.packageName == current.packageName) relocate(index, current, fresh) else null
                        if (now == null) {
                            lines += "$n. $tool: STOPPED: screen changed at step $n"
                            finalScreen = fresh
                            break
                        }
                        if (now != index) stepArgs = JsonObject(stepArgs + ("index" to JsonPrimitive(now)))
                    }
                    working = fresh
                }
                if (!runStep(tool, stepArgs, working, live = if (n >= 2 || target != null) working else null)) {
                    lines += "$n. $tool: CANCELLED by user"
                    break
                }
                lines += "$n. $tool: OK"
                acted = tool
                actedOn?.let { working = it }
            } catch (e: ScreenInterruptedException) {
                lines += "$n. $tool: STOPPED: $INTERRUPTED"
                break
            } catch (e: StaleTarget) {
                lines += "$n. $tool: STOPPED: $STALE"
                finalScreen = e.fresh
                break
            } catch (e: ServiceUnavailableException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: ArgException) {
                lines += "$n. $tool: ERROR: invalid arguments: ${e.message}"
                break
            } catch (e: Exception) {
                lines += "$n. $tool: ERROR: ${e.message ?: e.toString()}"
                break
            }
        }
        return screenResult(lines.joinToString("\n"), finalScreen ?: refresh(afterTool = acted, baseline = working))
    }

    /**
     * The screen on which an element labelled [label] shows, and its index there: [first], else screens re-read
     * every [TARGET_POLL_MS] until [TARGET_WAIT_MS] has passed (a new screen still loading). When it never shows,
     * the last screen read and null.
     */
    private suspend fun awaitTarget(label: String, first: CompressedScreen): Pair<CompressedScreen, Int?> {
        var screen = first
        var waited = 0L
        while (true) {
            findLabel(label, screen)?.let { return screen to it }
            if (waited >= TARGET_WAIT_MS) return screen to null
            delay(TARGET_POLL_MS)
            waited += TARGET_POLL_MS
            screen = readNow()
        }
    }

    /**
     * The index in [fresh] of the element [index] of [old]: same window, child path and label;
     * failing that, a label that names exactly one element on both screens. Null if it is gone.
     */
    private fun relocate(index: Int, old: CompressedScreen, fresh: CompressedScreen): Int? {
        val node = element(index, old)
        val label = old.fullLabels[index]
        fresh.elements.entries
            .firstOrNull { (i, n) -> n.windowId == node.windowId && n.path == node.path && fresh.fullLabels[i] == label }
            ?.let { return it.key }
        if (label.isNullOrBlank() || old.fullLabels.values.count { it == label } != 1) return null
        return fresh.fullLabels.entries.singleOrNull { it.value == label }?.key
    }

    // ---- non-step tools -----------------------------------------------------------------------

    private suspend fun listApps(query: String?): ToolResult {
        val apps = device.installedApps().filter {
            query.isNullOrBlank() || it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
        return ToolResult(apps.joinToString("\n") { "${it.label} (${it.packageName})" }, null, null, null)
    }

    private suspend fun screenshotTool(): ToolResult {
        if (!screenshots) return error("screenshots are off in settings (text only); use get_screen")
        val before = TreeCompressor.compress(device.snapshot())
        if (isProtected(before)) return ToolResult("STOPPED: nothing was done.\n\n# Screen\n${protectedText(before)}", before, null, null)
        val shot = device.screenshot() ?: return error("screenshot blocked (secure screen)")
        val screen = TreeCompressor.compress(device.snapshot())
        return ToolResult("OK\n\n# Screen\n${screen.text}", screen, shot, null)
    }

    // ---- results ------------------------------------------------------------------------------

    /**
     * Waits for the UI, then reads the screen. [afterTool] is the action just taken, if any: then the wait is the
     * settle after an action (see [DeviceController.settleAfterAction]), [baseline] being the screen it acted on.
     */
    private suspend fun refresh(afterTool: String? = null, baseline: CompressedScreen? = null): CompressedScreen {
        seenInterruptions = device.interruptions()
        watch = null
        val waitStart = System.nanoTime()
        var remaining = 0L
        val how = when (afterTool) {
            // Nothing acted (a plain read, or the user had the screen), or "wait" already waited.
            null, "wait" -> {
                device.waitForIdle()
                null
            }
            else -> if (Speculation.enabled) {
                val quick = device.settleQuick(baseline?.text, settleKind(afterTool, baseline))
                remaining = quick.remainingMs
                quick.how
            } else {
                device.settleAfterAction(baseline?.text, settleKind(afterTool, baseline))
            }
        }
        clock.settled(sinceMs(waitStart), how)
        val readStart = System.nanoTime()
        val screen = TreeCompressor.compress(device.snapshot())
        clock.read(sinceMs(readStart), screen.text.length)
        if (remaining > 0) watch = ScreenWatch(screen.text.hashCode(), remaining, ScreenJournal.fingerprint(screen))
        return screen
    }

    /**
     * The live screen's [ScreenJournal.fingerprint] (digits left out), read without waiting and without touching
     * the interruption count; for the speculative watch, which ignores a ticking clock or a progress number.
     */
    suspend fun liveFingerprint(): Int = ScreenJournal.fingerprint(TreeCompressor.compress(device.snapshot()))

    private fun sinceMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

    /** Opening the app that is already in front changes little, so it settles like a tap. */
    private fun settleKind(tool: String, baseline: CompressedScreen?): SettleKind = when (tool) {
        "open_app", "open_route" ->
            if (launchedPackage != null && launchedPackage == baseline?.packageName) SettleKind.ACTION else SettleKind.APP
        "open_uri" -> SettleKind.LINK
        else -> SettleKind.ACTION
    }

    /** Reads the live screen now, without waiting (the model took seconds to answer; the UI has had its time). */
    private suspend fun readNow(): CompressedScreen {
        seenInterruptions = device.interruptions()
        val readStart = System.nanoTime()
        val screen = TreeCompressor.compress(device.snapshot())
        clock.read(sinceMs(readStart), screen.text.length)
        return screen
    }

    private suspend fun settled(head: String, afterTool: String? = null, baseline: CompressedScreen? = null): ToolResult =
        screenResult(head, refresh(afterTool, baseline))

    private suspend fun screenResult(head: String, screen: CompressedScreen): ToolResult {
        val shot = look(screen)
        if (isProtected(screen, shot, tried = screenshots)) return ToolResult("$head\n\n# Screen\n${protectedText(screen)}", screen, null, null)
        return ToolResult("$head\n\n# Screen\n${screen.text}", screen, shot, null)
    }

    /**
     * The screenshot that goes with every screen read (vision first): each listed element that can be acted on is
     * outlined and tagged with its number, so what the model sees matches the list it acts by. Null when
     * screenshots are off in settings, or the capture failed (a protected app).
     */
    private suspend fun look(screen: CompressedScreen): ByteArray? {
        if (!screenshots || protectedApps[screen.packageName] == true) return null
        val start = System.nanoTime()
        return device.markedScreenshot(marks(screen)).also { clock.shot(sinceMs(start)) }
    }

    /** The elements a marked screenshot tags: the actionable ones, or every listed one on a screen with none. */
    private fun marks(screen: CompressedScreen): List<ScreenMark> {
        val tagged = screen.elements.filterValues { it.isActionable }.ifEmpty { screen.elements }
        return tagged.entries.take(MAX_MARKS).map { (i, n) ->
            ScreenMark(i, (n.uncovered ?: n.bounds).clippedTo(screen.displayWidth, screen.displayHeight))
        }
    }

    /**
     * Whether [screen]'s app blocks screen capture (FLAG_SECURE: banking, rail ticketing, payment apps). Checked once
     * per app per run with a screenshot that is never sent anywhere; a failure is tried once more, so one slow capture
     * does not lock an ordinary app out.
     */
    private suspend fun isProtected(screen: CompressedScreen, taken: ByteArray? = null, tried: Boolean = false): Boolean {
        val pkg = screen.packageName ?: return false
        protectedApps[pkg]?.let { return it }
        // A capture just [taken] of this screen answers it; one that was [tried] and failed counts as the first try.
        var shot = taken ?: if (tried) null else device.screenshot()
        if (shot == null) {
            delay(PROTECTED_RETRY_MS)
            shot = device.screenshot()
        }
        return (shot == null).also { protectedApps[pkg] = it }
    }

    /** What the model sees of a protected app's screen: its name and why nothing more is shown. */
    private fun protectedText(screen: CompressedScreen): String = "app: ${screen.packageName}\n$PROTECTED"

    private fun error(reason: String) = ToolResult("ERROR: $reason", null, null, null)

    private fun cancelled() = ToolResult("CANCELLED by user", null, null, null)

    private fun interruptedSinceRead(): Boolean = seenInterruptions.let { it != null && it != device.interruptions() }

    /** Nothing done: the screen the model aimed at may be gone; it gets the fresh screen to retry on. */
    private suspend fun interrupted(): ToolResult = screenResult("STOPPED: $INTERRUPTED Nothing more was done; use the screen below.", refresh())

    // ---- argument helpers ---------------------------------------------------------------------

    private fun parseArgs(json: String): JsonObject {
        if (json.isBlank()) return JsonObject(emptyMap())
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw ArgException("bad JSON")
        }
        return element as? JsonObject ?: throw ArgException("arguments must be a JSON object")
    }

    companion object {
        /** At most this many tags on a marked screenshot; past it they hide the screen they label. */
        private const val MAX_MARKS = 120

        private const val PROTECTED_RETRY_MS = 400L

        /** In place of the screen of an app that blocks screen capture. */
        const val PROTECTED =
            "PROTECTED APP: this app blocks screen capture (as banking, ticketing and payment apps do), so it is " +
                "not operated: its screen is not read and taps, typing and scrolling in it are refused. Do not try " +
                "other ways in. Finish now: tell the user this app protects its screen, so they need to do this " +
                "part in the app themselves, and give them whatever else they asked for that you could do."
        private const val SEND_PREVIEW_CHARS = 60
        private const val CONTEXT_PREVIEW_CHARS = 80
        private const val LABEL_PREVIEW_CHARS = 120
        /** A field named as a password or PIN; "암호화폐" (crypto) is not one. */
        private val PASSWORD_FIELD = Regex(
            """비밀\s*번호|암호(?!화)|패스워드|(?<![a-z])(password|passcode|passwd|pwd|pin)(?![a-z])""",
        )
        private val SPOT_KEYS = listOf("index", "x", "y", "x1", "y1")
        private const val UNSEEN = "you have not seen the screen yet, so element numbers and positions are guesses; choose on the screen below."

        /** Whitespace collapsed to single spaces, trimmed, lower case (comparing sent text with the user's words). */
        private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim().lowercase()

        /** Delete verbs in the user's own words (normalized, see [norm]) that make a DELETE skippable ([userAskedDelete]). */
        private val DELETE_VERBS_CJK = listOf("삭제", "지워", "지우", "제거", "없애", "빼줘", "빼 줘", "削除", "删除", "刪除")
        private val DELETE_VERBS_EN = Regex("""(?<![a-z])(?:delete|remove|erase|clear)""")

        /** A phone number as people write it inside a sentence: digits with spaces, dashes, dots or brackets between them. */
        private val NUMBER_TOKEN = Regex("""\+?[0-9][0-9 ().\-]*[0-9]""")

        /**
         * Whether [words] hold [number] written as one number: the same digits, or the same line with or without its
         * country code. Digits scattered over the message (a time, a count, a date) never add up to one.
         */
        private fun saysNumber(words: String, number: String): Boolean {
            val wanted = SmsTool.normalizeNumber(number) ?: return false
            return NUMBER_TOKEN.findAll(words).any { token ->
                SmsTool.normalizeNumber(token.value)?.let { ContactAdder.sameNumber(it, wanted) } == true
            }
        }

        /**
         * Whether [words] hold [name] (2+ letters) as a word of its own: not the tail of a longer word ("민수" in
         * "김민수"), nor the head of a longer Latin one ("min" in "minsu"). What follows a Korean name is a particle.
         */
        private fun saysName(words: String, name: String): Boolean {
            val wanted = norm(name)
            if (wanted.length < 2) return false
            val latinEnd = wanted.last().let { it in 'a'..'z' || it in '0'..'9' }
            var at = words.indexOf(wanted)
            while (at >= 0) {
                val before = words.getOrNull(at - 1)
                val after = words.getOrNull(at + wanted.length)
                val runsOn = latinEnd && after != null && (after in 'a'..'z' || after in '0'..'9')
                if (before?.isLetterOrDigit() != true && !runsOn) return true
                at = words.indexOf(wanted, at + 1)
            }
            return false
        }

        /** Phrases with a call word in them that ask about calls, or not to call, instead of asking to place one. */
        private val NOT_A_CALL = listOf(
            "전화번호", "전화 번호", "통화 기록", "통화기록", "통화 내역", "통화내역", "부재중", "전화 왔", "전화왔", "전화 온", "전화온",
            "전화하지", "전화 하지", "통화하지", "통화 하지", "전화했", "전화 했", "통화했", "통화 했", "전화받", "전화 받", "통화 녹음", "통화녹음",
            "電話番号", "着信", "电话号码", "電話號碼", "未接", "phone number", "call log", "call history", "missed call",
        )
        private val NOT_A_CALL_EN = Regex("""(?<![a-z])(?:don['’]?t|do not|never|did(?:n['’]?t)?)(?![a-z])[^.?!]*?(?<![a-z])call""")
        private val CALL_WORDS_CJK = listOf(
            "전화", "통화", "걸어줘", "걸어 줘", "걸어주", "걸어 주", "걸어봐", "걸어 봐", "電話", "かけて", "电话", "打给", "打給", "拨打", "撥打",
        )
        private val CALL_WORDS_EN = Regex("""(?<![a-z])(?:call|dial)(?![a-z])""")

        /** Asking to stop, remove or look at an alarm is not asking for a new one; these are taken out before the alarm words are looked for. */
        private val NOT_AN_ALARM = listOf(
            Regex("""알람[을은이도]?\s*(?:좀\s*|다\s*|모두\s*|전부\s*)*(?:꺼|끄|삭제|지워|지우|취소|해제|없애|몇|언제|있|확인|보여|알려)"""),
            Regex("""(?:アラーム|目覚まし)[をはが]?\s*(?:消|止|解除|キャンセル|削除|オフ)"""),
            Regex("""(?:关闭|取消|删除|關閉|刪除|停止)[^,.。，]{0,6}(?:闹钟|鬧鐘)"""),
            Regex("""(?<![a-z])(?:cancel|delete|remove|dismiss|turn off|disable|stop|snooze|next|what|when|which|any)(?![a-z])[a-z ]{0,20}alarm"""),
        )
        private val ALARM_WORDS_CJK = listOf("알람", "깨워", "모닝콜", "アラーム", "目覚まし", "起こして", "闹钟", "鬧鐘", "叫醒")
        private val ALARM_WORDS_EN = Regex("""(?<![a-z])(?:alarms?|wake)(?![a-z])""")

        /** The same for a timer. */
        private val NOT_A_TIMER = listOf(
            Regex("""타이머[를은이도]?\s*(?:좀\s*|다\s*|모두\s*|전부\s*)*(?:꺼|끄|취소|중지|멈|정지|삭제|지워|지우|해제|없애|얼마|몇|확인)"""),
            Regex("""タイマー[をはが]?\s*(?:消|止|解除|キャンセル|削除|オフ)"""),
            Regex("""(?:关闭|取消|删除|關閉|刪除|停止|暂停|暫停)[^,.。，]{0,6}(?:计时|計時|定时)"""),
            Regex("""(?<![a-z])(?:cancel|delete|remove|dismiss|stop|pause|reset|turn off|how much|how long)(?![a-z])[a-z ]{0,20}timer"""),
        )
        private val TIMER_WORDS_CJK = listOf("타이머", "タイマー", "计时", "計時", "定时", "倒计时")
        private val TIMER_WORDS_EN = Regex("""(?<![a-z])(?:timers?|countdown)(?![a-z])""")

        /** Words for "all / many" in the user's request that make a delete bulk ([userAskedBulkDelete]). */
        private val BULK_WORDS_CJK = listOf(
            "전부", "모두", "모든", "싹 ", "싹다", "다 지", "다 삭제", "다 없애", "다 제거", "다 빼", "다지워", "다삭제",
            "全部", "すべて", "全て", "所有",
        )
        private val BULK_WORDS_EN = Regex("""(?<![a-z])(?:all|every|everything|each)(?![a-z])""")

        private val SENSITIVE_FIELD = Regex(
            """카드\s*번호|주민\s*(등록)?\s*번호|보안\s*코드|외국인\s*등록\s*번호|(?<![a-z])(cvc|cvv|card[\s_-]*(number|no)|security[\s_-]*code|ssn)(?![a-z])""",
        )
        private val SEARCH_MARKERS = listOf("검색", "찾기", "search", "query", "url_bar", "location_bar", "omnibox")
        private const val MAX_WAIT_MS = 5000
        private const val DEFAULT_SWIPE_MS = 300

        /** Swipes shorter than this land as taps, and the risk gate judges them as taps. */
        private const val TAP_SLOP_PX = 24.0
        private val GENERIC_AFFIRMATIVES = listOf(
            "확인", "예", "네", "계속", "동의", "허용", "OK", "Yes", "Continue", "Confirm", "Done", "Accept", "Allow",
        )
        private const val INTERRUPTED = "the user used voice input, so the screen may have changed and element numbers are stale."
        private val DIRECTIONS = setOf("up", "down", "left", "right")
        private val KEYS = mapOf(
            "back" to GlobalAction.BACK,
            "home" to GlobalAction.HOME,
            "recents" to GlobalAction.RECENTS,
            "notifications" to GlobalAction.NOTIFICATIONS,
            "quick_settings" to GlobalAction.QUICK_SETTINGS,
        )
        private const val STALE = "the screen changed since you read it, so element numbers are stale; choose again on the screen below."
        private val SCREEN_TOOLS = setOf("tap", "long_press", "type_text", "scroll", "swipe", "pinch", "batch")
        /** Tools that act on or read the phone screen; their results carry deviceTool=true. */
        val DEVICE_TOOLS: Set<String> = setOf(
            "tap", "long_press", "type_text", "scroll", "swipe", "pinch", "press_key", "open_app", "open_uri", "batch",
            "get_screen", "screenshot", "wait", "open_route",
        )
        /** How long a batch step's target label may take to appear, and how often the screen is re-read. */
        const val TARGET_WAIT_MS = 3000L
        const val TARGET_POLL_MS = 150L

        /**
         * The element labelled [label] on [screen]: an exact label (ignoring case and spacing) before one that
         * contains it, an actionable element before plain text, the first in reading order. Null if none.
         */
        fun findLabel(label: String, screen: CompressedScreen): Int? {
            val want = squash(label)
            if (want.isEmpty()) return null
            val hits = screen.fullLabels.entries.filter { squash(it.value).contains(want) }
            return hits.sortedWith(
                compareBy<Map.Entry<Int, String>>(
                    { squash(it.value) != want },
                    { screen.elements[it.key]?.isActionable != true },
                ),
            ).firstOrNull()?.key
        }

        private fun squash(t: String) = t.lowercase().replace(Regex("\\s+"), "")

        private val BATCH_TOOLS = setOf(
            "tap", "long_press", "type_text", "scroll", "swipe", "pinch", "press_key", "open_app", "open_uri", "wait",
        )
    }
}
