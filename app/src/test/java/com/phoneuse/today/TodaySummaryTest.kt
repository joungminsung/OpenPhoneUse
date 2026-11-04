package com.phoneuse.today

import com.phoneuse.phone.StoredNotification
import com.phoneuse.proactive.NudgeReaders
import com.phoneuse.proactive.PaymentRecord
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
        val tiles = TodaySummary.build(now, TodaySources(notifications, NudgeReaders.parcels(notifications, emptyList()), events = events))
        assertEquals(TodayTile.Parcels(1, "CJ대한통운 · 배송 출발", "배송 출발"), tiles[0])
        // The read message is no longer unread and needs no reply.
        assertEquals(TodayTile.Notifications(unread = 2, reply = 1), tiles[1])
        assertEquals(listOf("20:00", "21:00"), (tiles[2] as TodayTile.Events).items.map { it.time })
    }

    @Test
    fun spendingWithoutTextsIsTheLastWeekFromNotifications() {
        val payments = listOf(
            PaymentRecord("배달의민족", 20_000, at(11, 13, 19)),
            PaymentRecord("배달의민족", 15_000, at(11, 5, 19)),
            PaymentRecord("스타벅스", 9_000, at(11, 14, 9)),
        )
        val tile = TodaySummary.build(now, TodaySources(payments = payments, monthCovered = false)).single() as TodayTile.Spending
        assertEquals("최근 7일 배달 (알림 기준)", tile.label)
        assertEquals(20_000L, tile.total)
        assertEquals(null, tile.diff)
    }

    @Test
    fun spendingComparesWithLastMonth() {
        val payments = listOf(
            PaymentRecord("배달의민족", 120_000, at(11, 3)),
            PaymentRecord("쿠팡이츠", 66_400, at(11, 14)),
            PaymentRecord("스타벅스", 9_000, at(11, 14)),
            // Last month up to the same day counts; later in the month does not.
            PaymentRecord("배달의민족", 154_300, at(10, 10)),
            PaymentRecord("배달의민족", 50_000, at(10, 25)),
        )
        val tile = TodaySummary.build(now, TodaySources(payments = payments)).single() as TodayTile.Spending
        assertEquals("이번 달 배달", tile.label)
        assertEquals(186_400L, tile.total)
        assertEquals(32_100L, tile.diff)
        assertEquals("+₩32,100", TodaySummary.signedWon(tile.diff!!))
        // Without delivery orders it is all spending; without last month's history there is no comparison.
        val plain = TodaySummary.build(now, TodaySources(payments = listOf(PaymentRecord("스타벅스", 9_000, at(11, 14))))).single() as TodayTile.Spending
        assertEquals("이번 달 지출", plain.label)
        assertNull(plain.diff)
    }
}
