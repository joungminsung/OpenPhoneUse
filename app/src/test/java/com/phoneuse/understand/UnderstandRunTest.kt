package com.phoneuse.understand

import com.phoneuse.model.InputItem
import com.phoneuse.model.ModelClient
import com.phoneuse.model.ModelException
import com.phoneuse.model.ModelTurn
import com.phoneuse.model.ToolSpec
import com.phoneuse.model.Usage
import com.phoneuse.phone.NotificationSql
import com.phoneuse.phone.StoredNotification
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class UnderstandRunTest {
    private val zone = ZoneId.of("Asia/Seoul")
    // 2026-11-17 10:00 in Seoul.
    private var now = 1_794_877_200_000L
    private val sql = JdbcSqlPort.fresh()
    private val store = UnderstandingStore(sql) { now }

    private inner class FakeModel(var answer: (String) -> String) : ModelClient {
        var calls = 0
        override suspend fun next(instructions: String, input: List<InputItem>, tools: List<ToolSpec>, onTextDelta: (String) -> Unit): ModelTurn {
            calls++
            return ModelTurn(answer((input.single() as InputItem.UserText).text), emptyList(), Usage(3000, 800))
        }
    }

    private inner class Deps(val model: ModelClient?) : UnderstandDeps {
        var on = true
        var usage = mutableListOf<Pair<Int, Int>>()
        var pokes = 0
        override suspend fun enabled() = on
        override suspend fun client() = model?.let { it to "light" }
        override val store = this@UnderstandRunTest.store
        override suspend fun notifications(keys: List<String>): List<StoredNotification> = sql.query(
            "SELECT ${NotificationSql.READ_COLUMNS} FROM notifications WHERE key IN (${keys.joinToString(",") { "?" }})", keys,
        ) { r -> StoredNotification(r.string(0)!!, r.string(1)!!, r.string(2)!!, r.string(3)!!, r.string(4)!!, r.long(5)!!, r.long(6) == 1L) }
        override suspend fun recordUsage(inputTokens: Int, outputTokens: Int) { usage += inputTokens to outputTokens }
        override fun appLanguage() = "ko"
        override fun zone() = zone
        override fun now() = now
        override suspend fun pokeProactive() { pokes++ }
    }

    private fun post(key: String, title: String, text: String, pkg: String = "com.x") {
        sql.exec(NotificationSql.UPSERT, listOf(key, pkg, "앱", title, text, now, 0))
        store.noteArrival(key, now, changed = false)
    }

    private fun starbucks() {
        post("sms", "15888900", "삼성카드 승인 4,500원 일시불 스타벅스강남")
        post("card", "삼성카드", "승인 4,500원 스타벅스 강남점")
        post("pay", "토스페이", "결제 완료 | 스타벅스 4,500원")
        post("bank", "KB스타뱅킹", "출금 4,500원 토스페이")
        now += 30_000
    }

    private val starbucksAnswer = """{"items":[
        {"i":0,"kind":"spend","amount":"4,500","currency":"KRW","merchant":"스타벅스","instrument":"삼성카드","sender":"business","needs_reply":false,"summary":"스타벅스 4,500원"},
        {"i":1,"kind":"spend","amount":"4,500","currency":"KRW","merchant":"스타벅스","same_as":{"item":0},"sender":"business","needs_reply":false,"summary":"s"},
        {"i":2,"kind":"spend","amount":"4,500","currency":"KRW","merchant":"스타벅스","instrument":"토스페이","same_as":{"item":0},"sender":"business","needs_reply":false,"summary":"s"},
        {"i":3,"kind":"spend","amount":"4,500","currency":"KRW","same_as":{"item":2},"sender":"business","needs_reply":false,"summary":"s"}]}"""

    private fun statuses() = sql.query("SELECT status FROM notification_facts ORDER BY key") { it.string(0) }

    @Test fun happyPathStoresFactsAndOneEvent() = runBlocking {
        starbucks()
        val model = FakeModel { starbucksAnswer }
        val deps = Deps(model)
        assertEquals(RunResult.Done(4, more = false), UnderstandRun(deps).once())
        assertEquals(listOf(4500L), sql.query("SELECT amount FROM money_events") { it.long(0) })
        assertEquals(listOf("done", "done", "done", "done"), statuses())
        assertEquals(1, deps.pokes)
    }

    @Test fun aBurstIsJudgedAndStoredInOneCall() = runBlocking {
        // Four notices 4 s apart; the run starts when the first one's 20 s wait is over.
        for ((i, k) in listOf("sms", "card", "pay", "bank").withIndex()) {
            post(k, "t", "승인 4,500원 스타벅스")
            if (i < 3) now += 4_000
        }
        now += 20_000 - 12_000
        val model = FakeModel { starbucksAnswer }
        assertEquals(RunResult.Done(4, more = false), UnderstandRun(Deps(model)).once())
        assertEquals(1, model.calls)
        assertEquals(listOf("done", "done", "done", "done"), statuses())
        assertEquals(listOf(4500L), sql.query("SELECT amount FROM money_events") { it.long(0) })
    }

    @Test fun aTimedOutCallCountsTowardTheGapAndTheBudget() = runBlocking {
        starbucks()
        val slow = object : ModelClient {
            override suspend fun next(instructions: String, input: List<InputItem>, tools: List<ToolSpec>, onTextDelta: (String) -> Unit): ModelTurn {
                kotlinx.coroutines.delay(5_000)
                error("not reached")
            }
        }
        assertEquals(RunResult.Failed(4), UnderstandRun(Deps(slow), timeoutMs = 50).once())
        assertEquals(1, store.callsOn("2026-11-17"))
        assertEquals(now, store.lastCallAt())
    }

    @Test fun anErrorAfterTheCallStillCountsIt() = runBlocking {
        starbucks()
        val broken = object : ModelClient {
            override suspend fun next(instructions: String, input: List<InputItem>, tools: List<ToolSpec>, onTextDelta: (String) -> Unit): ModelTurn =
                throw java.io.IOException("connection reset")
        }
        assertEquals(RunResult.Failed(4), UnderstandRun(Deps(broken)).once())
        assertEquals(1, store.callsOn("2026-11-17"))
    }

    @Test fun aFailureNeverBooksTheNextRunSooner() {
        val t = 1_000_000L
        // Keys are still ready (nextReadyAt in the past): the next run still waits the gap.
        assertEquals(UnderstandRun.MIN_GAP_MS, UnderstandRun.nextDelay(RunResult.Failed(0), t, t - 5_000))
        assertEquals(UnderstandRun.MIN_GAP_MS, UnderstandRun.nextDelay(RunResult.Failed(3), t, t))
        assertEquals(3_600_000L, UnderstandRun.nextDelay(RunResult.Failed(3), t, t + 3_600_000))
        assertEquals(UnderstandRun.MIN_GAP_MS, UnderstandRun.nextDelay(RunResult.Done(20, more = true), t, t))
        assertEquals(40_000L, UnderstandRun.nextDelay(RunResult.TooSoon(t + 40_000), t, t))
        assertEquals(15_000L, UnderstandRun.nextDelay(RunResult.NothingReady, t, t + 15_000))
        assertEquals(null, UnderstandRun.nextDelay(RunResult.NothingReady, t, null))
        assertEquals(null, UnderstandRun.nextDelay(RunResult.Disabled, t, t))
    }

    @Test fun anErrorWhileStoringFailsTheBatchInsteadOfResendingIt() = runBlocking {
        starbucks()
        // An answer that cannot be stored: a refund pointing at an event the store does not have is fine, so break
        // the store instead by dropping the money table under the run.
        sql.exec("DROP TABLE money_events")
        val r = UnderstandRun(Deps(FakeModel { starbucksAnswer })).once()
        assertEquals(RunResult.Failed(4), r)
        assertEquals(listOf(1L, 1L, 1L, 1L), sql.query("SELECT attempts FROM notification_facts") { it.long(0) })
    }

    @Test fun recordsTokensAndCountsTheCall() = runBlocking {
        starbucks()
        val deps = Deps(FakeModel { starbucksAnswer })
        UnderstandRun(deps).once()
        assertEquals(listOf(3000 to 800), deps.usage)
        assertEquals(1, store.callsOn("2026-11-17"))
    }

    @Test fun disabledSendsNothing() = runBlocking {
        starbucks()
        val model = FakeModel { starbucksAnswer }
        val deps = Deps(model).apply { on = false }
        assertEquals(RunResult.Disabled, UnderstandRun(deps).once())
        assertEquals(0, model.calls)
        assertEquals(listOf("pending", "pending", "pending", "pending"), statuses())
    }

    @Test fun noBackendLeavesPending() = runBlocking {
        starbucks()
        assertEquals(RunResult.NoBackend, UnderstandRun(Deps(null)).once())
        assertEquals(listOf("pending", "pending", "pending", "pending"), statuses())
    }

    @Test fun nothingReadyBeforeTheWait() = runBlocking {
        post("a", "t", "x")
        val model = FakeModel { starbucksAnswer }
        assertEquals(RunResult.NothingReady, UnderstandRun(Deps(model)).once())
        assertEquals(0, model.calls)
    }

    @Test fun budget200StopsForTheDay() = runBlocking {
        starbucks()
        repeat(200) { store.countCall("2026-11-17", now - 3_600_000) }
        val model = FakeModel { starbucksAnswer }
        val r = UnderstandRun(Deps(model)).once()
        assertTrue(r.toString(), r is RunResult.OverBudget)
        assertEquals(0, model.calls)
    }

    @Test fun waitsAMinuteBetweenCalls() = runBlocking {
        starbucks()
        store.countCall("2026-11-17", now - 20_000)
        val model = FakeModel { starbucksAnswer }
        assertEquals(RunResult.TooSoon(now + 40_000), UnderstandRun(Deps(model)).once())
        assertEquals(0, model.calls)
    }

    @Test fun invalidJsonCountsFailure() = runBlocking {
        starbucks()
        val r = UnderstandRun(Deps(FakeModel { "Sorry, I can't." })).once()
        assertEquals(RunResult.Failed(4), r)
        assertEquals(listOf(1L, 1L, 1L, 1L), sql.query("SELECT attempts FROM notification_facts") { it.long(0) })
    }

    @Test fun missingItemFailsOnlyThatKey() = runBlocking {
        post("a", "엄마", "저녁 먹고 오니?")
        post("b", "광고", "오늘만 50% 할인")
        now += 30_000
        val answer = """{"items":[{"i":0,"kind":"message","sender":"person","needs_reply":true,"summary":"엄마: 저녁"}]}"""
        assertEquals(RunResult.Done(1, more = false), UnderstandRun(Deps(FakeModel { answer })).once())
        assertEquals(listOf("done", "pending"), statuses())
        assertEquals(listOf(0L, 1L), sql.query("SELECT attempts FROM notification_facts ORDER BY key") { it.long(0) })
    }

    @Test fun usageLimitPausesWithoutFailure() = runBlocking {
        starbucks()
        val model = object : ModelClient {
            override suspend fun next(instructions: String, input: List<InputItem>, tools: List<ToolSpec>, onTextDelta: (String) -> Unit): ModelTurn =
                throw ModelException(429, "limit", code = ModelException.PLAN_USAGE_LIMIT)
        }
        assertEquals(RunResult.Paused(now + 3_600_000), UnderstandRun(Deps(model)).once())
        assertEquals(listOf(0L, 0L, 0L, 0L), sql.query("SELECT attempts FROM notification_facts") { it.long(0) })
        assertTrue(store.pendingBatch(now + 3_599_000).isEmpty())
    }

    @Test fun inventedAmountIsNotSpending() = runBlocking {
        post("step", "토스", "만보기 5,000걸음 달성! 10원 받기", pkg = "viva.republica.toss")
        now += 30_000
        val answer = """{"items":[{"i":0,"kind":"spend","amount":"4,500","currency":"KRW","merchant":"토스","sender":"business","needs_reply":false,"summary":"x"}]}"""
        UnderstandRun(Deps(FakeModel { answer })).once()
        assertEquals(0, sql.query("SELECT id FROM money_events") { it.long(0) }.size)
        assertEquals(listOf("other"), sql.query("SELECT kind FROM notification_facts") { it.string(0) })
    }

    @Test fun moreIsTrueWhenOverTwentyWait() = runBlocking {
        for (i in 0 until 21) post("k%02d".format(i), "t", "x")
        now += 30_000
        val answer = (0 until 20).joinToString(",", "{\"items\":[", "]}") { """{"i":$it,"kind":"other","sender":"system","needs_reply":false,"summary":""}""" }
        assertEquals(RunResult.Done(20, more = true), UnderstandRun(Deps(FakeModel { answer })).once())
    }

    @Test fun vanishedNotificationIsDropped() = runBlocking {
        post("a", "t", "x")
        sql.exec("DELETE FROM notifications WHERE key = 'a'")
        now += 30_000
        val model = FakeModel { "{\"items\":[]}" }
        assertEquals(RunResult.NothingReady, UnderstandRun(Deps(model)).once())
        assertEquals(0, model.calls)
        assertTrue(statuses().isEmpty())
    }
}
