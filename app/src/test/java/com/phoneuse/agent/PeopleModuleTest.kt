package com.phoneuse.agent

import com.phoneuse.cards.PermissionCard
import com.phoneuse.phone.CallEntry
import com.phoneuse.phone.CallKind
import com.phoneuse.phone.CallLogSource
import com.phoneuse.phone.CallLogTool
import com.phoneuse.phone.ContactAdder
import com.phoneuse.phone.ContactWriter
import com.phoneuse.risk.RiskCategory
import com.phoneuse.screen.CompressedScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** recent_calls and add_contact through their module: what is offered, the confirmation card and permission cards. */
class PeopleModuleTest {
    private val now = ZonedDateTime.of(2026, 11, 2, 21, 0, 0, 0, ZoneId.of("Asia/Seoul"))

    private var callsAllowed = true
    private val calls = object : CallLogSource {
        override fun canRead() = callsAllowed
        override fun recent(since: Long, kinds: Set<CallKind>?, limit: Int) =
            listOf(CallEntry("엄마", "01011112222", CallKind.MISSED, now.minusHours(1).toInstant().toEpochMilli(), 0))
    }
    private var writable = true
    private val inserted = mutableListOf<String>()
    private val writer = object : ContactWriter {
        override fun canWrite() = writable
        override fun insert(name: String, phone: String, email: String?): Boolean {
            inserted += "$name $phone"
            return true
        }
    }
    private val gate = FakeUserGate()
    private val context = object : ToolContext {
        override val screen: CompressedScreen? = null
        override suspend fun settled(head: String, afterTool: String?, launched: String?) = ToolResult(head, null, null, null)
    }

    /** A run's module; [typed] is what the user wrote in it. */
    private fun module(typed: String = "") = PeopleModule(CallLogTool(calls) { now }, ContactAdder(writer), gate) { name, number, email ->
        number.filter { it.isDigit() } in typed.filter { it.isDigit() } && name in typed && (email == null || email in typed)
    }

    private fun run(module: PeopleModule, name: String, args: String) =
        runBlocking { module.run(name, Json.parseToJsonElement(args).jsonObject, context) }

    private val minsu = """{"name":"민수","phone":"010-1234-5678"}"""

