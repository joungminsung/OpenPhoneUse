package com.phoneuse.model

import kotlinx.serialization.json.JsonObject

/**
 * The input as alternating assistant and user messages, for the chat-style APIs (Claude Messages, Chat
 * Completions). The agent loop appends a turn's calls one at a time, each followed by its output and maybe a
 * screenshot (call 1, output 1, image, call 2, output 2); these APIs want one assistant message holding all of the
 * turn's calls and one reply holding all of their results, so a call joins the assistant message before the last
 * user message when [sameTurn] says it came from the same model response.
 */
internal object Conversation {
    sealed interface Message

    class Assistant : Message {
        /** [InputItem.Reasoning.raw] of this turn, in order. */
        val reasoning = ArrayList<JsonObject>()
        val texts = ArrayList<String>()
        val calls = ArrayList<InputItem.FunctionCall>()
    }

    class User : Message {
        val results = ArrayList<InputItem.FunctionOutput>()
        /** Text, app context and images, in order; providers put them after the results. */
        val parts = ArrayList<Part>()
    }

    sealed interface Part {
        data class Text(val text: String) : Part
        data class Context(val text: String) : Part
        data class Image(val image: InputItem.UserImage) : Part
    }

    /** [keepReasoning] picks the reasoning items this provider can read back; the rest are dropped. */
    fun group(
        input: List<InputItem>,
        keepReasoning: (JsonObject) -> Boolean,
        sameTurn: (Assistant, InputItem.FunctionCall) -> Boolean,
    ): List<Message> {
        val out = ArrayList<Message>()
        fun assistant(): Assistant = (out.lastOrNull() as? Assistant) ?: Assistant().also { out += it }
        fun user(): User = (out.lastOrNull() as? User) ?: User().also { out += it }
        for (item in input) {
            when (item) {
                is InputItem.Reasoning -> if (keepReasoning(item.raw)) assistant().reasoning += item.raw
                is InputItem.AssistantText -> if (item.text.isNotEmpty()) assistant().texts += item.text
                is InputItem.FunctionCall -> {
                    val last = out.lastOrNull()
                    val before = out.getOrNull(out.size - 2) as? Assistant
                    if (last is User && last.results.isNotEmpty() && before != null && before.calls.isNotEmpty() && sameTurn(before, item)) {
                        before.calls += item
                    } else {
                        assistant().calls += item
                    }
                }
                is InputItem.FunctionOutput -> user().results += item
                is InputItem.UserText -> {
                    val u = user()
                    item.images.forEach { u.parts += Part.Image(it) }
                    if (item.text.isNotEmpty()) u.parts += Part.Text(item.text)
                }
                is InputItem.UserImage -> user().parts += Part.Image(item)
                is InputItem.DeveloperText -> if (item.text.isNotEmpty()) user().parts += Part.Context(item.text)
            }
        }
        return out
    }

    /** How app context is put into a user message. */
    fun context(text: String) = "<app_context>\n$text\n</app_context>"
}
