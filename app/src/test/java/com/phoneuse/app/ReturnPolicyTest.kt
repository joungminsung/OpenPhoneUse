package com.phoneuse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.phoneuse.routine.RoutineRun

class ReturnPolicyTest {
    @Test fun appRunThatUsedTheDeviceReturnsOnEveryEnding() {
        for (source in listOf(Source.APP, Source.CARD)) {
            for (outcome in RunOutcome.entries) {
                assertTrue("$source $outcome", ReturnPolicy.shouldReturn(source, usedDevice = true, stayInApp = false, outcome))
            }
        }
    }

    @Test fun runWithoutDeviceUseStays() {
        for (outcome in RunOutcome.entries) {
            assertFalse(ReturnPolicy.shouldReturn(Source.APP, usedDevice = false, stayInApp = false, outcome))
        }
    }

    @Test fun stayInAppKeepsAFinishedRunInTheOtherApp() {
        assertFalse(ReturnPolicy.shouldReturn(Source.APP, usedDevice = true, stayInApp = true, RunOutcome.FINISHED))
        assertFalse(ReturnPolicy.shouldReturn(Source.CARD, usedDevice = true, stayInApp = true, RunOutcome.FINISHED))
    }

    @Test fun stayInAppDoesNotApplyToFailures() {
        assertTrue(ReturnPolicy.shouldReturn(Source.APP, usedDevice = true, stayInApp = true, RunOutcome.FAILED))
        assertTrue(ReturnPolicy.shouldReturn(Source.APP, usedDevice = true, stayInApp = true, RunOutcome.CANCELLED))
    }

    @Test fun assistRunsNeverReturn() {
        for (outcome in RunOutcome.entries) {
            for (stay in listOf(true, false)) {
                assertFalse(ReturnPolicy.shouldReturn(Source.ASSIST, usedDevice = true, stayInApp = stay, outcome))
            }
        }
    }

    @Test fun routineRunsNeverReturn() {
        for (outcome in RunOutcome.entries) {
            for (stay in listOf(true, false)) {
                assertFalse(ReturnPolicy.shouldReturn(Source.ROUTINE, usedDevice = true, stayInApp = stay, outcome))
            }
        }
    }

    @Test fun onlyChatRunsTrustUserAuthorized() {
        assertTrue(Source.APP.trustsUserAuthorized(null))
        assertTrue(Source.ASSIST.trustsUserAuthorized(null))
        assertFalse(Source.CARD.trustsUserAuthorized(null))
        // A routine sends without the confirm card only when the user switched that on for it.
        assertFalse(Source.ROUTINE.trustsUserAuthorized(null))
        assertFalse(Source.ROUTINE.trustsUserAuthorized(RoutineRun(1, "t", trustSend = false, thirdParty = false)))
        assertTrue(Source.ROUTINE.trustsUserAuthorized(RoutineRun(1, "t", trustSend = true, thirdParty = false)))
        assertTrue(Source.ROUTINE.allowed(listOf()).isEmpty())
    }

    @Test fun fallbackOnlyWhenChatNeverResumedSinceLaunch() {
        // Never resumed since the launch at 1000: blocked.
        assertTrue(ReturnPolicy.needsFallback(launchedAt = 1000, lastResumedAt = 0))
        assertTrue(ReturnPolicy.needsFallback(launchedAt = 1000, lastResumedAt = 999))
        // Resumed after the launch, even if the user has left the chat again since: not blocked.
        assertFalse(ReturnPolicy.needsFallback(launchedAt = 1000, lastResumedAt = 1000))
        assertFalse(ReturnPolicy.needsFallback(launchedAt = 1000, lastResumedAt = 2500))
        assertEquals(listOf(1500L, 4000L), ReturnPolicy.FALLBACK_CHECKS_MS)
    }

    @Test fun fallbackTextUsesTheFirstLineShortened() {
        assertEquals("작업 끝 · 민수에게 보냈어요", ReturnPolicy.fallbackText("\n  민수에게 보냈어요\n자세한 내용"))
        assertEquals("작업 끝 · 완료", ReturnPolicy.fallbackText(null))
        assertEquals("작업 끝 · 완료", ReturnPolicy.fallbackText("  \n "))
        val long = ReturnPolicy.fallbackText("가".repeat(200))
        assertEquals("작업 끝 · " + "가".repeat(79) + "…", long)
    }
}
