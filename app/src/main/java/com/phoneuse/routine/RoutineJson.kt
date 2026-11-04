package com.phoneuse.routine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalTime

sealed interface RoutineParse {
    data class Ok(val routine: Routine) : RoutineParse
    data class Error(val reason: String) : RoutineParse
}

/**
 * Routine JSON: what make_routine's model sends, and the stored form (the same shape plus id, state and the
 * user-only switches). From the model ([parse] with fromModel) the stored fields are ignored, so the model can
 * never turn on "확인 없이 보내기" or point a routine at a conversation.
 *
 * ```
 * {"title", "trigger": {"type": "time", "hour", "minute", "days": ["mon",…]}
 *                    | {"type": "place", "name", "lat", "lng", "radius_m", "on": "enter"|"exit"}
 *                    | {"type": "notification", "package"?, "keyword"?}
 *                    | {"type": "device", "kind": "charger"|"bluetooth"|"headset"|"wifi", "detail"?}
 *                    | {"type": "before_event", "minutes"},
 *  "conditions": {"days"?, "time_from"?, "time_to"?, "only_when_unlocked"?, "cooldown_min"?},
 *  "action": {"type": "ask", "prompt", "needs_device"?, "steps"?} | {"type": "notify", "text"}
 *          | {"type": "open_app", "package", "name"?}}
 * ```
 */
