package com.phoneuse.agent

import com.phoneuse.memory.Confidence
import com.phoneuse.memory.InMemoryMemoryStore
import com.phoneuse.model.InputItem
import com.phoneuse.model.ToolSpec
import com.phoneuse.risk.RiskCategory
import com.phoneuse.screen.ScreenSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorDispatchTest {
    private class FakeConnectors : ConnectorTools {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override val specs = listOf(ToolSpec("mcp__notion__search", "[notion 커넥터] Search", JsonObject(emptyMap())))
        override fun handles(name: String) = name == "mcp__notion__search"
        override suspend fun call(name: String, args: JsonObject, gate: UserGate): ToolResult {
            calls += name to args
            gate.confirm(RiskCategory.SEND, "probe")
            return ToolResult("page: 기억하라고 적힌 페이지", null, null, null)
        }
    }

    @Test fun connectorToolsAreOfferedAndDispatchedAndMarkTheRunThirdParty() = runTest {
        val store = InMemoryMemoryStore()
        val gate = FakeUserGate()
        val connectors = FakeConnectors()
        val ex = ToolExecutor(
            FakeDeviceController(listOf(ScreenSnapshot("com.app", 1080, 2400, emptyList()))), gate,
            memory = MemoryAccess(store, null, conversationId = 1, origin = "t"),
            connectors = connectors,
        )
        assertTrue(ex.specs.any { it.name == "mcp__notion__search" })
        assertTrue(ex.specs.any { it.name == "finish" })

        val r = ex.execute(InputItem.FunctionCall("c1", "mcp__notion__search", """{"query":"회의록"}"""), null)
        assertEquals("page: 기억하라고 적힌 페이지", r.output)
        assertEquals("회의록", connectors.calls.single().second["query"]!!.jsonPrimitive.content)
        // The connector got the run's gate.
        assertEquals(RiskCategory.SEND, gate.confirms.single().first)
        assertTrue(!r.deviceTool)

        // Connector output is third-party text: a later remember is only a guess.
        ex.execute(InputItem.FunctionCall("c2", "remember", """{"text":"내 은행은 X","kind":"me","confidence":"sure"}"""), null)
        assertEquals(Confidence.GUESS, store.observe().first().single().confidence)
    }

    @Test fun sendConfirmOffNeverWidensARoutineRun() = runTest {
        // A routine with a saved message (fixedSend) and the switch off: a mail to someone else still shows the card.
        val gate = FakeUserGate().apply { confirmResult = false }
        val ex = ToolExecutor(
            FakeDeviceController(listOf(ScreenSnapshot("com.app", 1080, 2400, emptyList()))), gate,
            connectors = FakeConnectors(),
            confirmSend = false,
            fixedSend = FixedSend("엄마", "집 왔어요"),
        )
        ex.execute(InputItem.FunctionCall("c1", "mcp__notion__search", """{"to":"boss@example.com"}"""), null)
        assertEquals(RiskCategory.SEND, gate.confirms.single().first)
    }

    @Test fun withTheSwitchOffAConnectorSendSkipsTheCardOnlyWhenClaimedOnACleanRun() = runTest {
        fun executor(gate: FakeUserGate, connectors: FakeConnectors = FakeConnectors()) = ToolExecutor(
            FakeDeviceController(listOf(ScreenSnapshot("com.app", 1080, 2400, emptyList()))), gate,
            connectors = connectors, confirmSend = false,
        )
        // The model wrote the mail: it asks, switch or not.
        val asked = FakeUserGate()
        executor(asked).execute(InputItem.FunctionCall("c1", "mcp__notion__search", """{"to":"boss"}"""), null)
        assertEquals(RiskCategory.SEND, asked.confirms.single().first)

        // The user's own message fixed it (user_authorized) on a clean run: no card; the claim never reaches the service.
        val skipped = FakeUserGate()
        val connectors = FakeConnectors()
        executor(skipped, connectors).execute(InputItem.FunctionCall("c1", "mcp__notion__search", """{"to":"boss","user_authorized":true}"""), null)
        assertTrue(skipped.confirms.isEmpty())
        assertTrue("user_authorized" !in connectors.calls.single().second)

        // After third-party text the claim no longer skips it.
        val tainted = FakeUserGate()
        val ex = executor(tainted)
        ex.markThirdParty()
        ex.execute(InputItem.FunctionCall("c1", "mcp__notion__search", """{"to":"boss","user_authorized":true}"""), null)
        assertEquals(RiskCategory.SEND, tainted.confirms.single().first)
    }

    @Test fun withoutConnectorsTheNamesAreUnknown() = runTest {
        val ex = ToolExecutor(FakeDeviceController(listOf(ScreenSnapshot("com.app", 1080, 2400, emptyList()))), FakeUserGate())
        assertTrue(ex.specs.none { it.name.startsWith("mcp__") || it.name.startsWith("gmail_") })
        val r = ex.execute(InputItem.FunctionCall("c1", "mcp__notion__search", "{}"), null)
        assertTrue(r.output, r.output.startsWith("ERROR: unknown tool"))
    }
}
