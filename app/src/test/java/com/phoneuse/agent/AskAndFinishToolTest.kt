package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.screen.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAndFinishToolTest {
    private fun call(name: String, args: String) = InputItem.FunctionCall("c1", name, args)
    private val gate = FakeUserGate()
    private val ex = ToolExecutor(FakeDeviceController(listOf(ScreenSnapshot("com.app", 1080, 2400, emptyList()))), gate)

    @Test fun finishCarriesStayInApp() = runTest {
        assertTrue(ex.execute(call("finish", """{"summary":"재생 중","stay_in_app":true}"""), null).stayInApp)
        assertFalse(ex.execute(call("finish", """{"summary":"보냈어요"}"""), null).stayInApp)
    }

    @Test fun askWithFewerThanTwoOptionsStillShowsTheCard() = runTest {
        gate.askResult = "내일"
        val r = ex.execute(call("ask_user", """{"question":"언제요?","options":["내일"]}"""), null)
        assertEquals("User answered: 내일", r.output)
        val r2 = ex.execute(call("ask_user", """{"question":"이름?"}"""), null)
        assertEquals("User answered: 내일", r2.output)
        assertEquals(listOf("언제요?" to listOf("내일"), "이름?" to emptyList<String>()), gate.asks)
    }

    @Test fun askSchemaWantsTwoToFourOptions() {
        val specs = ex.specs
        val ask = specs.first { it.name == "ask_user" }
        val options = (ask.parameters["properties"] as JsonObject)["options"] as JsonObject
        assertEquals(JsonPrimitive(2), options["minItems"])
        assertEquals(JsonPrimitive(4), options["maxItems"])
        val finish = specs.first { it.name == "finish" }
        assertTrue("stay_in_app" in (finish.parameters["properties"] as JsonObject))
    }
}
