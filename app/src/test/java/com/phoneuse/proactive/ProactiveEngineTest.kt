package com.phoneuse.proactive

import com.phoneuse.memory.Confidence
import com.phoneuse.memory.Memory
import com.phoneuse.memory.MemoryKind
import com.phoneuse.phone.StoredNotification
import com.phoneuse.routine.UpcomingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ProactiveEngineTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val settings = NudgeSettings()

    private fun at(day: Int, h: Int, m: Int = 0, month: Int = 11) = ZonedDateTime.of(2026, month, day, h, m, 0, 0, seoul)

    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    private fun kakao(postedAt: ZonedDateTime, key: String = "k1", group: Boolean = false) =
        StoredNotification(key, "com.kakao.talk", "카카오톡", "김민수", "내일 몇 시에 볼까?", ms(postedAt), group)

    private fun sent(n: Nudge, at: ZonedDateTime, outcome: NudgeOutcome = NudgeOutcome.PENDING) =
        SentNudge(n, ms(at), outcome, if (outcome == NudgeOutcome.PENDING) null else ms(at))

    private fun filler(i: Int, kind: NudgeKind = NudgeKind.DELIVERY) =
        Nudge(kind, "t$i", "b", emptyList(), 0, Long.MAX_VALUE, "filler:$i")

    /** A payment at 3x+ the usual for that merchant, 10 minutes before [now]. */
    private fun unusual(now: ZonedDateTime) = NudgeSources(
        payments = listOf(
            PaymentRecord("배달의민족", 20_000, ms(now.minusDays(10))),
            PaymentRecord("배달의민족", 22_000, ms(now.minusDays(3))),
            PaymentRecord("배달의민족", 90_000, ms(now.minusMinutes(10))),
        ),
    )

    @Test
    fun respectsQuietHours() {
        val night = at(2, 23, 30)
        assertTrue(ProactiveEngine.plan(night, unusual(night), settings, emptyList()).isEmpty())
        // The same payment is still worth telling once the quiet hours end (it has not expired).
        val morning = at(3, 7, 5)
        val later = ProactiveEngine.plan(morning, unusual(night), settings, emptyList())
        assertEquals(listOf(NudgeKind.UNUSUAL_PAYMENT), later.map { it.kind })
        // Quiet hours set by the user, not wrapping midnight.
        val lunchQuiet = settings.copy(quietStart = java.time.LocalTime.of(12, 0), quietEnd = java.time.LocalTime.of(13, 0))
        assertTrue(ProactiveEngine.plan(at(2, 12, 30), unusual(at(2, 12, 30)), lunchQuiet, emptyList()).isEmpty())
    }

    @Test
    fun capsSixPerDay() {
        val now = at(2, 14)
        val five = (1..5).map { sent(filler(it), now.minusHours(1)) }
        val sources = unusual(now).copy(notifications = listOf(kakao(now.minusHours(4), "a"), kakao(now.minusHours(5), "b")))
        assertEquals(1, ProactiveEngine.plan(now, sources, settings, five).size)
        val six = five + sent(filler(6), now.minusMinutes(30))
        assertTrue(ProactiveEngine.plan(now, sources, settings, six).isEmpty())
        // Yesterday's nudges do not count against today.
        val yesterday = (1..6).map { sent(filler(it), now.minusDays(1)) }
        assertEquals(3, ProactiveEngine.plan(now, sources, settings, yesterday).size)
        // The time-critical one wins the last slot.
        assertEquals(NudgeKind.UNUSUAL_PAYMENT, ProactiveEngine.plan(now, sources, settings, five).single().kind)
    }

    @Test
    fun dedupes() {
        val now = at(2, 14)
        val sources = unusual(now)
        val first = ProactiveEngine.plan(now, sources, settings, emptyList())
        assertEquals(1, first.size)
        val log = first.map { sent(it, now) }
        assertTrue(ProactiveEngine.plan(now.plusMinutes(15), sources, settings, log).isEmpty())
        // The same parcel text seen twice (notification and SMS) is one nudge.
        val parcel = ParcelRecord("n1", "CJ대한통운", "123456789012", "배송 출발", ms(now.minusMinutes(5)))
        val twice = NudgeSources(parcels = listOf(parcel, parcel.copy(key = "sms:1")))
        assertEquals(1, ProactiveEngine.plan(now, twice, settings, emptyList()).count { it.kind == NudgeKind.DELIVERY })
    }

    @Test
    fun backsOffAfterThreeIgnores() {
        val now = at(10, 14)
        val sources = NudgeSources(notifications = listOf(kakao(now.minusHours(4))))
        fun reply(i: Int) = filler(i, NudgeKind.REPLY)
        val ignored = (1..3).map { sent(reply(it), now.minusDays(4 - it.toLong()), NudgeOutcome.IGNORED) }
        assertTrue(ProactiveEngine.plan(now, sources, settings, ignored).none { it.kind == NudgeKind.REPLY })
        // Other kinds are not affected.
        assertTrue(ProactiveEngine.plan(now, sources.copy(payments = unusual(now).payments), settings, ignored).any { it.kind == NudgeKind.UNUSUAL_PAYMENT })
        // An opened one in between resets the count.
        val mixed = listOf(ignored[0], sent(reply(9), now.minusDays(3).plusHours(1), NudgeOutcome.OPENED), ignored[1], ignored[2])
        assertTrue(ProactiveEngine.plan(now, sources, settings, mixed).any { it.kind == NudgeKind.REPLY })
        // A week after the third ignore the kind comes back.
        val weekLater = now.plusDays(7)
        val fresh = NudgeSources(notifications = listOf(kakao(weekLater.minusHours(4), "k2")))
        assertTrue(ProactiveEngine.plan(weekLater, fresh, settings, ignored).any { it.kind == NudgeKind.REPLY })
    }

    @Test
    fun unusualPaymentIsImmediate() {
        val now = at(2, 14, 3)
        val planned = ProactiveEngine.plan(now, unusual(now), settings, emptyList())
        val n = planned.single()
        assertEquals(NudgeKind.UNUSUAL_PAYMENT, n.kind)
        assertEquals("배달의민족 ₩90,000 · 평소 ₩21,000", n.body)
        // An ordinary payment waits for the 21:00 total.
        val usual = NudgeSources(payments = unusual(now).payments.dropLast(1) + PaymentRecord("배달의민족", 25_000, ms(now.minusMinutes(10))))
        assertTrue(ProactiveEngine.plan(now, usual, settings, emptyList()).isEmpty())
        val evening = ProactiveEngine.plan(at(2, 21, 1), usual, settings, emptyList()).single()
        assertEquals("오늘 결제 1건 · ₩25,000", evening.title)
        // A first payment somewhere has no "usual" to compare with.
        val first = NudgeSources(payments = listOf(PaymentRecord("항공사", 900_000, ms(now.minusMinutes(1)))))
        assertTrue(ProactiveEngine.plan(now, first, settings, emptyList()).isEmpty())
        // Switched off: nothing.
        assertTrue(ProactiveEngine.plan(now, unusual(now), settings.copy(enabled = NudgeKind.entries.toSet() - NudgeKind.UNUSUAL_PAYMENT), emptyList()).isEmpty())
    }

    @Test
    fun repeatedAskSuggestsRoutine() {
        val now = at(5, 9)
        val asks = listOf(
            AskRecord("오늘 날씨 알려줘", ms(at(2, 8, 10))),
            AskRecord("오늘 날씨 알려줘!", ms(at(3, 7, 50))),
            AskRecord("오늘  날씨 알려 줘. 우산 필요해?", ms(at(4, 8, 5))),
            // Same words in the evening are another kind of ask.
            AskRecord("오늘 날씨 알려줘", ms(at(4, 19, 0))),
        )
        val n = ProactiveEngine.plan(now, NudgeSources(asks = asks), settings, emptyList()).single { it.kind == NudgeKind.ROUTINE }
        assertEquals("루틴 만들어줘: 매일 8시에 오늘  날씨 알려 줘", n.actions.single().prompt)
        assertTrue(n.body.startsWith("“오늘  날씨 알려 줘” · 이번 주 3번"))
        // Twice is not a habit yet; older than a week does not count.
        assertTrue(ProactiveEngine.plan(now, NudgeSources(asks = asks.drop(1)), settings, emptyList()).none { it.kind == NudgeKind.ROUTINE })
        assertTrue(ProactiveEngine.plan(at(12, 9), NudgeSources(asks = asks), settings, emptyList()).none { it.kind == NudgeKind.ROUTINE })
    }

    @Test
    fun anniversaryThreeDaysBefore() {
        fun memory(confidence: Confidence = Confidence.SURE, date: String? = "11월 20일") =
            Memory(7, "엄마 생신은 11월 20일", MemoryKind.PERSON, confidence, 0, 0, date = date)
        val sources = NudgeSources(memories = listOf(memory()))
        // Without the morning briefing, which is also due at these times.
        val settings = settings.copy(enabled = NudgeKind.entries.toSet() - NudgeKind.BRIEFING)
        val before = ProactiveEngine.plan(at(17, 9, 30), sources, settings, emptyList()).single()
        assertEquals(NudgeKind.ANNIVERSARY, before.kind)
        assertEquals("기념일이 3일 남았어요 · 11월 20일 금요일", before.title)
        assertEquals(listOf("선물 고르기", "나중에"), before.actions.map { it.label })
        // Not yet at 08:00, and nothing on other days.
        assertTrue(ProactiveEngine.plan(at(17, 8), sources, settings, emptyList()).none { it.kind == NudgeKind.ANNIVERSARY })
        assertTrue(ProactiveEngine.plan(at(16, 10), sources, settings, emptyList()).none { it.kind == NudgeKind.ANNIVERSARY })
        // The day itself, in the morning.
        val day = ProactiveEngine.plan(at(20, 8), sources, settings, emptyList()).single { it.kind == NudgeKind.ANNIVERSARY }
        assertEquals("축하 문자 초안", day.actions.first().label)
        // A guess (maybe from someone else's text) is never used; the date may also be only in the text.
        assertTrue(ProactiveEngine.plan(at(17, 9, 30), NudgeSources(memories = listOf(memory(Confidence.GUESS))), settings, emptyList()).isEmpty())
        assertEquals(1, ProactiveEngine.plan(at(17, 9, 30), NudgeSources(memories = listOf(memory(date = null))), settings, emptyList()).size)
    }

    @Test
    fun unansweredMessengerAfterThreeHours() {
        val posted = at(2, 10)
        val sources = NudgeSources(notifications = listOf(kakao(posted)))
        assertTrue(ProactiveEngine.plan(at(2, 12, 59), sources, settings, emptyList()).isEmpty())
        val n = ProactiveEngine.plan(at(2, 13, 1), sources, settings, emptyList()).single()
        assertEquals(NudgeKind.REPLY, n.kind)
        assertEquals("김민수님에게 아직 답장 안 했어요", n.title)
        // The button names the app and the time only, never the message.
        val prompt = n.actions.single().prompt!!
        assertEquals("카카오톡에서 10:00에 온 1:1 메시지에 보낼 답장 초안 써줘", prompt)
        assertFalse(prompt.contains("몇 시"))
        // Read or answered (gone from the shade): nothing to remind.
        val read = kakao(posted).copy(removedAt = ms(at(2, 11)))
        assertTrue(ProactiveEngine.plan(at(2, 13, 1), NudgeSources(notifications = listOf(read)), settings, emptyList()).isEmpty())
        // Group chats need no personal reply.
        assertTrue(ProactiveEngine.plan(at(2, 13, 1), NudgeSources(notifications = listOf(kakao(posted, group = true))), settings, emptyList()).isEmpty())
    }

    @Test
    fun leaveNudgeTenMinutesBeforeDeparture() {
        val start = at(2, 15)
        val event = UpcomingEvent("치과", ms(start), "강남역 치과")
        val travel = mapOf(NudgeSources.leaveKey(event) to TravelEstimate(27, 37.49, 127.02, 5000.0))
        val sources = NudgeSources(events = listOf(event), travel = travel)
        // Leave at 14:28 (27 min + 5 min buffer), told at 14:18.
        assertTrue(ProactiveEngine.plan(at(2, 14, 17), sources, settings, emptyList()).isEmpty())
        val n = ProactiveEngine.plan(at(2, 14, 18), sources, settings, emptyList()).single()
        assertEquals("14:28에 나가면 치과 14:55 도착", n.title)
        assertEquals(listOf("지도 앱", "10분 늦는다고 보내기"), n.actions.map { it.label })
        val leaveOnly = settings.copy(enabled = setOf(NudgeKind.LEAVE))
        // The leave time gets an exact alarm once it is near; further off, the plain inexact poll.
        assertEquals(ProactiveEngine.NextCheck(ms(at(2, 14, 18)), exact = true), ProactiveEngine.nextCheck(at(2, 14, 10), sources, leaveOnly, emptyList()))
        assertEquals(ProactiveEngine.NextCheck(ms(at(2, 13, 15)), exact = false), ProactiveEngine.nextCheck(at(2, 13, 0), sources, leaveOnly, emptyList()))
        // A resting kind does not wake the phone.
        val ignored = (1..3).map { i ->
            SentNudge(n.copy(dedupeKey = "leave:old$i"), ms(at(1, 9 + i)), NudgeOutcome.IGNORED, ms(at(1, 9 + i)))
        }
        assertEquals(false, ProactiveEngine.nextCheck(at(2, 14, 10), sources, leaveOnly, ignored).exact)
        assertEquals(listOf(event), ProactiveEngine.needsTravel(ms(at(2, 13, 30)), listOf(event)))
        assertTrue(ProactiveEngine.needsTravel(ms(at(2, 12, 30)), listOf(event)).isEmpty())
    }

    @Test
    fun todoOnItsDueMorning() {
        val said = at(1, 20)
        val todo = Memory(3, "보고서 제출", MemoryKind.TODO, Confidence.SURE, ms(said), ms(said), due = "내일")
        val sources = NudgeSources(memories = listOf(todo))
        val n = ProactiveEngine.plan(at(2, 8), sources, settings, emptyList()).single { it.kind == NudgeKind.TODO }
        assertEquals("보고서 제출", n.body)
        assertTrue(ProactiveEngine.plan(at(3, 8), sources, settings, emptyList()).none { it.kind == NudgeKind.TODO })
    }

    @Test fun aCheckThatFailsKeepsTheLastExactDueTime() {
        // A timed-out check must not turn a known leave alarm into the 15-minute inexact poll.
        val now = 1_000_000L
        val leave = ProactiveEngine.NextCheck(now + 5 * 60_000L, exact = true)
        assertEquals(leave, ProactiveEngine.fallbackCheck(leave, now))
        // Already past (the check that failed was meant to post it): try again exactly in a minute.
        val missed = ProactiveEngine.NextCheck(now - 1, exact = true)
        assertEquals(ProactiveEngine.NextCheck(now + ProactiveEngine.RETRY_EXACT_MS, exact = true), ProactiveEngine.fallbackCheck(missed, now))
        // Failing again backs off 2, 4, 8 minutes, then returns to the ordinary poll.
        assertEquals(listOf(2L, 4L, 8L), (1..3).map { (ProactiveEngine.fallbackCheck(missed, now, it).at - now) / 60_000L })
        assertTrue((4..20).all { ProactiveEngine.fallbackCheck(missed, now, it) == ProactiveEngine.NextCheck(now + ProactiveEngine.CHECK_EVERY_MS, exact = false) })
        // Nothing known, or only the ordinary poll: the inexact 15 minutes.
        val poll = ProactiveEngine.NextCheck(now + ProactiveEngine.CHECK_EVERY_MS, exact = false)
        assertEquals(poll, ProactiveEngine.fallbackCheck(null, now))
        assertEquals(poll, ProactiveEngine.fallbackCheck(ProactiveEngine.NextCheck(now - 1, exact = false), now))
        // A later inexact one is never pushed past the poll.
        assertEquals(poll, ProactiveEngine.fallbackCheck(ProactiveEngine.NextCheck(now + 3 * ProactiveEngine.CHECK_EVERY_MS, exact = false), now))
    }
}
