package com.phoneuse.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmToolTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val now = ZonedDateTime.of(2026, 11, 2, 22, 0, 0, 0, seoul)

    private inner class Source(var next: ZonedDateTime? = null, var opens: Boolean = true, var registers: Boolean = true) : AlarmSource {
        val set = mutableListOf<Triple<Int, Int, String>>()
        override fun next() = next
        override fun set(hour: Int, minute: Int, label: String): Boolean {
            set += Triple(hour, minute, label)
            if (opens && registers) next = now.withHour(hour).withMinute(minute).let { if (it.isAfter(now)) it else it.plusDays(1) }
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
        assertEquals(listOf("알람 설정: 07:30 · 기상"), asked)
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
}
