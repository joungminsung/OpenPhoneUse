package com.phoneuse.agent

import android.content.Context
import com.phoneuse.files.FileTools
import com.phoneuse.model.ToolSpec
import com.phoneuse.screen.CompressedScreen
import kotlinx.serialization.json.JsonObject

/**
 * A family of tools the agent can call: it declares their specs and runs their calls. The executor offers the
 * specs of every module in [ToolRegistry] order and routes each call to the module that [handles] it, so a new
 * capability is one module file plus one registry line. What a module needs (a gate, an access object) comes
 * through its constructor from [ToolDeps].
 *
 * The executor still applies its run-wide rules around every call: malformed arguments ([ArgException]) become
 * "ERROR: invalid arguments: ...", and any output a module does not list in [userText] marks the run as having
 * read third-party text (memory then saves only guesses, see [ToolExecutor.markThirdParty]).
 */
interface ToolModule {
    /** Every tool of this module, in the order the model sees them (part of the prompt and its cache). */
    val specs: List<ToolSpec>

    /**
     * The names of [specs] this run offers the model; the others stay out of the tool list. A call to one left
     * out still reaches [run], which should refuse it.
     */
    val offered: Set<String> get() = specs.mapTo(HashSet()) { it.name }

    /** Tools whose output is only the user's own words or data, so it does not mark the run third-party. */
    val userText: Set<String> get() = emptySet()

    /** Whether [run] takes calls to [name]. */
    fun handles(name: String): Boolean = specs.any { it.name == name }

    suspend fun run(name: String, args: JsonObject, context: ToolContext): ToolResult
}

/** What the executor lends a module for one call. */
interface ToolContext {
    /** The screen the model last saw, or null when it has not read one. */
    val screen: CompressedScreen?

    /**
     * Waits for the UI to settle, then returns [head] with the fresh screen. [afterTool] names the action just
     * taken (it decides how long to wait); [launched] is the app that action opened, if any: reopening the app
     * already in front settles as quickly as a tap.
     */
    suspend fun settled(head: String, afterTool: String? = null, launched: String? = null): ToolResult
}

/** One run's parts that modules are built from (see [ToolExecutor] for what each means). */
class ToolDeps internal constructor(
    val device: DeviceController,
    /** Asks every time (send_sms, forget in a tainted run, ...). */
    val gate: UserGate,
    /** Asks unless the "confirm before sending" setting lets this run skip it. */
    val confirming: UserGate,
    val files: FileTools?,
    val memory: MemoryAccess?,
    val phone: PhoneAccess?,
    val messaging: MessagingAccess?,
    val routines: RoutineAccess?,
    val connectors: ConnectorTools?,
    /** True once third-party text entered this run itself (history aside). */
    val runTainted: () -> Boolean,
    /** An earlier run of this conversation read third-party text. */
    val historyTainted: Boolean,
    /** The executor's own screen tools (tap, scroll, batch, ...); they share its screen state. */
    val screen: ToolModule,
    /** The app context for a module that reads the phone itself; null in unit tests. */
    val context: Context?,
    /** Whether the user's own words this run name both this recipient and exactly this text (see ToolExecutor.userFixedSend). */
    val userFixedSend: (to: String, body: String) -> Boolean = { _, _ -> false },
    /** Whether the user's own words this run ask for a deletion (see ToolExecutor.userAskedDelete). */
    val userAskedDelete: () -> Boolean = { false },
)
