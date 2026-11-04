package com.phoneuse.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CipherCodecTest {
    @Test
    fun roundTrips() {
        val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val ct = byteArrayOf(-1, 0, 127, -128, 55)
        val decoded = CipherCodec.decode(CipherCodec.encode(iv, ct))
        assertNotNull(decoded)
        assertArrayEquals(iv, decoded!!.first)
        assertArrayEquals(ct, decoded.second)
    }

    @Test
    fun malformedIsNull() {
        assertNull(CipherCodec.decode("garbage"))
        assertNull(CipherCodec.decode("v1:"))
        assertNull(CipherCodec.decode("v2:a:b"))
    }
}
