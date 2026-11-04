package com.phoneuse.agent

import com.phoneuse.i18n.tr
import com.phoneuse.cards.PermissionCard
import com.phoneuse.cards.RoutineCard
import com.phoneuse.memory.MemoryStore
import com.phoneuse.model.ToolSpec
import com.phoneuse.phone.PlaceSearch
import com.phoneuse.routine.PlaceResolver
import com.phoneuse.routine.Routine
import com.phoneuse.routine.RoutineJson
import com.phoneuse.routine.RoutineParse
import com.phoneuse.routine.RoutineText
import com.phoneuse.routine.Trigger
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * What make_routine may use. [places] geocodes a place name; [memories] are searched first for a PLACE memory
 * ("회사는 강남구 테헤란로 152"). [permissionFor] is the first permission the routine still needs.
 * [placesSupported] is false without Google Play services: place routines cannot work on this device.
 */
class RoutineAccess(
    val places: PlaceSearch?,
    val memories: MemoryStore?,
    val permissionFor: (Routine) -> PermissionCard? = { null },
    val placesSupported: Boolean = true,
)

/**
 * make_routine: the model sends routine JSON; the app checks it and shows a preview card with [루틴 저장]. Nothing
 * is saved here: the user's tap on the card saves it. A place given by name only gets coordinates from the
 * user's place memories or a geocode, and the card shows where they came from (with a map to check).
 */
internal class RoutineModule(private val access: RoutineAccess?) : ToolModule {
    override val specs: List<ToolSpec> get() = SPECS

    override val offered: Set<String> = if (access != null) specs.mapTo(HashSet()) { it.name } else emptySet()

    override suspend fun run(name: String, args: JsonObject, context: ToolContext): ToolResult {
        val a = access ?: return fail("make_routine is not available")
        val raw = (args["routine"] as? JsonObject) ?: args.takeIf { "trigger" in it } ?: return fail("invalid arguments: routine required")
        var routine = raw
        var placeFrom: String? = null
        var placeAddress: String? = null
        val trigger = raw["trigger"] as? JsonObject
        if (trigger != null && trigger.text("type")?.lowercase() == "place") {
            if (!a.placesSupported) return fail("이 기기에서 못 씀: 장소 루틴은 Google Play 서비스가 필요해요. 사용자에게 그렇게 말하고 다른 트리거(시간 등)를 제안하세요.")
            if (trigger.number("lat") == null || trigger.number("lng") == null) {
                val name = trigger.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return fail("루틴 오류: trigger.name이 비었어요")
                val found = PlaceResolver(a.places, a.memories).resolve(name) ?: return fail(
                    "장소 '$name'의 위치를 기억에서도 지도 검색에서도 찾지 못했어요. 사용자에게 주소를 물어본 뒤 lat/lng를 넣어 다시 부르세요.",
                )
                placeFrom = found.from
                placeAddress = found.address
                routine = JsonObject(
                    raw + ("trigger" to JsonObject(trigger + mapOf("lat" to JsonPrimitive(found.lat), "lng" to JsonPrimitive(found.lng)))),
                )
            } else {
                placeFrom = tr("Set by you", "직접 지정")
            }
        }
        val sentence = args.text("sentence")?.trim()?.takeIf { it.isNotEmpty() }
        if (sentence != null) routine = JsonObject(routine + ("sentence" to JsonPrimitive(sentence.take(MAX_SENTENCE))))
        val parsed = when (val p = RoutineJson.parse(routine, fromModel = true)) {
            is RoutineParse.Ok -> p.routine
            is RoutineParse.Error -> return fail("루틴 오류: ${p.reason}. 고쳐서 다시 부르세요.")
        }
        val permission = try {
            a.permissionFor(parsed)
        } catch (e: Exception) {
            null
        }
        val card = RoutineCard(RoutineJson.encode(parsed, forModel = true), placeFrom, placeAddress, permission)
        val steps = RoutineText.steps(parsed.action).joinToString(", ") { it.text }
        val need = permission?.let { " 이 루틴이 울리려면 ${it.label} 권한도 필요해요(카드 아래 [권한 허용])." }.orEmpty()
        return ToolResult(
            "OK: 미리보기 카드를 띄웠어요 (${RoutineText.whenText(parsed.trigger)} → $steps). 아직 저장 안 됐어요: 사용자가 카드의 " +
                "[루틴 저장]을 눌러야 저장돼요.$need 짧게 안내하고 finish 하세요.",
            null, null, null,
            card = card,
        )
    }

    private fun JsonObject.text(key: String): String? = when (val v = this[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.contentOrNull
        else -> null
    }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun fail(reason: String) = ToolResult("ERROR: $reason", null, null, null)

    companion object {
        private const val MAX_SENTENCE = 300

        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                "make_routine",
                "Make a routine (automation) from what the user said, e.g. \"회사 도착하면 무음으로\", \"매일 7시 반에 브리핑\". " +
                    "Shows a preview card; nothing is saved until the user taps [루틴 저장]. " +
                    "routine: {title, trigger, conditions?, action}. trigger is one of " +
                    "{type:\"time\", hour, minute, days?:[\"mon\"..\"sun\"]} | " +
                    "{type:\"place\", name, lat?, lng?, radius_m?(>=150, default 200), on:\"enter\"|\"exit\"} (lat/lng may be left out: " +
                    "the app looks the name up in the user's place memories, then on the map) | " +
                    "{type:\"notification\", package?, keyword?} (at least one) | " +
                    "{type:\"device\", kind:\"charger\"|\"bluetooth\"|\"headset\"|\"wifi\", detail?(device or Wi-Fi name)} | " +
                    "{type:\"before_event\", minutes} (before calendar events that have a location). " +
                    "conditions: {days?, time_from?, time_to?(HH:mm), only_when_unlocked?, cooldown_min?(default 60; 1440 = once a day)}. " +
                    "action: {type:\"ask\", prompt (what the agent should do each time, in Korean), needs_device (true if it must operate apps), " +
                    "steps?:[{text, device}]} | {type:\"notify\", text} (just a notification) | {type:\"open_app\", package, name?}.",
                schema(
                    required = listOf("routine"),
                    props = mapOf(
                        "routine" to prop("object", "The routine as described above."),
                        "sentence" to prop("string", "The user's request, as they said it."),
                    ),
                ),
            ),
        )
    }
}
