package com.phoneuse.understand

import com.phoneuse.model.InputItem
import com.phoneuse.model.ModelClient
import com.phoneuse.model.ModelTurn
import com.phoneuse.model.ToolSpec
import com.phoneuse.phone.NotificationSql
import com.phoneuse.phone.StoredNotification
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * The real notices the old regex rules misread (docs/misread-investigation.md), each with the judgment a correct
 * model gives. The fake model answers exactly that, so this checks everything after the model: the amount check,
 * merging, the stored events and what the screens count from them.
 */
class RegressionTest {
    data class Case(val id: String, val notifications: List<JsonObject>, val answer: String, val expect: JsonObject)

    companion object {
        fun cases(): List<Case> {
            val text = RegressionTest::class.java.classLoader!!.getResource("understanding/cases.json")!!.readText()
            return Json.parseToJsonElement(text).jsonArray.map { el ->
                val o = el.jsonObject
                Case(
                    o["id"]!!.jsonPrimitive.content,
                    o["notifications"]!!.jsonArray.map { it.jsonObject },
                    buildJsonObject { put("items", o["expected"]!!) }.toString(),
                    o["expect"]!!.jsonObject,
                )
            }
        }
    }

    private class Outcome(val spendTotal: Long, val spendEvents: Int, val parcels: Int, val replies: Int)

    private fun run(case: Case): Outcome = runBlocking {
        var now = 1_794_877_200_000L // 2026-11-17 10:00 Seoul
        val sql = JdbcSqlPort.fresh()
        val store = UnderstandingStore(sql) { now }
        val stored = case.notifications.mapIndexed { i, n ->
            val at = now + n["offsetSec"]!!.jsonPrimitive.long * 1000
            val s = StoredNotification("k$i", n["pkg"]!!.jsonPrimitive.content, n["app"]!!.jsonPrimitive.content, n["title"]!!.jsonPrimitive.content, n["text"]!!.jsonPrimitive.content, at)
            sql.exec(NotificationSql.UPSERT, listOf(s.key, s.packageName, s.appLabel, s.title, s.text, s.postedAt, 0))
            store.noteArrival(s.key, s.postedAt, changed = false)
            s
        }
        now = stored.maxOf { it.postedAt } + UnderstandingStore.FIRST_WAIT_MS
        val model = object : ModelClient {
            override suspend fun next(instructions: String, input: List<InputItem>, tools: List<ToolSpec>, onTextDelta: (String) -> Unit) =
                ModelTurn(case.answer, emptyList(), null)
        }
        val deps = object : UnderstandDeps {
            override suspend fun enabled() = true
            override suspend fun client() = model to "light"
            override val store = store
            override suspend fun notifications(keys: List<String>) = stored.filter { it.key in keys }
            override suspend fun recordUsage(inputTokens: Int, outputTokens: Int) = Unit
            override fun appLanguage() = "ko"
            override fun zone(): ZoneId = ZoneId.of("Asia/Seoul")
            override fun now() = now
            override suspend fun pokeProactive() = Unit
        }
        val result = UnderstandRun(deps).once()
        assertEquals(case.id, RunResult.Done(stored.size, more = false), result)
        val data = UnderstoodData(sql)
        val facts = data.facts(stored.map { it.key })
        val payments = data.payments(0)
        Outcome(payments.sumOf { it.amountMinor }, payments.size, data.parcels(now).size, stored.count { DigestGroups.needsReply(it, facts[it.key]) })
    }

    @Test fun everyCaseAddsUpAsExpected() {
        val cases = cases()
        assertEquals(19, cases.size)
        for (case in cases) {
            val got = run(case)
            val e = case.expect
            assertEquals("${case.id} spend total", e["spendTotal"]!!.jsonPrimitive.long, got.spendTotal)
            assertEquals("${case.id} spend events", e["spendEvents"]!!.jsonPrimitive.int, got.spendEvents)
            assertEquals("${case.id} parcels", e["parcels"]!!.jsonPrimitive.int, got.parcels)
            assertEquals("${case.id} replies", e["replies"]!!.jsonPrimitive.int, got.replies)
        }
    }
}
