package com.phoneuse.phone

import com.phoneuse.i18n.tr
import com.phoneuse.cards.CardSpec
import com.phoneuse.cards.EventCard
import com.phoneuse.cards.EventConflict
import com.phoneuse.cards.parseIso
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** An event (instance) of the phone's calendars, in device-local time. All-day events run midnight to midnight. */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val location: String?,
    val allDay: Boolean = false,
)

/** The phone's calendars (CalendarContract); Android's is [AndroidCalendarSource]. */
interface CalendarSource {
    fun canRead(): Boolean
    fun canWrite(): Boolean

    /** Event instances that overlap [from, to). */
    fun events(from: LocalDateTime, to: LocalDateTime): List<CalendarEvent>

    /** Adds to the primary writable calendar; the new id, or null when there is no calendar to write to. */
    fun insert(title: String, start: LocalDateTime, end: LocalDateTime, location: String?, reminderMin: Int?, allDay: Boolean): Long?
}

/**
 * calendar_add's arguments as the model gave them; times are ISO-8601 (date for all-day, else date-time). An
 * all-day event's [end] is its last day (inclusive), as on the event card.
 */
data class EventDraft(
    val title: String,
    val start: String,
    val end: String,
    val location: String? = null,
    val reminderMin: Int? = null,
)

/** A draft with its times read: what is saved, previewed and compared. */
internal data class NormalEvent(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val location: String?,
    val reminderMin: Int?,
)

/** calendar_add(confirmed=true) saves only what the user saw: the conversation's latest event card. */
object EventPreview {
    /** The draft read, or a reason it cannot be. A date-only event runs to the end of its (inclusive) last day. */
    internal fun normalize(d: EventDraft): Result<NormalEvent> {
        val title = d.title.replace(Regex("\\s+"), " ").trim()
        if (title.isEmpty()) return fail("title이 비었어요")
        if (title.length > MAX_TITLE) return fail("title이 너무 길어요 (최대 ${MAX_TITLE}자)")
        val start = parseIso(d.start) ?: return fail("start는 ISO 날짜·시간이어야 해요 (${d.start})")
        var end = parseIso(d.end) ?: return fail("end는 ISO 날짜·시간이어야 해요 (${d.end})")
        val allDay = !d.start.contains('T') && !d.end.contains('T')
        if (end.isBefore(start)) return fail("end가 start보다 앞이에요")
        // Stored all-day ends are exclusive midnights.
        if (allDay) end = end.toLocalDate().plusDays(1).atStartOfDay()
        if (d.reminderMin != null && d.reminderMin !in 0..MAX_REMINDER_MIN) return fail("reminder_min은 0–$MAX_REMINDER_MIN 이어야 해요")
        val location = d.location?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LOCATION)
        return Result.success(NormalEvent(title, start, end, allDay, location, d.reminderMin))
    }

    /** True when [card] shows exactly [draft] (same title, times, place and reminder). */
    fun matches(card: EventCard?, draft: EventDraft): Boolean {
        card ?: return false
        val shown = normalize(EventDraft(card.title, card.start, card.end, card.location, card.reminderMin)).getOrNull() ?: return false
        val asked = normalize(draft).getOrNull() ?: return false
        return shown == asked
    }

    private fun fail(reason: String): Result<NormalEvent> = Result.failure(IllegalArgumentException(reason))

    private const val MAX_TITLE = 100
    private const val MAX_LOCATION = 200
    private const val MAX_REMINDER_MIN = 40_320
}

/** Which existing events share time with a new one. Pure. */
object Overlaps {
    const val MAX = 20

    /**
     * Events overlapping [new], by start, at most [MAX]. A zero-length event counts as one minute. Existing all-day
     * events (birthdays, holidays) do not clash with a timed event; a new all-day event shows that day's events.
     */
    fun find(new: CalendarEvent, existing: List<CalendarEvent>): List<CalendarEvent> {
        val (s, e) = span(new)
        return existing
            .filter { !(it.allDay && !new.allDay) }
            .filter { val (os, oe) = span(it); s < oe && os < e }
            .sortedBy { it.start }
            .take(MAX)
    }

    private fun span(ev: CalendarEvent): Pair<LocalDateTime, LocalDateTime> =
        ev.start to if (ev.end.isAfter(ev.start)) ev.end else ev.start.plusMinutes(1)
}

/**
 * calendar_list and calendar_add. Adding never writes at once: it shows an event card (with conflicts), whose
 * [그대로 저장] asks the model to call again with confirmed=true; that call saves only when the conversation's
 * latest event card ([priorCards], from earlier replies) shows the same event.
 */
