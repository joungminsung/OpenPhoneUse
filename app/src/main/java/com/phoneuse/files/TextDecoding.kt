package com.phoneuse.files

import java.nio.charset.Charset

/**
 * Decodes the start of a text file the user attached. UTF-8 first; a UTF-16 BOM is honoured; when more than
 * [BAD_RATIO] of the UTF-8 result is U+FFFD, Korean CP949 (a superset of EUC-KR) is tried. Returns null for
 * anything that still does not read as text (binary data, NUL characters).
 */
object TextDecoding {
    const val BAD_RATIO = 0.01

    /** Bytes to read for [chars] characters in the widest supported encoding (UTF-8, up to 4 bytes each). */
    fun bytesFor(chars: Int): Int = chars * 4

    private val cp949: Charset? by lazy {
        listOf("x-windows-949", "windows-949", "MS949", "EUC-KR").firstNotNullOfOrNull { name ->
            try {
                if (Charset.isSupported(name)) Charset.forName(name) else null
            } catch (e: Exception) {
                null
            }
        }
    }

    fun decode(bytes: ByteArray): String? {
        val text = when {
            starts(bytes, 0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            starts(bytes, 0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            starts(bytes, 0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            else -> {
                val utf8 = String(bytes, Charsets.UTF_8)
                if (!garbled(utf8)) utf8 else cp949?.let { String(bytes, it) } ?: utf8
            }
        }
        return text.takeUnless { garbled(it) || '\u0000' in it }
    }

    private fun garbled(text: String): Boolean {
        if (text.isEmpty()) return false
        val bad = text.count { it == '�' }
        // One replacement at the very end is a character cut by the read limit.
        val counted = if (text.endsWith('�')) bad - 1 else bad
        return counted > 0 && counted.toDouble() / text.length > BAD_RATIO
    }

    private fun starts(bytes: ByteArray, vararg prefix: Int): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 0xFF) == prefix[it] }
}
