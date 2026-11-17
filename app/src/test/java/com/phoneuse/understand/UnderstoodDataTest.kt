package com.phoneuse.understand

import com.phoneuse.phone.NotificationSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UnderstoodDataTest {
    private val now = 1_794_877_200_000L // 2026-11-17 10:00 Seoul
    private val day = 24 * 60 * 60_000L
    private val sql = JdbcSqlPort.fresh()
    private val data = UnderstoodData(sql)

    private fun money(kind: String, amount: Long, at: Long, cancelled: Long = 0, merchant: String? = "가게", currency: String = "KRW") =
        sql.insert(
            "INSERT INTO money_events (kind, amount, currency, merchant, first_at, last_at, source_count, cancelled_amount) VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
            listOf(kind, amount, currency, merchant, at, at, cancelled),
        )

    @Test fun cancelledPaymentNetsOut() {
        money("spend", 4500, now - day, cancelled = 1500)
        money("spend", 3000, now - day, cancelled = 3000)
        assertEquals(listOf(3000L), data.payments(now - 30 * day).map { it.amountMinor })
    }

    @Test fun refundWithoutOriginalIsSeparate() {
        money("refund", 12000, now - day)
        assertEquals(emptyList<Any>(), data.payments(0))
        assertEquals(listOf(12000L), data.refunds(0).map { it.amountMinor })
    }

    @Test fun ownTransferRewardIncomeAndBillsAreNotPayments() {
        for (k in listOf("own_transfer", "reward", "income", "bill_payment")) money(k, 1000, now - day)
        money("spend", 4500, now - day, merchant = "스타벅스")
        val p = data.payments(0).single()
        assertEquals("스타벅스", p.merchant)
        assertEquals("KRW", p.currency)
    }

    @Test fun paymentsSinceAreOldestFirst() {
        money("spend", 1, now - 40 * day)
        money("spend", 2, now - 2 * day)
        money("spend", 3, now - day)
        assertEquals(listOf(2L, 3L), data.payments(now - 30 * day).map { it.amountMinor })
        assertEquals(now - 40 * day, data.coverageStart())
    }

    @Test fun parcelsCarryTheirStage() {
        sql.insert(
            "INSERT INTO parcels (carrier, tracking, item_hint, stage, first_at, updated_at, delivered_at) VALUES ('CJ대한통운', '123', '운동화', 'delivered', ?, ?, ?)",
            listOf(now - day, now - 60_000, now - 60_000),
        )
        val p = data.parcels(now).single()
        assertEquals(ParcelStage.DELIVERED, p.stage)
        assertEquals(now - 60_000, p.deliveredAt)
        assertEquals("운동화", p.itemHint)
    }

    @Test fun factsByKeyAndPendingCount() {
        sql.exec(NotificationSql.UPSERT, listOf("a", "p", "앱", "t", "x", now, 0))
        sql.exec(
            "INSERT INTO notification_facts (key, posted_at, status, ready_at, kind, sender, needs_reply, summary, amount, currency) VALUES ('a', ?, 'done', 0, 'spend', 'business', 0, '스타벅스 4,500원', 4500, 'KRW')",
            listOf(now),
        )
        sql.exec("INSERT INTO notification_facts (key, posted_at, status, ready_at) VALUES ('b', ?, 'pending', 0)", listOf(now))
        val f = data.facts(listOf("a", "b", "c"))
        assertEquals(setOf("a"), f.keys)
        assertEquals(NoticeKind.SPEND, f.getValue("a").j.kind)
        assertEquals(4500L, f.getValue("a").j.amountMinor)
        assertEquals(1, data.pendingCount())
    }

    @Test fun noHistoryHasNoCoverageStart() {
        assertNull(data.coverageStart())
    }

    @Test fun parcelsTodayAreOutForDeliveryOrDeliveredToday() {
        val zone = ZoneId.of("Asia/Seoul")
        val today = Instant.ofEpochMilli(now).atZone(zone)
        fun p(id: Long, stage: ParcelStage, at: Long, delivered: Long? = null) = ParcelRecord(id, "CJ", null, null, stage, at, delivered)
        val list = listOf(
            p(1, ParcelStage.OUT_FOR_DELIVERY, now - 60_000),
            p(2, ParcelStage.DELIVERED, now - 60_000, now - 60_000),
            p(3, ParcelStage.DELIVERED, now - day, now - day), // yesterday, already picked up
            p(4, ParcelStage.IN_TRANSIT, now - 60_000),
            p(5, ParcelStage.OUT_FOR_DELIVERY, now - 2 * day), // stale
        )
        assertEquals(listOf(1L, 2L), UnderstoodData.parcelsToday(list, today).map { it.id })
    }
}
