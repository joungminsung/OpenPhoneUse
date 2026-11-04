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

/** A Google API call that failed; the message carries the HTTP code, never a token. */
class GoogleApiException(message: String, val status: Int? = null) : Exception(message)

/** Gmail and Drive REST calls with a bearer [token]. The bases are overridable for tests. */
class GoogleApi(
    private val http: OkHttpClient,
    private val token: suspend () -> String,
    private val gmailBase: String = "https://gmail.googleapis.com/gmail/v1",
    private val driveBase: String = "https://www.googleapis.com/drive/v3",
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class MailSummary(val id: String, val from: String, val subject: String, val date: String, val snippet: String)
    data class Mail(val id: String, val from: String, val to: String, val subject: String, val date: String, val body: String)
    data class DriveFile(val id: String, val name: String, val mimeType: String, val modified: String?, val link: String?)

    suspend fun searchMail(q: String, max: Int = 10): List<MailSummary> {
        val list = getJson(gmail("users/me/messages").addQueryParameter("q", q).addQueryParameter("maxResults", max.toString()).build())
        val ids = (list["messages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("id") }
        return ids.map { id ->
            val m = getJson(
                gmail("users/me/messages/$id")
                    .addQueryParameter("format", "metadata")
                    .addQueryParameter("metadataHeaders", "From")
                    .addQueryParameter("metadataHeaders", "Subject")
                    .addQueryParameter("metadataHeaders", "Date")
                    .build(),
            )
            val headers = headers(m)
            MailSummary(id, headers["from"].orEmpty(), headers["subject"].orEmpty(), headers["date"].orEmpty(), m.str("snippet").orEmpty())
        }
    }

    suspend fun readMail(id: String): Mail {
        val m = getJson(gmail("users/me/messages/${seg(id)}").addQueryParameter("format", "full").build())
        val h = headers(m)
        val payload = m["payload"] as? JsonObject
        val body = payload?.let { textOf(it, "text/plain") } ?: payload?.let { textOf(it, "text/html") }?.let { stripHtml(it) } ?: m.str("snippet").orEmpty()
        return Mail(id, h["from"].orEmpty(), h["to"].orEmpty(), h["subject"].orEmpty(), h["date"].orEmpty(), body)
    }

    /** Creates a draft; returns its id. */
    suspend fun createDraft(to: String, subject: String, body: String): String {
        val payload = buildJsonObject { putJsonObject("message") { put("raw", raw(to, subject, body)) } }
        val reply = sendJson(gmail("users/me/drafts").build(), payload)
        return reply.str("id") ?: throw GoogleApiException(tr("Didn't receive a draft id", "초안 id를 받지 못했어요"))
    }

    suspend fun sendDraft(draftId: String): String? {
        val reply = sendJson(gmail("users/me/drafts/send").build(), buildJsonObject { put("id", draftId) })
        return reply.str("id")
    }

    suspend fun profileEmail(): String? = getJson(gmail("users/me/profile").build()).str("emailAddress")

    suspend fun searchDrive(q: String, max: Int = 10): List<DriveFile> {
        val escaped = q.replace("\\", "\\\\").replace("'", "\\'")
        val url = drive("files")
            .addQueryParameter("q", "fullText contains '$escaped' and trashed = false")
            .addQueryParameter("pageSize", max.toString())
            .addQueryParameter("fields", "files(id,name,mimeType,modifiedTime,webViewLink)")
            .build()
        return (getJson(url)["files"] as? JsonArray).orEmpty().mapNotNull { el ->
            val f = el as? JsonObject ?: return@mapNotNull null
            DriveFile(f.str("id") ?: return@mapNotNull null, f.str("name").orEmpty(), f.str("mimeType").orEmpty(), f.str("modifiedTime"), f.str("webViewLink"))
        }
    }

    /** The file's text: Google Docs/Sheets/Slides exported, text files downloaded; null for other types. */
    suspend fun readDriveText(id: String): Pair<String, String>? {
        val meta = getJson(drive("files/${seg(id)}").addQueryParameter("fields", "id,name,mimeType").build())
        val name = meta.str("name").orEmpty()
        val mime = meta.str("mimeType").orEmpty()
        val url = when {
            mime in EXPORTS -> drive("files/${seg(id)}/export").addQueryParameter("mimeType", EXPORTS.getValue(mime)).build()
            mime.startsWith("text/") || mime in TEXT_TYPES -> drive("files/${seg(id)}").addQueryParameter("alt", "media").build()
            else -> return null
        }
        // Only the start is downloaded: enough bytes for the character cap, the rest of the file is never read.
        return name to execute(Request.Builder().url(url).get(), maxBytes = MAX_TEXT_BYTES, prefixOnly = true).let { truncateText(it) }
    }

    private fun gmail(path: String): HttpUrl.Builder = "$gmailBase/$path".toHttpUrl().newBuilder()

    private fun drive(path: String): HttpUrl.Builder = "$driveBase/$path".toHttpUrl().newBuilder()

    private fun seg(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { throw GoogleApiException(tr("The id is invalid", "id가 올바르지 않아요")) }

    private suspend fun getJson(url: HttpUrl): JsonObject = parse(execute(Request.Builder().url(url).get()))


    private suspend fun sendJson(url: HttpUrl, body: JsonObject): JsonObject =
        parse(execute(Request.Builder().url(url).post(body.toString().toRequestBody(JSON_TYPE))))

    private fun parse(text: String): JsonObject = try {
        json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw GoogleApiException(tr("Couldn't read the Google response", "Google 응답을 읽을 수 없어요"))
    }

    /**
     * Runs one call, cancellable with the coroutine. The body is read up to [maxBytes]: past it, an error, or with
     * [prefixOnly] just that much.
     */
    private suspend fun execute(builder: Request.Builder, maxBytes: Long = MAX_BODY_BYTES, prefixOnly: Boolean = false): String {
        val bearer = token()
        val request = try {
            builder.header("Authorization", "Bearer $bearer").build()
        } catch (e: IllegalArgumentException) {
            throw GoogleApiException(tr("The Google credentials are invalid", "Google 인증 값이 올바르지 않아요"))
        }
        return try {
            http.callCancellable(request) { r, _ ->
                if (!r.isSuccessful) {
                    val why = when (r.code) {
                        401 -> tr("Authentication expired", "인증이 만료됐어요")
                        403 -> tr("No permission (check the scope or that the API is enabled)", "권한이 없어요(범위 또는 API 사용 설정 확인)")
                        404 -> tr("Not found", "찾을 수 없어요")
                        429 -> tr("Too many requests", "요청이 너무 많아요")
                        else -> tr("Error", "오류")
                    }
                    throw GoogleApiException("Google API $why (HTTP ${r.code})", r.code)
                }
                if (prefixOnly) r.readPrefix(maxBytes) else r.readCapped(maxBytes)
            }
        } catch (e: TooLargeException) {
            throw GoogleApiException(tr("The Google response is too large", "Google 응답이 너무 커요"))
        } catch (e: IOException) {
            throw GoogleApiException(tr("Couldn't reach Google because of a network error", "네트워크 오류로 Google에 연결할 수 없어요"))
        }
    }

    private fun headers(m: JsonObject): Map<String, String> =
        ((m["payload"] as? JsonObject)?.get("headers") as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            (o.str("name")?.lowercase() ?: return@mapNotNull null) to o.str("value").orEmpty()
        }.toMap()

    /** The first part of [mime] in the MIME tree, decoded. */
    private fun textOf(part: JsonObject, mime: String): String? {
        if (part.str("mimeType") == mime) {
            val data = (part["body"] as? JsonObject)?.str("data")
            if (data != null) return decode(data)
        }
        return (part["parts"] as? JsonArray).orEmpty().firstNotNullOfOrNull { (it as? JsonObject)?.let { p -> textOf(p, mime) } }
    }

    private fun decode(data: String): String? = try {
        String(Base64.getUrlDecoder().decode(data.trimEnd('=').replace('+', '-').replace('/', '_')), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n\n")
        .trim()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val MAX_TEXT_CHARS = McpClient.MAX_RESULT_CHARS

        /** UTF-8 is at most 4 bytes a character (2 per UTF-16 unit for most text); a little over the char cap. */
        private const val MAX_TEXT_BYTES = MAX_TEXT_CHARS * 4L + 4
        private const val MAX_BODY_BYTES = 2L * 1024 * 1024
        private val JSON_TYPE = "application/json".toMediaType()
        private val EXPORTS = mapOf(
            "application/vnd.google-apps.document" to "text/plain",
            "application/vnd.google-apps.spreadsheet" to "text/csv",
            "application/vnd.google-apps.presentation" to "text/plain",
        )
        private val TEXT_TYPES = setOf("application/json", "application/xml", "application/csv", "application/x-yaml")

        fun truncateText(s: String) = McpClient.truncate(s, MAX_TEXT_CHARS)

        /** RFC 2822 message, UTF-8, subject as an RFC 2047 encoded word, body base64; then base64url for the API. */
        fun raw(to: String, subject: String, body: String): String {
            val oneLine = { s: String -> s.replace(Regex("[\\r\\n]+"), " ").trim() }
            val encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(oneLine(subject).toByteArray(Charsets.UTF_8)) + "?="
            val mime = "To: ${oneLine(to)}\r\n" +
                "Subject: $encodedSubject\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\n" +
                Base64.getMimeEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mime.toByteArray(Charsets.UTF_8))
        }
    }
}
