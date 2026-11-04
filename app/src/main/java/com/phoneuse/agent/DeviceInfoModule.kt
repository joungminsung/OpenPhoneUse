package com.phoneuse.agent

import android.content.Context
import android.os.BatteryManager
import com.phoneuse.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The battery as the phone reports it. */
data class BatteryStatus(val percent: Int, val charging: Boolean)

/** Reads the battery; null when the phone does not say. */
fun interface BatteryProvider {
    fun read(): BatteryStatus?

    companion object {
        /** The real battery, from [BatteryManager]. */
        fun of(context: Context): BatteryProvider = BatteryProvider {
            val manager = context.getSystemService(BatteryManager::class.java) ?: return@BatteryProvider null
            val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (percent !in 0..100) null else BatteryStatus(percent, manager.isCharging)
        }
    }
}

/**
 * An example module, and the template for a new one: a single read-only tool, battery_status. It is not in
 * [ToolRegistry], so the model never sees it; this line there would offer it (see CONTRIBUTING.md):
 *
 *     d.context?.let { DeviceInfoModule(BatteryProvider.of(it)) },
 *
 * Its source comes in through the constructor ([battery]), so a unit test can pass a fake one.
 */
internal class DeviceInfoModule(private val battery: BatteryProvider) : ToolModule {
    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "battery_status",
            "The phone's battery: {percent, charging}.",
            schema(),
        ),
    )

    /** The phone's own state, not text from someone else. */
    override val userText: Set<String> = setOf("battery_status")

    override suspend fun run(name: String, args: JsonObject, context: ToolContext): ToolResult = when (name) {
        "battery_status" -> {
            val status = battery.read()
            if (status == null) {
                ToolResult("ERROR: battery status unavailable", null, null, null)
            } else {
                val json = buildJsonObject {
                    put("percent", status.percent)
                    put("charging", status.charging)
                }
                ToolResult(json.toString(), null, null, null)
            }
        }
        else -> ToolResult("ERROR: unknown tool $name", null, null, null)
    }
}
