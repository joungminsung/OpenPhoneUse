package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.model.ModelClient
import com.phoneuse.model.ModelException
import com.phoneuse.model.ToolSpec
import com.phoneuse.model.Usage
import com.phoneuse.screen.CompressedScreen
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The fast helper behind the main model's `delegate` tool: a lighter model (Luna) does one mechanical, bounded
 * piece of phone work — scrolling a list and collecting its items, paging through results, repeating taps — and
 * reports back. The main model (Terra) keeps every judgement: it plans, reads the report and answers. The helper
 * acts through the same [ToolExecutor], so every risk check, confirmation and taint rule applies unchanged.
 * It keeps its own reasoning and a journal of screens between steps and judges its own progress; a model error or a
 * declined confirmation ends it early, and its step limit is the only fixed stop. Either way the main model gets the screen.
 */
class Helper(private val model: ModelClient, private val executor: ToolExecutor) {
    data class Result(
        val ok: Boolean,
        val report: String,
        val screen: CompressedScreen?,
        val scrolls: Int,
        val steps: Int,
    )

    /**
     * False after the helper's model failed for good (retries spent, or a request error): the rest of the run does
     * the work itself. delegate stays in the tool list (the prompt cache), and answers with an error.
     */
    var available = true
        private set

    /**
     * Runs [task] until it reports, at most [maxSteps] model calls. [onAction] is told of each action before it
     * runs, with the screen it acts on (the progress line) and [onUsage] of each model call's tokens.
     * [interrupted] is checked before each step: true (the user sent a new instruction) ends the helper at once,
     * so the main model reads it.
     */
    suspend fun run(
        task: String,
        doneWhen: String?,
        maxSteps: Int,
        onAction: suspend (InputItem.FunctionCall, CompressedScreen?) -> Unit,
        onUsage: suspend (Usage) -> Unit,
        interrupted: () -> Boolean = { false },
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): Result {
        val start = executor.observe()
        var current = start.screen
        val input = ArrayList<InputItem>()
        input += InputItem.UserText(taskText(task, doneWhen))
        var screenAt = input.size
        var shown = start.screen
        input += InputItem.UserText(start.output)
        val journal = ScreenJournal()
        start.screen?.let { journal.revisit(0, "start", it) }
        val tools = executor.specs.filter { it.name in TOOLS }.map(::withoutClaim) + REPORT
        var scrolls = 0
        var lastText: String? = null
        for (step in 1..maxSteps) {
            if (interrupted()) {
                return Result(false, "stopped: the user sent a new instruction; last note: ${lastText ?: "none"}", current, scrolls, step - 1)
            }
            // Told before its last call, so what it collected is reported instead of lost at the step limit.
            if (step == maxSteps && maxSteps > 1) input += InputItem.UserText(LAST_STEP)
            val turn = try {
                ModelRetry.call(random = random) { model.next(INSTRUCTIONS, input, tools) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelException) {
                available = false
                return Result(false, "helper unavailable (${e.message}); do this yourself", current, scrolls, step - 1)
            }
            turn.usage?.let { onUsage(it) }
            input += turn.reasoning
            turn.text?.let { lastText = it; input += InputItem.AssistantText(it) }
            if (turn.calls.isEmpty()) return Result(true, turn.text ?: "(no report)", current, scrolls, step)
            // One call per turn, as in the main loop: a later call's element numbers were chosen on a screen the
            // first one may have changed. The others go back unrun.
            val call = turn.calls.first()
            if (call.name == "report") {
                val args = parse(call.argumentsJson)
                val ok = (args?.get("ok") as? JsonPrimitive)?.content != "false"
                val text = (args?.get("result") as? JsonPrimitive)?.content ?: "(empty report)"
                return Result(ok, text, current, scrolls, step)
            }
            val unrun = turn.calls.drop(1)
            fun sendBackUnrun() = unrun.forEach {
                input += it
                input += InputItem.FunctionOutput(it.callId, ONE_CALL)
            }
            input += call
            if (call.name !in TOOLS) {
                input += InputItem.FunctionOutput(call.callId, "ERROR: not available to the helper; use report")
                sendBackUnrun()
                continue
            }
            onAction(call, current)
            val result = executor.execute(call, current, fromHelper = true)
            var out = result.output
            result.screen?.let { screen ->
                // Only the newest screen stays in full, as in the main loop; a revisit says so.
                shown?.let { old -> input[screenAt] = digested(input[screenAt], old) }
                screenAt = input.size
                shown = screen
                current = screen
                journal.revisit(step, call.name + " " + call.argumentsJson.take(60), screen)?.let { seen ->
                    out = ScreenJournal.withNote(out, seen)
                }
            }
            input += InputItem.FunctionOutput(call.callId, out)
            sendBackUnrun()
            // A declined confirmation is the user's answer: the helper does not try again.
            if (out.startsWith("CANCELLED") || out.contains(": CANCELLED")) {
                return Result(false, "the user cancelled a step; stopped", current, scrolls, step)
            }
            if (!out.startsWith("ERROR")) scrolls += scrollsIn(call)
        }
        return Result(false, "step limit reached; last note: ${lastText ?: "none"}", current, scrolls, maxSteps)
    }

    private fun parse(json: String): JsonObject? = try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Tools the helper may use: acting inside the current app, never leaving it or using data tools. */
        val TOOLS = setOf("tap", "long_press", "type_text", "scroll", "swipe", "press_key", "wait", "batch", "get_screen")

        private val BATCHED_SCROLL = Regex("\"tool\"\\s*:\\s*\"(scroll|swipe)\"")

        /** Scrolls in [call]: a scroll or swipe, or those inside a batch (reported to the main model). */
        fun scrollsIn(call: InputItem.FunctionCall): Int = when (call.name) {
            "scroll", "swipe" -> 1
            "batch" -> BATCHED_SCROLL.findAll(call.argumentsJson).count()
            else -> 0
        }

        const val DEFAULT_STEPS = 15
        const val MAX_STEPS = 30

        const val ONE_CALL = "NOT RUN: only the first call of a response runs. Use batch for several steps; call it again if it is still needed on the screen above."
        const val LAST_STEP = "This is your last step: call report now with everything you collected so far (ok=false if the task is not finished, saying where you stopped)."

        /**
         * [spec] without its user_authorized property: the helper never saw what the user wrote, so it cannot vouch
         * for a send (the executor ignores the flag from it anyway).
         */
        internal fun withoutClaim(spec: ToolSpec): ToolSpec {
            val props = spec.parameters["properties"] as? JsonObject ?: return spec
            if ("user_authorized" !in props) return spec
            return spec.copy(parameters = JsonObject(spec.parameters + ("properties" to JsonObject(props - "user_authorized"))))
        }

        /** The main model's delegate result, with the screen the helper ended on. */
        fun output(r: Result): String = buildString {
            append(if (r.ok) "DELEGATE DONE" else "DELEGATE INCOMPLETE")
            append(" (helper steps: ").append(r.steps).append(", scrolls: ").append(r.scrolls).append(")\n")
            append(r.report)
            r.screen?.let { append("\n\n# Screen\n").append(it.text) }
        }

        /** [item] with [screen]'s tree replaced by its one-line digest. */
        private fun digested(item: InputItem, screen: CompressedScreen): InputItem {
            fun cut(text: String) = if (text.endsWith(screen.text)) text.dropLast(screen.text.length) + ScreenJournal.digest(screen) else text
            return when (item) {
                is InputItem.FunctionOutput -> item.copy(output = cut(item.output))
                is InputItem.UserText -> item.copy(text = cut(item.text))
                else -> item
            }
        }

        private fun taskText(task: String, doneWhen: String?) = buildString {
            append("Task: ").append(task)
            doneWhen?.takeIf { it.isNotBlank() }?.let { append("\nDone when: ").append(it) }
        }

        val INSTRUCTIONS = """
            You are the fast helper of a phone assistant. The main assistant gave you one concrete, mechanical task on the app that is open now. Do exactly that task, quickly, then call report.
            - You read the screen as a text tree; act on elements by their [n] index. A list with more-below has more items: scroll on.
            - Only the first call of each response runs. Do several predictable steps per call with batch (elements on screens you have not seen can be named by args.target, their visible label).
            - Collect facts exactly as shown (names, prices, dates, counts). Do not judge, choose or recommend: the main assistant decides.
            - Stay in this app. Do not send, pay, delete, log in or change settings; if the task needs that, report and say so.
            ${RiskJudge.PROMPT}
            - You keep your earlier reasoning, and earlier screens stay as one-line digests; a result that says "Seen before" means you are back on a screen you already saw. If you are not making progress, report ok=false with what you saw rather than repeating yourself. Keep reports compact: one line per item.
        """.trimIndent()

        private val REPORT = ToolSpec(
            "report",
            "Finish the task: give the main assistant your result.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("result") {
                        put("type", "string")
                        put("description", "What you found or did: the collected items one per line, or why you stopped.")
                    }
                    putJsonObject("ok") {
                        put("type", "boolean")
                        put("description", "False when the task could not be completed.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("result")) }
            },
        )
    }
}
