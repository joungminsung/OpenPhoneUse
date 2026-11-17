package com.phoneuse.today

import com.phoneuse.phone.StoredNotification
import com.phoneuse.understand.NoticeFact
import com.phoneuse.understand.NoticeJudgment
import com.phoneuse.understand.NoticeKind
import com.phoneuse.understand.ParcelRecord
import com.phoneuse.understand.Sender
import com.phoneuse.understand.ParcelStage
import com.phoneuse.understand.PaymentRecord
import com.phoneuse.understand.RefundRecord
import com.phoneuse.routine.UpcomingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TodaySummaryTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    // Sunday 2026-11-15 19:40.
    private val now = ZonedDateTime.of(2026, 11, 15, 19, 40, 0, 0, seoul)

    private fun at(month: Int, day: Int, h: Int = 12) = ZonedDateTime.of(2026, month, day, h, 0, 0, 0, seoul).toInstant().toEpochMilli()

    private fun ago(days: Long, h: Int) = now.minusDays(days).withHour(h).toInstant().toEpochMilli()

    @Test
    fun omitsEmptyTiles() {
        assertTrue(TodaySummary.build(now, TodaySources()).isEmpty())
        // Only what has data: an alarm tomorrow morning, nothing else.
        val alarm = TodaySummary.build(now, TodaySources(nextAlarm = ZonedDateTime.of(2026, 11, 16, 7, 0, 0, 0, seoul).toInstant().toEpochMilli()))
        assertEquals(listOf(TodayTile.Alarm("내일 아침", "07:00")), alarm)
        // An alarm more than a day away, yesterday's notifications, a finished event and an all-day one show nothing.
        val stale = TodaySources(
            notifications = listOf(StoredNotification("3", "com.kakao.talk", "카카오톡", "팀장님", "어제 메시지", ago(1, 20))),
            events = listOf(UpcomingEvent("점심", ago(0, 12), "강남"), UpcomingEvent("휴가", ago(0, 0), null, allDay = true)),
            nextAlarm = now.plusDays(2).toInstant().toEpochMilli(),
        )
        assertTrue(TodaySummary.build(now, stale).isEmpty())
    }

    @Test
    fun todaysParcelsNotificationsAndEvents() {
        val notifications = listOf(
            StoredNotification("1", "com.kakao.talk", "카카오톡", "엄마", "저녁 먹고 오니?", ago(0, 19)),
            StoredNotification("2", "com.google.android.apps.messaging", "메시지", "CJ대한통운", "[CJ대한통운] 고객님의 상품이 배송될 예정입니다. 운송장번호 : 123456789012", ago(0, 9)),
            StoredNotification("4", "com.kakao.talk", "카카오톡", "친구", "읽음", ago(0, 10), removedAt = ago(0, 11)),
        )
        val events = listOf(
            UpcomingEvent("저녁 약속", now.withHour(21).withMinute(0).toInstant().toEpochMilli(), "강남역"),
            UpcomingEvent("알림 요약 받기", now.withHour(20).withMinute(0).toInstant().toEpochMilli(), null),
            UpcomingEvent("늦은 일정", now.withHour(23).withMinute(0).toInstant().toEpochMilli(), null),
        )
        val parcels = listOf(ParcelRecord(1, "CJ대한통운", "123456789012", null, ParcelStage.OUT_FOR_DELIVERY, ago(0, 9), null))
        val mom = NoticeFact(NoticeJudgment(0, NoticeKind.MESSAGE, null, null, null, null, null, null, null, Sender.PERSON, true, ""), null, null)
        val tiles = TodaySummary.build(now, TodaySources(notifications, parcels, events = events, facts = mapOf("1" to mom, "4" to mom)))
        assertEquals(TodayTile.Parcels(1, "CJ대한통운 · 배송 출발", ParcelStage.OUT_FOR_DELIVERY), tiles[0])
        // The read message is no longer unread and needs no reply.
        assertEquals(TodayTile.Notifications(unread = 2, reply = 1), tiles[1])
        assertEquals(listOf("20:00", "21:00"), (tiles[2] as TodayTile.Events).items.map { it.time })
    }

    private var nextId = 1L
    private fun pay(amount: Long, at: Long, merchant: String = "가게", currency: String = "KRW") = PaymentRecord(nextId++, merchant, amount, currency, at)

    @Test
    fun spendingComparesWithLastMonth() {
        val payments = listOf(
            pay(120_000, at(11, 3), "배달의민족"),
            pay(66_400, at(11, 14), "쿠팡이츠"),
            pay(9_000, at(11, 14), "스타벅스"),
            // Last month up to the same day counts; later in the month does not.
            pay(154_300, at(10, 10), "배달의민족"),
            pay(50_000, at(10, 25), "배달의민족"),
        )
        val tile = TodaySummary.build(now, TodaySources(payments = payments, coverageStart = at(9, 1))).single() as TodayTile.Spending
        assertEquals("이번 달 지출", tile.label)
        assertEquals(195_400L, tile.total)
        assertEquals("KRW", tile.currency)
        assertEquals(41_100L, tile.diff)
        assertEquals("+₩41,100", TodaySummary.signed(tile.diff!!, tile.currency))
        assertNull(tile.since)
    }

    @Test
    fun spendingShowsCoverageWhenHistoryStartsThisMonth() {
        val tile = TodaySummary.build(now, TodaySources(payments = listOf(pay(9_000, at(11, 14))), coverageStart = at(11, 10))).single() as TodayTile.Spending
        assertEquals(9_000L, tile.total)
        assertNull(tile.diff)
        assertEquals(at(11, 10), tile.since)
    }

    @Test
    fun noDiffWhenLastMonthIsOnlyPartlyKnown() {
        // History starts Oct 10: October 1-9 is unknown, so "vs last month" would look like a jump.
        val payments = listOf(pay(9_000, at(11, 14)), pay(1_000, at(10, 12)))
        val tile = TodaySummary.build(now, TodaySources(payments = payments, coverageStart = at(10, 10))).single() as TodayTile.Spending
        assertNull(tile.diff)
        assertNull(tile.since)
    }

    @Test
    fun spendingWithoutLastMonthHasNoDiff() {
        val tile = TodaySummary.build(now, TodaySources(payments = listOf(pay(9_000, at(11, 14))), coverageStart = at(9, 1))).single() as TodayTile.Spending
        assertNull(tile.diff)
    }

    @Test
    fun refundsAndPendingAreShownApart() {
        val tile = TodaySummary.build(
            now,
            TodaySources(payments = listOf(pay(9_000, at(11, 14))), refunds = listOf(RefundRecord(12_000, "KRW", at(11, 13))), pending = 3, coverageStart = at(9, 1)),
        ).single() as TodayTile.Spending
        assertEquals(9_000L, tile.total)
        assertEquals(12_000L, tile.refunds)
        assertEquals(3, tile.pending)
    }

    @Test
    fun spendingUsesTheMostCommonCurrency() {
        val tile = TodaySummary.build(
            now,
            TodaySources(payments = listOf(pay(1250, at(11, 2), currency = "USD"), pay(9_000, at(11, 3)), pay(4_500, at(11, 4))), coverageStart = at(9, 1)),
        ).single() as TodayTile.Spending
        assertEquals("KRW", tile.currency)
        assertEquals(13_500L, tile.total)
    }

    @Test
    fun parcelCountUsesStages() {
        val parcels = listOf(
            ParcelRecord(1, "CJ대한통운", null, null, ParcelStage.OUT_FOR_DELIVERY, ago(0, 9), null),
            ParcelRecord(2, null, null, "운동화", ParcelStage.DELIVERED, ago(0, 12), ago(0, 12)),
            ParcelRecord(3, "한진택배", null, null, ParcelStage.IN_TRANSIT, ago(0, 13), null),
            ParcelRecord(4, "한진택배", null, null, ParcelStage.DELIVERED, ago(1, 13), ago(1, 13)),
        )
        val tile = TodaySummary.build(now, TodaySources(parcels = parcels)).single() as TodayTile.Parcels
        assertEquals(2, tile.count)
        assertEquals("운동화 · 도착", tile.line)
        assertEquals(ParcelStage.DELIVERED, tile.stage)
    }
}