    @Test
    fun toolsOnlyWithTheirParts() {
        val tool = CallLogTool(calls) { now }
        val adder = ContactAdder(writer)
        assertEquals(listOf("recent_calls", "add_contact"), PeopleModule.SPECS.map { it.name })
        assertEquals(setOf("recent_calls", "add_contact"), module().offered)
        assertEquals(setOf("recent_calls"), PeopleModule(tool, null, gate).offered)
        // Saving needs the gate for its card.
        assertEquals(setOf("recent_calls"), PeopleModule(tool, adder, null).offered)
        assertEquals(setOf("add_contact"), PeopleModule(null, adder, gate).offered)
        val bare = PeopleModule(null, null, null)
        assertTrue(bare.offered.isEmpty())
        // A call to a tool left out is refused.
        assertTrue(bare.handles("recent_calls"))
        assertEquals("ERROR: recent_calls is not available", run(bare, "recent_calls", "{}").output)
        assertEquals("ERROR: add_contact is not available", run(PeopleModule(tool, adder, null), "add_contact", minsu).output)
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun bothToolsAreTheUsersOwnData() {
        assertEquals(setOf("recent_calls", "add_contact"), module().userText)
    }

    @Test
    fun specsNameTheirParameters() {
        val (recent, add) = PeopleModule.SPECS.map { it.parameters }
        assertEquals(setOf("type", "limit", "days"), recent.getValue("properties").jsonObject.keys)
        assertTrue(recent.getValue("required").jsonArray.isEmpty())
        assertEquals(
            listOf("missed", "incoming", "outgoing", "all"),
            recent.getValue("properties").jsonObject.getValue("type").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(setOf("name", "phone", "email"), add.getValue("properties").jsonObject.keys)
        assertEquals(listOf("name", "phone"), add.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun recentCallsAnswersJson() {
        val m = module()
        val out = run(m, "recent_calls", """{"type":"missed","limit":5,"days":3}""").output
        assertTrue(out, out.contains("\"name\":\"엄마\"") && out.contains("\"type\":\"missed\"") && out.contains("\"at\":\"2026-11-02T20:00\""))
        assertTrue(run(m, "recent_calls", "{}").output.startsWith("["))
        assertTrue(run(m, "recent_calls", """{"limit":"many"}""").output.startsWith("ERROR: invalid arguments"))
        assertTrue(run(m, "recent_calls", """{"type":"video"}""").output.startsWith("ERROR"))
        assertTrue(run(m, "recent_calls", """{"days":0}""").output.startsWith("ERROR"))
    }

    @Test
    fun aContactTheUserTypedIsSavedWithoutACard() {
        val r = run(module("010-1234-5678 민수로 저장해줘"), "add_contact", minsu)
        assertTrue(r.output, r.output.startsWith("OK"))
        assertTrue(gate.confirms.isEmpty())
        assertEquals(listOf("민수 01012345678"), inserted)
    }

    @Test
    fun aNameOrAnAddressTheModelChoseStillAsks() {
        // Only the number was typed: the name is the model's.
        assertTrue(run(module("010-1234-5678 저장해줘"), "add_contact", minsu).output.startsWith("OK"))
        assertEquals(listOf(RiskCategory.CHANGE to ContactAdder.confirmSummary("민수", "01012345678")), gate.confirms)
        // The address was not typed, and the card shows it.
        val mail = """{"name":"민수","phone":"010-1234-5678","email":" ms@example.com "}"""
        assertTrue(run(module("010-1234-5678 민수로 저장해줘"), "add_contact", mail).output.startsWith("OK"))
        assertEquals(RiskCategory.CHANGE to ContactAdder.confirmSummary("민수", "01012345678", "ms@example.com"), gate.confirms.last())
        assertEquals(2, gate.confirms.size)
    }

    @Test
    fun anyOtherNumberGoesThroughTheConfirmationCard() {
        // The user typed another number, so this one is the model's own choice.
        val m = module("010-9999-0000 민수로 저장해줘")
        gate.confirmResult = false
        assertTrue(run(m, "add_contact", minsu).output.startsWith("CANCELLED"))
        assertTrue(inserted.isEmpty())
        gate.confirmResult = true
        assertTrue(run(m, "add_contact", minsu).output.startsWith("OK"))
        assertEquals(listOf("민수 01012345678"), inserted)
        val card = RiskCategory.CHANGE to ContactAdder.confirmSummary("민수", "01012345678")
        assertEquals(listOf(card, card), gate.confirms)
    }

    @Test
    fun badArgumentsAskNothing() {
        val m = module()
        assertEquals("ERROR: invalid arguments: phone required", run(m, "add_contact", """{"name":"민수"}""").output)
        assertTrue(run(m, "add_contact", """{"name":"민수","phone":"민수 번호"}""").output.startsWith("ERROR"))
        assertTrue(run(m, "add_contact", """{"name":"민수","phone":"010-1234-5678","email":"minsu"}""").output.startsWith("ERROR"))
        assertTrue(gate.confirms.isEmpty())
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun permissionCardIsShownOncePerLabel() {
        val m = module()
        callsAllowed = false
        writable = false
        val denied = run(m, "recent_calls", "{}")
        assertTrue(denied.output, denied.output.startsWith("권한 필요: 통화 기록") && denied.output.contains("[권한 허용]"))
        assertEquals(PermissionCard("통화 기록", listOf("android.permission.READ_CALL_LOG")), denied.card)
        assertNull(run(m, "recent_calls", "{}").card)
        // Another label brings its own card, once.
        val save = run(m, "add_contact", minsu)
        assertTrue(save.output, save.output.startsWith("권한 필요: 연락처 저장"))
        assertEquals(PermissionCard("연락처 저장", listOf("android.permission.WRITE_CONTACTS")), save.card)
        assertNull(run(m, "add_contact", minsu).card)
        assertTrue(gate.confirms.isEmpty())
        // A new run asks again.
        assertEquals(PermissionCard("통화 기록", listOf("android.permission.READ_CALL_LOG")), run(module(), "recent_calls", "{}").card)
    }
}
