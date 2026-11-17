package com.phoneuse.understand

import com.phoneuse.phone.NotificationSql
import com.phoneuse.phone.StoredNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [EventMerger.plan] applied with [UnderstandingStore.apply] on real SQLite. */
class EventMergerTest {
    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L
    private val sql = JdbcSqlPort.fresh()
    private val store = UnderstandingStore(sql) { t0 }

    private fun n(key: String, pkg: String, title: String, text: String, at: Long): StoredNotification {
        sql.exec(NotificationSql.UPSERT, listOf(key, pkg, "앱", title, text, at, 0))
        sql.exec("INSERT INTO notification_facts (key, posted_at, status, ready_at) VALUES (?, ?, 'pending', 0)", listOf(key, at))
        return StoredNotification(key, pkg, "앱", title, text, at)
    }

    private fun spend(i: Int, amount: Long, merchant: String?, instrument: String?, sameAs: Ref? = null) =
        NoticeJudgment(i, NoticeKind.SPEND, amount, "KRW", merchant, instrument, sameAs, null, null, Sender.BUSINESS, false, "")

    private fun parcel(i: Int, stage: ParcelStage, tracking: String?, sameAs: Ref? = null) =
        NoticeJudgment(i, NoticeKind.PARCEL, null, null, null, null, sameAs, null, ParcelInfo("CJ대한통운", tracking, "운동화", stage), Sender.BUSINESS, false, "")

    private fun run(batch: List<StoredNotification>, js: List<NoticeJudgment>, now: Long) =
        store.apply(EventMerger.plan(batch, js.associateBy { it.index }, store.recentEvents(now)), "light", now)

    private fun longs(q: String) = sql.query(q) { it.long(0) }
    private fun strings(q: String) = sql.query(q) { it.string(0) }

    @Test fun oneStarbucksPurchaseFourNoticesIsOneEvent() {
        val batch = listOf(
            n("sms", "com.samsung.android.messaging", "15888900", "삼성카드 승인 4,500원 일시불 11/17 10:00 스타벅스강남", t0),
            n("card", "kr.co.samsungcard.mpocket", "삼성카드", "승인 4,500원 스타벅스 강남점", t0 + 20_000),
            n("pay", "viva.republica.toss", "토스페이", "결제 완료 | 스타벅스 4,500원", t0 + 40_000),
            n("bank", "com.kbstar.kbbank", "KB스타뱅킹", "출금 4,500원 토스페이", t0 + 60_000),
        )
        run(
            batch,
            listOf(
                spend(0, 4500, "스타벅스", "삼성카드"),
                spend(1, 4500, "스타벅스", "삼성카드", Ref.Batch(0)),
                spend(2, 4500, "스타벅스", "토스페이", Ref.Batch(0)),
                spend(3, 4500, "스타벅스", "KB 계좌", Ref.Batch(2)),
            ),
            t0 + 2 * minute,
        )
        assertEquals(listOf(4500L), longs("SELECT amount FROM money_events"))
        assertEquals(listOf(4L), longs("SELECT source_count FROM money_events"))
        assertEquals(listOf("스타벅스"), strings("SELECT merchant FROM money_events"))
        assertEquals(4L, longs("SELECT COUNT(DISTINCT money_event) FROM notification_facts WHERE status = 'done'").single()!! * 4)
        assertEquals(listOf("done", "done", "done", "done"), strings("SELECT status FROM notification_facts"))
    }

    @Test fun laterNoticeJoinsAStoredEvent() {
        run(listOf(n("sms", "sms", "삼성카드", "승인 4,500원 스타벅스", t0)), listOf(spend(0, 4500, "스타벅스", "삼성카드")), t0)
        val id = longs("SELECT id FROM money_events").single()!!
        run(listOf(n("bank", "bank", "KB", "출금 4,500원", t0 + 5 * minute)), listOf(spend(0, 4500, null, "KB 계좌", Ref.Event(id))), t0 + 6 * minute)
        assertEquals(listOf(2L), longs("SELECT source_count FROM money_events"))
    }

    @Test fun sameAsWithDifferentAmountStartsNewEvent() {
        run(listOf(n("a", "p", "t", "승인 4,500원", t0)), listOf(spend(0, 4500, "스타벅스", null)), t0)
        val id = longs("SELECT id FROM money_events").single()!!
        run(listOf(n("b", "p", "t", "승인 5,000원", t0 + minute)), listOf(spend(0, 5000, "스타벅스", null, Ref.Event(id))), t0 + minute)
        assertEquals(listOf(4500L, 5000L), longs("SELECT amount FROM money_events ORDER BY id"))
    }

