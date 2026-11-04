package com.phoneuse.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class StepTimingsTest {
    @Test fun formatsModelAndToolTimes() {
        val t = StepTimings(
            step = 3, modelMs = 2140, firstByteMs = 310, firstOutputMs = 1650, inputTokens = 8123, inputChars = 23000,
            tools = listOf("tap"), toolMs = 820, settleMs = 410, settle = "quiet", readMs = 95, screenChars = 3412,
        )
        assertEquals(
            "step=3 model=2140ms ttfb=310ms out=1650ms in=8123tok/23000ch | tap 820ms settle=410ms(quiet) read=95ms screen=3412ch",
            t.format(),
        )
    }

    @Test fun formatsAnswerOnlyStepWithoutToolPart() {
        val t = StepTimings(step = 1, modelMs = 900, inputChars = 5000)
        assertEquals("step=1 model=900ms in=5000ch", t.format())
    }

    @Test fun formatsSeveralToolsAndUnknownSettle() {
        val t = StepTimings(step = 2, modelMs = 1, tools = listOf("get_screen", "finish"), toolMs = 40, readMs = 30, screenChars = 10)
        assertEquals("step=2 model=1ms in=0ch | get_screen+finish 40ms settle=0ms read=30ms screen=10ch", t.format())
    }

    @Test fun toolClockAccumulatesAndResets() {
        val c = ToolClock()
        c.settled(200, "no_change")
        c.settled(100, null)
        c.read(30, 1200)
        c.read(20, 900)
        assertEquals(300, c.settleMs)
        assertEquals("no_change", c.settle)
        assertEquals(50, c.readMs)
        assertEquals(900, c.screenChars)
        c.reset()
        assertEquals(0, c.settleMs)
        assertEquals(null, c.settle)
        assertEquals(0, c.screenChars)
    }
}
