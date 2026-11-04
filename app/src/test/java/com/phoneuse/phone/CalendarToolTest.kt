package com.phoneuse.phone

import com.phoneuse.cards.CardSpec
import com.phoneuse.cards.EventCard
import com.phoneuse.cards.EventConflict
import com.phoneuse.cards.StatCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CalendarToolTest {
    private class Source(var read: Boolean = true, var write: Boolean = true) : CalendarSource {
        val events = mutableListOf(
            CalendarEvent(1, "팀 회의", LocalDateTime.of(2026, 11, 3, 14, 0), LocalDateTime.of(2026, 11, 3, 15, 0), "3층", false),
            CalendarEvent(2, "점심", LocalDateTime.of(2026, 11, 3, 12, 0), LocalDateTime.of(2026, 11, 3, 13, 0), null, false),
        )
        val inserted = mutableListOf<CalendarEvent>()
        override fun canRead() = read
        override fun canWrite() = write
        override fun events(from: LocalDateTime, to: LocalDateTime): List<CalendarEvent> {
            if (!read) throw SecurityException("no")
            return events.filter { it.start < to && from < it.end }
        }

        override fun insert(title: String, start: LocalDateTime, end: LocalDateTime, location: String?, reminderMin: Int?, allDay: Boolean): Long? {
            if (!write) throw SecurityException("no")
            val e = CalendarEvent(100L + inserted.size, title, start, end, location, allDay)
            inserted += e
            events += e
            return e.id
        }
    }

    private val source = Source()
    private var prior: List<CardSpec> = emptyList()
    private val tool = CalendarTool(source) { prior }
    private val draft = EventDraft("치과", "2026-11-03T14:30", "2026-11-03T15:30", "강남역", 30)

    @Test
    fun previewCardListsConflictsAndSavesNothing() = runBlocking {
        val r = tool.add(draft, confirmed = false) as PhoneReply.Ok
        val card = r.card as EventCard
        assertEquals("치과", card.title)
        assertEquals(listOf(EventConflict("팀 회의", "2026-11-03T14:00", "2026-11-03T15:00")), card.conflicts)
        assertNull(card.validate())
        assertTrue(r.text, r.text.contains("팀 회의"))
        assertTrue(source.inserted.isEmpty())
    }

    @Test
    fun addRequiresMatchingPreviewCard() = runBlocking {
        // No card shown yet: refused.
        assertTrue(tool.add(draft, confirmed = true) is PhoneReply.Failed)
        // The latest event card differs (another time): refused.
        val shown = (tool.add(draft, confirmed = false) as PhoneReply.Ok).card as EventCard
        val other = (tool.add(draft.copy(start = "2026-11-03T16:00", end = "2026-11-03T17:00"), false) as PhoneReply.Ok).card!!
        prior = listOf(shown, other)
        assertTrue(tool.add(draft, confirmed = true) is PhoneReply.Failed)
        assertTrue(source.inserted.isEmpty())
        // Pure comparison: trimmed text and the same instant match; any other field does not.
        assertTrue(EventPreview.matches(shown, draft.copy(title = " 치과 ", start = "2026-11-03T14:30:00")))
        assertFalse(EventPreview.matches(shown, draft.copy(reminderMin = 10)))
        assertFalse(EventPreview.matches(shown, draft.copy(location = null)))
        assertFalse(EventPreview.matches(shown, draft.copy(title = "치과 예약")))
        assertFalse(EventPreview.matches(null, draft))
        // The latest event card matches, even with other cards after it.
        prior = listOf(other, shown, StatCard("x", "1"))
        val saved = tool.add(draft, confirmed = true)
        assertTrue(saved.text, saved is PhoneReply.Ok && saved.text.startsWith("OK"))
        assertEquals(listOf("치과"), source.inserted.map { it.title })
        assertEquals(LocalDateTime.of(2026, 11, 3, 14, 30), source.inserted[0].start)
        // Pressing [그대로 저장] twice does not save it twice.
        val again = tool.add(draft, confirmed = true)
        assertTrue(again.text, again.text.contains("이미"))
        assertEquals(1, source.inserted.size)
    }

    @Test
    fun returnsPermissionNeededWhenDenied() = runBlocking {
        source.read = false
        val list = tool.list("2026-11-03", "2026-11-03")
        assertTrue(list.text, list.text.startsWith("권한 필요: 캘린더"))
        assertEquals(CalendarTool.READ_PERMISSIONS, (list as PhoneReply.PermissionNeeded).permissions)
        assertEquals(CalendarTool.PERMISSIONS, (tool.add(draft, false) as PhoneReply.PermissionNeeded).permissions)
        assertTrue(tool.add(draft, confirmed = false) is PhoneReply.PermissionNeeded)
        // Read allowed but write not: the save asks too.
        source.read = true
        source.write = false
        prior = listOf((tool.add(draft, false) as PhoneReply.Ok).card!!)
        assertTrue(tool.add(draft, confirmed = true) is PhoneReply.PermissionNeeded)
        assertTrue(source.inserted.isEmpty())
    }

    @Test
    fun listReturnsEventsOfTheRange() {
        val r = tool.list("2026-11-03", "2026-11-03")
        assertTrue(r.text, r is PhoneReply.Ok && r.text.contains("\"title\":\"점심\"") && r.text.contains("\"start\":\"2026-11-03T12:00\""))
        // Sorted by start.
        assertTrue(r.text.indexOf("점심") < r.text.indexOf("팀 회의"))
        assertTrue(tool.list("2026-11-04", "2026-11-04").text.startsWith("일정 없음"))
        assertTrue(tool.list("내일", "2026-11-04") is PhoneReply.Failed)
        assertTrue(tool.list("2026-11-05", "2026-11-04") is PhoneReply.Failed)
    }

    @Test
    fun allDayEndIsTheLastDayInclusive() = runBlocking {
        val trip = EventDraft("제주 여행", "2026-11-03", "2026-11-05")
        val card = (tool.add(trip, false) as PhoneReply.Ok).card as EventCard
        // The card shows the last day; the save covers three whole days.
        assertEquals("2026-11-03", card.start)
        assertEquals("2026-11-05", card.end)
        // An all-day new event shows the timed events of those days.
        assertEquals(listOf("점심", "팀 회의"), card.conflicts.map { it.title })
        prior = listOf(card)
        assertTrue(tool.add(trip, confirmed = true).text.startsWith("OK"))
        val saved = source.inserted.single()
        assertEquals(LocalDateTime.of(2026, 11, 6, 0, 0), saved.end)
        assertTrue(saved.allDay)
        // A one-day event: end equal to start.
        assertTrue(EventPreview.matches(EventCard("휴가", "2026-11-09", "2026-11-09"), EventDraft("휴가", "2026-11-09", "2026-11-09")))
        val list = tool.list("2026-11-03", "2026-11-05").text
        assertTrue(list, list.contains("\"start\":\"2026-11-03\",\"end\":\"2026-11-05\",\"all_day\":true"))
    }

    @Test
    fun listAsksOnlyToRead() {
        source.read = false
        assertEquals(listOf("android.permission.READ_CALENDAR"), (tool.list("2026-11-03", "2026-11-03") as PhoneReply.PermissionNeeded).permissions)
    }

    @Test
    fun badTimesAreRejected() = runBlocking {
        assertTrue(tool.add(draft.copy(start = "오후 2시"), false) is PhoneReply.Failed)
        assertTrue(tool.add(draft.copy(end = "2026-11-03T13:00"), false) is PhoneReply.Failed)
        assertTrue(tool.add(draft.copy(title = " "), false) is PhoneReply.Failed)
        assertTrue(tool.add(draft.copy(reminderMin = -5), false) is PhoneReply.Failed)
        assertEquals(0, source.inserted.size)
    }
}