    @Test fun sameAsOlderThan30MinutesStartsNewEvent() {
        run(listOf(n("a", "p", "t", "승인 4,500원", t0)), listOf(spend(0, 4500, "스타벅스", null)), t0)
        val id = longs("SELECT id FROM money_events").single()!!
        run(listOf(n("b", "p", "t", "승인 4,500원", t0 + 31 * minute)), listOf(spend(0, 4500, "스타벅스", null, Ref.Event(id))), t0 + 31 * minute)
        assertEquals(2, longs("SELECT id FROM money_events").size)
    }

    @Test fun cancellationWithin30DaysReducesOriginal() {
        run(listOf(n("a", "p", "t", "승인 4,500원", t0)), listOf(spend(0, 4500, "스타벅스", null)), t0)
        val id = longs("SELECT id FROM money_events").single()!!
        val later = t0 + 3 * 24 * 60 * minute
        val refund = NoticeJudgment(0, NoticeKind.REFUND, 4500, "KRW", "스타벅스", null, null, Ref.Event(id), null, Sender.BUSINESS, false, "")
        run(listOf(n("c", "p", "t", "승인취소 4,500원", later)), listOf(refund), later)
        assertEquals(listOf(4500L), longs("SELECT cancelled_amount FROM money_events WHERE id = $id"))
        assertEquals(1, longs("SELECT id FROM money_events").size)
        assertEquals(listOf(id), longs("SELECT money_event FROM notification_facts WHERE key = 'c'"))
    }

    @Test fun refundInTheSameBatchCancelsItsPayment() {
        // A fuel pre-authorisation and its cancellation arrive within seconds.
        val batch = listOf(
            n("ok", "p", "현대카드", "승인 100,000원 GS칼텍스", t0),
            n("cancel", "p", "현대카드", "승인취소 100,000원 GS칼텍스", t0 + 10_000),
        )
        val refund = NoticeJudgment(1, NoticeKind.REFUND, 100_000, "KRW", "GS칼텍스", null, null, Ref.Batch(0), null, Sender.BUSINESS, false, "")
        run(batch, listOf(spend(0, 100_000, "GS칼텍스", "현대카드"), refund), t0 + minute)
        assertEquals(listOf(100_000L), longs("SELECT cancelled_amount FROM money_events"))
        assertEquals(1, longs("SELECT id FROM money_events").size)
    }

    @Test fun unmatchedRefundStandsAlone() {
        val refund = NoticeJudgment(0, NoticeKind.REFUND, 12000, "KRW", "쿠팡", null, null, Ref.Event(999), null, Sender.BUSINESS, false, "")
        run(listOf(n("r", "p", "t", "환불 12,000원", t0)), listOf(refund), t0)
        assertEquals(listOf("refund"), strings("SELECT kind FROM money_events"))
        assertNull(longs("SELECT refund_of FROM money_events").single())
    }

    @Test fun refundLargerThanTheOriginalDoesNotCancelIt() {
        run(listOf(n("a", "p", "t", "승인 4,500원", t0)), listOf(spend(0, 4500, "스타벅스", null)), t0)
        val id = longs("SELECT id FROM money_events").single()!!
        val refund = NoticeJudgment(0, NoticeKind.REFUND, 9000, "KRW", null, null, null, Ref.Event(id), null, Sender.BUSINESS, false, "")
        run(listOf(n("c", "p", "t", "환불 9,000원", t0 + minute)), listOf(refund), t0 + minute)
        assertEquals(listOf(0L), longs("SELECT cancelled_amount FROM money_events WHERE id = $id"))
        assertEquals(2, longs("SELECT id FROM money_events").size)
    }

    @Test fun lateOutForDeliveryAfterDeliveredKeepsDelivered() {
        run(listOf(n("a", "p", "t", "배송 완료", t0)), listOf(parcel(0, ParcelStage.DELIVERED, "123456789012")), t0)
        run(listOf(n("b", "p", "t", "배송 출발", t0 + minute)), listOf(parcel(0, ParcelStage.OUT_FOR_DELIVERY, "123456789012")), t0 + minute)
        assertEquals(listOf("delivered"), strings("SELECT stage FROM parcels"))
        assertEquals(listOf(t0), longs("SELECT delivered_at FROM parcels"))
    }

