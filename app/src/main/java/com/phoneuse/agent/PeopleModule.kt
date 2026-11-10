package com.phoneuse.agent

import com.phoneuse.cards.PermissionCard
import com.phoneuse.model.ToolSpec
import com.phoneuse.phone.CallLogTool
import com.phoneuse.phone.ContactAdder
import com.phoneuse.phone.PhoneReply
import com.phoneuse.risk.RiskCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * recent_calls / add_contact; a null part leaves its tool out. Saving a contact is CHANGE-confirmed through
 * [gate], except when the user typed that very number, name and email themselves in this run ([userGaveContact],
 * given the normalized number). Both read or write the user's own data, so their output does not mark the run
 * third-party.
 */
internal class PeopleModule(
    private val callLog: CallLogTool?,
    private val adder: ContactAdder?,
    private val gate: UserGate?,
    private val userGaveContact: (name: String, number: String, email: String?) -> Boolean = { _, _, _ -> false },
) : ToolModule {
    override val specs: List<ToolSpec> get() = SPECS

    override val offered: Set<String> = buildSet {
        if (callLog != null) add(RECENT_CALLS)
        if (adder != null && gate != null) add(ADD_CONTACT)
    }

    override val userText: Set<String> get() = OWN_DATA

    /** Labels whose [권한 허용] card this run already showed; a second call does not add another. */
    private val asked = HashSet<String>()

    override suspend fun run(name: String, args: JsonObject, context: ToolContext): ToolResult {
        if (name !in offered) return fail("$name is not available")
        val reply = try {
            when (name) {
                RECENT_CALLS -> {
                    val tool = callLog ?: return fail("$name is not available")
                    val type = args.str("type")
                    val limit = args.int("limit")
                    val days = args.int("days")
                    io { tool.recent(type, limit, days) }
                }
                ADD_CONTACT -> {
                    val tool = adder ?: return fail("$name is not available")
                    val asks = gate ?: return fail("$name is not available")
                    val who = args.requireStr("name")
                    val phone = args.requireStr("phone")
                    val email = args.str("email")
                    val mail = email?.trim()?.takeIf { it.isNotEmpty() }
                    io { tool.add(who, phone, email) { number, summary -> userGaveContact(who, number, mail) || asks.confirm(RiskCategory.CHANGE, summary) } }
                }
                else -> return fail("unknown tool $name")
            }
        } catch (e: IllegalArgumentException) {
            return fail("invalid arguments: ${e.message}")
        }
        return when (reply) {
            is PhoneReply.Ok -> ToolResult(reply.text, null, null, null, card = reply.card)
            is PhoneReply.PermissionNeeded -> ToolResult(
                "${reply.text} — 채팅에 [권한 허용] 카드를 띄웠어요. 사용자에게 허용해 달라고 짧게 말하고 finish 하세요.",
                null, null, null,
                card = if (asked.add(reply.label)) PermissionCard(reply.label, reply.permissions) else null,
            )
            is PhoneReply.Failed, PhoneReply.Cancelled -> ToolResult(reply.text, null, null, null)
        }
    }

    /** Provider reads and writes block, so they run off the caller's thread. */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun fail(reason: String) = ToolResult("ERROR: $reason", null, null, null)

    companion object {
        const val RECENT_CALLS = "recent_calls"
        const val ADD_CONTACT = "add_contact"

        /** The user's own call history and address book, not text from someone else. */
        val OWN_DATA = setOf(RECENT_CALLS, ADD_CONTACT)

        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                RECENT_CALLS,
                "The phone's call history, newest first: [{name?, number, type, at, duration_sec}]. type is missed, incoming, " +
                    "outgoing, rejected, blocked, voicemail or other; name is the saved contact's name, absent for an unsaved number. " +
                    "Use it for missed calls, \"who called\" and \"what number just called\".",
                schema(
                    props = mapOf(
                        "type" to enumProp(
                            "missed, incoming (every call that came in, answered or not), outgoing, or all (default).",
                            listOf("missed", "incoming", "outgoing", "all"),
                        ),
                        "limit" to prop("integer", "At most this many (default 10, at most 30)."),
                        "days" to prop("integer", "How many days back (default 7, at most 90)."),
                    ),
                ),
            ),
            ToolSpec(
                ADD_CONTACT,
                "Save a new contact in the phone's address book, for \"save this number as …\". The user confirms on a card " +
                    "first unless they typed the number themselves. It does not change a contact that already exists.",
                schema(
                    required = listOf("name", "phone"),
                    props = mapOf(
                        "name" to prop("string", "The name to save it under, e.g. \"민수\"."),
                        "phone" to prop("string", "Phone number, e.g. 010-1234-5678."),
                        "email" to prop("string", "Email address (optional)."),
                    ),
                ),
            ),
        )
    }
}
