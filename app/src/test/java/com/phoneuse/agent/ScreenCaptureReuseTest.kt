package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureReuseTest {
    private fun screen(vararg labels: String, window: Int = 1) = ScreenSnapshot(
        "com.example", 900, 1600, labels.mapIndexed { index, label ->
            val top = 100 + 100 * index
            UiNode(
                windowId = window, path = listOf(index), className = "Button", text = label,
                contentDescription = null, hint = null, viewId = null,
                bounds = Bounds(20, top, 100, top + 40), clickable = true,
            )
        },
    )

    private val scroll = InputItem.FunctionCall("c1", "scroll", """{"direction":"down"}""")
    private val tapWhenReady = InputItem.FunctionCall(
        "c1", "batch", """{"actions":[{"tool":"tap","args":{"target":"Continue"}}]}""",
    )

    private fun FakeDeviceController.capturesAndActions() = calls.filter {
        it == "screenshot()" || it.startsWith("tap(") || it.startsWith("scroll(")
    }

    @Test fun unchangedTreeReusesItsCheckButAnotherWindowWithTheSameLabelGetsChecked() = runTest {
        for (images in listOf(true, false)) {
            val device = FakeDeviceController(listOf(screen("Panel")))
            val executor = ToolExecutor(device, FakeUserGate(), screenshots = images)
            val seen = executor.observe().screen
            device.calls.clear()

            val first = executor.execute(scroll, seen)
            assertEquals(listOf("scroll(screen,down)", "screenshot()"), device.capturesAndActions())
            assertEquals(images, first.screenshot != null)

            device.calls.clear()
            device.snapshots += screen("Panel", window = 2)
            val next = executor.execute(scroll, first.screen)
            assertEquals(listOf("screenshot()", "scroll(screen,down)", "screenshot()"), device.capturesAndActions())
            assertEquals(images, next.screenshot != null)
        }
    }

    @Test fun partialEmptyAndUnidentifiedTreesNeedAnotherCheckBeforeAnAction() = runTest {
        val panel = screen("Panel")
        for (tree in listOf(panel.copy(truncated = true), panel.copy(roots = emptyList()), panel.copy(packageName = null))) {
            val device = FakeDeviceController(listOf(tree))
            val executor = ToolExecutor(device, FakeUserGate())
            val seen = executor.observe().screen
            device.calls.clear()

            val result = executor.execute(scroll, seen)
            assertTrue(result.output, result.output.startsWith("OK"))
            assertEquals(listOf("screenshot()", "scroll(screen,down)", "screenshot()"), device.capturesAndActions())
        }
    }

    @Test fun waitingForALabelCapturesTheMatchAndTheResult() = runTest {
        val ready = screen("Continue")
        val delayed = listOf(screen("Home"), screen("Loading"), screen("Almost done"), ready)
        for (frames in listOf(delayed, listOf(ready))) {
            val device = FakeDeviceController(frames)
            val executor = ToolExecutor(device, FakeUserGate())
            val seen = executor.observe().screen
            device.calls.clear()

            val result = executor.execute(tapWhenReady, seen)
            assertTrue(result.output, result.output.startsWith("1. tap: OK"))
            assertEquals(listOf("screenshot()", "tap(60,120)", "screenshot()"), device.capturesAndActions())
        }
    }

    @Test fun aMissingLabelOnlyCapturesTheScreenReturnedAtTimeout() = runTest {
        for (images in listOf(true, false)) {
            val device = FakeDeviceController(listOf(screen("Loading")))
            val executor = ToolExecutor(device, FakeUserGate(), screenshots = images)
            val seen = executor.observe().screen
            device.calls.clear()

            val result = executor.execute(tapWhenReady, seen)
            assertTrue(result.output, result.output.contains("timed out waiting for \"Continue\""))
            assertTrue(result.output, result.output.contains("\"Loading\""))
            assertEquals(images, result.screenshot != null)
            assertEquals(listOf("screenshot()"), device.capturesAndActions())
        }
    }

    @Test fun aLabelFirstShownDuringTheFinalCaptureRetryKeepsTheTimeoutAndDoesNotTap() = runTest {
        val home = screen("Home").copy(packageName = "com.example.home")
        val device = FakeDeviceController(listOf(home, screen("Loading")))
        val executor = ToolExecutor(device, FakeUserGate())
        val seen = executor.observe().screen
        device.calls.clear()
        device.screenshotResult = null
        // The final observation retries in the newly opened app. The button appears during that retry.
        device.snapshotHook = { frame ->
            if ("screenshot()" in device.calls) {
                device.screenshotResult = byteArrayOf(1)
                screen("Continue")
            } else frame
        }

        val result = executor.execute(tapWhenReady, seen)
        assertTrue(result.output, result.output.startsWith("1. tap: STOPPED: timed out waiting for \"Continue\""))
        assertEquals("Continue", result.screen?.labels?.get(1))
        assertEquals(listOf("screenshot()", "screenshot()"), device.capturesAndActions())
    }

    @Test fun aFailedCaptureAtTimeoutWithholdsTheLabelDiagnosisAndKeepsCompletedSteps() = runTest {
        val batch = InputItem.FunctionCall("c1", "batch", """{"actions":[
            {"tool":"wait","args":{"ms":0}},
            {"tool":"tap","args":{"target":"Continue"}}
        ]}""")
        for (images in listOf(true, false)) {
            val device = FakeDeviceController(listOf(screen("Home"), screen("Loading")))
            val executor = ToolExecutor(device, FakeUserGate(), screenshots = images)
            val seen = executor.observe().screen
            device.calls.clear()
            device.screenshotResult = null

            val result = executor.execute(batch, seen)
            assertTrue(result.output, result.output.startsWith("1. wait: OK"))
            assertTrue(result.output, result.output.contains("get_screen"))
            assertFalse(result.output, result.output.contains("timed out waiting for"))
            assertFalse(result.output, result.output.contains("Continue"))
            assertFalse(result.output, result.output.contains("Loading"))
            assertTrue(requireNotNull(result.screen).captureBlocked)
            assertNull(result.screenshot)
            assertEquals(listOf("screenshot()"), device.capturesAndActions())
        }
    }

    @Test fun aLabelFoundBeforeACaptureRetryIsLocatedAgainOnTheVerifiedScreen() = runTest {
        val home = screen("Home").copy(packageName = "com.example.home")
        val firstMatch = screen("Continue")
        val moved = screen("Back", "Continue")
        val device = FakeDeviceController(listOf(home, firstMatch, moved))
        device.snapshotHook = { frame ->
            device.screenshotResult = if (frame === firstMatch) null else byteArrayOf(1)
            frame
        }
        val executor = ToolExecutor(device, FakeUserGate())
        val seen = executor.observe().screen
        val result = executor.execute(tapWhenReady, seen)
        assertTrue(result.output, result.output.startsWith("1. tap: OK"))
        assertEquals(listOf("tap(60,220)"), device.calls.filter { it.startsWith("tap(") })
    }

    @Test fun voiceDuringALabelWaitCancelsThePendingTap() = runTest {
        val ready = screen("Continue")
        val inner = FakeDeviceController(listOf(screen("Home"), screen("Loading"), ready))
        var sessions = 0
        val device = object : DeviceController by inner {
            override fun interruptions() = sessions
        }
        val executor = ToolExecutor(device, FakeUserGate())
        val seen = executor.observe().screen
        inner.snapshotHook = { frame ->
            if (frame === ready) sessions = 1
            frame
        }

        val result = executor.execute(tapWhenReady, seen)
        assertTrue(result.output, result.output.startsWith("1. tap: STOPPED: the user used voice input"))
        assertTrue(inner.calls.none { it.startsWith("tap(") })
    }
}
