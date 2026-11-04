package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.screen.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanModuleTest {
    private val ex = ToolExecutor(FakeDeviceController(listOf(ScreenSnapshot("com.app", 1080, 2400, emptyList()))), FakeUserGate())
    private val steps = """{"steps":[{"text":"Open Settings","status":"done"},{"text":"Turn on DND","status":"in_progress"},{"text":"Check the tile","status":"pending"}]}"""

    @Test fun offeredAndEchoesThePlanAsAChecklist() = runTest {
        assertTrue(ex.specs.any { it.name == "update_plan" })
        val out = ex.execute(InputItem.FunctionCall("c", "update_plan", steps), null).output
        assertEquals("OK: plan updated (1/3 done)\n[x] Open Settings\n[>] Turn on DND\n[ ] Check the tile", out)
    }

    @Test fun emptyPlanIsAnError() = runTest {
        assertEquals("ERROR: steps required", ex.execute(InputItem.FunctionCall("c", "update_plan", """{"steps":[]}"""), null).output)
    }

    @Test fun currentStepIsTheOneInProgress() {
        assertEquals("Turn on DND", PlanModule.current(Json.parseToJsonElement(steps) as JsonObject))
        assertNull(PlanModule.current(Json.parseToJsonElement("""{"steps":[{"text":"a","status":"done"}]}""") as JsonObject))
    }
}
