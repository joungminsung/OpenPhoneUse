package com.phoneuse.agent

import com.phoneuse.model.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** ask_user and hand_to_user: the run waits on the user through [gate]. */
internal class UserModule(private val gate: UserGate) : ToolModule {
    override val specs: List<ToolSpec> get() = SPECS

    /** ask_user's answer is the user's own. */
    override val userText: Set<String> get() = USER_TEXT

    override suspend fun run(name: String, args: JsonObject, context: ToolContext): ToolResult = when (name) {
        "ask_user" -> {
            val options = (args["options"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            // The schema asks for 2-4 buttons; fewer still shows the card (the user can type or speak).
            if (options.size < 2) LOG.warning("ask_user with ${options.size} option(s)")
            val answer = gate.ask(args.requireStr("question"), options)
            ToolResult("User answered: $answer", null, null, null)
        }
        "hand_to_user" -> {
            gate.handToUser(args.requireStr("reason"))
            context.settled("OK (user finished)")
        }
        else -> ToolResult("ERROR: unknown tool $name", null, null, null)
    }

    private companion object {
        private val LOG: java.util.logging.Logger = java.util.logging.Logger.getLogger("PhoneUse")
        private val USER_TEXT = setOf("ask_user")

        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                "ask_user",
                "Ask the user one short question and wait for the answer. Only when truly blocked (see the rules). " +
                    "The user answers with one tap on an option button (or by voice/typing), so the options must " +
                    "cover the likely answers.",
                schema(
                    required = listOf("question", "options"),
                    props = mapOf(
                        "question" to prop("string", "One short question."),
                        "options" to buildJsonObject {
                            put("type", "array")
                            put(
                                "description",
                                "2-4 short answers shown as big buttons (a few words each, e.g. contact names). " +
                                    "They must cover the likely answers so the user can just tap one.",
                            )
                            put("minItems", 2)
                            put("maxItems", 4)
                            putJsonObject("items") { put("type", "string") }
                        },
                    ),
                ),
            ),
            ToolSpec(
                "hand_to_user",
                "Hand control to the user for something you must not do yourself (passwords, CAPTCHA, payment). Returns when the user is done.",
                schema(required = listOf("reason"), props = mapOf("reason" to prop("string", "Why the user must act."))),
            ),
        )
    }
}
