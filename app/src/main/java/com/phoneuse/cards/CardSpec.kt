package com.phoneuse.cards

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * A result card the model shows with `show_card`. Stored with the assistant message as JSON whose `kind`
 * field names the subtype. [validate] returns null when the card can be drawn, else a short reason.
 */
@Serializable
sealed interface CardSpec {
    fun validate(): String?
}

@Serializable
data class LatLng(val lat: Double, val lng: Double)

@Serializable
data class Place(
    val name: String,
    val lat: Double,
    val lng: Double,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("walk_min") val walkMin: Int? = null,
    @SerialName("status_text") val statusText: String? = null,
    val phone: String? = null,
    val address: String? = null,
)

/** Places on a map. [center] is the user's location or the search origin; it is drawn as the blue dot. */
@Serializable
@SerialName("places")
data class PlacesCard(val title: String, val center: LatLng? = null, val items: List<Place> = emptyList()) : CardSpec {
    override fun validate(): String? {
        if (title.isBlank()) return "title이 비었어요"
        if (items.size !in 1..MAX_PLACES) return "items는 1–${MAX_PLACES}개여야 해요 (${items.size}개)"
        center?.let { c -> coordError("center", c.lat, c.lng)?.let { return it } }
        items.forEachIndexed { i, p ->
            if (p.name.isBlank()) return "items[$i].name이 비었어요"
            coordError("items[$i]", p.lat, p.lng)?.let { return it }
            if (p.distanceM != null && !(p.distanceM >= 0)) return "items[$i].distance_m가 잘못됐어요"
            if (p.walkMin != null && p.walkMin < 0) return "items[$i].walk_min이 잘못됐어요"
        }
        return null
    }

    companion object {
        const val MAX_PLACES = 10
    }
}

@Serializable
data class RouteLeg(val at: String, val kind: String, val label: String, val detail: String = "")

@Serializable
data class RouteDest(val name: String, val lat: Double? = null, val lng: Double? = null)

/** When to leave and how to get there; [departAt]/[arriveAt] are HH:mm. */
@Serializable
@SerialName("route")
data class RouteCard(
    @SerialName("depart_at") val departAt: String,
    @SerialName("arrive_at") val arriveAt: String,
    val summary: String = "",
    val legs: List<RouteLeg> = emptyList(),
    val dest: RouteDest,
) : CardSpec {
    override fun validate(): String? {
        if (!isClock(departAt)) return "depart_at은 HH:mm이어야 해요 ($departAt)"
        if (!isClock(arriveAt)) return "arrive_at은 HH:mm이어야 해요 ($arriveAt)"
        if (dest.name.isBlank()) return "dest.name이 비었어요"
        if ((dest.lat == null) != (dest.lng == null)) return "dest의 lat와 lng는 함께 줘야 해요"
        if (dest.lat != null && dest.lng != null) coordError("dest", dest.lat, dest.lng)?.let { return it }
        capError("legs", legs.size)?.let { return it }
        legs.forEachIndexed { i, leg ->
            if (leg.kind !in LEG_KINDS) return "legs[$i].kind는 ${LEG_KINDS.joinToString("|")} 중 하나여야 해요 (${leg.kind})"
            if (!isClock(leg.at)) return "legs[$i].at은 HH:mm이어야 해요 (${leg.at})"
            if (leg.label.isBlank()) return "legs[$i].label이 비었어요"
        }
        return null
    }

    companion object {
        val LEG_KINDS = listOf("walk", "bus", "subway", "train", "car")
    }
}

@Serializable
data class EventConflict(val title: String, val start: String, val end: String)

/** A calendar event preview; [start]/[end] are ISO-8601 (date, local date-time or with offset). */
@Serializable
@SerialName("event")
data class EventCard(
    val title: String,
    val start: String,
    val end: String,
    val location: String? = null,
    @SerialName("reminder_min") val reminderMin: Int? = null,
    val conflicts: List<EventConflict> = emptyList(),
) : CardSpec {
    override fun validate(): String? {
        if (title.isBlank()) return "title이 비었어요"
        val s = parseIso(start) ?: return "start는 ISO 날짜·시간이어야 해요 ($start)"
        val e = parseIso(end) ?: return "end는 ISO 날짜·시간이어야 해요 ($end)"
        if (e.isBefore(s)) return "end가 start보다 앞이에요"
        if (reminderMin != null && reminderMin < 0) return "reminder_min이 잘못됐어요"
        capError("conflicts", conflicts.size)?.let { return it }
        conflicts.forEachIndexed { i, c ->
            if (parseIso(c.start) == null || parseIso(c.end) == null) return "conflicts[$i]의 시간이 ISO가 아니에요"
        }
        return null
    }
}

