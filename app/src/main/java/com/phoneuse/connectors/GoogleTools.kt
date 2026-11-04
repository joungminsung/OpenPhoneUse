package com.phoneuse.connectors

import com.phoneuse.i18n.tr
import com.phoneuse.agent.ToolResult
import com.phoneuse.agent.UserGate
import com.phoneuse.cards.DraftCard
import com.phoneuse.mcp.McpClient
import com.phoneuse.mcp.TooLargeException
import com.phoneuse.mcp.callCancellable
import com.phoneuse.mcp.readCapped
import com.phoneuse.mcp.readPrefix
import com.phoneuse.model.ToolSpec
import com.phoneuse.risk.RiskCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

/**
 * The Google connector's tools: gmail_search, gmail_read, gmail_draft, gmail_send, drive_search, drive_read_text.
 * gmail_draft only saves a draft (and shows the draft card); gmail_send always asks SEND first and then sends via
 * drafts.send, reusing the draft gmail_draft made for the same mail.
 */
class GoogleTools(private val api: GoogleApi) {
    /** Drafts this process created, by (to, subject, body), so [send] sends that same draft. */
    private val drafts = LinkedHashMap<Triple<String, String, String>, String>()

    val specs: List<ToolSpec> = listOf(
        spec(
            "gmail_search",
            "Search the user's Gmail (connected Google account). q uses Gmail search syntax, e.g. \"from:kim newer_than:7d\", " +
                "\"is:unread\", \"subject:견적\". Returns up to 10 mails: id, from, subject, date, snippet.",
            "q" to "Gmail search query.",
        ),
        spec("gmail_read", "Read one Gmail message (from gmail_search) as text.", "id" to "Message id."),
        spec(
            "gmail_draft",
            "Save an email draft in the user's Gmail and show it as a draft card. Does not send. " +
                "Use it when the user wants to write or reply to an email.",
            "to" to "Recipient email address(es), comma separated.",
            "subject" to "Subject.",
            "body" to "Plain-text body.",
        ),
        spec(
            "gmail_send",
            "Send an email from the user's Gmail. The app always asks the user to confirm first. Use it when the user " +
                "asks to send (for example after a draft card's 보내기); pass the same to, subject and body as the draft.",
            "to" to "Recipient email address(es), comma separated.",
            "subject" to "Subject.",
            "body" to "Plain-text body.",
        ),
        spec(
            "drive_search",
            "Search the user's Google Drive by file content and name. Returns up to 10 files: id, name, type, modified.",
            "q" to "Words to look for.",
        ),
        spec("drive_read_text", "Read a Google Drive file (from drive_search) as text: Docs, Sheets (CSV), Slides and text files.", "id" to "File id."),
    )

    val names: Set<String> = specs.mapTo(HashSet()) { it.name }

    suspend fun call(name: String, args: JsonObject, gate: UserGate): ToolResult = try {
        when (name) {
            "gmail_search" -> {
                val found = api.searchMail(args.need("q"))
                ok(if (found.isEmpty()) "메일 없음" else found.joinToString("\n") { "[${it.id}] ${it.date} · ${it.from} · ${it.subject} — ${it.snippet}" })
            }
            "gmail_read" -> {
                val m = api.readMail(args.need("id"))
                ok(GoogleApi.truncateText("From: ${m.from}\nTo: ${m.to}\nDate: ${m.date}\nSubject: ${m.subject}\n\n${m.body}"))
            }
            "gmail_draft" -> draft(args.need("to"), args.str("subject").orEmpty(), args.need("body"))
            "gmail_send" -> send(args.need("to"), args.str("subject").orEmpty(), args.need("body"), gate)
            "drive_search" -> {
                val files = api.searchDrive(args.need("q"))
                ok(if (files.isEmpty()) "파일 없음" else files.joinToString("\n") { "[${it.id}] ${it.name} (${it.mimeType.substringAfterLast('.')}${it.modified?.let { m -> ", $m" }.orEmpty()})" })
            }
            "drive_read_text" -> {
                val (fileName, text) = api.readDriveText(args.need("id")) ?: return fail("텍스트로 읽을 수 없는 파일이에요")
                ok("# $fileName\n$text")
            }
            else -> fail("unknown tool $name")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        fail("invalid arguments: ${e.message}")
    } catch (e: Exception) {
        fail(e.message ?: e.toString())
    }

    private suspend fun draft(to: String, subject: String, body: String): ToolResult {
        val id = api.createDraft(to, subject, body)
        synchronized(drafts) {
            drafts[Triple(to, subject, body)] = id
            while (drafts.size > MAX_DRAFTS) drafts.remove(drafts.keys.first())
        }
        return ToolResult(
            "OK: Gmail에 초안을 저장했어요 (draft_id $id). 초안 카드를 보여줬어요. 보내려면 사용자가 원할 때 gmail_send를 같은 내용으로 호출하세요.",
            null, null, null,
            card = DraftCard("email", to, subject.ifBlank { null }, body)
        )
    }

    private suspend fun send(to: String, subject: String, body: String, gate: UserGate): ToolResult {
        // The whole body: the user approves exactly what is sent (an injected mail could hide a payload past a cut).
        val flat = body.replace(Regex("\\s+"), " ").trim()
        val preview = if (flat.length <= BODY_PREVIEW_CHARS) {
            flat
        } else {
            flat.take(BODY_PREVIEW_CHARS) + tr("… (${flat.length - BODY_PREVIEW_CHARS} more characters)", "… (${flat.length - BODY_PREVIEW_CHARS}자 더 있음)")
        }
        if (!gate.confirm(RiskCategory.SEND, tr("Send mail: $to · \"${subject.take(PREVIEW_CHARS)}\" — \"$preview\"", "메일 보내기: $to · \"${subject.take(PREVIEW_CHARS)}\" — \"$preview\""))) {
            return ToolResult("CANCELLED by user", null, null, null)
        }
        val key = Triple(to, subject, body)
        val draftId = synchronized(drafts) { drafts.remove(key) } ?: api.createDraft(to, subject, body)
        val messageId = api.sendDraft(draftId)
        return ok("OK: 메일을 보냈어요${messageId?.let { " (message_id $it)" }.orEmpty()}")
    }

    private fun ok(output: String) = ToolResult(output, null, null, null)

    private fun fail(reason: String) = ToolResult("ERROR: $reason", null, null, null)

    private fun JsonObject.str(key: String): String? = when (val v = this[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.content
        else -> throw IllegalArgumentException("$key must be a string")
    }

    private fun JsonObject.need(key: String): String = str(key)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$key required")

    private companion object {
        const val MAX_DRAFTS = 20
        const val PREVIEW_CHARS = 60

        /** A mail body longer than this is shown cut, with the count of characters left out. */
        const val BODY_PREVIEW_CHARS = 4_000

        fun spec(name: String, description: String, vararg props: Pair<String, String>) = ToolSpec(
            name,
            "[Google 커넥터] $description",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    for ((k, d) in props) putJsonObject(k) {
                        put("type", "string")
                        put("description", d)
                    }
                }
                putJsonArray("required") { props.filter { it.first != "subject" }.forEach { add(JsonPrimitive(it.first)) } }
            },
        )
    }
}
