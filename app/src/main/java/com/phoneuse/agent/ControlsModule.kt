package com.phoneuse.agent

import com.phoneuse.model.ToolSpec
import com.phoneuse.phone.ControlsTool
import com.phoneuse.phone.MediaAction
import com.phoneuse.phone.PhoneReply
import com.phoneuse.phone.RingerMode
import com.phoneuse.phone.VolumeStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * flashlight / set_volume / media_control / device_status: quick controls done in one call through public
 * Android APIs, with no screen work. None asks for confirmation (each is harmless and undone by the opposite
 * call), and their output is the phone's own state, so it does not mark the run third-party. A null [controls]
 * offers nothing.
 */
internal class ControlsModule(private val controls: ControlsTool?) : ToolModule {
    override val specs: List<ToolSpec> get() = SPECS

    override val offered: Set<String> = if (controls == null) emptySet() else NAMES

    override val userText: Set<String> get() = NAMES

    override suspend fun run(name: String, args: JsonObject, context: ToolContext): ToolResult {
        val c = controls ?: return fail("$name is not available")
        // Each branch reads its arguments before it calls the tool, so a malformed call changes nothing.
        return try {
            when (name) {
                "flashlight" -> {
                    val on = args.bool("on") ?: throw ArgException("on required")
                    reply(io { c.flashlight(on) })
                }
                "set_volume" -> {
                    val stream = args.str("stream")
                    val percent = args.int("percent")
                    val ringer = args.str("ringer")
                    reply(io { c.volume(stream, percent, ringer) })
                }
                "media_control" -> {
                    val action = args.requireStr("action")
                    reply(io { c.media(action) })
                }
                "device_status" -> reply(io { c.status() })
                else -> fail("unknown tool $name")
            }
        } catch (e: ArgException) {
            fail("invalid arguments: ${e.message}")
        }
    }

    /** Binder calls, and media_control waits a moment for the player. */
    private suspend fun io(block: () -> PhoneReply): PhoneReply = withContext(Dispatchers.IO) { block() }

    private fun reply(r: PhoneReply) = ToolResult(r.text, null, null, null)

    private fun fail(reason: String) = ToolResult("ERROR: $reason", null, null, null)

    companion object {
        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                "flashlight",
                "Turn the phone's flashlight (camera flash) on or off directly. For \"turn the flashlight on/off\" requests.",
                schema(required = listOf("on"), props = mapOf("on" to prop("boolean", "true turns it on, false turns it off."))),
            ),
            ToolSpec(
                "set_volume",
                "Set a volume level and/or the ringer mode directly, without opening settings: give percent, ringer, or both. " +
                    "For \"turn the sound down\", \"volume to half\", \"put it on vibrate\" requests; for a relative change " +
                    "(\"a bit quieter\") read the current level with device_status first.",
                schema(
                    props = mapOf(
                        "stream" to enumProp("Which volume percent sets (default media).", VolumeStream.entries.map { it.id }),
                        "percent" to prop("integer", "Level, 0 to 100."),
                        "ringer" to enumProp("Ringer mode.", RingerMode.entries.map { it.id }),
                    ),
                ),
            ),
            ToolSpec(
                "media_control",
                "Press a media button for whichever player is active (music, podcast, video). The result cannot confirm " +
                    "that a player reacted. For \"next song\", \"pause the music\" requests.",
                schema(required = listOf("action"), props = mapOf("action" to enumProp("The button to press.", MediaAction.entries.map { it.id }))),
            ),
            ToolSpec(
                "device_status",
                "The phone's state: {battery {percent, charging}, volume {media, ring, alarm} in percent, ringer, do_not_disturb, " +
                    "airplane_mode, network (wifi|cellular|other|none), storage_free_gb}; a value the phone does not give is left " +
                    "out. For \"how much battery is left?\", \"is Wi-Fi connected?\" questions.",
                schema(),
            ),
        )

        /** All four: offered together, and all the user's own data. */
        val NAMES: Set<String> = SPECS.mapTo(LinkedHashSet()) { it.name }
    }
}
