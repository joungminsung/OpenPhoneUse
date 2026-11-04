package com.phoneuse.agent

import com.phoneuse.i18n.tr
import com.phoneuse.cards.LatLng
import com.phoneuse.cards.PermissionCard
import com.phoneuse.model.ToolSpec
import com.phoneuse.phone.AlarmTool
import com.phoneuse.phone.CalendarTool
import com.phoneuse.phone.ConfirmGate
import com.phoneuse.phone.ConfirmKind
import com.phoneuse.phone.ContactsTool
import com.phoneuse.phone.EventDraft
import com.phoneuse.phone.LocationReply
import com.phoneuse.phone.LocationTool
import com.phoneuse.phone.MapsIntents
import com.phoneuse.phone.Near
import com.phoneuse.phone.PhoneReply
import com.phoneuse.phone.PhotoQuery
import com.phoneuse.phone.PhotosTool
import com.phoneuse.phone.PlaceSearch
import com.phoneuse.phone.RunTrigger
import com.phoneuse.risk.RiskCategory
import com.phoneuse.routine.ReminderScheduler
import com.phoneuse.routine.ReminderTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.ZonedDateTime

/** Phone data tools a run may use; a null part leaves its tool out. [now] is for remind_at's "HH:mm". */
class PhoneAccess(
    val location: LocationTool?,
    val places: PlaceSearch?,
    val reminders: ReminderScheduler?,
    val contacts: ContactsTool? = null,
    val calendar: CalendarTool? = null,
    val alarms: AlarmTool? = null,
    val photos: PhotosTool? = null,
    /** Who started the run; confirmed calendar/album writes need the user (see [ConfirmGate]). */
    val trigger: RunTrigger = RunTrigger.Other,
    val now: () -> ZonedDateTime = ZonedDateTime::now,
)

/**
 * get_location / geocode / remind_at, contacts, calendar, alarms and photos, plus open_route (directions in the
 * map app, which needs only the [device]). Keeps the run's last location for places cards. set_alarm asks on
 * [gate] first.
 */
