package com.phoneuse.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDecodingTest {
    private val korean = "안녕하세요. 오늘 회의는 3시에 시작합니다.\n자막 파일 예시입니다."

    @Test fun utf8AndItsBom() {
        assertEquals(korean, TextDecoding.decode(korean.toByteArray(Charsets.UTF_8)))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc".toByteArray()
        assertEquals("abc", TextDecoding.decode(bom))
    }

    @Test fun cp949FallsBackFromGarbledUtf8() {
        assertEquals(korean, TextDecoding.decode(korean.toByteArray(charset("x-windows-949"))))
    }

    @Test fun utf16WithBom() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + korean.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + korean.toByteArray(Charsets.UTF_16BE)
        assertEquals(korean, TextDecoding.decode(le))
        assertEquals(korean, TextDecoding.decode(be))
    }

    @Test fun binaryIsRefused() {
        assertNull(TextDecoding.decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0x14, 0)))
        // High bytes that read as neither UTF-8 nor CP949 text.
        val noise = ByteArray(400) { i -> if (i % 2 == 0) 0xFF.toByte() else 0x80.toByte() }
        assertNull(TextDecoding.decode(noise))
    }

    @Test fun characterCutByTheReadLimitIsFine() {
        val bytes = korean.toByteArray(Charsets.UTF_8)
        assertTrue(TextDecoding.decode(bytes.copyOf(bytes.size - 1))!!.startsWith("안녕하세요"))
    }

    @Test fun isTextByTypeOrExtension() {
        assertTrue(AttachmentStore.isText("text/plain", "a"))
        assertTrue(AttachmentStore.isText("application/json", "a"))
        assertTrue(AttachmentStore.isText("application/octet-stream", "자막.SRT"))
        assertFalse(AttachmentStore.isText("application/octet-stream", "photo.heic"))
        assertFalse(AttachmentStore.isText("application/zip", "a.zip"))
    }
}
