package com.phoneuse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanPauseTest {
    private val now = 1_800_000_000_000L

    @Test fun holdsForAnHourFromTheLimit() {
        val until = PlanPause.until(now)
        assertEquals(now + 60 * 60_000L, until)
        assertTrue(PlanPause.holds(true, until, now))
        assertTrue(PlanPause.holds(true, until, until - 1))
        assertFalse(PlanPause.holds(true, until, until))
        assertFalse(PlanPause.holds(true, until, until + 1))
    }

    @Test fun neverSetOrClearedMeansNoPause() {
        assertFalse(PlanPause.holds(true, 0L, now))
    }

    @Test fun anotherProviderIsNotHeldBack() {
        assertFalse(PlanPause.holds(false, PlanPause.until(now), now))
    }

    @Test fun aClockMovedBackCannotStretchThePause() {
        val until = PlanPause.until(now)
        assertTrue(PlanPause.holds(true, until, until - PlanPause.PAUSE_MS))
        assertFalse(PlanPause.holds(true, until, until - PlanPause.PAUSE_MS - 1))
    }
}
