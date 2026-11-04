package com.phoneuse.chat

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings "대화 내보내기": every conversation with its messages (text, steps, when, attached file names) as one
 * JSON file in Downloads. Cards and memories are not included; the file is the user's to keep or share.
 */
object ChatExport {
    private val pretty = Json { prettyPrint = true }

    fun json(conversations: List<Pair<Conversation, List<ChatMessage>>>, exportedAt: Long): String {
        val root = buildJsonObject {
            put("app", "OpenPhoneUse")
            put("exported_at", Instant.ofEpochMilli(exportedAt).toString())
            putJsonArray("conversations") {
                for ((c, messages) in conversations) {
                    add(
                        buildJsonObject {
                            put("id", c.id)
                            put("title", c.title)
                            put("updated_at", Instant.ofEpochMilli(c.updatedAt).toString())
                            put("messages", messagesJson(messages))
                        },
                    )
                }
            }
        }
        return pretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), root)
    }

    private fun messagesJson(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        for (m in messages) {
            add(
                buildJsonObject {
                    put("role", m.role.name.lowercase())
                    put("text", m.text)
                    put("created_at", Instant.ofEpochMilli(m.createdAt).toString())
                    if (m.steps.isNotEmpty()) putJsonArray("steps") { m.steps.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.text)) } }
                    val names = m.attachments.mapNotNull { it.name }
                    if (names.isNotEmpty()) putJsonArray("attachments") { names.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                },
            )
        }
    }

    /** The file name for an export made at [at], e.g. phone-use-chats-20261102-2130.json. */
    fun fileName(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "phone-use-chats-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(zone).format(Instant.ofEpochMilli(at)) + ".json"

    /** Writes the export to Downloads (MediaStore, no permission needed); returns the file name. */
    suspend fun saveToDownloads(context: Context, repo: ChatRepository, now: Long = System.currentTimeMillis()): String =
        // All of it off the main thread: reading every conversation and building the JSON can be large.
        withContext(Dispatchers.IO) {
            val all = repo.conversations().first().map { it to repo.messages(it.id).first() }
            val text = json(all, now)
            val name = fileName(now)
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads unavailable")
            try {
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("cannot write")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            name
        }
}
