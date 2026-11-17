package com.phoneuse.understand

import com.phoneuse.phone.NotificationSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderstandingStoreTest {
    private var now = 1_800_000_000_000L
    private val sql = JdbcSqlPort.fresh()
    private val store = UnderstandingStore(sql) { now }
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun post(key: String, at: Long = now, text: String = "x") {
        sql.exec(NotificationSql.UPSERT, listOf(key, "com.kakao.talk", "카카오톡", "t", text, at, 0))
    }

    private fun status(key: String): String? =
        sql.query("SELECT status FROM notification_facts WHERE key = ?", listOf(key)) { it.string(0) }.firstOrNull()

    @Test fun newNotificationWaits20s() {
        post("a"); store.noteArrival("a", now, changed = false)
        assertTrue(store.pendingBatch(now + 19_000).isEmpty())
        assertEquals(listOf("a"), store.pendingBatch(now + 20_000))
        assertEquals(now + 20_000, store.nextReadyAt())
    }

    @Test fun updatedKeyWaits2MinutesFromLastChange() {
        post("room"); store.noteArrival("room", now, changed = false)
        // Messages keep arriving in the same chat room every minute.
        for (i in 1..3) {
            now += minute
            post("room", text = "message $i")
            store.noteArrival("room", now, changed = true)
        }
        assertTrue(store.pendingBatch(now + 119_000).isEmpty())
        assertEquals(listOf("room"), store.pendingBatch(now + 120_000))
    }

    @Test fun aBurstIsTakenTogetherOnceItsFirstNoticeIsReady() {
        // One purchase: card text, card app, pay app and bank withdrawal a few seconds apart.
        for (i in 0 until 4) { post("k$i", at = now + i * 4_000L); store.noteArrival("k$i", now + i * 4_000L, changed = false); now += 4_000 }
        now -= 16_000
        assertEquals(listOf("k0", "k1", "k2", "k3"), store.pendingBatch(now + 20_000))
    }

    @Test fun aChatStillChangingIsNotPulledIntoABurst() {
        post("a"); store.noteArrival("a", now, changed = false)
        post("room"); store.noteArrival("room", now, changed = false)
        sql.exec("UPDATE notification_facts SET ready_at = ? WHERE key = 'room'", listOf(now + 120_000))
        assertEquals(listOf("a"), store.pendingBatch(now + 20_000))
    }

    @Test fun repostWithSameContentKeepsAnalysedFact() {
        post("a"); store.noteArrival("a", now, changed = false)
        sql.exec("UPDATE notification_facts SET status = 'done' WHERE key = 'a'")
        store.noteArrival("a", now + minute, changed = false)
        assertEquals("done", status("a"))
    }

    @Test fun batchIsOldestFirstAndLimited() {
        for (i in 0 until 25) { post("k$i", at = now + i); store.noteArrival("k$i", now + i, changed = false) }
        val batch = store.pendingBatch(now + day)
        assertEquals(20, batch.size)
        assertEquals("k0", batch.first())
    }

    @Test fun failThreeTimesMarksFailed() {
        post("a"); store.noteArrival("a", now, changed = false)
        store.fail(listOf("a"), now)
        assertEquals("pending", status("a"))
        assertTrue(store.pendingBatch(now + 59 * minute).isEmpty())
        assertEquals(listOf("a"), store.pendingBatch(now + 60 * minute))
        store.fail(listOf("a"), now); store.fail(listOf("a"), now)
        assertEquals("failed", status("a"))
        assertTrue(store.pendingBatch(now + day).isEmpty())
    }

    @Test fun postponeKeepsAttempts() {
        post("a"); store.noteArrival("a", now, changed = false)
        store.postpone(listOf("a"), now + 60 * minute)
        assertTrue(store.pendingBatch(now + 59 * minute).isEmpty())
        assertEquals(0L, sql.query("SELECT attempts FROM notification_facts", emptyList()) { it.long(0) }.single())
    }

    @Test fun callsAreCountedPerDay() {
        assertEquals(0, store.callsOn("2026-11-17"))
        assertNull(store.lastCallAt())
        store.countCall("2026-11-17", now)
        store.countCall("2026-11-17", now + 5)
        assertEquals(2, store.callsOn("2026-11-17"))
        assertEquals(now + 5, store.lastCallAt())
        assertEquals(0, store.callsOn("2026-11-18"))
    }

    @Test fun pendingCountCountsOnlyPending() {
        post("a"); store.noteArrival("a", now, changed = false)
        post("b"); store.noteArrival("b", now, changed = false)
        store.fail(listOf("b"), now); store.fail(listOf("b"), now); store.fail(listOf("b"), now)
        assertEquals(1, store.pendingCount())
    }

    @Test fun clearEmptiesAllUnderstandingTables() {
        post("a"); store.noteArrival("a", now, changed = false)
        sql.exec("INSERT INTO money_events (kind, amount, currency, first_at, last_at, source_count) VALUES ('spend', 1, 'KRW', 1, 1, 1)")
        sql.exec("INSERT INTO parcels (stage, first_at, updated_at) VALUES ('received', 1, 1)")
        store.countCall("2026-11-17", now)
        store.clear()
        for (t in listOf("notification_facts", "money_events", "parcels", "understand_calls")) {
            assertEquals(t, 0L, sql.query("SELECT COUNT(*) FROM $t", emptyList()) { it.long(0) }.single())
        }
    }

    @Test fun pruneKeepsMoneyEvents90DaysAndDropsOrphanFacts() {
        post("kept"); store.noteArrival("kept", now, changed = false)
        store.noteArrival("gone", now, changed = false) // its notification was never stored (or pruned)
        sql.exec("INSERT INTO money_events (kind, amount, currency, first_at, last_at, source_count) VALUES ('spend', 1, 'KRW', ?, ?, 1)", listOf(now - 89 * day, now - 89 * day))
        sql.exec("INSERT INTO money_events (kind, amount, currency, first_at, last_at, source_count) VALUES ('spend', 2, 'KRW', ?, ?, 1)", listOf(now - 91 * day, now - 91 * day))
        sql.exec("INSERT INTO parcels (stage, first_at, updated_at, delivered_at) VALUES ('delivered', ?, ?, ?)", listOf(now - 20 * day, now - 15 * day, now - 15 * day))
        sql.exec("INSERT INTO parcels (stage, first_at, updated_at, delivered_at) VALUES ('delivered', ?, ?, ?)", listOf(now - 5 * day, now - 3 * day, now - 3 * day))
        sql.exec("INSERT INTO parcels (stage, first_at, updated_at) VALUES ('in_transit', ?, ?)", listOf(now - 40 * day, now - 31 * day))
        store.prune(now)
        assertEquals(listOf("kept"), sql.query("SELECT key FROM notification_facts", emptyList()) { it.string(0) })
        assertEquals(listOf(1L), sql.query("SELECT amount FROM money_events", emptyList()) { it.long(0) })
        assertEquals(1L, sql.query("SELECT COUNT(*) FROM parcels", emptyList()) { it.long(0) }.single())
    }

    @Test fun recentEventsHoldLast30MinutesSpendsForRefundsAndOpenParcels() {
        sql.exec("INSERT INTO money_events (kind, amount, currency, merchant, first_at, last_at, source_count) VALUES ('spend', 4500, 'KRW', '스타벅스', ?, ?, 2)", listOf(now - 10 * minute, now - 9 * minute))
        sql.exec("INSERT INTO money_events (kind, amount, currency, merchant, first_at, last_at, source_count) VALUES ('income', 9000, 'KRW', '홍길동', ?, ?, 1)", listOf(now - 2 * day, now - 2 * day))
        sql.exec("INSERT INTO money_events (kind, amount, currency, merchant, first_at, last_at, source_count) VALUES ('spend', 30000, 'KRW', '쿠팡', ?, ?, 1)", listOf(now - 10 * day, now - 10 * day))
        sql.exec("INSERT INTO money_events (kind, amount, currency, merchant, first_at, last_at, source_count) VALUES ('spend', 1, 'KRW', '옛날', ?, ?, 1)", listOf(now - 40 * day, now - 40 * day))
        sql.exec("INSERT INTO parcels (carrier, tracking, stage, first_at, updated_at) VALUES ('CJ대한통운', '123', 'in_transit', ?, ?)", listOf(now - day, now - day))
        sql.exec("INSERT INTO parcels (carrier, stage, first_at, updated_at, delivered_at) VALUES ('한진', 'delivered', ?, ?, ?)", listOf(now - 5 * day, now - 3 * day, now - 3 * day))
        val recent = store.recentEvents(now)
        assertEquals(setOf("스타벅스", "쿠팡"), recent.filter { it.kind != NoticeKind.PARCEL }.mapNotNull { it.merchant }.toSet())
        val parcel = recent.single { it.tracking != null }
        assertEquals(ParcelStage.IN_TRANSIT, parcel.stage)
        assertEquals(NoticeKind.PARCEL, parcel.kind)
    }
}
