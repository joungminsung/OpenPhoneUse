package com.phoneuse.agent

import com.phoneuse.phone.ControlsTool
import com.phoneuse.phone.FakeControls
import com.phoneuse.phone.RingerMode
import com.phoneuse.phone.VolumeStream
import com.phoneuse.screen.CompressedScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** flashlight / set_volume / media_control / device_status through their module: what is offered, and argument errors. */
class ControlsModuleTest {
    private val source = FakeControls()
    private val module = ControlsModule(ControlsTool(source) {})
    private val names = listOf("flashlight", "set_volume", "media_control", "device_status")
    private val context = object : ToolContext {
        override val screen: CompressedScreen? = null
        override suspend fun settled(head: String, afterTool: String?, launched: String?) = ToolResult(head, null, null, null)
    }

    private fun run(name: String, args: String = "{}", m: ControlsModule = module) =
        runBlocking { m.run(name, Json.parseToJsonElement(args).jsonObject, context) }

    @Test
    fun offersNothingWithoutControls() {
        val none = ControlsModule(null)
        assertTrue(none.offered.isEmpty())
        // The specs stay (the tool list order is pinned); a call that still arrives is refused.
        assertEquals(names, none.specs.map { it.name })
        for (name in names) assertEquals("ERROR: $name is not available", run(name, m = none).output)
    }

    @Test
    fun offersAllFourAsTheUsersOwnData() {
        assertEquals(names, module.specs.map { it.name })
        assertEquals(names.toSet(), module.offered)
        assertEquals(names.toSet(), module.userText)
        assertTrue(names.all { module.handles(it) })
    }

    @Test
    fun specsDeclareTheirParameters() {
        val specs = ControlsModule.SPECS.associateBy { it.name }
        fun required(name: String) = specs.getValue(name).parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        fun enumOf(name: String, prop: String) = specs.getValue(name).parameters.getValue("properties").jsonObject
            .getValue(prop).jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("on"), required("flashlight"))
        assertTrue(required("set_volume").isEmpty())
        assertEquals(listOf("action"), required("media_control"))
        assertTrue(required("device_status").isEmpty())
        assertEquals(listOf("media", "ring", "alarm", "call"), enumOf("set_volume", "stream"))
        assertEquals(listOf("sound", "vibrate", "silent"), enumOf("set_volume", "ringer"))
        assertEquals(listOf("play", "pause", "toggle", "next", "previous", "stop"), enumOf("media_control", "action"))
    }

    @Test
    fun runsEachTool() {
        assertEquals("OK: 손전등을 켰어요", run("flashlight", """{"on":true}""").output)
        assertEquals("OK: 손전등을 껐어요", run("flashlight", """{"on":false}""").output)
        assertEquals(listOf(true, false), source.torch)

        assertEquals("OK: 미디어 음량 47% (7/15)", run("set_volume", """{"percent":47}""").output)
        // A whole number written as 3.0 counts, and JSON null reads as not given.
        assertEquals("OK: 알람 음량 100% (7/7)", run("set_volume", """{"stream":"alarm","percent":100.0,"ringer":null}""").output)
        assertEquals("OK: 벨 모드 진동", run("set_volume", """{"ringer":"vibrate"}""").output)
        assertEquals(RingerMode.VIBRATE, source.ringerMode)
        assertEquals(7, source.levels[VolumeStream.MEDIA])

        val next = run("media_control", """{"action":"next"}""")
        assertTrue(next.output, next.output.startsWith("OK: 다음 곡을 요청했어요"))
        assertEquals(listOf(87), source.keys)

        val status = run("device_status")
        assertEquals(82, Json.parseToJsonElement(status.output).jsonObject.getValue("battery").jsonObject.getValue("percent").jsonPrimitive.int)
        // Plain text results: no screen, no card.
        assertNull(status.screen)
        assertNull(status.card)
    }

    @Test
    fun aRefusedChangeIsToolOutput() {
        val silent = run("set_volume", """{"ringer":"silent"}""")
        assertTrue(silent.output, silent.output.startsWith("ERROR: ") && "빠른 설정" in silent.output)
    }

    @Test
    fun argumentErrorsComeBackAsToolOutput() {
        assertEquals("ERROR: invalid arguments: on required", run("flashlight").output)
        assertEquals("ERROR: invalid arguments: on must be a boolean", run("flashlight", """{"on":"yes"}""").output)
        assertEquals("ERROR: invalid arguments: percent must be an integer", run("set_volume", """{"percent":"loud"}""").output)
        assertEquals("ERROR: invalid arguments: percent must be an integer", run("set_volume", """{"percent":12.5}""").output)
        assertEquals("ERROR: invalid arguments: stream must be a scalar", run("set_volume", """{"stream":["media"],"percent":10}""").output)
        assertEquals("ERROR: invalid arguments: action required", run("media_control").output)
        // What the tool itself rejects reads the same way.
        assertEquals("ERROR: percent must be 0-100", run("set_volume", """{"percent":150}""").output)
        assertEquals("ERROR: percent or ringer required", run("set_volume").output)
        assertTrue(run("set_volume", """{"stream":"speaker","percent":10}""").output.startsWith("ERROR: stream must be one of"))
        assertTrue(run("set_volume", """{"ringer":"loud"}""").output.startsWith("ERROR: ringer must be one of"))
        assertTrue(run("media_control", """{"action":"rewind"}""").output.startsWith("ERROR: action must be one of"))
        assertEquals("ERROR: unknown tool teleport", run("teleport").output)
        // None of them touched the phone.
        assertTrue(source.torch.isEmpty())
        assertTrue(source.changes.isEmpty())
        assertTrue(source.keys.isEmpty())
    }
}