@Serializable
data class PhotoFilter(val label: String, val uris: List<String> = emptyList())

/** Photos found on the phone, as MediaStore content:// URIs. Filter URIs must be among [uris]. */
@Serializable
@SerialName("photos")
data class PhotosCard(val title: String, val uris: List<String> = emptyList(), val filters: List<PhotoFilter> = emptyList()) : CardSpec {
    override fun validate(): String? {
        if (title.isBlank()) return "title이 비었어요"
        if (uris.size !in 1..MAX_PHOTOS) return "uris는 1–${MAX_PHOTOS}개여야 해요 (${uris.size}개)"
        uris.forEachIndexed { i, u -> if (!isPhotoUri(u)) return "uris[$i]는 사진 보관함(content://media/…) 주소여야 해요" }
        val all = uris.toSet()
        capError("filters", filters.size)?.let { return it }
        filters.forEachIndexed { i, f ->
            if (f.label.isBlank()) return "filters[$i].label이 비었어요"
            if (f.uris.isEmpty()) return "filters[$i].uris가 비었어요"
            if (f.uris.any { it !in all }) return "filters[$i].uris는 uris 안에 있어야 해요"
        }
        return null
    }

    companion object {
        const val MAX_PHOTOS = 60
    }
}

@Serializable
data class ResultItem(val title: String, val subtitle: String? = null, val meta: String? = null, val url: String? = null)

@Serializable
data class SourceLink(val title: String, val url: String)

/** Web or other search results with their sources. */
@Serializable
@SerialName("results")
data class ResultsCard(val title: String, val items: List<ResultItem> = emptyList(), val sources: List<SourceLink> = emptyList()) : CardSpec {
    override fun validate(): String? {
        if (title.isBlank()) return "title이 비었어요"
        if (items.size !in 1..MAX_RESULTS) return "items는 1–${MAX_RESULTS}개여야 해요 (${items.size}개)"
        items.forEachIndexed { i, it ->
            if (it.title.isBlank()) return "items[$i].title이 비었어요"
            if (it.url != null && !isWebUrl(it.url)) return "items[$i].url은 http(s) 주소여야 해요"
        }
        capError("sources", sources.size)?.let { return it }
        sources.forEachIndexed { i, s -> if (!isWebUrl(s.url)) return "sources[$i].url은 http(s) 주소여야 해요" }
        return null
    }

    companion object {
        const val MAX_RESULTS = 10
    }
}

/** A text the user may send or keep: a message, an email or a note. */
@Serializable
@SerialName("draft")
data class DraftCard(val channel: String, val to: String? = null, val subject: String? = null, val body: String) : CardSpec {
    override fun validate(): String? {
        if (channel !in CHANNELS) return "channel은 ${CHANNELS.joinToString("|")} 중 하나여야 해요 ($channel)"
        if (body.isBlank()) return "body가 비었어요"
        return null
    }

    companion object {
        val CHANNELS = listOf("sms", "email", "note")
    }
}

@Serializable
data class DigestItem(val who: String? = null, val text: String, val at: String? = null, val action: String? = null)

@Serializable
data class DigestGroup(val label: String, val tone: String, val items: List<DigestItem> = emptyList())

/** Grouped notifications or messages. */
@Serializable
@SerialName("digest")
data class DigestCard(val groups: List<DigestGroup> = emptyList()) : CardSpec {
    override fun validate(): String? {
        if (groups.isEmpty()) return "groups가 비었어요"
        capError("groups", groups.size)?.let { return it }
        groups.forEachIndexed { i, g ->
            capError("groups[$i].items", g.items.size)?.let { return it }
            if (g.label.isBlank()) return "groups[$i].label이 비었어요"
            if (g.tone !in TONES) return "groups[$i].tone은 ${TONES.joinToString("|")} 중 하나여야 해요 (${g.tone})"
            g.items.forEachIndexed { j, it -> if (it.text.isBlank()) return "groups[$i].items[$j].text가 비었어요" }
        }
        return null
    }

    companion object {
        val TONES = listOf("urgent", "money", "info", "muted")
    }
}

@Serializable
data class StatBar(val label: String, val value: Double)

/** One figure with an optional change and bars. */
@Serializable
@SerialName("stat")
data class StatCard(val label: String, val value: String, val delta: String? = null, val bars: List<StatBar> = emptyList()) : CardSpec {
    override fun validate(): String? {
        if (label.isBlank()) return "label이 비었어요"
        if (value.isBlank()) return "value가 비었어요"
        if (bars.size > MAX_BARS) return "bars는 최대 ${MAX_BARS}개예요"
        bars.forEachIndexed { i, b -> if (!b.value.isFinite() || b.value < 0) return "bars[$i].value가 잘못됐어요" }
        return null
    }

