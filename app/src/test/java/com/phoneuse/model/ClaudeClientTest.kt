package com.phoneuse.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Request building and response parsing; the SDK's HTTP layer is not exercised (no network in tests). */
class ClaudeClientTest {
    private val client = ClaudeClient(ClaudeClient.build("test-key", "http://127.0.0.1:1/"), ModelOptions("claude-opus-5-5", fast = false, effort = "medium"))
    private val tool = ToolSpec("tap", "Tap a node", buildJsonObject { put("type", "object") })

    private fun roles(messages: List<JsonObject>) = messages.map { it["role"]!!.jsonPrimitive.content }
    private fun types(message: JsonObject) = message["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }

    private fun turn(vararg blocks: JsonObject) = InputItem.Reasoning(buildJsonObject {
        put("type", ClaudeClient.TURN_TYPE)
        put("content", buildJsonArray { blocks.forEach { add(it) } })
    })

    private val thinking = buildJsonObject { put("type", "thinking"); put("thinking", "plan"); put("signature", "SIG") }
    private fun toolUse(id: String) = buildJsonObject { put("type", "tool_use"); put("id", id); put("name", "tap"); put("input", buildJsonObject {}) }

    @Test fun aTurnGoesBackWholeWithItsThinkingAndAllResultsInOneUserMessage() {
        val input = listOf(
            InputItem.UserText("go"),
            turn(thinking, toolUse("a"), toolUse("b")),
            InputItem.FunctionCall("a", "tap", "{}"), InputItem.FunctionOutput("a", "OK 1"), InputItem.UserImage("SU1H", "image/jpeg"),
            InputItem.FunctionCall("b", "tap", "{}"), InputItem.FunctionOutput("b", "OK 2"),
        )
        val messages = client.messages(input)
        assertEquals(listOf("user", "assistant", "user"), roles(messages))
        // Thinking first and unchanged (signature kept), each call once.
        assertEquals(listOf("thinking", "tool_use", "tool_use"), types(messages[1]))
        assertEquals("SIG", messages[1]["content"]!!.jsonArray[0].jsonObject["signature"]!!.jsonPrimitive.content)
        // Every tool_result before the screenshot.
        assertEquals(listOf("tool_result", "tool_result", "image"), types(messages[2]))
        val source = messages[2]["content"]!!.jsonArray[2].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("SU1H", source["data"]!!.jsonPrimitive.content)
    }

    @Test fun appContextBecomesATaggedUserTextAndOpenAiReasoningIsDropped() {
        val openAi = InputItem.Reasoning(buildJsonObject { put("type", "reasoning"); put("encrypted_content", "x") })
        val messages = client.messages(listOf(InputItem.DeveloperText("memory: likes tea"), InputItem.UserText("hi"), openAi, InputItem.AssistantText("hello")))
        assertEquals(listOf("user", "assistant"), roles(messages))
        val first = messages[0]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(first, first.startsWith("<app_context>") && first.contains("likes tea"))
        assertFalse(messages.toString().contains("encrypted_content"))
    }

    @Test fun paramsCacheTheSystemAndLastToolAndUseAdaptiveThinkingWithEffort() {
        val params = client.params("be helpful", client.messages(listOf(InputItem.UserText("hi"))), listOf(tool, tool.copy(name = "swipe")))
        val body = Json.parseToJsonElement(com.anthropic.core.jsonMapper().writeValueAsString(params._body())).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(body["system"]!!.jsonArray[0].jsonObject.containsKey("cache_control"))
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertFalse(tools[0].containsKey("cache_control"))
        assertTrue(tools[1].containsKey("cache_control"))
        assertNull(body["temperature"])
    }

    @Test fun parsesTextCallsCitationsAndUsageCountingCachedInput() {
        val (turn, stop) = client.parse(
            Json.parseToJsonElement(
                """{"content":[
                  {"type":"thinking","thinking":"x","signature":"s"},
                  {"type":"text","text":"See ","citations":[{"type":"web_search_result_location","url":"https://a.example/x","title":"A"}]},
                  {"type":"tool_use","id":"t1","name":"tap","input":{"index":2}}],
                  "stop_reason":"tool_use",
                  "usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":90,"cache_creation_input_tokens":0}}""",
            ).jsonObject,
        )
        assertEquals("tool_use", stop)
        assertEquals("See ", turn.text)
        assertEquals(InputItem.FunctionCall("t1", "tap", """{"index":2}"""), turn.calls.single())
        assertEquals(Usage(100, 5, 90), turn.usage)
        assertEquals("https://a.example/x", turn.citations.single().url)
    }

    @Test fun aRefusalRunsNoCalls() {
        val (turn, _) = client.parse(
            Json.parseToJsonElement("""{"content":[{"type":"tool_use","id":"t1","name":"tap","input":{}}],"stop_reason":"refusal"}""").jsonObject,
        )
        assertTrue(turn.calls.isEmpty())
        assertTrue(turn.text!!.isNotEmpty())
    }
}
