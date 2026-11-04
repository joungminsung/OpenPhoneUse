package com.phoneuse.agent

import com.phoneuse.model.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * update_plan: the agent's own checklist for a long task. The model writes the steps and marks them as it goes;
 * the plan stays in its context as a tool result (screens are cut to digests, this is not), so a 50-step task
 * keeps its goal and progress in view. The step being worked on shows in the progress lines.
 */
internal class PlanModule : ToolModule {
    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            NAME,
            "Write or update your plan for a task with several steps: the whole list each time, in order, each with " +
                "its status. Mark exactly one step in_progress while working and the finished ones done. Use it at the " +
                "start of a longer task and whenever a step is done or the plan changes. Call it in the same response " +
                "as your next action (both run), never in a response of its own; do not update it just to mark the last " +
                "step done before finish.",
            schema(
                listOf("steps"),
                mapOf(
                    "steps" to arr(
                        obj(
                            "text" to str("The step, short (what to do or check)."),
                            "status" to enumProp("Where the step stands.", STATUSES),
                            required = listOf("text", "status"),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** The model's own words. */
    override val userText: Set<String> = setOf(NAME)

    override suspend fun run(name: String, args: JsonObject, context: ToolContext): ToolResult {
        val steps = (args["steps"] as? JsonArray)?.mapNotNull { it as? JsonObject }
        if (steps.isNullOrEmpty()) return ToolResult("ERROR: steps required", null, null, null)
        val lines = steps.map { step ->
            val text = (step["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val status = (step["status"] as? JsonPrimitive)?.content?.takeIf { it in STATUSES } ?: "pending"
            "${MARKS.getValue(status)} $text"
        }
        val done = steps.count { (it["status"] as? JsonPrimitive)?.content == "done" }
        return ToolResult("OK: plan updated ($done/${steps.size} done)\n" + lines.joinToString("\n"), null, null, null)
    }

    companion object {
        const val NAME = "update_plan"
        val STATUSES = listOf("pending", "in_progress", "done")
        private val MARKS = mapOf("pending" to "[ ]", "in_progress" to "[>]", "done" to "[x]")

        /** The step in progress, for the progress line; null when none is. */
        fun current(args: JsonObject?): String? = (args?.get("steps") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["status"] as? JsonPrimitive)?.content == "in_progress" }
            ?.let { (it["text"] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
    }
}