    @Test fun stagesMoveForwardOnOneParcel() {
        run(listOf(n("a", "p", "t", "접수", t0)), listOf(parcel(0, ParcelStage.RECEIVED, "123456789012")), t0)
        run(listOf(n("b", "p", "t", "배송 출발", t0 + minute)), listOf(parcel(0, ParcelStage.OUT_FOR_DELIVERY, "123456789012")), t0 + minute)
        assertEquals(listOf("out_for_delivery"), strings("SELECT stage FROM parcels"))
        assertEquals(1, longs("SELECT id FROM parcels").size)
    }

    @Test fun sameTrackingJoinsWithoutSameAs() {
        run(
            listOf(n("sms", "p", "t", "배송 출발 123456789012", t0), n("push", "q", "t", "오늘 도착 예정", t0 + 10_000)),
            listOf(parcel(0, ParcelStage.OUT_FOR_DELIVERY, "123456789012"), parcel(1, ParcelStage.OUT_FOR_DELIVERY, "123456789012")),
            t0 + minute,
        )
        assertEquals(1, longs("SELECT id FROM parcels").size)
    }

    @Test fun differentTrackingNeverJoins() {
        run(listOf(n("a", "p", "t", "배송 출발", t0)), listOf(parcel(0, ParcelStage.OUT_FOR_DELIVERY, "111111111111")), t0)
        val id = longs("SELECT id FROM parcels").single()!!
        run(
            listOf(n("b", "p", "t", "배송 출발", t0 + minute)),
            listOf(parcel(0, ParcelStage.OUT_FOR_DELIVERY, "222222222222", Ref.Event(UnderstandingStore.PARCEL_REF_BASE + id))),
            t0 + minute,
        )
        assertEquals(2, longs("SELECT id FROM parcels").size)
    }

    @Test fun sameAsParcelWithoutTrackingJoins() {
        run(listOf(n("a", "p", "t", "쿠팡 배송 출발", t0)), listOf(parcel(0, ParcelStage.OUT_FOR_DELIVERY, null)), t0)
        val id = longs("SELECT id FROM parcels").single()!!
        run(
            listOf(n("b", "p", "t", "쿠팡 배송 완료", t0 + 60 * minute)),
            listOf(parcel(0, ParcelStage.DELIVERED, null, Ref.Event(UnderstandingStore.PARCEL_REF_BASE + id))),
            t0 + 60 * minute,
        )
        assertEquals(listOf("delivered"), strings("SELECT stage FROM parcels"))
    }

    @Test fun foodDeliveryMakesNoParcel() {
        val j = NoticeJudgment(0, NoticeKind.FOOD_DELIVERY, null, null, null, null, null, null, ParcelInfo("쿠팡이츠", null, null, ParcelStage.DELIVERED), Sender.BUSINESS, false, "")
        run(listOf(n("e", "com.coupang.eats", "쿠팡이츠", "배달 완료", t0)), listOf(j), t0)
        assertEquals(0, longs("SELECT id FROM parcels").size)
        assertEquals(listOf("food_delivery"), strings("SELECT kind FROM notification_facts"))
    }

    @Test fun messageFactKeepsSenderAndReply() {
        val j = NoticeJudgment(0, NoticeKind.MESSAGE, null, null, null, null, null, null, null, Sender.PERSON, true, "엄마: 저녁 먹고 오니?")
        run(listOf(n("m", "com.kakao.talk", "엄마", "저녁 먹고 오니?", t0)), listOf(j), t0)
        assertEquals(listOf("person"), strings("SELECT sender FROM notification_facts"))
        assertEquals(listOf(1L), longs("SELECT needs_reply FROM notification_facts"))
        assertEquals(listOf("light"), strings("SELECT model FROM notification_facts"))
    }

    @Test fun factChangedWhileAnalysingIsNotOverwritten() {
        val batch = listOf(n("room", "com.kakao.talk", "민수", "밥 먹었어?", t0))
        val plan = EventMerger.plan(batch, mapOf(0 to NoticeJudgment(0, NoticeKind.MESSAGE, null, null, null, null, null, null, null, Sender.PERSON, true, "")), emptyList())
        // A new message arrives in the same room before the answer is stored.
        sql.exec("UPDATE notification_facts SET ready_at = ? WHERE key = 'room'", listOf(t0 + 2 * minute))
        store.apply(plan, "light", t0 + 30_000)
        assertEquals(listOf("pending"), strings("SELECT status FROM notification_facts"))
    }
}