internal class PhoneModule(
    private val access: PhoneAccess?,
    private val device: DeviceController,
    private val gate: UserGate? = null,
    /** True once third-party text entered the run (ToolExecutor's taint). */
    private val tainted: () -> Boolean = { true },
    /** An earlier run of this conversation read third-party text (see ConfirmGate.refusal). */
    private val historyTainted: Boolean = false,
) : ToolModule {
    override val specs: List<ToolSpec> get() = PhoneToolSpecs.all

    /** The phone-data tools this run may use (open_route aside). */
    private val enabled: Set<String> = buildSet {
        val a = access ?: return@buildSet
        if (a.location != null) add("get_location")
        if (a.places != null) add("geocode")
        if (a.reminders != null) add("remind_at")
        if (a.contacts != null) add("find_contact")
        if (a.calendar != null) addAll(listOf("calendar_list", "calendar_add"))
        if (a.alarms != null) {
            add("next_alarm")
            if (gate != null) add("set_alarm")
        }
        if (a.photos != null) {
            addAll(listOf("photos_find", "photos_look", "photos_album"))
            if (gate != null) add("set_wallpaper")
        }
    }

    override val offered: Set<String> = enabled + ROUTE

    override val userText: Set<String> get() = OWN_DATA

    /** The last fix get_location returned in this run. */
    var lastLocation: LatLng? = null
        private set

    override suspend fun run(name: String, args: JsonObject, context: ToolContext): ToolResult {
        if (name == ROUTE) return openRoute(args, context)
        if (name !in enabled) return fail("$name is not available")
        val a = access ?: return fail("$name is not available")
        return try {
            when (name) {
                "get_location" -> location(a.location!!)
                "geocode" -> geocode(a.places!!, args)
                "remind_at" -> remind(a.reminders!!, a.now(), args)
                "find_contact" -> reply(io { a.contacts!!.find(args.str("name") ?: throw IllegalArgumentException("name required")) })
                "calendar_list" -> reply(io { a.calendar!!.list(args.requireStr("from"), args.requireStr("to")) })
                "calendar_add" -> {
                    val draft = EventDraft(args.requireStr("title"), args.requireStr("start"), args.requireStr("end"), args.str("location"), args.int("reminder_min"))
                    val confirmed = args.bool("confirmed") == true
                    if (confirmed) ConfirmGate.refusal(ConfirmKind.EVENT, a.trigger, tainted(), historyTainted)?.let { return fail(it) }
                    reply(io { a.calendar!!.add(draft, confirmed) })
                }
                "next_alarm" -> reply(io { a.alarms!!.next() })
                "set_alarm" -> {
                    val hour = args.int("hour") ?: throw IllegalArgumentException("hour required")
                    val minute = args.int("minute") ?: throw IllegalArgumentException("minute required")
                    reply(a.alarms!!.set(hour, minute, args.str("label").orEmpty()) { gate!!.confirm(RiskCategory.CHANGE, it) })
                }
                "photos_find" -> {
                    val near = (args["near"] as? JsonObject)?.let {
                        Near(
                            it.num("lat") ?: throw IllegalArgumentException("near.lat required"),
                            it.num("lng") ?: throw IllegalArgumentException("near.lng required"),
                            it.num("radius_km") ?: DEFAULT_RADIUS_KM,
                        )
                    }
                    val query = PhotoQuery.parse(args.str("from"), args.str("to"), near, args.str("album"), args.int("limit"), a.now().zone)
                        .getOrElse { throw IllegalArgumentException(it.message) }
                    reply(io { a.photos!!.find(query, args.str("place")) })
                }
                "photos_look" -> reply(io { a.photos!!.look(args.strings("uris"), args.requireStr("question")) })
                "photos_album" -> {
                    val name = args.requireStr("name")
                    val uris = args.strings("uris")
                    val confirmed = args.bool("confirmed") == true
                    if (confirmed) ConfirmGate.refusal(ConfirmKind.ALBUM, a.trigger, tainted(), historyTainted)?.let { return fail(it) }
                    reply(io { a.photos!!.album(name, uris, confirmed) })
                }
                "set_wallpaper" -> {
                    val uri = args.requireStr("uri")
                    reply(io { a.photos!!.wallpaper(uri) { gate!!.confirm(RiskCategory.CHANGE, it) } })
                }
                else -> fail("unknown tool $name")
            }
        } catch (e: IllegalArgumentException) {
            fail("invalid arguments: ${e.message}")
        } catch (e: IOException) {
            fail(e.message ?: "network error")
        }
    }

    /** Opens transit directions in the first installed map app (MapsIntents order), then the settled screen. */
    private suspend fun openRoute(args: JsonObject, context: ToolContext): ToolResult {
        val lat = args.requireNum("lat")
        val lng = args.requireNum("lng")
        val name = args.requireStr("name")
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) throw ArgException("lat/lng out of range")
        val installed = device.installedApps().mapTo(HashSet()) { it.packageName }
        val target = MapsIntents.routeTarget(lat, lng, name) { it in installed }
        // The app's own link first; a plain link (browser) if that app refuses it.
        if (!device.openUri(MapsIntents.launchUri(target)) && !(target.packageName != null && device.openUri(target.uri))) {
            return fail("$ROUTE failed")
        }
        return context.settled("OK: ${MapsIntents.appName(target.packageName)}에서 경로를 열었어요", ROUTE, launched = target.packageName)
    }

    private suspend fun location(tool: LocationTool): ToolResult = when (val reply = tool.get()) {
        is LocationReply.Ok -> {
            lastLocation = LatLng(reply.lat, reply.lng)
            ok(reply.text)
        }
        LocationReply.PermissionNeeded -> permissionNeeded(reply.text, tr("Location", "위치"), LocationTool.PERMISSIONS)
        is LocationReply.Failed -> ok(reply.text)
    }

    private suspend fun geocode(places: PlaceSearch, args: JsonObject): ToolResult {
        val query = args.str("query")?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("query required")
        val lat = args.num("near_lat")
        val lng = args.num("near_lng")
        if ((lat == null) != (lng == null)) throw IllegalArgumentException("near_lat and near_lng go together")
        if (lat != null && lat !in -90.0..90.0 || lng != null && lng !in -180.0..180.0) throw IllegalArgumentException("near_lat/near_lng out of range")
        val hits = places.search(query, lat, lng)
        if (hits.isEmpty()) return ok("결과 없음. 더 정확한 이름이나 주소로 다시 찾아보세요.")
        return ok(
            buildJsonArray {
                for (p in hits) {
                    add(
                        buildJsonObject {
                            put("name", p.name)
                            put("lat", p.lat)
                            put("lng", p.lng)
                            put("address", p.address)
                            p.distanceM?.let { put("distance_m", it.toInt()) }
                        },
                    )
                }
            }.toString(),
        )
    }

    private suspend fun remind(scheduler: ReminderScheduler, now: ZonedDateTime, args: JsonObject): ToolResult {
        val time = args.str("time") ?: throw IllegalArgumentException("time required")
        val text = args.str("text")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("text required")
        val at = ReminderTime.parse(time, now) ?: throw IllegalArgumentException("time must be HH:mm or ISO 8601 date-time ($time)")
        if (!at.isAfter(now)) return fail("지난 시간이에요 ($time)")
        if (!scheduler.canNotify()) {
            return permissionNeeded(
                ReminderScheduler.PERMISSION_NEEDED, tr("Notifications", "알림"), scheduler.notifyPermissions(), PermissionCard.SETTINGS_NOTIFICATIONS,
            )
        }
        // In a run that read third-party text, the user sees the text before it becomes our own notification.
        if ((tainted() || historyTainted) &&
            gate?.confirm(RiskCategory.CHANGE, tr("Schedule reminder: ${ReminderTime.describe(at, now)} \"${text.take(MAX_TEXT)}\"", "알림 예약: ${ReminderTime.describe(at, now)} \"${text.take(MAX_TEXT)}\"")) == false
        ) {
            return ToolResult("CANCELLED by user", null, null, null)
        }
        val exact = scheduler.oneShot(at.toInstant(), text.take(MAX_TEXT))
        val late = if (exact) "" else " 정확한 시각 알림이 허용되지 않아 몇 분 늦게 울릴 수 있어요. 사용자에게 그렇게 알려 주세요."
        return ok("OK: ${ReminderTime.describe(at, now)}에 알림을 예약했어요 (알람이 아니라 앱 알림).$late")
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    /** A data tool's answer as a tool result; a missing permission brings the [권한 허용] card. */
    private fun reply(r: PhoneReply): ToolResult = when (r) {
        is PhoneReply.Ok -> ToolResult(r.text, null, null, null, card = r.card)
        is PhoneReply.PermissionNeeded -> permissionNeeded(r.text, r.label, r.permissions)
        is PhoneReply.Failed, PhoneReply.Cancelled -> ToolResult(r.text, null, null, null)
    }

    /** Labels whose [권한 허용] card this run already showed; a second call does not add another. */
    private val asked = HashSet<String>()

    private fun permissionNeeded(head: String, label: String, permissions: List<String>, settings: String? = null) = ToolResult(
        "$head — 채팅에 [권한 허용] 카드를 띄웠어요. 사용자에게 허용해 달라고 짧게 말하고 finish 하세요.",
        null, null, null,
        card = if (asked.add(label)) PermissionCard(label, permissions, settings) else null,
    )

    private fun ok(output: String) = ToolResult(output, null, null, null)

    private fun fail(reason: String) = ToolResult("ERROR: $reason", null, null, null)

    companion object {
        private const val ROUTE = "open_route"
        const val DEFAULT_RADIUS_KM = 1.0

        /** Tools whose output is the user's own data or the app's words, so it does not mark the run third-party. */
        val OWN_DATA = setOf("find_contact", "next_alarm", "set_alarm", "photos_find", "photos_album", "set_wallpaper")
        const val MAX_TEXT = 200
    }
}
