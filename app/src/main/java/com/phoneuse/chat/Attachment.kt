package com.phoneuse.chat

import com.phoneuse.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

enum class AttachmentKind { IMAGE, TEXT }

/**
 * Something the user attached to a message. An IMAGE is a downscaled JPEG the app copied into its own storage;
 * [file] is its name inside `files/attachments` (resolved by [AttachmentFiles.resolve]), so it outlives the
 * picker's URI grant and a moved data folder. [name] is set for PDF pages. A TEXT is a file's extracted text (at
 * most [MAX_TEXT_CHARS]) with the file [name]. [id] is unique per attachment (list keys; two files may share a name).
 */
@Serializable
data class Attachment(
    val kind: AttachmentKind,
    val file: String? = null,
    val name: String? = null,
    val text: String? = null,
    val id: String = UUID.randomUUID().toString(),
) {
    companion object {
        const val MAX_TEXT_CHARS = 20_000

        /** Album, camera and screenshot attachments together. */
        const val MAX_PER_MESSAGE = 4

        fun image(file: String, name: String? = null) = Attachment(AttachmentKind.IMAGE, file = file, name = name)

        fun text(name: String, text: String) = Attachment(AttachmentKind.TEXT, name = name, text = text.take(MAX_TEXT_CHARS))

        /** A new conversation's title: the text, or what was attached when the message is only that. */
        fun title(text: String, attachments: List<Attachment>, maxChars: Int = 30): String = when {
            text.isNotBlank() || attachments.isEmpty() -> text.take(maxChars)
            attachments.all { it.kind == AttachmentKind.IMAGE && it.name == null } -> tr("Photo", "사진")
            else -> (attachments.firstNotNullOfOrNull { it.name } ?: tr("File", "파일")).take(maxChars)
        }
    }
}

/** `messages.attachments_json` (v4): a JSON array of [Attachment]; NULL when the message has none. */
object AttachmentsColumn {
    // The id has a default, so defaults must be written for it to survive a round trip.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(Attachment.serializer())

    fun encode(list: List<Attachment>): String? = if (list.isEmpty()) null else json.encodeToString(serializer, list)

    fun decode(raw: String?): List<Attachment> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** The app-private folder of attachment images (`files/attachments`): lookup, cleanup and the startup sweep. */
object AttachmentFiles {
    const val DIR = "attachments"
    const val SWEEP_AGE_MS = 24L * 60 * 60 * 1000

    /**
     * The image file of [attachment] directly inside [dir], or null. Only the last path segment of the stored
     * value is used, so a bad value read from the database can never reach anything outside [dir].
     */
    fun resolve(attachment: Attachment, dir: File): File? {
        val stored = attachment.file ?: return null
        val name = File(stored).name
        if (name.isEmpty() || name == "." || name == "..") return null
        return try {
            val root = dir.canonicalFile
            File(root, name).canonicalFile.takeIf { it.parentFile == root }
        } catch (e: IOException) {
            null
        }
    }

    /** Deletes the stored image files of [attachments]. */
    fun delete(attachments: List<Attachment>, dir: File) {
        for (a in attachments) resolve(a, dir)?.delete()
    }

    /**
     * Deletes files in [dir] that no message refers to ([referenced] file names) and that are older than [maxAgeMs];
     * younger ones may belong to a draft or a message being stored. Returns how many were deleted.
     */
    fun sweep(dir: File, referenced: Set<String>, now: Long, maxAgeMs: Long = SWEEP_AGE_MS): Int {
        val files = dir.listFiles() ?: return 0
        var deleted = 0
        for (f in files) {
            if (!f.isFile || f.name in referenced || now - f.lastModified() < maxAgeMs) continue
            if (f.delete()) deleted++
        }
        return deleted
    }
}
