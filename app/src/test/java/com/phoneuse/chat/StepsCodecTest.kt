package com.phoneuse.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsCodecTest {
    @Test
    fun roundTrips() {
        val steps = listOf(StepLog("탭: 설정"), StepLog("say \"hi\", ok"))
        assertEquals(steps, StepsCodec.decode(StepsCodec.encode(steps)))
        assertEquals("[]", StepsCodec.encode(emptyList()))
    }

    @Test
    fun corruptValueReadsAsNoSteps() {
        assertTrue(StepsCodec.decode("{not json").isEmpty())
        assertTrue(StepsCodec.decode("{\"a\":1}").isEmpty())
        assertTrue(StepsCodec.decode("").isEmpty())
        assertTrue(StepsCodec.decode(null).isEmpty())
    }
}
