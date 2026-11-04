package com.phoneuse.proactive

import com.phoneuse.memory.Confidence
import com.phoneuse.memory.Memory
import com.phoneuse.memory.MemoryKind
import com.phoneuse.phone.SmsMessage
import com.phoneuse.phone.StoredNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

class NudgeSupportTest {
    @get:Rule val tmp = TemporaryFolder()
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun nudge(key: String, expiresAt: Long = 10_000, kind: NudgeKind = NudgeKind.REPLY) =
        Nudge(kind, "t", "b", emptyList(), 0, expiresAt, key)

    @Test
    fun logSettlesOnceAndExpiresAsIgnored() {
        val file = File(tmp.root, NudgeLog.FILE_NAME)
        val log = NudgeLog(file)
        log.add(nudge("a"), 1_000)
        log.add(nudge("b"), 2_000)
        assertEquals(NudgeOutcome.OPENED, log.resolve("a", NudgeOutcome.OPENED, 3_000)?.outcome)
        // Already settled: a later swipe does not turn it into an ignore.
        assertNull(log.resolve("a", NudgeOutcome.IGNORED, 4_000))
        assertEquals(listOf("b"), NudgeLog.active(log.all(), 5_000).map { it.dedupeKey })
        assertEquals(listOf("b"), log.expire(10_000).map { it.dedupeKey })
        assertEquals(NudgeOutcome.IGNORED, log.get("b")?.outcome)
        // It survives a restart.
        assertEquals(log.all(), NudgeLog(file).all())
        // A month later both are gone.
        log.expire(2_000 + NudgeLog.KEEP_MS)
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun briefingTextFromOurOwnFields() {
        assertEquals("서울특별시 강남구", BriefingText.area("역삼동, 강남구, 서울특별시, 06236, 대한민국"))
        assertEquals("성남시 분당구", BriefingText.area("정자동, 분당구, 성남시, 경기도, 13561, 대한민국"))
        assertNull(BriefingText.area(null))
        assertEquals("맑음 4°→13°, 우산 필요 없어요", BriefingText.weatherLine("**맑음 4°→13°, 우산 필요 없어요** ([기상청](https://weather.go.kr))\n출처: …"))
        assertNull(BriefingText.weatherLine("  \n "))
        assertEquals("오늘 남은 일정은 없어요. 밤사이 답장 필요한 메시지 2개.", BriefingText.body(emptyList(), 0, 2, seoul))
    }

    @Test
    fun memoryDates() {
        fun m(text: String, date: String? = null, due: String? = null, createdAt: Long = 0) =
            Memory(1, text, MemoryKind.PERSON, Confidence.SURE, createdAt, createdAt, date = date, due = due)
        assertEquals(MonthDay.of(11, 20), MemoryDates.monthDay(m("엄마 생신", date = "11월 20일")))
        assertEquals(MonthDay.of(3, 5), MemoryDates.monthDay(m("결혼기념일", date = "1999-03-05")))
        assertEquals(MonthDay.of(3, 5), MemoryDates.monthDay(m("결혼기념일", date = "3/5")))
        assertEquals(MonthDay.of(1, 9), MemoryDates.monthDay(m("아빠 생일은 1월 9일")))
        assertNull(MemoryDates.monthDay(m("할머니 생신 음력 8월 3일")))
        val said = LocalDate.of(2026, 11, 1).atTime(20, 0).atZone(seoul).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 2), MemoryDates.due(m("보고서", due = "내일", createdAt = said), seoul))
        assertEquals(LocalDate.of(2026, 11, 15), MemoryDates.due(m("보고서", due = "2026-11-15"), seoul))
        assertEquals(LocalDate.of(2027, 1, 3), MemoryDates.due(m("보고서", due = "1월 3일", createdAt = said), seoul))
        assertTrue(MemoryDates.usable(listOf(m("a"), m("b").copy(confidence = Confidence.GUESS))).size == 1)
    }

    @Test
    fun paymentsReadOnceFromNotificationAndText() {
        val text = "[Web발신]\n신한카드(1234)승인 홍*동 12,500원(일시불)11/02 12:30 스타벅스 누적1,234,000원"
        val n = StoredNotification("k", "com.samsung.android.messaging", "메시지", "15447200", text, 1_000_000)
        val sms = SmsMessage("15447200", text, 1_030_000, incoming = true)
        val got = NudgeReaders.payments(listOf(n), listOf(sms))
        assertEquals(1, got.size)
        assertEquals(12_500L, got.single().amount)
        // A text the user sent is never a payment to them.
        assertTrue(NudgeReaders.payments(emptyList(), listOf(sms.copy(incoming = false))).isEmpty())
    }

    @Test
    fun quietHoursComeFromTheAppSetting() {
        val night = java.time.LocalTime.of(23, 30)
        assertTrue(NudgeSettings().withQuiet(true, 23 * 60, 7 * 60).isQuiet(night))
        assertTrue(!NudgeSettings().withQuiet(false, 23 * 60, 7 * 60).isQuiet(night))
        assertTrue(NudgeSettings().withQuiet(true, 0, 8 * 60).isQuiet(java.time.LocalTime.of(7, 59)))
    }

    @Test
    fun travelMinutes() {
        assertEquals(14, Travel.minutes(1000.0))
        assertEquals(27, Travel.minutes(5000.0))
        assertEquals(180, Travel.minutes(500_000.0))
    }

    /** Deleting a conversation also drops the first message it left in asks.json. */
    @Test
    fun askLogForgetsDeletedConversations() {
        val log = AskLog(File(tmp.root, AskLog.FILE_NAME))
        log.add("엄마한테 전화번호 물어봐", 1, conversationId = 1)
        log.add("오늘 날씨", 2, conversationId = 2)
        log.forget(setOf(1L))
        assertEquals(listOf(2L), log.all().map { it.conversationId })
        assertEquals(listOf(2L), AskLog(File(tmp.root, AskLog.FILE_NAME)).all().map { it.conversationId })
    }

    /** The repeated-ask source is typed asks only, outside tainted conversations, never our own prompts. */
    @Test
    fun askLogKeepsOnlyTheUsersOwnAsks() {
        val log = AskLog(File(tmp.root, AskLog.FILE_NAME))
        val day = 24 * 60 * 60_000L
        log.add("  오늘 날씨 알려줘 ", 1 * day, conversationId = 1)
        log.add("오늘 날씨 알려줘", 2 * day, conversationId = 2)
        log.add("오늘 날씨 알려줘", 3 * day, conversationId = 3)
        // A tile puts this prompt in the composer; sent unchanged it is ours, not a habit.
        log.add(com.phoneuse.today.TodaySummary.PARCEL_PROMPT, 3 * day, conversationId = 4)
        log.add("루틴 만들어줘: 매일 8시에 오늘 날씨", 3 * day, conversationId = 5)
        assertEquals(5, AskLog(File(tmp.root, AskLog.FILE_NAME)).all().size)

        val usable = AskLog.usable(log.all(), tainted = setOf(2L), now = 4 * day)
        assertEquals(listOf("오늘 날씨 알려줘" to 1 * day, "오늘 날씨 알려줘" to 3 * day), usable.map { it.text to it.at })
        // Conversation 2 read third-party text since, so only two remain: no routine suggestion.
        val now = java.time.Instant.ofEpochMilli(4 * day).atZone(seoul)
        assertTrue(ProactiveEngine.plan(now, NudgeSources(asks = usable), NudgeSettings(), emptyList()).none { it.kind == NudgeKind.ROUTINE })
        log.clear()
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun parcelsApartWithoutTrackingNumbers() {
        fun note(key: String, text: String, at: Long) = StoredNotification(key, "com.coupang.mobile", "쿠팡", "쿠팡", text, at)
        val shoes = "[CJ대한통운] 운동화 상품이 배송 출발했습니다"
        val book = "[CJ대한통운] 책 상품이 배송 출발했습니다"
        val parcels = NudgeReaders.parcels(listOf(note("k", shoes, 1_000), note("k", book, 2_000)), emptyList())
        assertEquals(2, parcels.map { it.key }.distinct().size)
        // The same text again is the same parcel.
        assertEquals(parcels[0].key, NudgeReaders.parcels(listOf(note("k2", shoes, 9_000)), emptyList()).single().key)
    }
}
