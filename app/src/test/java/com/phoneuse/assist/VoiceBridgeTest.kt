package com.phoneuse.assist

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBridgeTest {
    @After fun clear() = VoiceBridge.deliver(null)

    @Test fun deliversOnceToTheWaitingRequest() {
        val got = ArrayList<String?>()
        VoiceBridge.request { got += it }
        assertTrue(VoiceBridge.listening.value)
        VoiceBridge.deliver("민수에게")
        VoiceBridge.deliver("again")
        assertEquals(listOf<String?>("민수에게"), got)
        assertFalse(VoiceBridge.listening.value)
    }

    @Test fun aNewRequestEndsTheOldOneWithNull() {
        val first = ArrayList<String?>()
        val second = ArrayList<String?>()
        VoiceBridge.request { first += it }
        VoiceBridge.request { second += it }
        VoiceBridge.deliver("네")
        assertEquals(listOf<String?>(null), first)
        assertEquals(listOf<String?>("네"), second)
    }

    @Test fun awaitIdleReturnsAtOnceWhenNotListening() = runTest {
        val start = testScheduler.currentTime
        assertFalse(VoiceBridge.awaitIdle())
        assertEquals(start, testScheduler.currentTime)
    }

    @Test fun deviceActionsWaitUntilVoiceReturns() = runTest {
        VoiceBridge.request { }
        val waiting = async { VoiceBridge.awaitIdle() }
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(waiting.isCompleted)
        VoiceBridge.deliver(null) // cancelled
        advanceTimeBy(VoiceBridge.SETTLE_MS + 1)
        runCurrent()
        assertTrue(waiting.isCompleted)
    }

    @Test fun aStuckRequestStopsBlockingAfterTheTimeout() = runTest {
        VoiceBridge.request { }
        val waiting = async { VoiceBridge.awaitIdle() }
        advanceTimeBy(VoiceBridge.MAX_WAIT_MS + VoiceBridge.SETTLE_MS + 1)
        runCurrent()
        assertTrue(waiting.isCompleted)
        assertTrue(waiting.await())
        // The timeout dropped the request, so later calls do not wait again.
        assertFalse(VoiceBridge.listening.value)
        val start = testScheduler.currentTime
        assertFalse(VoiceBridge.awaitIdle())
        assertEquals(start, testScheduler.currentTime)
    }

    @Test fun sessionsCountEveryRequest() {
        val before = VoiceBridge.sessions
        VoiceBridge.request { }
        VoiceBridge.deliver(null)
        assertEquals(before + 1, VoiceBridge.sessions)
    }
}
