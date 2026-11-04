package com.phoneuse.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleWaiterTest {
    private val policy = SettlePolicy()
    private val old = ScreenSample("old".hashCode(), busy = false)
    private val new = ScreenSample("new".hashCode(), busy = false)

    // ---- the pure decision --------------------------------------------------------------------

    @Test fun decideAfterActionWithoutEventsWaitsForFirstEventWindow() {
        assertNull(policy.decide(start = 0, now = 599, lastEvent = null, waitForChange = true))
        assertEquals(Settled.NO_CHANGE, policy.decide(0, 600, null, waitForChange = true))
    }

    @Test fun decidePlainReadWithoutEventsIsQuietAfterQuietMs() {
        assertNull(policy.decide(0, 149, null, waitForChange = false))
        assertEquals(Settled.QUIET, policy.decide(0, 150, null, waitForChange = false))
    }

    @Test fun decideNeedsQuietMsAfterLatestEvent() {
        assertNull(policy.decide(0, 400, lastEvent = 300, waitForChange = true))
        assertEquals(Settled.QUIET, policy.decide(0, 450, lastEvent = 300, waitForChange = true))
    }

    @Test fun decideHardCap() {
        assertEquals(Settled.CAP, policy.decide(0, 1500, lastEvent = 1490, waitForChange = true))
        assertEquals(Settled.CAP, policy.decide(0, 1500, lastEvent = 1490, waitForChange = false))
    }

    // ---- the wait, on a virtual clock ---------------------------------------------------------

    private fun TestScope.waiter() = IdleWaiter { currentTime }

    /** Content changes from a different view each time: the page really keeps changing. */
    private fun TestScope.changing(w: IdleWaiter, windowId: Int = 1) = launch {
        var i = 0
        while (true) {
            w.onEvent("com.app", IdleWaiter.TYPE_WINDOW_CONTENT_CHANGED, windowId, "View${i++}")
            delay(50)
        }
    }

    private fun TestScope.ticker(w: IdleWaiter) = launch {
        while (true) {
            w.onEvent("com.app", IdleWaiter.TYPE_WINDOW_CONTENT_CHANGED)
            delay(50)
        }
    }

    @Test fun actionWithoutVisibleEffectReturnsAtFirstEventWindow() = runTest {
        val w = waiter()
        assertEquals(Settled.NO_CHANGE, w.settle(policy, waitForChange = true))
        assertTrue("returned at $currentTime", currentTime in 600..650)
    }

    @Test fun plainReadWithoutEventsReturnsAfterQuietMs() = runTest {
        val w = waiter()
        assertEquals(Settled.QUIET, w.settle(policy, waitForChange = false))
        assertTrue("returned at $currentTime", currentTime in 150..200)
    }

    @Test fun eventThenSilenceReturnsQuietSoonAfter() = runTest {
        val w = waiter()
        launch {
            delay(40)
            w.onEvent()
            delay(60)
            w.onEvent()
        }
        val result = w.settle(policy, waitForChange = true, baseline = old.hash) { new }
        // A changed screen that holds still ends the wait (stable after two samples, or quiet at 250).
        assertTrue(result.toString(), result == Settled.STABLE || result == Settled.QUIET)
        assertTrue("returned at $currentTime", currentTime in 100..300)
    }

    @Test fun clickEventAloneIsNotAChange() = runTest {
        val w = waiter()
        launch {
            delay(20)
            w.onEvent("com.app", IdleWaiter.TYPE_VIEW_CLICKED)
            w.onEvent("com.app", IdleWaiter.TYPE_VIEW_FOCUSED)
        }
        // Without a content, window or scroll change the click does not start the quiet window.
        assertEquals(Settled.NO_CHANGE, w.settle(policy, waitForChange = true, baseline = old.hash) { old })
        assertTrue("returned at $currentTime", currentTime in 600..650)
    }

    @Test fun quietOnTheUnchangedScreenWaitsForTheFirstEventWindow() = runTest {
        val w = waiter()
        launch {
            delay(20)
            w.onEvent()
        }
        // One event, then silence, but the screen still equals the one the tap was aimed at: the transition may
        // not have started yet, so QUIET is not taken before 600 ms.
        val result = w.settle(policy, waitForChange = true, baseline = old.hash) { old }
        assertEquals(Settled.QUIET, result)
        assertTrue("returned at $currentTime", currentTime in 600..800)
    }

    @Test fun quietOnTheUnchangedScreenEndsAsSoonAsTheScreenChanges() = runTest {
        val w = waiter()
        launch {
            delay(20)
            w.onEvent()
        }
        val result = w.settle(policy, waitForChange = true, baseline = old.hash) { if (currentTime < 300) old else new }
        assertEquals(Settled.QUIET, result)
        assertTrue("returned at $currentTime", currentTime in 300..450)
    }

    @Test fun changesFromEverNewSourcesStopAtCapWithoutSampler() = runTest {
        val w = waiter()
        val t = changing(w)
        assertEquals(Settled.CAP, w.settle(policy, waitForChange = true))
        assertTrue("returned at $currentTime", currentTime in 1500..1550)
        t.cancel()
    }

    @Test fun oneSourceFiringWithoutBreakCountsAsAnimationAndStopsCounting() = runTest {
        val w = waiter()
        val t = ticker(w)
        // The same view firing every 50 ms is an animation once it has gone on for ANIMATION_STREAK_MS.
        assertEquals(Settled.QUIET, w.settle(policy, waitForChange = true))
        assertTrue("returned at $currentTime", currentTime in 900..1000)
        t.cancel()
    }

    @Test fun liveUpdatingAppWithStableTreeEndsEarly() = runTest {
        val w = waiter()
        val t = ticker(w)
        // The tree changes once (old screen -> new screen), then stays the same while the app keeps animating.
        val result = w.settle(policy, waitForChange = true, baseline = old.hash) {
            delay(20)
            if (currentTime < 100) ScreenSample(1, busy = false) else new
        }
        assertEquals(Settled.STABLE, result)
        assertTrue("returned at $currentTime", currentTime < 600)
        t.cancel()
    }

    @Test fun treeStillEqualToBaselineIsNotStableBeforeFirstEventWindow() = runTest {
        val w = waiter()
        val t = ticker(w)
        val result = w.settle(policy, waitForChange = true, baseline = old.hash) { old }
        assertEquals(Settled.STABLE, result)
        assertTrue("returned at $currentTime", currentTime in 600..800)
        t.cancel()
    }

    @Test fun loadingScreenIsNeitherStableNorQuietUntilTheCap() = runTest {
        val w = waiter()
        launch {
            delay(20)
            w.onEvent()
        }
        val loading = ScreenSample("spinner".hashCode(), busy = true)
        assertEquals(Settled.CAP, w.settle(policy, waitForChange = true, baseline = old.hash) { loading })
        assertTrue("returned at $currentTime", currentTime in 1500..1550)
    }

    @Test fun loadingThatFinishesEndsTheWait() = runTest {
        val w = waiter()
        launch {
            delay(20)
            w.onEvent()
            delay(600)
            w.onEvent()
        }
        val result = w.settle(policy, waitForChange = true, baseline = old.hash) {
            if (currentTime < 620) ScreenSample(7, busy = true) else new
        }
        assertTrue(result.toString(), result == Settled.QUIET || result == Settled.STABLE)
        assertTrue("returned at $currentTime", currentTime in 620..900)
    }

    @Test fun ownOverlayEventsAreIgnored() = runTest {
        val w = waiter()
        val t = launch {
            while (true) {
                w.onEvent("com.phoneuse")
                delay(50)
            }
        }
        assertEquals(Settled.NO_CHANGE, w.settle(policy, waitForChange = true))
        assertTrue("returned at $currentTime", currentTime in 600..650)
        t.cancel()
    }

    @Test fun speedSettingScalesEveryWait() {
        // Fast shortens only the caps; the quiet/stable gaps stay, so a moving screen is never taken as settled.
        val fast = policy.scaled(0.7)
        assertEquals(SettlePolicy(firstEventMs = 420, quietMs = 150, capMs = 1050, stableGapMs = 120, animatedCapMs = 490), fast)
        assertEquals(
            SettlePolicy(firstEventMs = 840, quietMs = 210, capMs = 2100, stableGapMs = 168, animatedCapMs = 980),
            policy.scaled(1.4),
        )
        assertEquals(policy, policy.scaled(1.0))
    }

    @Test fun slowSpeedWaitsLongerForAChange() = runTest {
        val w = waiter()
        assertEquals(Settled.NO_CHANGE, w.settle(policy.scaled(1.4), waitForChange = true))
        assertTrue("returned at $currentTime", currentTime in 840..890)
    }

    @Test fun eventsBeforeTheWaitDoNotCount() = runTest {
        val w = waiter()
        w.onEvent()
        delay(10)
        assertEquals(Settled.NO_CHANGE, w.settle(policy, waitForChange = true))
    }

    // ---- animated screens (maps, players, system UI) ------------------------------------------

    @Test fun systemWindowEventsDoNotCount() = runTest {
        val w = waiter()
        w.ignoredWindows = setOf(7)
        val t = changing(w, windowId = 7) // the status bar's clock and speed meter
        assertEquals(Settled.NO_CHANGE, w.settle(policy, waitForChange = true))
        assertTrue("returned at $currentTime", currentTime in 600..650)
        t.cancel()
    }

    @Test fun ownOverlayWindowChangesDoNotCount() = runTest {
        val w = waiter()
        launch {
            delay(20)
            w.ownWindowsChanged() // the blocker toggling its flags around a gesture
            delay(40)
            w.onEvent(null, IdleWaiter.TYPE_WINDOWS_CHANGED)
        }
        assertEquals(Settled.NO_CHANGE, w.settle(policy, waitForChange = true))
    }

    @Test fun windowsChangedLongAfterOurOwnChangeCounts() = runTest {
        val w = waiter()
        launch {
            delay(20)
            w.ownWindowsChanged()
            delay(300)
            w.onEvent(null, IdleWaiter.TYPE_WINDOWS_CHANGED)
        }
        assertEquals(Settled.QUIET, w.settle(policy, waitForChange = true))
    }

    @Test fun surfaceViewEventsDoNotCount() = runTest {
        val w = waiter()
        val t = launch {
            while (true) {
                w.onEvent("com.google.android.apps.maps", IdleWaiter.TYPE_WINDOW_CONTENT_CHANGED, 1, "android.view.TextureView")
                delay(50)
            }
        }
        assertEquals(Settled.NO_CHANGE, w.settle(policy, waitForChange = true))
        t.cancel()
    }

    @Test fun mapThatKeepsAnimatingSettlesSoonAfterTheRealChange() = runTest {
        val w = waiter()
        val t = ticker(w)
        delay(1000) // the map camera has been moving for a while
        launch {
            delay(20)
            w.onEvent("com.app", IdleWaiter.TYPE_WINDOW_STATE_CHANGED, 1, "android.widget.FrameLayout")
        }
        val start = currentTime
        val result = w.settle(policy, waitForChange = true, baseline = old.hash) { delay(40); new }
        assertTrue(result.toString(), result == Settled.QUIET || result == Settled.STABLE)
        assertTrue("took ${currentTime - start}", currentTime - start < 400)
        t.cancel()
    }

    @Test fun animatingScreenWhoseTextKeepsChangingEndsAtTheAnimatedCap() = runTest {
        val w = waiter()
        val anim = ticker(w)
        delay(1000)
        val real = changing(w) // a player's controls keep changing too
        var n = 0
        val start = currentTime
        val result = w.settle(policy, waitForChange = true) { ScreenSample(n++, busy = false) }
        assertEquals(Settled.ANIMATED, result)
        assertTrue("took ${currentTime - start}", currentTime - start in 700..800)
        anim.cancel()
        real.cancel()
    }

    @Test fun loadingScreenIsNotCutShortByTheAnimatedCap() = runTest {
        val w = waiter()
        val anim = ticker(w)
        delay(1000)
        val real = changing(w)
        val start = currentTime
        val loading = ScreenSample(5, busy = true)
        assertEquals(Settled.CAP, w.settle(policy, waitForChange = true) { loading })
        assertTrue("took ${currentTime - start}", currentTime - start in 1500..1550)
        anim.cancel()
        real.cancel()
    }

    @Test fun capIsADeadlineEvenWithSlowReads() = runTest {
        val w = waiter()
        val t = changing(w)
        var calls = 0
        val deadlines = ArrayList<Long>()
        val result = w.settle(policy, waitForChange = true) { deadline ->
            deadlines.add(deadline)
            calls++
            delay(400) // a slow tree read that ignores its deadline
            ScreenSample(calls, busy = false)
        }
        assertEquals(Settled.CAP, result)
        // No read starts that could not finish before the cap, so the wait does not overrun it.
        assertTrue("returned at $currentTime", currentTime in 1500..1530)
        assertTrue(deadlines.toString(), deadlines.all { it == 1500L })
        t.cancel()
    }

    @Test fun aLongFlingIsNeverTakenForAnAnimation() = runTest {
        val w = waiter()
        val t = launch {
            while (true) {
                w.onEvent("com.app", IdleWaiter.TYPE_VIEW_SCROLLED, 1, "androidx.recyclerview.widget.RecyclerView")
                delay(50)
            }
        }
        assertEquals(Settled.CAP, w.settle(policy, waitForChange = true))
        t.cancel()
    }

    @Test fun truncatedSamplesNeverCountAsStable() = runTest {
        val w = waiter()
        val t = changing(w)
        val cut = ScreenSample(0, busy = false, truncated = true)
        assertEquals(Settled.CAP, w.settle(policy, waitForChange = true) { cut })
        t.cancel()
    }

    @Test fun appOpeningSamplesOnlyAfterSampleFromMs() = runTest {
        val w = waiter()
        val t = changing(w)
        val app = SettlePolicy(firstEventMs = 1500, quietMs = 300, capMs = 3000, sampleFromMs = 1500)
        var first = Long.MAX_VALUE
        val result = w.settle(app, waitForChange = true) {
            first = minOf(first, currentTime)
            new
        }
        assertEquals(Settled.STABLE, result)
        assertTrue("first sample at $first", first >= 1500)
        assertTrue("returned at $currentTime", currentTime in 1500..1800)
        t.cancel()
    }
}
