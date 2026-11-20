package com.phoneuse.assist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceBridgeTest {
    @Before fun setMainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After fun clear() {
        VoiceBridge.activeSession.value?.let { VoiceBridge.deliver(it, null) }
        Dispatchers.resetMain()
    }

    @Test fun deliversOnceToTheWaitingRequest() {
        val got = ArrayList<String?>()
        val id = VoiceBridge.request { got += it }
        assertEquals(id, VoiceBridge.activeSession.value)
        VoiceBridge.update(id, "민수")
        VoiceBridge.deliver(id, "민수에게")
        VoiceBridge.deliver(id, "again")
        assertEquals(listOf<String?>("민수에게"), got)
        assertEquals(null, VoiceBridge.activeSession.value)
    }

    @Test fun replacingARequestKeepsItsLatestWordsAndIgnoresItsLateCallbacks() {
        val first = ArrayList<String?>()
        val second = ArrayList<String?>()
        val old = VoiceBridge.request { first += it }
        VoiceBridge.update(old, "earlier")
        VoiceBridge.update(old, "latest words")
        VoiceBridge.update(old, "")
        val id = VoiceBridge.request { second += it }
        VoiceBridge.update(old, "stale partial")
        VoiceBridge.interrupt(old)
        VoiceBridge.deliver(old, "stale final")
        assertEquals(id, VoiceBridge.activeSession.value)
        VoiceBridge.deliver(id, "네")
        assertEquals(listOf<String?>("latest words"), first)
        assertEquals(listOf<String?>("네"), second)
    }

    @Test fun explicitCancelDiscardsThePartialAndLateResults() {
        val got = ArrayList<String?>()
        val id = VoiceBridge.request { got += it }
        VoiceBridge.update(id, "discard")
        VoiceBridge.deliver(id, null)
        VoiceBridge.deliver(id, "late final")
        VoiceBridge.interrupt(id)
        assertEquals(listOf<String?>(null), got)
    }

    @Test fun awaitIdleReturnsAtOnceWhenNotListening() = runTest {
        val start = testScheduler.currentTime
        assertFalse(VoiceBridge.awaitIdle())
        assertEquals(start, testScheduler.currentTime)
    }

    @Test fun stoppingTheRunWhileWaitingForAnExternalRecognizerKeepsItsPartial() = runTest {
        val got = mutableListOf<String?>()
        val id = VoiceBridge.request { got += it }
        VoiceBridge.update(id, "add one more")
        VoiceBridge.setExternalUi(id, true)
        val runJob = Job()
        val cleanup = async { VoiceBridge.finishForRun(id, runJob) }
        runCurrent()
        assertFalse(cleanup.isCompleted)

        runJob.cancel()
        runCurrent()
        assertTrue(cleanup.isCompleted)
        cleanup.await()
        assertEquals(null, VoiceBridge.activeSession.value)
        VoiceBridge.deliver(id, "late result")
        assertEquals(listOf("add one more"), got)
    }

    @Test fun deviceActionsWaitUntilVoiceReturns() = runTest {
        val id = VoiceBridge.request { }
        val waiting = async { VoiceBridge.awaitIdle() }
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(waiting.isCompleted)
        VoiceBridge.deliver(id, null)
        advanceTimeBy(VoiceBridge.SETTLE_MS + 1)
        runCurrent()
        assertTrue(waiting.isCompleted)
    }

    @Test fun aStuckRequestReturnsItsPartialAndStopsBlockingAfterTheTimeout() = runTest {
        val got = ArrayList<String?>()
        val id = VoiceBridge.request { got += it }
        VoiceBridge.update(id, "keep this")
        val waiting = async { VoiceBridge.awaitIdle() }
        advanceTimeBy(VoiceBridge.MAX_WAIT_MS + VoiceBridge.SETTLE_MS + 1)
        runCurrent()
        assertTrue(waiting.isCompleted)
        assertTrue(waiting.await())
        // The timeout dropped the request, so later calls do not wait again.
        assertEquals(listOf("keep this"), got)
        assertEquals(null, VoiceBridge.activeSession.value)
        val start = testScheduler.currentTime
        assertFalse(VoiceBridge.awaitIdle())
        assertEquals(start, testScheduler.currentTime)
    }

    @Test fun eachSpeechActivityGetsItsOwnTimeout() = runTest {
        VoiceBridge.request { }
        val waiting = async { VoiceBridge.awaitIdle(1_000L) }
        advanceTimeBy(950L)
        val second = VoiceBridge.request { }
        advanceTimeBy(100L)
        assertEquals(second, VoiceBridge.activeSession.value)
        assertFalse(waiting.isCompleted)
        VoiceBridge.deliver(second, null)
        assertTrue(waiting.await())
    }

    @Test fun aLongDictationKeepsListeningAsTheTranscriptGrows() = runTest {
        val got = ArrayList<String?>()
        val id = VoiceBridge.request { got += it }
        val waiting = async { VoiceBridge.awaitIdle() }
        advanceTimeBy(50_000L)
        VoiceBridge.update(id, "first phrase")
        advanceTimeBy(50_000L)
        VoiceBridge.update(id, "first phrase and second phrase")
        advanceTimeBy(30_000L)
        assertEquals(id, VoiceBridge.activeSession.value)
        assertTrue(got.isEmpty())
        assertFalse(waiting.isCompleted)
        VoiceBridge.deliver(id, "complete instruction")
        assertTrue(waiting.await())
        assertEquals(listOf("complete instruction"), got)
    }

    @Test fun anExternalDialogCanReturnAFinalBeforeItsDeadline() = runTest {
        val got = ArrayList<String?>()
        val id = VoiceBridge.request { got += it }
        val waiting = async { VoiceBridge.awaitIdle() }
        advanceTimeBy(1_000)
        VoiceBridge.setExternalUi(id, true)
        advanceTimeBy(VoiceBridge.MAX_WAIT_MS - 1)
        assertFalse(waiting.isCompleted)
        VoiceBridge.setExternalUi(id, false)
        VoiceBridge.deliver(id, "complete external transcript")
        assertTrue(waiting.await())
        assertEquals(listOf("complete external transcript"), got)
    }

    @Test fun anAbandonedExternalDialogTimesOutAndItsLateResultIsIgnored() = runTest {
        val got = mutableListOf<String?>()
        val id = VoiceBridge.request { got += it }
        VoiceBridge.setExternalUi(id, true)
        val waiting = async { VoiceBridge.awaitIdle() }
        advanceTimeBy(VoiceBridge.MAX_WAIT_MS - 1)
        assertFalse(waiting.isCompleted)
        VoiceBridge.update(id, "last words")
        VoiceBridge.heardAudio(id)
        advanceTimeBy(VoiceBridge.SETTLE_MS + 2)
        assertTrue(waiting.isCompleted)
        assertTrue(waiting.await())
        VoiceBridge.deliver(id, "stale result")
        assertEquals(listOf("last words"), got)
        assertFalse(VoiceBridge.awaitIdle())
    }

    @Test fun endingARunBoundsTheWaitEvenIfTheMicKeepsReportingActivity() = runTest {
        val got = mutableListOf<String?>()
        val id = VoiceBridge.request { got += it }
        val runJob = Job()
        val finishing = async { VoiceBridge.finishForRun(id, runJob) }
        advanceTimeBy(VoiceBridge.MAX_WAIT_MS - 1)
        VoiceBridge.heardAudio(id)
        VoiceBridge.update(id, "keep these words")
        assertFalse(finishing.isCompleted)
        advanceTimeBy(2)
        assertTrue(finishing.isCompleted)
        finishing.await()
        VoiceBridge.deliver(id, "old final")
        assertEquals(listOf("keep these words"), got)
        assertEquals(null, VoiceBridge.activeSession.value)
        runJob.cancel()
    }

    @Test fun endingOneRunDoesNotWaitForOrInterruptAReplacementSession() = runTest {
        val id = VoiceBridge.request { }
        val runJob = Job()
        val finishing = async { VoiceBridge.finishForRun(id, runJob) }
        runCurrent()
        val next = VoiceBridge.request { }
        runCurrent()
        assertTrue(finishing.isCompleted)
        finishing.await()
        assertEquals(next, VoiceBridge.activeSession.value)
        runJob.cancel()
    }

    @Test fun cancellingAnExternalRequestReleasesTheWaitAndIgnoresALateResult() = runTest {
        val got = ArrayList<String?>()
        val id = VoiceBridge.request { got += it }
        VoiceBridge.setExternalUi(id, true)
        val waiting = async { VoiceBridge.awaitIdle() }
        advanceTimeBy(1_000)
        assertFalse(waiting.isCompleted)
        VoiceBridge.deliver(id, null)
        assertTrue(waiting.await())
        VoiceBridge.setExternalUi(id, false)
        VoiceBridge.deliver(id, "late external result")
        assertEquals(listOf<String?>(null), got)
    }

    @Test fun nativeListeningAfterPermissionWaitsWhileThereIsAudioThenTimesOutWhenStalled() = runTest {
        val got = ArrayList<String?>()
        val id = VoiceBridge.request { got += it }
        VoiceBridge.setExternalUi(id, true)
        val waiting = async { VoiceBridge.awaitIdle() }
        advanceTimeBy(1_000)
        VoiceBridge.setExternalUi(id, false)
        advanceTimeBy(50_000)
        VoiceBridge.heardAudio(id)
        advanceTimeBy(50_000)
        assertFalse(waiting.isCompleted)
        assertTrue(got.isEmpty())
        advanceTimeBy(VoiceBridge.MAX_WAIT_MS)
        assertTrue(waiting.await())
        assertEquals(listOf<String?>(null), got)
    }

    @Test fun sessionsCountEveryRequest() {
        val before = VoiceBridge.sessions
        val id = VoiceBridge.request { }
        VoiceBridge.deliver(id, null)
        assertEquals(before + 1, VoiceBridge.sessions)
    }
}
