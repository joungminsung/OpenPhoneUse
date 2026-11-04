package com.phoneuse.connectors

import com.phoneuse.agent.FakeUserGate
import com.phoneuse.cards.DraftCard
import com.phoneuse.risk.RiskCategory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class GoogleToolsTest {
    private lateinit var server: MockWebServer
    private lateinit var tools: GoogleTools
    private val gate = FakeUserGate()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val base = server.url("/").toString().trimEnd('/')
        tools = GoogleTools(GoogleApi(OkHttpClient(), token = { "gtok" }, gmailBase = "$base/gmail/v1", driveBase = "$base/drive/v3"))
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun args(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun b64url(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Test fun specsAreTheSixGoogleTools() {
        assertEquals(
            listOf("gmail_search", "gmail_read", "gmail_draft", "gmail_send", "drive_search", "drive_read_text"),
            tools.specs.map { it.name },
        )
    }

    @Test fun gmailSearchListsThenReadsMetadata() = runBlocking {
        server.enqueue(json("""{"messages":[{"id":"m1","threadId":"t1"}],"resultSizeEstimate":1}"""))
        server.enqueue(
            json(
                """{"id":"m1","snippet":"내일 회의","payload":{"headers":[
                  {"name":"From","value":"Kim <kim@x.com>"},{"name":"Subject","value":"회의"},{"name":"Date","value":"Mon, 2 Nov 2026 09:00:00 +0900"}]}}""",
            ),
        )
        val out = tools.call("gmail_search", args("q" to "from:kim newer_than:7d"), gate)
        assertTrue(out.output, out.output.contains("m1"))
        assertTrue(out.output.contains("회의"))
        assertTrue(out.output.contains("kim@x.com"))

        val list = server.takeRequest()
        assertEquals("GET", list.method)
        assertEquals("Bearer gtok", list.getHeader("Authorization"))
        val url = list.requestUrl!!
        assertEquals("/gmail/v1/users/me/messages", url.encodedPath)
        assertEquals("from:kim newer_than:7d", url.queryParameter("q"))
        assertEquals("10", url.queryParameter("maxResults"))
        val meta = server.takeRequest().requestUrl!!
        assertEquals("/gmail/v1/users/me/messages/m1", meta.encodedPath)
        assertEquals("metadata", meta.queryParameter("format"))
        assertEquals(listOf("From", "Subject", "Date"), meta.queryParameterValues("metadataHeaders"))
    }

    @Test fun gmailReadDecodesThePlainTextPart() = runBlocking {
        server.enqueue(
            json(
                """{"id":"m1","payload":{"mimeType":"multipart/alternative","headers":[{"name":"Subject","value":"안녕"},{"name":"From","value":"a@b.c"}],
                  "parts":[{"mimeType":"text/plain","body":{"data":"${b64url("본문입니다\n둘째 줄")}"}},
                           {"mimeType":"text/html","body":{"data":"${b64url("<p>html</p>")}"}}]}}""",
            ),
        )
        val out = tools.call("gmail_read", args("id" to "m1"), gate)
        assertTrue(out.output, out.output.contains("본문입니다\n둘째 줄"))
        assertTrue(out.output.contains("안녕"))
        assertFalse(out.output.contains("<p>"))
        val req = server.takeRequest().requestUrl!!
        assertEquals("/gmail/v1/users/me/messages/m1", req.encodedPath)
        assertEquals("full", req.queryParameter("format"))
    }

    @Test fun gmailDraftCreatesADraftAndShowsACard() = runBlocking {
        server.enqueue(json("""{"id":"d1","message":{"id":"m9"}}"""))
        val out = tools.call("gmail_draft", args("to" to "kim@x.com", "subject" to "회의 시간", "body" to "3시에 봬요"), gate)
        assertTrue(gate.confirms.isEmpty())
        assertEquals(DraftCard("email", "kim@x.com", "회의 시간", "3시에 봬요"), out.card)
        assertTrue(out.output.contains("d1"))

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/gmail/v1/users/me/drafts", req.requestUrl!!.encodedPath)
        val raw = Json.parseToJsonElement(req.body.readUtf8()).jsonObject["message"]!!.jsonObject["raw"]!!.jsonPrimitive.content
        val mime = String(Base64.getUrlDecoder().decode(raw))
        assertTrue(mime, mime.contains("To: kim@x.com\r\n"))
        assertTrue(mime.contains("Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString("회의 시간".toByteArray()) + "?=\r\n"))
        assertTrue(mime.contains("Content-Type: text/plain; charset=UTF-8"))
        assertTrue(mime.contains(Base64.getMimeEncoder().encodeToString("3시에 봬요".toByteArray())))
    }

    @Test fun gmailSendConfirmsThenSendsTheDraft() = runBlocking {
        server.enqueue(json("""{"id":"d2"}"""))
        server.enqueue(json("""{"id":"m2","labelIds":["SENT"]}"""))
        val out = tools.call("gmail_send", args("to" to "kim@x.com", "subject" to "s", "body" to "b"), gate)
        assertEquals(RiskCategory.SEND, gate.confirms.single().first)
        assertTrue(gate.confirms.single().second.contains("kim@x.com"))
        assertTrue(out.output, out.output.startsWith("OK"))
        assertEquals("/gmail/v1/users/me/drafts", server.takeRequest().requestUrl!!.encodedPath)
        val send = server.takeRequest()
        assertEquals("POST", send.method)
        assertEquals("/gmail/v1/users/me/drafts/send", send.requestUrl!!.encodedPath)
        assertEquals("d2", Json.parseToJsonElement(send.body.readUtf8()).jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun gmailSendReusesTheDraftItMadeAndStopsWhenDeclined() = runBlocking {
        server.enqueue(json("""{"id":"d3"}"""))
        tools.call("gmail_draft", args("to" to "a@b.c", "subject" to "s", "body" to "b"), gate)
        server.takeRequest()

        gate.confirmResult = false
        val declined = tools.call("gmail_send", args("to" to "a@b.c", "subject" to "s", "body" to "b"), gate)
        assertTrue(declined.output.startsWith("CANCELLED"))
        assertEquals(0, server.requestCount - 1)

        gate.confirmResult = true
        server.enqueue(json("""{"id":"m3"}"""))
        tools.call("gmail_send", args("to" to "a@b.c", "subject" to "s", "body" to "b"), gate)
        val send = server.takeRequest()
        assertEquals("/gmail/v1/users/me/drafts/send", send.requestUrl!!.encodedPath)
        assertEquals("d3", Json.parseToJsonElement(send.body.readUtf8()).jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun driveSearchEscapesTheQuery() = runBlocking {
        server.enqueue(json("""{"files":[{"id":"f1","name":"보고서","mimeType":"application/vnd.google-apps.document","modifiedTime":"2026-10-30T01:00:00Z"}]}"""))
        val out = tools.call("drive_search", args("q" to "kim's 보고서"), gate)
        assertTrue(out.output.contains("f1"))
        assertTrue(out.output.contains("보고서"))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/drive/v3/files", url.encodedPath)
        assertEquals("fullText contains 'kim\\'s 보고서' and trashed = false", url.queryParameter("q"))
        assertEquals("10", url.queryParameter("pageSize"))
        assertTrue(url.queryParameter("fields")!!.contains("files("))
    }

    @Test fun driveReadTextExportsGoogleDocsAndDownloadsText() = runBlocking {
        server.enqueue(json("""{"id":"f1","name":"보고서","mimeType":"application/vnd.google-apps.document"}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("문서 내용"))
        val doc = tools.call("drive_read_text", args("id" to "f1"), gate)
        assertTrue(doc.output, doc.output.contains("문서 내용"))
        assertEquals("/drive/v3/files/f1", server.takeRequest().requestUrl!!.encodedPath)
        val export = server.takeRequest().requestUrl!!
        assertEquals("/drive/v3/files/f1/export", export.encodedPath)
        assertEquals("text/plain", export.queryParameter("mimeType"))

        server.enqueue(json("""{"id":"f2","name":"a.txt","mimeType":"text/plain"}"""))
        server.enqueue(MockResponse().setBody("x".repeat(GoogleApi.MAX_TEXT_CHARS + 10)))
        val txt = tools.call("drive_read_text", args("id" to "f2"), gate)
        assertFalse(txt.output.contains("x".repeat(GoogleApi.MAX_TEXT_CHARS + 1)))
        server.takeRequest()
        assertEquals("media", server.takeRequest().requestUrl!!.queryParameter("alt"))

        server.enqueue(json("""{"id":"f3","name":"a.png","mimeType":"image/png"}"""))
        val img = tools.call("drive_read_text", args("id" to "f3"), gate)
        assertTrue(img.output.startsWith("ERROR"))
    }

    @Test fun httpErrorsBecomeToolErrors() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"insufficient scope"}}"""))
        val out = tools.call("gmail_search", args("q" to "x"), gate)
        assertTrue(out.output, out.output.startsWith("ERROR"))
        assertTrue(out.output.contains("403"))
        assertNull(out.card)
    }

    @Test fun authorizeUrlHasPkceAndScopes() {
        val url = GoogleOAuth.authorizeUrl("cid.apps.googleusercontent.com", "http://127.0.0.1:5555/", "chal", "st").toHttpUrl()
        assertEquals("accounts.google.com", url.host)
        assertEquals("cid.apps.googleusercontent.com", url.queryParameter("client_id"))
        assertEquals("http://127.0.0.1:5555/", url.queryParameter("redirect_uri"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("chal", url.queryParameter("code_challenge"))
        assertEquals("st", url.queryParameter("state"))
        assertEquals("offline", url.queryParameter("access_type"))
        assertEquals(
            "https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.compose https://www.googleapis.com/auth/drive.readonly",
            url.queryParameter("scope"),
        )
    }
}
