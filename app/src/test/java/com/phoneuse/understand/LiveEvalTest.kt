package com.phoneuse.understand

import com.phoneuse.auth.ApiKeyAuth
import com.phoneuse.model.ModelOptions
import com.phoneuse.model.OpenAiResponsesClient
import com.phoneuse.phone.StoredNotification
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The regression cases judged by a real model: prints, per case, which notices it got right. Costs money, so it runs
 * only with OPU_EVAL_KEY (an OpenAI API key) set, and only when someone asks for it; OPU_EVAL_MODEL picks the model.
 */
class LiveEvalTest {
    @Test fun judgeTheRegressionCasesWithARealModel() = runBlocking {
        val key = System.getenv("OPU_EVAL_KEY")
        assumeTrue("set OPU_EVAL_KEY to run the paid evaluation", !key.isNullOrBlank())
        val model = System.getenv("OPU_EVAL_MODEL") ?: "gpt-6-luna"
        val client = OpenAiResponsesClient(ApiKeyAuth(key!!), ModelOptions(model, fast = false, effort = "low"), OkHttpClient())
        val raw = Json.parseToJsonElement(LiveEvalTest::class.java.classLoader!!.getResource("understanding/cases.json")!!.readText()).jsonArray
        var right = 0
        var total = 0
        for (el in raw) {
            val o = el.jsonObject
            if (o["liveSkip"]?.jsonPrimitive?.content == "true") continue
            val t0 = 1_794_877_200_000L
            val batch = o["notifications"]!!.jsonArray.mapIndexed { i, n ->
                val m = n.jsonObject
                StoredNotification("k$i", m["pkg"]!!.jsonPrimitive.content, m["app"]!!.jsonPrimitive.content, m["title"]!!.jsonPrimitive.content, m["text"]!!.jsonPrimitive.content, t0 + m["offsetSec"]!!.jsonPrimitive.long * 1000)
            }
            val turn = client.next(UnderstandPrompt.INSTRUCTIONS, UnderstandPrompt.input(batch, emptyList(), ZoneId.of("Asia/Seoul"), "ko"), emptyList())
            val got = UnderstandPrompt.parse(turn.text, batch.size).orEmpty()
            for (exp in o["expected"]!!.jsonArray) {
                val e = exp.jsonObject
                val i = e["i"]!!.jsonPrimitive.content.toInt()
                val kind = got[i]?.let { JudgmentCheck.clean(it, batch[i]).kind.wire }
                val ok = kind == e["kind"]!!.jsonPrimitive.content
                total++
                if (ok) right++
                println("EVAL | ${o["id"]!!.jsonPrimitive.content}[$i] expected=${e["kind"]!!.jsonPrimitive.content} got=$kind ${if (ok) "OK" else "WRONG"}")
            }
        }
        println("EVAL | $model: $right/$total kinds right (${right * 100 / maxOf(total, 1)}%)")
    }
}
