package com.phoneuse.agent

import com.phoneuse.memory.Confidence
import com.phoneuse.memory.InMemoryMemoryStore
import com.phoneuse.model.InputItem
import com.phoneuse.screen.ScreenSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A module registered next to the built-in ones is offered and dispatched like them (DeviceInfoModule as the example). */
class ToolModuleTest {
    private var status: BatteryStatus? = BatteryStatus(42, charging = true)
    private val store = InMemoryMemoryStore()

    private fun executor(withDeviceInfo: Boolean) = ToolExecutor(
        FakeDeviceController(listOf(ScreenSnapshot("com.app", 1080, 2400, emptyList()))), FakeUserGate(),
        memory = MemoryAccess(store, null),
        registry = { d -> ToolRegistry.modules(d) + listOfNotNull(DeviceInfoModule { status }.takeIf { withDeviceInfo }) },
    )

    private fun call(name: String, args: String = "{}") = InputItem.FunctionCall("c", name, args)

    @Test fun notOfferedUnlessRegistered() {
        assertFalse(ToolExecutor(FakeDeviceController(listOf(ScreenSnapshot("com.app", 1080, 2400, emptyList()))), FakeUserGate()).specs.any { it.name == "battery_status" })
        assertEquals("ERROR: unknown tool battery_status", runTestResult(executor(false)).output)
    }

    @Test fun registeredModuleIsOfferedAfterTheBuiltInTools() {
        val names = executor(true).specs.map { it.name }
        assertEquals("battery_status", names.last())
        assertEquals(executor(false).specs.map { it.name } + "battery_status", names)
    }

    @Test fun registeredModuleIsDispatched() {
        assertEquals("""{"percent":42,"charging":true}""", runTestResult(executor(true)).output)
        status = null
        assertEquals("ERROR: battery status unavailable", runTestResult(executor(true)).output)
    }

    @Test fun userTextKeepsTheRunUntainted() = runTest {
        val ex = executor(true)
        ex.execute(call("battery_status"), null)
        assertFalse(ex.thirdPartySeen)
        // remember after it still saves a sure fact; a tool not in userText would have made it a guess.
        ex.execute(call("remember", """{"text":"배터리 아끼는 중","kind":"habit"}"""), null)
        assertEquals(Confidence.SURE, store.observe().first().single().confidence)
        ex.execute(call("list_apps"), null)
        assertTrue(ex.thirdPartySeen)
    }

    private fun runTestResult(ex: ToolExecutor): ToolResult = runBlocking { ex.execute(call("battery_status"), null) }
}
