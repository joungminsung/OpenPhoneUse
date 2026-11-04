package com.phoneuse.agent

import com.phoneuse.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Tools from connected services (MCP servers, Google) for one run. Their names never clash with the built-in
 * tools (`mcp__…`, `gmail_…`, `drive_…`). Results are third-party content (ToolExecutor marks the run for memory, as for
 * any tool output); a call that
 * sends, deletes or pays asks [UserGate.confirm] first, whatever the model claims about authorization.
 */
interface ConnectorTools {
    val specs: List<ToolSpec>

    fun handles(name: String): Boolean

    suspend fun call(name: String, args: JsonObject, gate: UserGate): ToolResult
}

/**
 * The run's [connectors] as a module (none when null). They ask the user themselves before sending, deleting or
 * paying, on [gate]. With the confirm switch off a send skips its card only when the call claimed user_authorized
 * on a run that has read no third-party text ([ConfirmPolicyGate.forClaim]; the gate checks the taint); the claim
 * is taken out of the arguments before they reach the service.
 */
internal class ConnectorModule(private val connectors: ConnectorTools?, private val gate: UserGate) : ToolModule {
    override val specs: List<ToolSpec> get() = connectors?.specs.orEmpty()

    override fun handles(name: String): Boolean = connectors?.handles(name) == true

    override suspend fun run(name: String, args: JsonObject, context: ToolContext): ToolResult {
        val c = connectors ?: return ToolResult("ERROR: unknown tool $name", null, null, null)
        val claimed = (args[CLAIM] as? JsonPrimitive)?.booleanOrNull == true
        val callGate = (gate as? ConfirmPolicyGate)?.forClaim(claimed) ?: gate
        return c.call(name, if (CLAIM in args) JsonObject(args - CLAIM) else args, callGate)
    }

    private companion object {
        const val CLAIM = "user_authorized"
    }
}
