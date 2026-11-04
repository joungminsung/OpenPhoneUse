package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.risk.RiskCategory
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.TreeCompressor
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Risk context path (ux fix review #1, #2) and stale indices after dock voice input (#11). */
class ScreenSafetyTest {
    private fun node(t: String, y: Int, clickable: Boolean, window: Int = 0) = UiNode(
        windowId = window, path = listOf(y), className = if (clickable) "android.widget.Button" else "android.widget.TextView",
        text = t, contentDescription = null, hint = null, viewId = null, bounds = Bounds(100, y, 300, y + 60), clickable = clickable,
    )
    private fun button(t: String, y: Int) = node(t, y, clickable = true)
    private fun text(t: String, y: Int) = node(t, y, clickable = false)
    private fun call(name: String, args: String) = InputItem.FunctionCall("c1", name, args)

    /** Counts voice sessions; [voiceOnTap] starts one during every tap, [throwOnTap] makes the tap wait-and-fail. */
    private class VoiceDevice(val inner: FakeDeviceController) : DeviceController by inner {
        var sessions = 0
        var voiceOnTap = false
        var throwOnTap = false
        override fun interruptions(): Int = sessions
        override suspend fun tap(x: Int, y: Int): Boolean {
            if (throwOnTap) throw ScreenInterruptedException()
            val ok = inner.tap(x, y)
            if (voiceOnTap) sessions++
            return ok
        }
    }

    private fun setup(s: ScreenSnapshot): Triple<VoiceDevice, FakeUserGate, ToolExecutor> {
        val dev = VoiceDevice(FakeDeviceController(listOf(s)))
        val gate = FakeUserGate()
        return Triple(dev, gate, ToolExecutor(dev, gate))
    }

    @Test fun searchWordInDialogTextDoesNotExemptADelete() = runTest {
        val dialog = ScreenSnapshot("com.app", 1080, 2400, listOf(text("최근 검색어를 모두 삭제하시겠어요?", 100), button("취소", 200), button("확인", 300)))
        val (dev, gate, ex) = setup(dialog)
        gate.confirmResult = false
        val r = ex.execute(call("tap", """{"index":3}"""), TreeCompressor.compress(dialog))
        assertEquals("CANCELLED by user", r.output)
        assertEquals(RiskCategory.DELETE, gate.confirms.single().first)
        assertTrue(dev.inner.calls.none { it.startsWith("tap") })
    }

    @Test fun clearingTheSearchFieldItselfStillPasses() = runTest {
        val s = ScreenSnapshot("com.app", 1080, 2400, listOf(button("검색어 지우기", 100), button("B", 200)))
        val (_, gate, ex) = setup(s)
        ex.execute(call("tap", """{"index":1}"""), TreeCompressor.compress(s))
        assertTrue(gate.confirms.isEmpty())
    }

    @Test fun englishAllowUsesTheConsentText() = runTest {
        val s = ScreenSnapshot("com.app", 1080, 2400, listOf(text("Notes wants to access your Google Account", 100), button("Cancel", 200), button("Allow", 300)))
        val (_, gate, ex) = setup(s)
        gate.confirmResult = false
        ex.execute(call("tap", """{"index":3}"""), TreeCompressor.compress(s))
        assertEquals(RiskCategory.SECURITY, gate.confirms.single().first)
    }

    private val screen = ScreenSnapshot("com.app", 1080, 2400, listOf(button("A", 100), button("B", 200)))

    @Test fun voiceSinceTheLastReadStopsTheTapAndRereads() = runTest {
        val (dev, _, ex) = setup(screen)
        val seen = ex.observe().screen!!
        dev.sessions++ // the user used the dock's 🎤
        val r = ex.execute(call("tap", """{"index":1}"""), seen)
        assertTrue(r.output, r.output.startsWith("STOPPED: the user used voice input"))
        assertTrue(r.output.contains("# Screen"))
        assertTrue(dev.inner.calls.none { it.startsWith("tap") })
        // The fresh screen it returned is current again.
        val again = ex.execute(call("tap", """{"index":1}"""), r.screen)
        assertTrue(again.output, again.output.startsWith("OK"))
    }

    @Test fun anActionThatWaitedForVoiceIsReportedAsStopped() = runTest {
        val (dev, _, ex) = setup(screen)
        val seen = ex.observe().screen!!
        dev.throwOnTap = true
        val r = ex.execute(call("tap", """{"index":1}"""), seen)
        assertTrue(r.output, r.output.startsWith("STOPPED: the user used voice input"))
    }

    @Test fun batchStopsAtTheVoicePause() = runTest {
        val (dev, _, ex) = setup(screen)
        val seen = ex.observe().screen!!
        dev.voiceOnTap = true
        val r = ex.execute(
            call("batch", """{"actions":[{"tool":"tap","args":{"index":1}},{"tool":"tap","args":{"index":2}}]}"""),
            seen,
        )
        assertTrue(r.output, r.output.startsWith("1. tap: OK\n2. tap: STOPPED: the user used voice input"))
        assertEquals(1, dev.inner.calls.count { it.startsWith("tap") })
    }

    @Test fun batchStepThatWaitedForVoiceStops() = runTest {
        val (dev, _, ex) = setup(screen)
        val seen = ex.observe().screen!!
        dev.throwOnTap = true
        val r = ex.execute(call("batch", """{"actions":[{"tool":"tap","args":{"index":1}}]}"""), seen)
        assertTrue(r.output, r.output.startsWith("1. tap: STOPPED: the user used voice input"))
    }
}