object RoutineJson {
    private val DAYS = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )
    private val CLOCK = Regex("""([01]?\d|2[0-3]):([0-5]\d)""")
    private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
    private const val MAX_TEXT = 300
    private const val MAX_STEPS = 10
    private const val MAX_EVENT_MINUTES = 24 * 60

    private class Bad(message: String) : Exception(message)

    fun parse(text: String, fromModel: Boolean): RoutineParse {
        val obj = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return RoutineParse.Error("JSON 객체가 아니에요")
        return parse(obj, fromModel)
    }

    fun parse(obj: JsonObject, fromModel: Boolean): RoutineParse = try {
        RoutineParse.Ok(read(obj, fromModel))
    } catch (e: Bad) {
        RoutineParse.Error(e.message ?: "형식이 맞지 않아요")
    }

    private fun read(o: JsonObject, fromModel: Boolean): Routine {
        val title = o.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: throw Bad("title이 비었어요")
        if (title.length > Routine.MAX_TITLE) throw Bad("title은 ${Routine.MAX_TITLE}자 이하여야 해요")
        val trigger = trigger(o.obj("trigger") ?: throw Bad("trigger가 없어요"))
        val conditions = o.obj("conditions")?.let { conditions(it) } ?: Conditions()
        val action = action(o.obj("action") ?: throw Bad("action이 없어요"))
        val base = Routine(title = title, trigger = trigger, conditions = conditions, action = action, sentence = o.str("sentence")?.take(MAX_TEXT))
        if (fromModel) return base
        return base.copy(
            id = o.long("id") ?: 0,
            enabled = o.bool("enabled") ?: true,
            lastRunAt = o.long("last_run_at"),
            lastResult = o.str("last_result"),
            conversationId = o.long("conversation_id"),
            sendWithoutConfirm = o.bool("send_without_confirm") ?: false,
            sendTo = o.str("send_to")?.take(MAX_TEXT),
            sendBody = o.str("send_body")?.take(Routine.MAX_PROMPT),
            cardKey = o.str("card_key"),
        )
    }

    private fun trigger(t: JsonObject): Trigger = when (val type = t.str("type")?.trim()?.lowercase()) {
        "time" -> {
            // "at": "07:30" is accepted as well as hour/minute.
            val at = t.str("at")?.let { clock(it, "trigger.at") }
            val hour = t.int("hour") ?: at?.hour ?: throw Bad("trigger.hour가 없어요")
            val minute = t.int("minute") ?: at?.minute ?: 0
            if (hour !in 0..23) throw Bad("trigger.hour는 0–23이어야 해요 ($hour)")
            if (minute !in 0..59) throw Bad("trigger.minute는 0–59여야 해요 ($minute)")
            Trigger.Time(hour, minute, days(t["days"], "trigger.days") ?: emptySet())
        }
        "place" -> {
            val name = t.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: throw Bad("trigger.name이 비었어요")
            val lat = t.num("lat") ?: throw Bad("trigger.lat/lng가 없어요 (장소 좌표)")
            val lng = t.num("lng") ?: throw Bad("trigger.lat/lng가 없어요 (장소 좌표)")
            if (!(lat in -90.0..90.0) || !(lng in -180.0..180.0)) throw Bad("trigger 좌표가 범위를 벗어났어요 ($lat, $lng)")
            val radius = t.int("radius_m") ?: Routine.DEFAULT_RADIUS_M
            if (radius < Routine.MIN_RADIUS_M) throw Bad("trigger.radius_m은 ${Routine.MIN_RADIUS_M}m 이상이어야 해요 ($radius)")
            if (radius > Routine.MAX_RADIUS_M) throw Bad("trigger.radius_m은 ${Routine.MAX_RADIUS_M}m 이하여야 해요 ($radius)")
            val edge = t.str("on")?.let { PlaceEdge.fromWire(it) ?: throw Bad("trigger.on은 enter|exit여야 해요 ($it)") } ?: PlaceEdge.ENTER
            Trigger.Place(name.take(MAX_TEXT), lat, lng, radius, edge)
        }
        "notification" -> {
            val pkg = t.str("package")?.trim()?.takeIf { it.isNotEmpty() }
            val keyword = t.str("keyword")?.trim()?.takeIf { it.isNotEmpty() }
            if (pkg == null && keyword == null) throw Bad("알림 트리거에는 package나 keyword가 있어야 해요")
            if (pkg != null && !PACKAGE.matches(pkg)) throw Bad("trigger.package가 앱 패키지 이름이 아니에요 ($pkg)")
            Trigger.Notification(pkg, keyword?.take(MAX_TEXT))
        }
        "device" -> {
            val kind = DeviceKind.fromWire(t.str("kind")) ?: throw Bad("trigger.kind는 charger|bluetooth|headset|wifi 중 하나여야 해요 (${t.str("kind")})")
            Trigger.Device(kind, t.str("detail")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT))
        }
        "before_event" -> {
            val minutes = t.int("minutes") ?: throw Bad("trigger.minutes가 없어요")
            if (minutes !in 1..MAX_EVENT_MINUTES) throw Bad("trigger.minutes는 1–${MAX_EVENT_MINUTES}이어야 해요 ($minutes)")
            Trigger.BeforeEvent(minutes)
        }
        else -> throw Bad("알 수 없는 trigger.type: $type (time|place|notification|device|before_event)")
    }

    private fun conditions(c: JsonObject): Conditions {
        val cooldown = c.int("cooldown_min") ?: Conditions.DEFAULT_COOLDOWN_MIN
        if (cooldown !in 0..Conditions.MAX_COOLDOWN_MIN) throw Bad("conditions.cooldown_min은 0–${Conditions.MAX_COOLDOWN_MIN}이어야 해요")
        val from = c.str("time_from")?.let { clock(it, "conditions.time_from") }
        val to = c.str("time_to")?.let { clock(it, "conditions.time_to") }
        if ((from == null) != (to == null)) throw Bad("conditions.time_from과 time_to는 함께 줘야 해요")
        return Conditions(
            days = days(c["days"], "conditions.days")?.takeIf { it.isNotEmpty() },
            timeFrom = from,
            timeTo = to,
            onlyWhenUnlocked = c.bool("only_when_unlocked") ?: false,
            cooldownMin = cooldown,
        )
    }

    private fun action(a: JsonObject): RoutineAction = when (val type = a.str("type")?.trim()?.lowercase()) {
        "ask" -> {
            val prompt = a.str("prompt")?.trim()?.takeIf { it.isNotEmpty() } ?: throw Bad("action.prompt가 비었어요")
            if (prompt.length > Routine.MAX_PROMPT) throw Bad("action.prompt는 ${Routine.MAX_PROMPT}자 이하여야 해요")
            val steps = steps(a["steps"])
            // An omitted flag is "operates the phone" (the safe side: held while locked); only an explicit false opts out,
            // and a step that operates apps overrides it.
            RoutineAction.Ask(prompt, (a.bool("needs_device") ?: true) || steps.any { it.device }, steps)
        }
        "notify" -> RoutineAction.Notify(
            a.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT) ?: throw Bad("action.text가 비었어요"),
        )
        "open_app" -> {
            val pkg = a.str("package")?.trim() ?: throw Bad("action.package가 없어요")
            if (!PACKAGE.matches(pkg)) throw Bad("action.package가 앱 패키지 이름이 아니에요 ($pkg)")
            RoutineAction.OpenApp(pkg, a.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT))
        }
        else -> throw Bad("알 수 없는 action.type: $type (ask|notify|open_app)")
    }

    private fun steps(e: JsonElement?): List<RoutineStep> {
        val array = when (e) {
            null, is JsonNull -> return emptyList()
            is JsonArray -> e
            else -> throw Bad("action.steps는 배열이어야 해요")
        }
        if (array.size > MAX_STEPS) throw Bad("action.steps는 최대 ${MAX_STEPS}개예요")
        return array.mapNotNull { s ->
            when (s) {
                is JsonPrimitive -> s.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { RoutineStep(it.take(MAX_TEXT)) }
                is JsonObject -> s.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { RoutineStep(it.take(MAX_TEXT), s.bool("device") ?: false) }
                else -> throw Bad("action.steps 항목이 잘못됐어요")
            }
        }
    }

    private fun days(e: JsonElement?, what: String): Set<DayOfWeek>? {
        val array = when (e) {
            null, is JsonNull -> return null
            is JsonArray -> e
            else -> throw Bad("${what}는 배열이어야 해요")
        }
        return array.mapTo(LinkedHashSet()) { d ->
            val key = (d as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.take(3)
            DAYS[key] ?: throw Bad("$what 항목은 mon…sun이어야 해요 ($d)")
        }
    }

    private fun clock(s: String, what: String): LocalTime {
        val m = CLOCK.matchEntire(s.trim()) ?: throw Bad("${what}는 HH:mm이어야 해요 ($s)")
        return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    // ---- encoding -------------------------------------------------------------------------------

    /** The stored form; [forModel] leaves out the stored-only fields (for the history and previews). */
    fun encode(r: Routine, forModel: Boolean = false): JsonObject = buildJsonObject {
        put("title", r.title)
        put("trigger", encode(r.trigger))
        put("conditions", encode(r.conditions))
        put("action", encode(r.action))
        r.sentence?.let { put("sentence", it) }
        if (forModel) return@buildJsonObject
        put("id", r.id)
        put("enabled", r.enabled)
        r.lastRunAt?.let { put("last_run_at", it) }
        r.lastResult?.let { put("last_result", it) }
        r.conversationId?.let { put("conversation_id", it) }
        put("send_without_confirm", r.sendWithoutConfirm)
        r.sendTo?.let { put("send_to", it) }
        r.sendBody?.let { put("send_body", it) }
        r.cardKey?.let { put("card_key", it) }
    }

    fun encode(t: Trigger): JsonObject = buildJsonObject {
        when (t) {
            is Trigger.Time -> {
                put("type", "time")
                put("hour", t.hour)
                put("minute", t.minute)
                if (t.days.isNotEmpty()) put("days", encodeDays(t.days))
            }
            is Trigger.Place -> {
                put("type", "place")
                put("name", t.name)
                put("lat", t.lat)
                put("lng", t.lng)
                put("radius_m", t.radiusM)
                put("on", t.on.wire)
            }
            is Trigger.Notification -> {
                put("type", "notification")
                t.packageName?.let { put("package", it) }
                t.keyword?.let { put("keyword", it) }
            }
            is Trigger.Device -> {
                put("type", "device")
                put("kind", t.kind.wire)
                t.detail?.let { put("detail", it) }
            }
            is Trigger.BeforeEvent -> {
                put("type", "before_event")
                put("minutes", t.minutes)
            }
        }
    }

    private fun encode(c: Conditions): JsonObject = buildJsonObject {
        c.days?.let { put("days", encodeDays(it)) }
        c.timeFrom?.let { put("time_from", clockText(it)) }
        c.timeTo?.let { put("time_to", clockText(it)) }
        put("only_when_unlocked", c.onlyWhenUnlocked)
        put("cooldown_min", c.cooldownMin)
    }

    private fun encode(a: RoutineAction): JsonObject = buildJsonObject {
        when (a) {
            is RoutineAction.Ask -> {
                put("type", "ask")
                put("prompt", a.prompt)
                put("needs_device", a.needsDevice)
                if (a.steps.isNotEmpty()) {
                    put(
                        "steps",
                        buildJsonArray {
                            for (s in a.steps) add(buildJsonObject { put("text", s.text); put("device", s.device) })
                        },
                    )
                }
            }
            is RoutineAction.Notify -> {
                put("type", "notify")
                put("text", a.text)
            }
            is RoutineAction.OpenApp -> {
                put("type", "open_app")
                put("package", a.packageName)
                a.label?.let { put("name", it) }
            }
        }
    }

    private fun encodeDays(days: Set<DayOfWeek>) = buildJsonArray {
        for (d in days.sorted()) add(JsonPrimitive(DAYS.entries.first { it.value == d }.key))
    }

    fun clockText(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    // ---- field access ---------------------------------------------------------------------------

    private fun JsonObject.str(key: String): String? = when (val v = this[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.content
        else -> throw Bad("${key}는 문자열이어야 해요")
    }

    private fun JsonObject.obj(key: String): JsonObject? = when (val v = this[key]) {
        null, is JsonNull -> null
        is JsonObject -> v
        else -> throw Bad("${key}는 객체여야 해요")
    }

    private fun JsonObject.num(key: String): Double? = when (val v = this[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.doubleOrNull?.takeIf { it.isFinite() } ?: throw Bad("${key}는 숫자여야 해요")
        else -> throw Bad("${key}는 숫자여야 해요")
    }

    private fun JsonObject.int(key: String): Int? = num(key)?.let {
        if (it % 1.0 != 0.0 || it > Int.MAX_VALUE || it < Int.MIN_VALUE) throw Bad("${key}는 정수여야 해요")
        it.toInt()
    }

    private fun JsonObject.long(key: String): Long? = when (val v = this[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.longOrNull ?: throw Bad("${key}는 정수여야 해요")
        else -> throw Bad("${key}는 정수여야 해요")
    }

    private fun JsonObject.bool(key: String): Boolean? = when (val v = this[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.booleanOrNull ?: throw Bad("${key}는 true/false여야 해요")
        else -> throw Bad("${key}는 true/false여야 해요")
    }
}