class CalendarTool(private val source: CalendarSource, private val priorCards: suspend () -> List<CardSpec>) {
    fun list(from: String, to: String): PhoneReply {
        val start = parseIso(from) ?: return PhoneReply.Failed("from은 ISO 날짜·시간이어야 해요 ($from)")
        val endRaw = parseIso(to) ?: return PhoneReply.Failed("to는 ISO 날짜·시간이어야 해요 ($to)")
        // A date-only end means the whole of that day.
        val end = if (to.contains('T')) endRaw else endRaw.plusDays(1)
        if (!end.isAfter(start)) return PhoneReply.Failed("to가 from보다 앞이에요")
        if (ChronoUnit.DAYS.between(start, end) > MAX_DAYS) return PhoneReply.Failed("한 번에 ${MAX_DAYS}일까지만 볼 수 있어요")
        if (!source.canRead()) return PhoneReply.PermissionNeeded(LABEL, READ_PERMISSIONS)
        val events = try {
            source.events(start, end)
        } catch (e: SecurityException) {
            return PhoneReply.PermissionNeeded(LABEL, READ_PERMISSIONS)
        }.sortedBy { it.start }
        if (events.isEmpty()) return PhoneReply.Ok("일정 없음 ($from – $to)")
        return PhoneReply.Ok(
            buildJsonArray {
                for (ev in events.take(MAX_EVENTS)) {
                    add(
                        buildJsonObject {
                            put("id", ev.id)
                            put("title", ev.title)
                            // All-day: dates, the end being the last day (as calendar_add takes it).
                            put("start", iso(ev.start, ev.allDay))
                            put("end", if (ev.allDay) iso(ev.end.minusDays(1).coerceAtLeast(ev.start), true) else ev.end.toString())
                            ev.location?.takeIf { it.isNotBlank() }?.let { put("location", it) }
                            if (ev.allDay) put("all_day", true)
                        },
                    )
                }
            }.toString() + if (events.size > MAX_EVENTS) "\n(외 ${events.size - MAX_EVENTS}개 — 범위를 좁혀 다시 보세요)" else "",
        )
    }

    suspend fun add(draft: EventDraft, confirmed: Boolean): PhoneReply {
        val ev = EventPreview.normalize(draft).getOrElse { return PhoneReply.Failed(it.message ?: "invalid event") }
        if (confirmed) return save(draft, ev)
        if (!source.canRead()) return permission()
        val conflicts = try {
            Overlaps.find(CalendarEvent(-1, ev.title, ev.start, ev.end, ev.location, ev.allDay), source.events(ev.start, end(ev)))
        } catch (e: SecurityException) {
            return permission()
        }
        val card = EventCard(
            title = ev.title,
            start = iso(ev.start, ev.allDay),
            // The card shows an all-day event's last day, not the exclusive end.
            end = if (ev.allDay) iso(ev.end.minusDays(1), true) else iso(ev.end, false),
            location = ev.location,
            reminderMin = ev.reminderMin,
            conflicts = conflicts.map { EventConflict(it.title, it.start.toString(), it.end.toString()) },
        )
        val clash = if (conflicts.isEmpty()) {
            "겹치는 일정 없음."
        } else {
            "겹치는 일정: " + conflicts.joinToString("; ") { "${it.title} ${it.start.toLocalTime()}–${it.end.toLocalTime()}" } + "."
        }
        return PhoneReply.Ok(
            "미리보기 카드를 띄웠어요. 아직 저장하지 않았어요. $clash 사용자가 카드의 [그대로 저장]을 누르면 같은 값으로 " +
                "calendar_add(confirmed=true)를 부르세요. 짧게 확인을 부탁하는 말만 쓰세요.",
            card,
        )
    }

    private suspend fun save(draft: EventDraft, ev: NormalEvent): PhoneReply {
        val latest = priorCards().lastOrNull { it is EventCard } as EventCard?
        if (!EventPreview.matches(latest, draft)) {
            return PhoneReply.Failed(
                "저장 전 확인이 없어요. confirmed 없이 calendar_add를 불러 미리보기 카드를 먼저 보여주세요 " +
                    "(confirmed=true는 대화의 마지막 일정 카드와 내용이 같을 때만 저장해요).",
            )
        }
        if (!source.canRead() || !source.canWrite()) return permission()
        return try {
            val same = source.events(ev.start, end(ev)).any { it.title == ev.title && it.start == ev.start && it.end == ev.end }
            if (same) return PhoneReply.Ok("이미 저장된 일정이에요. 다시 저장하지 않았어요.")
            source.insert(ev.title, ev.start, ev.end, ev.location, ev.reminderMin, ev.allDay)
                ?: return PhoneReply.Failed("일정을 저장할 캘린더가 없어요. 사용자에게 캘린더 앱에서 계정을 추가해 달라고 하세요.")
            PhoneReply.Ok("OK: 캘린더에 '${ev.title}' 일정을 저장했어요 (${iso(ev.start, ev.allDay)}).")
        } catch (e: SecurityException) {
            permission()
        }
    }

    private fun end(ev: NormalEvent) = if (ev.end.isAfter(ev.start)) ev.end else ev.start.plusMinutes(1)

    private fun iso(t: LocalDateTime, allDay: Boolean) = if (allDay) t.toLocalDate().toString() else t.toString()

    private fun permission() = PhoneReply.PermissionNeeded(LABEL, PERMISSIONS)

    companion object {
        val LABEL get() = tr("Calendar", "캘린더")
        /** Adding reads (conflicts, duplicates) and writes. */
        val PERMISSIONS = listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR")

        /** Listing only reads. */
        val READ_PERMISSIONS = listOf("android.permission.READ_CALENDAR")
        const val MAX_DAYS = 62
        const val MAX_EVENTS = 50
    }
}
