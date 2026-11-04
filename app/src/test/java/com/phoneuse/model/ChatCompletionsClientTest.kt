package com.phoneuse.model

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ChatCompletionsClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun endpoint() = ChatCompletionsClient.Endpoint(server.url("/v1/").toString(), "check the key")
    private fun client(model: String = "grok-test") =
        ChatCompletionsClient(endpoint(), "test-key", ModelOptions(model, fast = false, effort = "low"), http)

    private val tool = ToolSpec("tap", "Tap a node", buildJsonObject { put("type", "object") })

    private fun sse(vararg chunks: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun bodyOf(req: okhttp3.mockwebserver.RecordedRequest) = Json.parseToJsonElement(req.body.readUtf8()).jsonObject

    @Test fun sendsChatCompletionsShapeWithKeyInHeaderOnly() = runBlocking {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}"""))
        client().next("be helpful", listOf(InputItem.UserText("hello")), listOf(tool)) {}
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer test-key", req.getHeader("Authorization"))
        assertFalse(req.requestUrl.toString().contains("test-key"))
        val body = bodyOf(req)
        assertEquals("grok-test", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("be helpful", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        val fn = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", fn["type"]!!.jsonPrimitive.content)
        assertEquals("tap", fn["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun streamsTextAndAccumulatesToolCallsByIndexWithUsage() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"content":"Hel"}}]}""",
                """{"choices":[{"delta":{"content":"lo"}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"tap","arguments":"{\"in"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"dex\":3}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":64}}}""",
            ),
        )
        val deltas = ArrayList<String>()
        val turn = client().next("i", listOf(InputItem.UserText("go")), listOf(tool)) { deltas += it }
        assertEquals(listOf("Hel", "lo"), deltas)
        assertEquals("Hello", turn.text)
        assertEquals(InputItem.FunctionCall("c1", "tap", """{"index":3}"""), turn.calls.single())
        assertEquals(Usage(100, 20, 64), turn.usage)
    }

    @Test fun aTurnGoesBackAsOneAssistantMessageThenToolMessagesThenTheScreenshot() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"tap","arguments":"{}"}},{"index":1,"id":"b","function":{"name":"tap","arguments":"{}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            ),
        )
        val c = client()
        val first = c.next("i", listOf(InputItem.UserText("go")), listOf(tool)) {}
        server.takeRequest()
        server.enqueue(sse("""{"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}"""))
        // The agent loop's order: call, output, image, call, output.
        val input = listOf(
            InputItem.UserText("go"),
            first.calls[0], InputItem.FunctionOutput("a", "OK 1"), InputItem.UserImage("SU1H", "image/jpeg"),
            first.calls[1], InputItem.FunctionOutput("b", "OK 2"),
        )
        c.next("i", input, listOf(tool)) {}
        val messages = bodyOf(server.takeRequest())["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool", "tool", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(2, messages[2]["tool_calls"]!!.jsonArray.size)
        val image = messages[5]["content"]!!.jsonArray[0].jsonObject
        assertEquals("image_url", image["type"]!!.jsonPrimitive.content)
        assertTrue(image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,SU1H"))
    }

    @Test fun geminiThoughtSignaturesComeBackWithTheirCall() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"tool_calls":[{"id":"g1","type":"function","function":{"name":"tap","arguments":"{}"},"extra_content":{"google":{"thought_signature":"SIG"}}}]},"finish_reason":"tool_calls"}]}""",
            ),
        )
        val c = client("gemini-test")
        val call = c.next("i", listOf(InputItem.UserText("go")), listOf(tool)) {}.calls.single()
        assertEquals("SIG", call.extra!!["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content)
        server.takeRequest()
        server.enqueue(sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        c.next("i", listOf(InputItem.UserText("go"), call, InputItem.FunctionOutput("g1", "OK")), listOf(tool)) {}
        val sent = bodyOf(server.takeRequest())["messages"]!!.jsonArray[2].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("SIG", sent["extra_content"]!!.jsonObject["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content)
    }

    @Test fun aModelThatRejectsReasoningEffortIsAskedAgainWithoutIt() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unknown parameter: reasoning_effort"}}"""))
        server.enqueue(sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val turn = client("no-effort-model").next("i", listOf(InputItem.UserText("go")), emptyList()) {}
        assertEquals("ok", turn.text)
        assertTrue(bodyOf(server.takeRequest()).containsKey("reasoning_effort"))
        assertFalse(bodyOf(server.takeRequest()).containsKey("reasoning_effort"))
    }

    @Test fun aBadKeyIsReportedAsTheProvidersKeyMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        try {
            client().next("i", listOf(InputItem.UserText("go")), emptyList()) {}
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertEquals(401, e.status)
            assertEquals("check the key", e.message)
        }
    }

    @Test fun listsModelsWithoutThePrefix() {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"models/gemini-x"},{"id":"grok-y"}]}"""))
        assertEquals(listOf("gemini-x", "grok-y"), ChatCompletionsClient.listModels(endpoint(), "test-key", http))
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test fun otherProvidersReasoningIsNotSent() = runBlocking {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val openAi = InputItem.Reasoning(buildJsonObject { put("type", "reasoning"); put("encrypted_content", "x") })
        client().next("i", listOf(InputItem.UserText("go"), openAi), emptyList()) {}
        val text = server.takeRequest().body.readUtf8()
        assertFalse(text, text.contains("encrypted_content"))
        assertNull((Json.parseToJsonElement(text) as JsonObject)["reasoning"])
    }
}