    companion object {
        const val MAX_BARS = 12
    }
}

/**
 * Made by the app, never by the model (show_card does not accept the kind): a tool needed a runtime permission
 * the app does not have. [label] names it in Korean ("위치"); the button requests [permissions]. [settings] is
 * the settings page to open when they cannot be asked for (permanently denied, or nothing to request, such as
 * notifications below Android 13): [SETTINGS_NOTIFICATIONS], or null for the app's details page. A special access
 * that only a Settings screen grants has no permissions and names its screen: [SETTINGS_NOTIFICATION_LISTENER].
 */
@Serializable
@SerialName("permission")
data class PermissionCard(val label: String, val permissions: List<String>, val settings: String? = null) : CardSpec {
    override fun validate(): String? {
        if (label.isBlank()) return "label이 비었어요"
        if (permissions.any { !it.startsWith("android.permission.") }) return "permissions가 잘못됐어요"
        if (permissions.isEmpty() && settings == null) return "permissions가 비었어요"
        return null
    }

    companion object {
        const val SETTINGS_NOTIFICATIONS = "notifications"

        /** Notification access (Settings › 알림 읽기), for the notification digest tools. */
        const val SETTINGS_NOTIFICATION_LISTENER = "notification_listener"
    }
}

@Serializable
data class ParcelEvent(val at: String, val text: String)

/**
 * Board "Parcel": one delivery's stage ([status], one of [STAGES]) with its latest steps, newest first.
 * [source] says where it was found ("문자에서 송장 찾음").
 */
@Serializable
@SerialName("parcel")
data class ParcelCard(
    val title: String,
    val status: String,
    val carrier: String? = null,
    val tracking: String? = null,
    val source: String? = null,
    val events: List<ParcelEvent> = emptyList(),
) : CardSpec {
    override fun validate(): String? {
        if (title.isBlank()) return "title이 비었어요"
        if (status !in STAGES) return "status는 ${STAGES.joinToString("|")} 중 하나여야 해요 ($status)"
        capError("events", events.size)?.let { return it }
        events.forEachIndexed { i, e -> if (e.text.isBlank()) return "events[$i].text가 비었어요" }
        return null
    }

    companion object {
        val STAGES = listOf("접수", "이동 중", "배송 출발", "도착")
    }
}

/** Lists other than photo URIs hold at most this many entries. */
const val MAX_LIST = 20

private fun capError(what: String, size: Int): String? =
    if (size > MAX_LIST) "${what}는 최대 ${MAX_LIST}개예요 (${size}개)" else null

/**
 * Gallery images only: a MediaStore image item, a Photo Picker item, or an image document of the media provider.
 * Other MediaStore collections (files, downloads, audio, video) and other providers are never thumbnailed, copied
 * or shared as photos.
 */
private val PHOTO_URI = Regex(
    """content://(?:""" +
        """media/[A-Za-z0-9_-]+/images/media/\d+""" +
        """|media/picker(?:_get_content)?/\d+/com\.android\.providers\.media\.photopicker/media/\d+""" +
        """|com\.android\.providers\.media\.photopicker/media/\d+""" +
        """|com\.android\.providers\.media\.documents/document/image(?::|%3A)\d+""" +
        """)""",
)

fun isPhotoUri(uri: String): Boolean = PHOTO_URI.matches(uri)

private val CLOCK = Regex("""([01]?\d|2[0-3]):[0-5]\d""")

internal fun isClock(s: String) = CLOCK.matches(s.trim())

private fun isWebUrl(s: String) = s.startsWith("https://") || s.startsWith("http://")

private fun coordError(what: String, lat: Double, lng: Double): String? = when {
    !(lat in -90.0..90.0) -> "$what.lat은 -90–90 사이여야 해요 ($lat)"
    !(lng in -180.0..180.0) -> "$what.lng는 -180–180 사이여야 해요 ($lng)"
    else -> null
}

/** Parses an ISO date, local date-time or offset date-time; offsets are converted to the device zone. */
internal fun parseIso(s: String): LocalDateTime? {
    val t = s.trim()
    return try {
        OffsetDateTime.parse(t).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
    } catch (e: DateTimeParseException) {
        try {
            LocalDateTime.parse(t)
        } catch (e: DateTimeParseException) {
            try {
                LocalDate.parse(t).atStartOfDay()
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }
}
