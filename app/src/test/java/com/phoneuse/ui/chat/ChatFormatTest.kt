package com.phoneuse.ui.chat

import com.phoneuse.chat.Conversation
import com.phoneuse.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ChatFormatTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // Sunday 2026-11-01 19:40
    private val now = at(2026, 11, 1, 19, 40)

    @Test
    fun modelNames() {
        assertEquals("GPT-6.1 Terra", modelDisplayName("gpt-6.1-terra"))
        assertEquals("GPT-6 Luna", modelDisplayName("gpt-6-luna"))
        assertEquals("GPT-5.6 Terra", modelDisplayName("gpt-5.6-terra"))
        assertEquals("GPT-5.6 Codex Mini", modelDisplayName("gpt-5.6-codex-mini"))
        assertEquals("o4-mini", modelDisplayName("o4-mini"))

        assertEquals("Terra", modelChipName("gpt-6.1-terra"))
        assertEquals("Codex Mini", modelChipName("gpt-5.6-codex-mini"))
        assertEquals("GPT-5", modelChipName("gpt-5"))
        assertEquals("o4-mini", modelChipName("o4-mini"))
    }

    @Test
    fun homeDateLine() {
        assertEquals("11.01 일 · 저녁 7:40", homeDateLine(now, zone))
        assertEquals("11.02 월 · 오전 9:05", homeDateLine(at(2026, 11, 2, 9, 5), zone))
        assertEquals("11.02 월 · 새벽 12:30", homeDateLine(at(2026, 11, 2, 0, 30), zone))
        assertEquals("11.02 월 · 오후 12:00", homeDateLine(at(2026, 11, 2, 12, 0), zone))
    }

    @Test
    fun groupsByDay() {
        val today = Conversation(1, "약국", at(2026, 11, 1, 19, 20))
        val todayEarly = Conversation(2, "PDF", at(2026, 11, 1, 0, 5))
        val yesterday = Conversation(3, "사진", at(2026, 10, 31, 23, 59))
        val week = Conversation(4, "전시", at(2026, 10, 26, 10, 0))
        val older = Conversation(5, "알람", at(2026, 10, 25, 23, 0))

        val groups = groupConversations(listOf(today, todayEarly, yesterday, week, older), now, zone)

        assertEquals(listOf("오늘", "어제", "지난 7일", "이전"), groups.map { it.first })
        assertEquals(listOf(listOf(1L, 2L), listOf(3L), listOf(4L), listOf(5L)), groups.map { g -> g.second.map { it.id } })
        assertEquals(emptyList<Any>(), groupConversations(emptyList(), now, zone))
    }

    @Test
    fun rowTimes() {
        assertEquals("19:20", conversationTime(at(2026, 11, 1, 19, 20), now, zone))
        assertEquals("토", conversationTime(at(2026, 10, 31, 9, 0), now, zone))
        assertEquals("월", conversationTime(at(2026, 10, 26, 9, 0), now, zone))
        assertEquals("10.25", conversationTime(at(2026, 10, 25, 9, 0), now, zone))

        assertEquals("19:20", relativeDay(at(2026, 11, 1, 19, 20), now, zone))
        assertEquals("어제", relativeDay(at(2026, 10, 31, 9, 0), now, zone))
        assertEquals("6일 전", relativeDay(at(2026, 10, 26, 9, 0), now, zone))
        assertEquals("10.25", relativeDay(at(2026, 10, 25, 9, 0), now, zone))
    }

    @Test
    fun englishFormats() {
        val was = Lang.korean
        Lang.korean = false
        try {
            assertEquals("Sun, Nov 1 · 7:40 PM", homeDateLine(now, zone))
            assertEquals("Mon, Nov 2 · 3:21 AM", homeDateLine(at(2026, 11, 2, 3, 21), zone))
            assertEquals("Sat", conversationTime(at(2026, 10, 31, 9, 0), now, zone))
            assertEquals("Oct 25", conversationTime(at(2026, 10, 25, 9, 0), now, zone))
            assertEquals("Yesterday", relativeDay(at(2026, 10, 31, 9, 0), now, zone))
            assertEquals("6d ago", relativeDay(at(2026, 10, 26, 9, 0), now, zone))
        } finally {
            Lang.korean = was
        }
    }
}
