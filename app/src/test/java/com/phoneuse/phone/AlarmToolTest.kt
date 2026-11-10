package com.phoneuse.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmToolTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val now = ZonedDateTime.of(2026, 11, 2, 22, 0, 0, 0, seoul)

    private inner class Source(var next: ZonedDateTime? = null, var opens: Boolean = true, var registers: Boolean = true) : AlarmSource {
        val set = mutableListOf<Triple<Int, Int, String>>()
        val days = mutableListOf<List<DayOfWeek>>()
        val timers = mutableListOf<Pair<Int, String>>()
        var front = true
        override fun next() = next
        override fun inFront() = front
        override fun set(hour: Int, minute: Int, label: String, days: List<DayOfWeek>): Boolean {
            set += Triple(hour, minute, label)
            this.days.add(days)
            if (opens && registers) {
                var at = now.withHour(hour).withMinute(minute).let { if (it.isAfter(now)) it else it.plusDays(1) }
                while (days.isNotEmpty() && at.dayOfWeek !in days) at = at.plusDays(1)
                next = at
            }
            return opens
        }
        override fun timer(seconds: Int, label: String): Boolean {
            timers += seconds to label
            return opens
        }
    }

    private val source = Source()
    private var waits = 0
    private val tool = AlarmTool(source, { waits++ }) { now }

    @Test
    fun nextAlarmIsHourMinute() {
        assertTrue(tool.next().text.startsWith("예약된 알람 없음"))
        source.next = ZonedDateTime.of(2026, 11, 3, 7, 5, 0, 0, seoul)
        assertEquals("07:05 (내일 11/3 화)", tool.next().text)
        source.next = ZonedDateTime.of(2026, 11, 2, 23, 0, 0, 0, seoul)
        assertEquals("23:00 (오늘 11/2 월)", tool.next().text)
    }

    @Test
    fun setAlarmAsksFirst() = runBlocking {
        val asked = mutableListOf<String>()
        val ok = tool.set(7, 30, " 기상 ") { asked += it; true }
        assertTrue(ok.text, ok is PhoneReply.Ok && ok.text.startsWith("OK") && ok.text.contains("07:30"))
        assertEquals(listOf(AlarmTool.alarmSummary("07:30", emptyList(), "기상")), asked)
        assertEquals(listOf(Triple(7, 30, "기상")), source.set)
        // Declined: nothing is set.
        assertTrue(tool.set(8, 0, "") { false } is PhoneReply.Cancelled)
        assertEquals(1, source.set.size)
        // No clock app answered.
        source.opens = false
        assertTrue(tool.set(9, 0, "") { true } is PhoneReply.Failed)
    }

    @Test
    fun anAlarmThatNeverAppearsIsNotReportedAsSet() = runBlocking {
        // A blocked background launch does not throw: the next alarm simply never shows 06:15.
        source.registers = false
        val r = tool.set(6, 15, "기상") { true }
        assertTrue(r.text, r is PhoneReply.Ok && r.text.startsWith("확인 못 함") && r.text.contains("다시 맞추지 말고"))
        assertTrue(waits > 1)
        assertEquals(1, source.set.size)
    }

    @Test
    fun badTimesAreRejectedBeforeAsking() = runBlocking {
        var asked = 0
        assertTrue(tool.set(24, 0, "") { asked++; true } is PhoneReply.Failed)
        assertTrue(tool.set(7, 60, "") { asked++; true } is PhoneReply.Failed)
        assertEquals(0, asked)
    }

    @Test
    fun repeatingAlarmGoesOutWithItsDays() = runBlocking {
        val asked = mutableListOf<String>()
        val r = tool.set(7, 0, "기상", listOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.MONDAY)) { asked += it; true }
        assertEquals(listOf(listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)), source.days)
        assertEquals(listOf(AlarmTool.alarmSummary("07:00", listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), "기상")), asked)
        assertEquals("OK: 07:00 알람을 맞췄어요 (매주 월, 금, 기상)", r.text)
    }

    @Test
    fun anEarlierAlarmHidesTheNewOne() = runBlocking {
        source.registers = false
        source.next = now.withHour(23)
        val r = tool.set(7, 30, "") { true }
        assertTrue(r.text, r is PhoneReply.Ok && r.text.startsWith("요청함") && "23:00" in r.text && "다시 맞추지 말고" in r.text)
        assertEquals(1, source.set.size)
    }

    @Test
    fun anAlarmAlreadyDueThenProvesNothing() = runBlocking {
        // Tuesday 07:30 is already there: seeing it afterwards does not show that a second one was set.
        source.next = now.plusDays(1).withHour(7).withMinute(30)
        val r = tool.set(7, 30, "") { true }
        assertTrue(r.text, r is PhoneReply.Ok && r.text.startsWith("요청함") && "이미" in r.text && "다시 맞추지 말고" in r.text)
        assertEquals(0, waits)
        assertEquals(1, source.set.size)
    }

    @Test
    fun sameTimeOnAnotherDayIsNotTheRepeatingAlarm() = runBlocking {
        // Tuesday 07:00 is already there; the weekend alarm at 07:00 cannot be told apart from it by the time alone.
        source.registers = false
        source.next = now.plusDays(1).withHour(7).withMinute(0)
        val r = tool.set(7, 0, "", listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) { true }
        assertTrue(r.text, r.text.startsWith("요청함") && "주말" in r.text)
    }

    @Test
    fun timerAsksThenStarts() = runBlocking {
        val asked = mutableListOf<String>()
        val r = tool.timer(180, "  라면 ") { asked += it; true }
        assertEquals(listOf(AlarmTool.timerSummary(180, "라면")), asked)
        assertEquals(listOf(180 to "라면"), source.timers)
        assertEquals("OK: 3분 타이머를 시작했어요 (라면)", r.text)

        assertEquals(PhoneReply.Cancelled, tool.timer(60, "") { false })
        assertEquals(1, source.timers.size)
    }

    @Test
    fun aTimerAskedForFromTheBackgroundIsOnlyRequested() = runBlocking {
        // A timer cannot be read back, and Android may drop a request sent while the app is off screen.
        source.front = false
        val r = tool.timer(60, "") { true }
        assertTrue(r.text, r is PhoneReply.Ok && r.text.startsWith("요청함: 시계 앱에 1분 타이머를") && "다시 시작하지 말고" in r.text)
        assertEquals(listOf(60 to ""), source.timers)
    }

    @Test
    fun badTimersAreRejectedBeforeAsking() = runBlocking {
        var asked = 0
        assertTrue(tool.timer(0, "") { asked++; true } is PhoneReply.Failed)
        assertTrue(tool.timer(86_401, "") { asked++; true } is PhoneReply.Failed)
        assertEquals(0, asked)
        source.opens = false
        assertTrue(tool.timer(60, "") { true } is PhoneReply.Failed)
    }

    @Test
    fun daysAndDurationsReadNaturally() {
        val week = DayOfWeek.values().toList()
        assertEquals("매일", AlarmTool.daysText(week, korean = true))
        assertEquals("평일", AlarmTool.daysText(week.take(5), korean = true))
        assertEquals("주말", AlarmTool.daysText(week.drop(5), korean = true))
        assertEquals("매주 월, 수", AlarmTool.daysText(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), korean = true))
        assertEquals("Every Mon, Wed", AlarmTool.daysText(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), korean = false))
        assertEquals("1시간 5분 30초", AlarmTool.duration(3930, korean = true))
        assertEquals("3분", AlarmTool.duration(180, korean = true))
        assertEquals("1 min 30 s", AlarmTool.duration(90, korean = false))
        assertEquals("1 h", AlarmTool.duration(3600, korean = false))
    }
}
