package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.risk.RiskCategory
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.CompressedScreen
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.TreeCompressor
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.phoneuse.files.ExifInfo
import com.phoneuse.files.ExifReader
import com.phoneuse.files.FileTools
import java.io.File

class ToolExecutorTest {
    private fun button(text: String, y: Int, password: Boolean = false, editable: Boolean = false) = UiNode(
        windowId = 0, path = listOf(y), className = "android.widget.Button", text = text,
        contentDescription = null, hint = null, viewId = null,
        bounds = Bounds(100, y, 300, y + 60), clickable = true, editable = editable, password = password,
    )

    private fun snap(vararg labels: String, pkg: String = "com.app") = ScreenSnapshot(
        pkg, 1080, 2400,
        labels.mapIndexed { i, l -> button(l, 100 + i * 100) },
    )

    private fun screenOf(s: ScreenSnapshot): CompressedScreen = TreeCompressor.compress(s)

    private fun call(name: String, args: String) = InputItem.FunctionCall("c1", name, args)

    private val base = snap("A", "B", "결제하기", "D")

    private fun setup(
        vararg snaps: ScreenSnapshot = arrayOf(base),
    ): Triple<FakeDeviceController, FakeUserGate, ToolExecutor> {
        val dev = FakeDeviceController(snaps.toList())
        val gate = FakeUserGate()
        return Triple(dev, gate, ToolExecutor(dev, gate))
    }

    @Test fun tapByIndexTapsCenterAndReturnsNewScreen() = runTest {
        val after = snap("X", "Y", "Z")
        // The live read before the tap still shows base; then the screen after the tap.
        val (dev, _, ex) = setup(base, after)
        val r = ex.execute(call("tap", """{"index":1}"""), screenOf(base))
        assertTrue(dev.calls.contains("tap(200,130)"))
        assertTrue(r.output.startsWith("OK\n\n# Screen\n"))
        assertTrue(r.output.contains("\"X\""))
        assertNotNull(r.screen)
        assertNotNull(r.screenshot)
    }

    @Test fun tapUnknownIndexErrorsWithoutTapping() = runTest {
        val (dev, _, ex) = setup()
        val r = ex.execute(call("tap", """{"index":9}"""), screenOf(base))
        assertEquals("ERROR: no element [9]", r.output)
        assertTrue(dev.calls.none { it.startsWith("tap") })
    }

    @Test fun riskyTapAsksAndCancels() = runTest {
        val (dev, gate, ex) = setup()
        gate.confirmResult = false
        val r = ex.execute(call("tap", """{"index":3}"""), screenOf(base))
        assertEquals("CANCELLED by user", r.output)
        assertTrue(dev.calls.none { it.startsWith("tap") })
        assertEquals(1, gate.confirms.size)
        assertEquals(RiskCategory.MONEY, gate.confirms[0].first)
        assertEquals("tap: 결제하기", gate.confirms[0].second)
    }

    @Test fun sendWithUserAuthorizedSkipsConfirm() = runTest {
        val sendScreen = snap("보내기")
        val (dev, gate, ex) = setup(sendScreen)
        ex.execute(call("tap", """{"index":1,"user_authorized":true}"""), screenOf(sendScreen))
        assertTrue(gate.confirms.isEmpty())
        assertTrue(dev.calls.any { it.startsWith("tap") })

        val r = ex.execute(call("tap", """{"index":1}"""), screenOf(sendScreen))
        assertEquals(1, gate.confirms.size)
        assertEquals(RiskCategory.SEND, gate.confirms[0].first)
        assertTrue(r.output.startsWith("OK"))
    }

    @Test fun sendConfirmCanBeDisabledButMoneyAndDeleteCannot() = runTest {
        val screen = snap("보내기", "결제하기", "삭제", "로그인")
        val dev = FakeDeviceController(listOf(screen))
        val gate = FakeUserGate()
        val ex = ToolExecutor(dev, gate, confirmSend = false)

        // A send the model wrote still asks with the switch off: only the user's own message can skip it.
        assertTrue(ex.execute(call("tap", """{"index":1}"""), screenOf(screen)).output.startsWith("OK"))
        assertEquals(RiskCategory.SEND, gate.confirms.single().first)
        gate.confirms.clear()
        val field = ScreenSnapshot("com.app", 1080, 2400, listOf(button("입력", 100, editable = true)))
        val typed = ToolExecutor(FakeDeviceController(listOf(field)), gate, confirmSend = false)
            .execute(call("type_text", """{"index":1,"text":"hi","submit":true}"""), screenOf(field))
        assertTrue(typed.output, typed.output.startsWith("OK"))
        assertEquals(RiskCategory.SEND, gate.confirms.single().first)
        gate.confirms.clear()

        // MONEY, DELETE and SECURITY still ask (the user's always-confirm scope).
        gate.confirmResult = false
        for (i in 2..4) {
            assertEquals("CANCELLED by user", ex.execute(call("tap", """{"index":$i}"""), screenOf(screen)).output)
        }
        assertEquals(listOf(RiskCategory.MONEY, RiskCategory.DELETE, RiskCategory.SECURITY), gate.confirms.map { it.first })

        // A run whose text the user did not write (a card button) still asks before sending.
        val cardGate = FakeUserGate().apply { confirmResult = false }
        val card = ToolExecutor(FakeDeviceController(listOf(screen)), cardGate, trustUserAuthorized = false, confirmSend = false)
        assertEquals("CANCELLED by user", card.execute(call("tap", """{"index":1}"""), screenOf(screen)).output)
        assertEquals(RiskCategory.SEND, cardGate.confirms.single().first)
    }

    @Test fun textOnlySettingKeepsScreenshotsOut() = runTest {
        val sparse = snap("A")
        val ex = ToolExecutor(FakeDeviceController(listOf(sparse, sparse)), FakeUserGate(), screenshots = false)
        assertNull(ex.observe().screenshot)
        assertTrue(ex.execute(call("screenshot", "{}"), null).output.startsWith("ERROR: screenshots are off"))
    }

    @Test fun suggestedRunIsTaintedFromItsFirstStep() = runTest {
        // A side-button suggestion's prompt was written from screen text. AgentRunner marks the run
        // third-party before its first step, so a search typed or a link opened from it runs under taint and a
        // later send still asks, with the "보내기 전 확인" switch off and the run otherwise user-trusted.
        val search = ScreenSnapshot(
            "com.app", 1080, 2400,
            listOf(button("", 100, editable = true).copy(hint = "검색"), button("보내기", 200)),
        )
        val dev = FakeDeviceController(List(6) { search })
        val gate = FakeUserGate().apply { confirmResult = false }
        val ex = ToolExecutor(dev, gate, confirmSend = false).also { it.markThirdParty() }
        assertTrue(ex.thirdPartySeen)

        val typed = ex.execute(call("type_text", """{"index":1,"text":"cats","submit":true}"""), screenOf(search))
        assertTrue(typed.output, typed.output.startsWith("OK"))
        val opened = ex.execute(call("open_uri", """{"uri":"https://example.com"}"""), screenOf(search))
        assertTrue(opened.output, opened.output.startsWith("OK"))
        assertTrue(ex.thirdPartySeen)

        assertEquals("CANCELLED by user", ex.execute(call("tap", """{"index":2}"""), screenOf(search)).output)
        assertEquals(RiskCategory.SEND, gate.confirms.single().first)
    }

    @Test fun historyTaintedRunIgnoresUserAuthorized() = runTest {
        val sendScreen = snap("보내기")
        val dev = FakeDeviceController(listOf(sendScreen))
        val gate = FakeUserGate().apply { confirmResult = false }
        val ex = ToolExecutor(dev, gate, historyTainted = true)
        val r = ex.execute(call("tap", """{"index":1,"user_authorized":true}"""), screenOf(sendScreen))
        assertEquals("CANCELLED by user", r.output)
        assertEquals(RiskCategory.SEND, gate.confirms.single().first)
        assertTrue(dev.calls.none { it.startsWith("tap") })
        assertTrue(ex.thirdPartySeen)
    }

    @Test fun cardOriginatedRunIgnoresUserAuthorized() = runTest {
        val sendScreen = snap("보내기")
        val dev = FakeDeviceController(listOf(sendScreen))
        val gate = FakeUserGate().apply { confirmResult = false }
        val ex = ToolExecutor(dev, gate, trustUserAuthorized = false)
        val r = ex.execute(call("tap", """{"index":1,"user_authorized":true}"""), screenOf(sendScreen))
        assertEquals("CANCELLED by user", r.output)
        assertEquals(RiskCategory.SEND, gate.confirms.single().first)
        assertTrue(dev.calls.none { it.startsWith("tap") })

        val field = ScreenSnapshot("com.app", 1080, 2400, listOf(button("입력", 100, editable = true)))
        val dev2 = FakeDeviceController(listOf(field))
        val gate2 = FakeUserGate().apply { confirmResult = false }
        val ex2 = ToolExecutor(dev2, gate2, trustUserAuthorized = false)
        val r2 = ex2.execute(
            call("type_text", """{"index":1,"text":"hi","submit":true,"user_authorized":true}"""),
            screenOf(field),
        )
        assertEquals("CANCELLED by user", r2.output)
        assertEquals(RiskCategory.SEND, gate2.confirms.single().first)
    }

    @Test fun passwordFieldRefused() = runTest {
        val pw = ScreenSnapshot("com.app", 1080, 2400, listOf(button("PW", 100, password = true, editable = true)))
        val (dev, _, ex) = setup(pw)
        val r = ex.execute(call("type_text", """{"index":1,"text":"secret"}"""), screenOf(pw))
        assertEquals("ERROR: password field — use hand_to_user", r.output)
        assertTrue(dev.calls.none { it.startsWith("setText") })
    }

    @Test fun openAppFreshStartsTheAppOver() = runTest {
        val (dev, _, ex) = setup()
        ex.execute(call("open_app", """{"package":"com.korail.talk","fresh":true}"""), screenOf(base))
        ex.execute(call("open_app", """{"package":"com.korail.talk"}"""), screenOf(base))
        assertEquals(listOf("launchApp(com.korail.talk, fresh)", "launchApp(com.korail.talk)"), dev.calls.filter { it.startsWith("launchApp") })
    }

    @Test fun openAppByName() = runTest {
        val (dev, _, ex) = setup()
        dev.apps = listOf(AppInfo("com.android.settings", "설정"), AppInfo("com.x", "Other"))
        val r = ex.execute(call("open_app", """{"name":"설정"}"""), screenOf(base))
        assertTrue(dev.calls.contains("launchApp(com.android.settings)"))
        assertTrue(r.output.startsWith("OK"))

        val missing = ex.execute(call("open_app", """{"name":"nothing"}"""), screenOf(base))
        assertEquals("ERROR: app not found: nothing", missing.output)
    }

    @Test fun batchRunsInOrderAndStopsOnError() = runTest {
        val (dev, _, ex) = setup()
        val ok = ex.execute(
            call("batch", """{"actions":[{"tool":"tap","args":{"index":1}},{"tool":"press_key","args":{"key":"back"}}]}"""),
            screenOf(base),
        )
        assertTrue(ok.output.startsWith("1. tap: OK\n2. press_key: OK\n\n# Screen\n"))
        assertTrue(dev.calls.indexOf("tap(200,130)") < dev.calls.indexOf("globalAction(BACK)"))

        dev.calls.clear()
        val err = ex.execute(
            call("batch", """{"actions":[{"tool":"tap","args":{"index":9}},{"tool":"press_key","args":{"key":"back"}}]}"""),
            screenOf(base),
        )
        assertTrue(err.output.startsWith("1. tap: ERROR: no element [9]"))
        assertFalse(err.output.contains("2. press_key"))
        assertTrue(dev.calls.none { it.startsWith("globalAction") })
    }

    @Test fun batchStopsWhenScreenChanges() = runTest {
        val changed = snap("A", "ZZZ", "결제하기", "D")
        val (dev, _, ex) = setup(changed)
        val r = ex.execute(
            call("batch", """{"actions":[{"tool":"tap","args":{"index":1}},{"tool":"tap","args":{"index":2}}]}"""),
            screenOf(base),
        )
        assertTrue(r.output.contains("2. tap: STOPPED: screen changed at step 2"))
        assertEquals(1, dev.calls.count { it.startsWith("tap") })
    }

    @Test fun aScreenshotInAnAppThatBlocksCaptureIsRefusedAsProtected() = runTest {
        val (dev, _, ex) = setup()
        dev.screenshotResult = null
        val r = ex.execute(call("screenshot", "{}"), screenOf(base))
        assertTrue(r.output, r.output.startsWith("STOPPED") && r.output.contains(ToolExecutor.PROTECTED))
        assertNull(r.screenshot)
    }

    @Test fun anAppThatBlocksCaptureIsNeitherReadNorTouched() = runTest {
        val (dev, _, ex) = setup()
        dev.screenshotResult = null
        val r = ex.execute(call("tap", """{"index":1}"""), screenOf(base))
        assertTrue(r.output, r.output.contains(ToolExecutor.PROTECTED))
        assertFalse(r.output, r.output.contains("[1]"))
        assertTrue(dev.calls.none { it.startsWith("tap") })
    }

    @Test fun leavingAProtectedAppStillWorksAndItIsCheckedOncePerRun() = runTest {
        val (dev, _, ex) = setup()
        dev.screenshotResult = null
        val first = ex.execute(call("press_key", """{"key":"back"}"""), screenOf(base))
        assertTrue(dev.calls.any { it.startsWith("globalAction") })
        assertTrue(first.output, first.output.contains(ToolExecutor.PROTECTED))
        val probes = dev.calls.count { it == "screenshot()" }
        assertEquals(2, probes) // one capture and one retry
        ex.execute(call("press_key", """{"key":"back"}"""), screenOf(base))
        assertEquals(probes, dev.calls.count { it == "screenshot()" })
    }

    @Test fun anAppThatAllowsCaptureIsOperatedAsUsual() = runTest {
        val (dev, _, ex) = setup()
        val r = ex.execute(call("tap", """{"index":1}"""), screenOf(base))
        assertFalse(r.output, r.output.contains(ToolExecutor.PROTECTED))
        assertTrue(dev.calls.any { it.startsWith("tap") })
    }

    @Test fun everyScreenComesWithAScreenshotTaggingWhatCanBeActedOn() = runTest {
        val (dev, _, ex) = setup(base)
        val r = ex.execute(call("press_key", """{"key":"home"}"""), screenOf(base))
        assertNotNull(r.screenshot)
        // One capture serves both the model and the protected-app check.
        assertEquals(1, dev.calls.count { it == "screenshot()" })
        assertEquals(listOf(1, 2, 3, 4), dev.marks.single().map { it.index })
        assertEquals(base.roots.first().bounds, dev.marks.single().first().bounds)

        // A screen with nothing to act on tags every listed element instead.
        val plain = ScreenSnapshot("com.app", 1080, 2400, listOf(base.roots.first().copy(clickable = false)))
        val (dev2, _, ex2) = setup(plain)
        ex2.execute(call("press_key", """{"key":"home"}"""), screenOf(base))
        assertEquals(listOf(1), dev2.marks.single().map { it.index })
    }

    @Test fun aProtectedAppIsNotCapturedAgainOnceKnown() = runTest {
        val (dev, _, ex) = setup()
        dev.screenshotResult = null
        ex.execute(call("press_key", """{"key":"back"}"""), screenOf(base))
        val shots = dev.calls.count { it == "screenshot()" }
        ex.execute(call("press_key", """{"key":"back"}"""), screenOf(base))
        assertEquals(shots, dev.calls.count { it == "screenshot()" })
    }

    @Test fun finishSetsSummary() = runTest {
        val (_, _, ex) = setup()
        val r = ex.execute(call("finish", """{"summary":"done it"}"""), screenOf(base))
        assertEquals("OK", r.output)
        assertEquals("done it", r.finishedSummary)
    }

    @Test fun specsHaveUniqueNamesAndObjectSchemas() {
        val (_, _, ex) = setup()
        val names = ex.specs.map { it.name }
        assertEquals(names.toSet().size, names.size)
        assertEquals(
            setOf(
                "tap", "long_press", "type_text", "scroll", "swipe", "pinch", "press_key", "open_app", "open_uri",
                "list_apps", "screenshot", "wait", "batch", "ask_user", "hand_to_user", "finish",
                "list_dir", "search_files", "file_info", "mkdir", "move_file", "copy_file", "rename_file",
                "trash_files", "get_screen", "show_card", "open_route", "delegate", "update_plan",
            ),
            names.toSet(),
        )
        ex.specs.forEach { assertEquals("\"object\"", it.parameters["type"].toString()) }
    }

    @Test fun serviceUnavailablePropagates() = runTest {
        val (dev, _, ex) = setup()
        dev.unavailable = true
        try {
            ex.execute(call("tap", """{"index":1}"""), screenOf(base))
            throw AssertionError("expected ServiceUnavailableException")
        } catch (_: ServiceUnavailableException) {
        }
    }

    @Test fun otherDeviceExceptionBecomesError() = runTest {
        val (dev, _, ex) = setup()
        dev.throwOnTap = IllegalStateException("boom")
        val r = ex.execute(call("tap", """{"index":1}"""), screenOf(base))
        assertEquals("ERROR: boom", r.output)
    }

    @Test fun badArgumentsAndUnknownTool() = runTest {
        val (_, _, ex) = setup()
        assertTrue(ex.execute(call("tap", "{}"), screenOf(base)).output.startsWith("ERROR: invalid arguments"))
        assertTrue(ex.execute(call("tap", "not json"), screenOf(base)).output.startsWith("ERROR: invalid arguments"))
        assertEquals("ERROR: unknown tool nope", ex.execute(call("nope", "{}"), screenOf(base)).output)
    }

    @Test fun askUserAndListApps() = runTest {
        val (dev, gate, ex) = setup()
        gate.askResult = "yes"
        dev.apps = listOf(AppInfo("com.android.settings", "설정"), AppInfo("com.x", "Chrome"))
        assertEquals(
            "User answered: yes",
            ex.execute(call("ask_user", """{"question":"q","options":["yes","no"]}"""), screenOf(base)).output,
        )
        assertEquals(
            "Chrome (com.x)",
            ex.execute(call("list_apps", """{"query":"chro"}"""), screenOf(base)).output,
        )
    }

    @Test fun moneyTapStillConfirmsWhenUserAuthorized() = runTest {
        val (_, gate, ex) = setup()
        gate.confirmResult = false
        val r = ex.execute(call("tap", """{"index":3,"user_authorized":true}"""), screenOf(base))
        assertEquals("CANCELLED by user", r.output)
        assertEquals(1, gate.confirms.size)
    }

    @Test fun riskGateInsideBatchCancelsAndStops() = runTest {
        val (dev, gate, ex) = setup()
        gate.confirmResult = false
        val r = ex.execute(
            call("batch", """{"actions":[{"tool":"tap","args":{"index":3}},{"tool":"press_key","args":{"key":"back"}}]}"""),
            screenOf(base),
        )
        assertTrue(r.output.startsWith("1. tap: CANCELLED by user"))
        assertEquals(1, gate.confirms.size)
        assertTrue(dev.calls.none { it.startsWith("tap") || it.startsWith("globalAction") })
    }

    @Test fun riskGateOnTypeText() = runTest {
        val field = ScreenSnapshot("com.app", 1080, 2400, listOf(button("송금 금액", 100, editable = true)))
        val (dev, gate, ex) = setup(field)
        gate.confirmResult = false
        val r = ex.execute(call("type_text", """{"index":1,"text":"1000"}"""), screenOf(field))
        assertEquals("CANCELLED by user", r.output)
        assertEquals(RiskCategory.MONEY, gate.confirms[0].first)
        assertTrue(dev.calls.none { it.startsWith("setText") })
    }

    @Test fun riskGateOnCoordinateTap() = runTest {
        val (dev, gate, ex) = setup()
        gate.confirmResult = false
        val r = ex.execute(call("tap", """{"x":200,"y":330}"""), screenOf(base))
        assertEquals("CANCELLED by user", r.output)
        assertEquals("tap: 결제하기", gate.confirms[0].second)
        assertTrue(dev.calls.none { it.startsWith("tap") })
    }

    @Test fun batchStaleCoordinatesAreCheckedAgainstRefreshedScreen() = runTest {
        // Planned on `base` where (200,330) is "C"; after step 1 that point is "결제하기".
        val planned = snap("A", "B", "C", "D")
        val after = snap("A", "B", "결제하기", "D")
        val (dev, gate, ex) = setup(after)
        gate.confirmResult = false
        val r = ex.execute(
            call("batch", """{"actions":[{"tool":"press_key","args":{"key":"back"}},{"tool":"tap","args":{"x":200,"y":330}}]}"""),
            screenOf(planned),
        )
        assertTrue(r.output.contains("2. tap: CANCELLED by user"))
        assertEquals("tap: 결제하기", gate.confirms[0].second)
        assertTrue(dev.calls.none { it.startsWith("tap") })
    }

    @Test fun unlabeledConfirmSummaryHasNoNull() = runTest {
        val s = ScreenSnapshot("com.android.packageinstaller", 1080, 2400, listOf(button("", 100)))
        val (_, gate, ex) = setup(s)
        gate.confirmResult = false
        ex.execute(call("tap", """{"index":1}"""), screenOf(s))
        assertTrue(gate.confirms.isNotEmpty())
        assertFalse(gate.confirms[0].second.contains("null"))
    }

    @Test fun observeWaitsForIdleFirst() = runTest {
        val (dev, _, ex) = setup()
        ex.observe()
        assertEquals("waitForIdle()", dev.calls.first())
    }

    @Test fun typeTextOnNonEditableErrorsWithoutDeviceCall() = runTest {
        val (dev, _, ex) = setup()
        val r = ex.execute(call("type_text", """{"index":1,"text":"hi"}"""), screenOf(base))
        assertEquals("ERROR: element [1] is not editable", r.output)
        assertTrue(dev.calls.none { it.startsWith("setText") || it.startsWith("tap") })
    }

    @Test fun shortSwipeIsGatedLikeATap() = runTest {
        val (dev, gate, ex) = setup()
        gate.confirmResult = false
        val r = ex.execute(call("swipe", """{"x1":200,"y1":330,"x2":210,"y2":340}"""), screenOf(base))
        assertEquals("CANCELLED by user", r.output)
        assertEquals(RiskCategory.MONEY, gate.confirms[0].first)
        assertEquals("swipe: 결제하기", gate.confirms[0].second)
        assertTrue(dev.calls.none { it.startsWith("swipe") })

        // A long swipe from a harmless element goes; one from the pay button is judged too.
        val long = ex.execute(call("swipe", """{"x1":200,"y1":130,"x2":200,"y2":1330}"""), screenOf(base))
        assertTrue(long.output.startsWith("OK"))
        assertEquals(1, gate.confirms.size)
        val slide = ex.execute(call("swipe", """{"x1":200,"y1":330,"x2":900,"y2":330}"""), screenOf(base))
        assertEquals("CANCELLED by user", slide.output)
        assertEquals(2, gate.confirms.size)
    }

    @Test fun pinchZoomsInOrOutAroundThePoint() = runTest {
        val (dev, gate, ex) = setup()
        assertTrue(ex.execute(call("pinch", """{"x":540,"y":900,"zoom":"in"}"""), screenOf(base)).output.startsWith("OK"))
        assertTrue(ex.execute(call("pinch", """{"x":540,"y":900,"zoom":"out"}"""), screenOf(base)).output.startsWith("OK"))
        assertEquals(listOf("pinch(540,900,in)", "pinch(540,900,out)"), dev.calls.filter { it.startsWith("pinch") })
        assertTrue(gate.confirms.isEmpty())
        assertEquals("ERROR: invalid arguments: zoom must be in or out", ex.execute(call("pinch", """{"x":540,"y":900,"zoom":"up"}"""), screenOf(base)).output)
    }

    @Test fun shortSwipeInsideBatchIsGated() = runTest {
        val (dev, gate, ex) = setup()
        gate.confirmResult = false
        val r = ex.execute(
            call("batch", """{"actions":[{"tool":"swipe","args":{"x1":200,"y1":330,"x2":200,"y2":340}}]}"""),
            screenOf(base),
        )
        assertTrue(r.output.startsWith("1. swipe: CANCELLED by user"))
        assertEquals(RiskCategory.MONEY, gate.confirms[0].first)
        assertTrue(dev.calls.none { it.startsWith("swipe") })
    }

    @Test fun longLabelEndingInPayStillConfirms() = runTest {
        val s = snap("가".repeat(116) + "결제하기")
        val (dev, gate, ex) = setup(s)
        gate.confirmResult = false
        val r = ex.execute(call("tap", """{"index":1}"""), screenOf(s))
        assertEquals("CANCELLED by user", r.output)
        assertEquals(RiskCategory.MONEY, gate.confirms[0].first)
        assertTrue(dev.calls.none { it.startsWith("tap") })
    }

    private fun text(t: String, y: Int, window: Int = 0) = UiNode(
        windowId = window, path = listOf(y), className = "android.widget.TextView", text = t,
        contentDescription = null, hint = null, viewId = null, bounds = Bounds(100, y, 300, y + 60),
    )

    @Test fun tapCenterIsClippedToDisplay() = runTest {
        val partly = ScreenSnapshot(
            "com.app", 1080, 2400,
            listOf(button("A", 100), button("B", 200), button("C", 300).copy(bounds = Bounds(-200, 2300, 400, 2700))),
        )
        val (dev, _, ex) = setup(partly)
        ex.execute(call("tap", """{"index":3}"""), screenOf(partly))
        assertTrue(dev.calls.toString(), dev.calls.contains("tap(200,2350)"))
    }

    private fun at(label: String, path: Int, y: Int, editable: Boolean = false, clickable: Boolean = true) = UiNode(
        windowId = 0, path = listOf(path), className = "android.widget.Button", text = label,
        contentDescription = null, hint = null, viewId = null,
        bounds = Bounds(100, y, 300, y + 60), clickable = clickable, editable = editable,
    )

    @Test fun batchFollowsElementWhoseIndexShifted() = runTest {
        // The keyboard opens after typing and the app adds a suggestion row above everything.
        val planned = ScreenSnapshot("com.app", 1080, 2400, listOf(at("검색", 1, 100, editable = true), at("A", 2, 200), at("B", 3, 300)))
        val shifted = ScreenSnapshot(
            "com.app", 1080, 2400,
            listOf(at("추천 검색어", 0, 50, clickable = false), at("검색", 1, 150, editable = true), at("A", 2, 250), at("B", 3, 350)),
            keyboardVisible = true,
        )
        val (dev, _, ex) = setup(shifted)
        val r = ex.execute(
            call("batch", """{"actions":[{"tool":"type_text","args":{"index":1,"text":"x"}},{"tool":"tap","args":{"index":3}}]}"""),
            screenOf(planned),
        )
        assertTrue(r.output, r.output.startsWith("1. type_text: OK\n2. tap: OK"))
        assertTrue(dev.calls.toString(), dev.calls.contains("tap(200,380)")) // B's new centre, not A's
    }

    @Test fun batchStopsWhenElementAtPathHasNewLabel() = runTest {
        val planned = ScreenSnapshot("com.app", 1080, 2400, listOf(at("A", 1, 100), at("B", 2, 200)))
        val replaced = ScreenSnapshot("com.app", 1080, 2400, listOf(at("A", 1, 100), at("삭제", 2, 200)))
        val (dev, _, ex) = setup(replaced)
        val r = ex.execute(
            call("batch", """{"actions":[{"tool":"tap","args":{"index":1}},{"tool":"tap","args":{"index":2}}]}"""),
            screenOf(planned),
        )
        assertTrue(r.output.contains("2. tap: STOPPED: screen changed at step 2"))
        assertEquals(1, dev.calls.count { it.startsWith("tap") })
    }

    @Test fun genericConfirmUsesDialogContext() = runTest {
        val dialog = ScreenSnapshot(
            "com.gallery", 1080, 2400,
            listOf(
                text("사진을 삭제할까요?", 100),
                button("취소", 200),
                button("확인", 300),
                text("결제 내역", 400, window = 7), // another window: not part of the dialog context
            ),
        )
        val (dev, gate, ex) = setup(dialog)
        gate.confirmResult = false
        val r = ex.execute(call("tap", """{"index":3}"""), screenOf(dialog))
        assertEquals("CANCELLED by user", r.output)
        assertEquals(RiskCategory.DELETE, gate.confirms[0].first)
        assertEquals("tap: 확인 — \"사진을 삭제할까요?\"", gate.confirms[0].second)
        assertTrue(dev.calls.none { it.startsWith("tap") })

        val plain = ScreenSnapshot("com.app", 1080, 2400, listOf(text("설정이 저장됩니다", 100), button(" OK ", 200)))
        val (dev2, gate2, ex2) = setup(plain)
        val r2 = ex2.execute(call("tap", """{"index":2}"""), screenOf(plain))
        assertTrue(r2.output.startsWith("OK"))
        assertTrue(gate2.confirms.isEmpty())
        assertTrue(dev2.calls.any { it.startsWith("tap") })
    }

    // ---- Task 5 -------------------------------------------------------------------------------

    @get:Rule val tmp = TemporaryFolder()

    private fun fileTools() = FileTools(tmp.root, object : ExifReader {
        override fun read(file: File): ExifInfo? = null
    })

    private fun withFiles(vararg snaps: ScreenSnapshot = arrayOf(base)): Triple<FakeDeviceController, FakeUserGate, ToolExecutor> {
        val dev = FakeDeviceController(snaps.toList())
        val gate = FakeUserGate()
        return Triple(dev, gate, ToolExecutor(dev, gate, fileTools()))
    }

    @Test fun trashAsksDeleteConfirmation() = runTest {
        val f = tmp.newFile("a.txt")
        val (_, gate, ex) = withFiles()
        val r = ex.execute(call("trash_files", """{"paths":["a.txt"]}"""), screenOf(base))
        assertEquals(1, gate.confirms.size)
        assertEquals(RiskCategory.DELETE, gate.confirms[0].first)
        assertEquals("휴지통으로 이동: a.txt", gate.confirms[0].second)
        assertFalse(r.output, r.output.startsWith("ERROR"))
        assertFalse(f.exists())
        assertFalse(r.deviceTool)
    }

    @Test fun trashSummaryLimitsToFivePaths() = runTest {
        val names = (1..7).map { "f$it.txt".also { n -> tmp.newFile(n) } }
        val (_, gate, ex) = withFiles()
        val arr = names.joinToString(",") { "\"$it\"" }
        ex.execute(call("trash_files", """{"paths":[$arr]}"""), screenOf(base))
        assertEquals("휴지통으로 이동: f1.txt, f2.txt, f3.txt, f4.txt, f5.txt(외 2개)", gate.confirms[0].second)
    }

    @Test fun trashDeclinedDoesNothing() = runTest {
        val f = tmp.newFile("a.txt")
        val (_, gate, ex) = withFiles()
        gate.confirmResult = false
        val r = ex.execute(call("trash_files", """{"paths":["a.txt"]}"""), screenOf(base))
        assertEquals("DECLINED by user", r.output)
        assertTrue(f.exists())
    }

    private val field = ScreenSnapshot("com.app", 1080, 2400, listOf(button("메시지 입력", 100, editable = true)))
    private fun fieldWith(text: String = "", hint: String? = null, viewId: String? = null) = ScreenSnapshot(
        "com.app", 1080, 2400,
        listOf(button(text, 100, editable = true).copy(hint = hint, viewId = viewId)),
    )

    private val searchField = fieldWith(hint = "검색")

    @Test fun aBatchsFirstLabelStepActsOnTheLiveScreen() = runTest {
        val seen = snap("A", "Buy")
        // Since the model read it, a banner pushed the button down.
        val live = ScreenSnapshot("com.app", 1080, 2400, listOf(button("Banner", 100), button("A", 200), button("Buy", 500)))
        val (dev, _, ex) = setup(live)
        val r = ex.execute(call("batch", """{"actions":[{"tool":"tap","args":{"target":"Buy"}}]}"""), screenOf(seen))
        assertTrue(r.output, r.output.startsWith("1. tap: OK"))
        assertTrue(dev.calls.toString(), dev.calls.contains("tap(200,530)"))
    }

    @Test fun typingIntoASendLabelledFieldShowsTheTextOnTheCard() = runTest {
        val composer = fieldWith(hint = "Send a message")
        val (dev, gate, ex) = setup(composer)
        gate.confirmResult = false
        val r = ex.execute(call("type_text", """{"index":1,"text":"내일 3시에 봬요","submit":true}"""), screenOf(composer))
        assertEquals("CANCELLED by user", r.output)
        val (category, summary) = gate.confirms.single()
        assertEquals(RiskCategory.SEND, category)
        assertTrue(summary, summary.contains("내일 3시에 봬요"))
        assertTrue(dev.calls.none { it.startsWith("setText") })
    }

    @Test fun aFieldNamedAsAPasswordIsRefusedEvenWhenNotFlagged() = runTest {
        for ((hint, viewId) in listOf("비밀번호" to null, null to "com.bank:id/pin_input", "Password" to null, "암호 입력" to null)) {
            val f = fieldWith(hint = hint, viewId = viewId)
            val (dev, _, ex) = setup(f)
            val r = ex.execute(call("type_text", """{"index":1,"text":"1234"}"""), screenOf(f))
            assertTrue("$hint $viewId: ${r.output}", r.output.startsWith("ERROR: password field"))
            assertTrue(dev.calls.none { it.startsWith("setText") })
        }
        // Not a password field: a crypto search box, a shopping field.
        for (hint in listOf("암호화폐 검색", "Shopping list")) {
            val f = fieldWith(hint = hint)
            val (_, _, ex) = setup(f)
            val r = ex.execute(call("type_text", """{"index":1,"text":"btc"}"""), screenOf(f))
            assertTrue("$hint: ${r.output}", r.output.startsWith("OK"))
        }
    }

    @Test fun afterThirdPartyTextUserAuthorizedCountsOnlyForTheUsersOwnWords() = runTest {
        fun chat(draft: String) = ScreenSnapshot(
            "com.app", 1080, 2400, listOf(button(draft, 1800, editable = true), button("보내기", 1900)),
        )
        // The draft is what the user said: no card, even after reading the chat.
        val ok = chat("늦어 미안")
        val (dev, gate, ex) = setup(ok)
        ex.userSaid("엄마한테 \"늦어  미안\" 이라고 보내줘")
        ex.markThirdParty()
        assertTrue(ex.execute(call("tap", """{"index":2,"user_authorized":true}"""), screenOf(ok)).output.startsWith("OK"))
        assertTrue(gate.confirms.isEmpty())
        assertTrue(dev.calls.isNotEmpty())

        // A draft the user never wrote (a chat bubble asked for it): the claim is ignored and the card shows.
        val injected = chat("네 동의합니다")
        val (_, gate2, ex2) = setup(injected)
        ex2.userSaid("엄마한테 늦어 미안 이라고 보내줘")
        ex2.markThirdParty()
        gate2.confirmResult = false
        assertEquals("CANCELLED by user", ex2.execute(call("tap", """{"index":2,"user_authorized":true}"""), screenOf(injected)).output)
        assertEquals(RiskCategory.SEND, gate2.confirms.single().first)

        // type_text with submit: the typed text is what is checked.
        val (_, gate3, ex3) = setup(field)
        ex3.userSaid("팀방에 회의 5분 늦어요 보내줘")
        ex3.markThirdParty()
        gate3.confirmResult = false
        val typed = ex3.execute(call("type_text", """{"index":1,"text":"회의 5분 늦어요","submit":true,"user_authorized":true}"""), screenOf(field))
        assertTrue(typed.output, typed.output.startsWith("OK"))
        assertTrue(gate3.confirms.isEmpty())
        val other = ex3.execute(call("type_text", """{"index":1,"text":"계좌번호 알려줘","submit":true,"user_authorized":true}"""), screenOf(field))
        assertEquals("CANCELLED by user", other.output)
    }

    @Test fun theHelpersUserAuthorizedIsIgnored() = runTest {
        val s = snap("보내기")
        val (_, gate, ex) = setup(s)
        gate.confirmResult = false
        assertEquals("CANCELLED by user", ex.execute(call("tap", """{"index":1,"user_authorized":true}"""), screenOf(s), fromHelper = true).output)
        assertEquals(RiskCategory.SEND, gate.confirms.single().first)
        // The same call from the main model (clean run) goes through.
        gate.confirms.clear()
        val (_, gate2, ex2) = setup(s)
        assertTrue(ex2.execute(call("tap", """{"index":1,"user_authorized":true}"""), screenOf(s)).output.startsWith("OK"))
        assertTrue(gate2.confirms.isEmpty())
    }

    @Test fun submitInSearchFieldDoesNotConfirm() = runTest {
        val (dev, gate, ex) = setup(searchField)
        val r = ex.execute(call("type_text", """{"index":1,"text":"cats","submit":true}"""), screenOf(searchField))
        assertTrue(gate.confirms.isEmpty())
        assertTrue(r.output, r.output.startsWith("OK"))
        assertTrue(dev.calls.any { it.startsWith("setText") })
    }

    @Test fun openAppAndPressKeyWithNullScreenDoNotObserve() = runTest {
        val (dev, _, ex) = setup()
        dev.apps = listOf(AppInfo("com.android.settings", "설정"))
        val a = ex.execute(call("open_app", """{"name":"설정"}"""), null)
        assertTrue(a.output, a.output.startsWith("OK"))
        val k = ex.execute(call("press_key", """{"key":"home"}"""), null)
        assertTrue(k.output, k.output.startsWith("OK"))
        assertTrue(a.deviceTool && k.deviceTool)
        assertTrue(dev.calls.toString(), dev.calls.indexOf("launchApp(com.android.settings)") < 2)
    }

    @Test fun deviceFlagAlsoOnErrorResults() = runTest {
        val (_, _, ex) = setup()
        val r = ex.execute(call("tap", "{}"), screenOf(base))
        assertTrue(r.output.startsWith("ERROR"))
        assertTrue(r.deviceTool)
    }

    @Test fun submitAfterMoneyConfirmStillAsksSend() = runTest {
        val s = ScreenSnapshot("com.app", 1080, 2400, listOf(button("송금 금액", 100, editable = true)))
        val (_, gate, ex) = setup(s)
        ex.execute(call("type_text", """{"index":1,"text":"1","submit":true}"""), screenOf(s))
        assertEquals(listOf(RiskCategory.MONEY, RiskCategory.SEND), gate.confirms.map { it.first })
    }

    @Test fun submitAfterSendConfirmAsksOnce() = runTest {
        val s = ScreenSnapshot("com.app", 1080, 2400, listOf(button("보내기", 100, editable = true)))
        val (_, gate, ex) = setup(s)
        ex.execute(call("type_text", """{"index":1,"text":"1","submit":true}"""), screenOf(s))
        assertEquals(listOf(RiskCategory.SEND), gate.confirms.map { it.first })
    }

    @Test fun emailAddressFieldIsNotExempt() = runTest {
        val s = fieldWith(hint = "받는 사람 이메일 주소")
        val (_, gate, ex) = setup(s)
        ex.execute(call("type_text", """{"index":1,"text":"a@b.c","submit":true}"""), screenOf(s))
        assertEquals(1, gate.confirms.size)
    }

    @Test fun fieldContentContainingSearchWordIsNotExempt() = runTest {
        val s = fieldWith(text = "검색 결과 공유", hint = "메시지 입력")
        val (_, gate, ex) = setup(s)
        ex.execute(call("type_text", """{"index":1,"text":"x","submit":true}"""), screenOf(s))
        assertEquals(1, gate.confirms.size)
    }

    @Test fun browserUrlBarIsExempt() = runTest {
        val s = fieldWith(viewId = "com.android.chrome:id/url_bar")
        val (_, gate, ex) = setup(s)
        ex.execute(call("type_text", """{"index":1,"text":"x.com","submit":true}"""), screenOf(s))
        assertTrue(gate.confirms.isEmpty())
    }

    @Test fun confirmTextCollapsesWhitespaceAndKeepsEmojiWhole() = runTest {
        val (_, gate, ex) = setup(field)
        gate.confirmResult = false
        val t = "😀".repeat(59) + "\n\n  a" + "b".repeat(20)
        ex.execute(call("type_text", """{"index":1,"text":${kotlinx.serialization.json.JsonPrimitive(t)},"submit":true}"""), screenOf(field))
        assertEquals("입력 후 전송: \"${"😀".repeat(59)}\"", gate.confirms[0].second)
    }

    @Test fun trashNullEntryIsRejectedAndLongPathKeepsTail() = runTest {
        val (_, gate, ex) = withFiles()
        val bad = ex.execute(call("trash_files", """{"paths":["a",null]}"""), null)
        assertTrue(bad.output, bad.output.startsWith("ERROR"))
        assertTrue(gate.confirms.isEmpty())
        val long = "d".repeat(80) + "/end.txt"
        ex.execute(call("trash_files", """{"paths":["$long"]}"""), null)
        assertEquals("휴지통으로 이동: …" + long.takeLast(59), gate.confirms[0].second)
    }

    @Test fun submitWithoutAuthorizationAsksSend() = runTest {
        val (dev, gate, ex) = setup(field)
        gate.confirmResult = false
        val long = "가".repeat(100)
        val r = ex.execute(call("type_text", """{"index":1,"text":"$long","submit":true}"""), screenOf(field))
        assertEquals("CANCELLED by user", r.output)
        assertEquals(RiskCategory.SEND, gate.confirms[0].first)
        assertEquals("입력 후 전송: \"${"가".repeat(60)}\"", gate.confirms[0].second)
        assertTrue(dev.calls.none { it.startsWith("setText") })
    }

    @Test fun submitWithAuthorizationSkipsConfirm() = runTest {
        val (dev, gate, ex) = setup(field)
        val r = ex.execute(
            call("type_text", """{"index":1,"text":"hi","submit":true,"user_authorized":true}"""),
            screenOf(field),
        )
        assertTrue(gate.confirms.isEmpty())
        assertTrue(r.output, r.output.startsWith("OK"))
        assertTrue(dev.calls.any { it.startsWith("setText") })
    }

    @Test fun genericConfirmSummaryIncludesDialogText() = runTest {
        val long = "사진을 삭제할까요? " + "가".repeat(100)
        val dialog = ScreenSnapshot("com.gallery", 1080, 2400, listOf(text(long, 100), button("확인", 300)))
        val (_, gate, ex) = setup(dialog)
        gate.confirmResult = false
        ex.execute(call("tap", """{"index":2}"""), screenOf(dialog))
        assertEquals("tap: 확인 — \"${long.take(80)}\"", gate.confirms[0].second)
    }

    @Test fun fileToolsWithoutPermissionReturnError() = runTest {
        val (_, _, ex) = setup()
        val r = ex.execute(call("list_dir", """{"path":"/storage/emulated/0"}"""), null)
        assertEquals("ERROR: 모든 파일 접근 권한이 필요해요", r.output)
    }

    @Test fun fileToolErrorsMapToErrorOutput() = runTest {
        val (_, _, ex) = withFiles()
        val r = ex.execute(call("list_dir", """{"path":"nope"}"""), null)
        assertTrue(r.output, r.output.startsWith("ERROR: "))
    }

    @Test fun deviceToolFlagSetOnlyForDeviceTools() = runTest {
        val (_, _, ex) = withFiles()
        assertTrue(ex.execute(call("tap", """{"index":1}"""), screenOf(base)).deviceTool)
        assertTrue(ex.execute(call("press_key", """{"key":"home"}"""), screenOf(base)).deviceTool)
        assertTrue(ex.execute(call("screenshot", "{}"), screenOf(base)).deviceTool)
        assertTrue(ex.execute(call("get_screen", "{}"), null).deviceTool)
        assertTrue(ex.execute(call("wait", """{"ms":1}"""), screenOf(base)).deviceTool)
        assertFalse(ex.execute(call("list_apps", "{}"), screenOf(base)).deviceTool)
        assertFalse(ex.execute(call("finish", """{"summary":"x"}"""), screenOf(base)).deviceTool)
        assertFalse(ex.execute(call("ask_user", """{"question":"q","options":[]}"""), screenOf(base)).deviceTool)
        assertFalse(ex.execute(call("list_dir", """{"path":"."}"""), null).deviceTool)
    }

    @Test fun deviceToolWithNullScreenObservesFirst() = runTest {
        val (dev, _, ex) = setup()
        val r = ex.execute(call("scroll", """{"direction":"down"}"""), null)
        assertTrue(dev.calls.toString(), dev.calls.indexOf("waitForIdle()") < dev.calls.indexOf("scroll(screen,down)"))
        assertTrue(r.output.startsWith("OK"))
    }

    @Test fun anIndexOrPointChosenBeforeAnyScreenWasSeenIsNotActedOn() = runTest {
        for (args in listOf(
            """{"index":1}""", """{"x":200,"y":130}""",
        )) {
            val (dev, _, ex) = setup()
            val r = ex.execute(call("tap", args), null)
            assertTrue(r.output, r.output.startsWith("STOPPED: you have not seen the screen yet"))
            assertTrue(r.output.contains("# Screen"))
            assertTrue(dev.calls.toString(), dev.calls.none { it.startsWith("tap") || it.startsWith("click") })
        }
        val (dev, _, ex) = setup()
        val typed = ex.execute(call("type_text", """{"index":1,"text":"x","submit":true}"""), null)
        assertTrue(typed.output.startsWith("STOPPED"))
        val batch = ex.execute(call("batch", """{"actions":[{"tool":"wait","args":{"ms":1}},{"tool":"tap","args":{"index":2}}]}"""), null)
        assertTrue(batch.output.startsWith("STOPPED"))
        assertTrue(dev.calls.none { it.startsWith("setText") || it.startsWith("tap") })
        // A batch that names elements only by label is fine: it waits for them on the live screen.
        val byLabel = ex.execute(call("batch", """{"actions":[{"tool":"tap","args":{"target":"B"}}]}"""), null)
        assertTrue(byLabel.output, byLabel.output.startsWith("1. tap: OK"))
    }

    // ---- memory -------------------------------------------------------------------------------

    private fun withMemory(
        allowRemember: Boolean = true,
        search: com.phoneuse.chat.ConversationSearch? = null,
    ): Pair<com.phoneuse.memory.InMemoryMemoryStore, ToolExecutor> {
        val store = com.phoneuse.memory.InMemoryMemoryStore()
        val ex = ToolExecutor(
            FakeDeviceController(listOf(base)), FakeUserGate(),
            memory = MemoryAccess(store, search, allowRemember = allowRemember, conversationId = 5, origin = "엄마 생신 선물"),
        )
        return store to ex
    }

    @Test fun rememberSavesAndReportsUndoLine() = runTest {
        val (store, ex) = withMemory()
        val r = ex.execute(
            call("remember", """{"text":"엄마 생신은 11월 20일","kind":"person","confidence":"sure","date":"11월 20일"}"""),
            null,
        )
        val saved = store.observe().first().single()
        assertEquals("엄마 생신은 11월 20일", saved.text)
        assertEquals(com.phoneuse.memory.MemoryKind.PERSON, saved.kind)
        assertEquals(com.phoneuse.memory.Confidence.SURE, saved.confidence)
        assertEquals("11월 20일", saved.date)
        assertEquals(5L, saved.conversationId)
        assertEquals("엄마 생신 선물", saved.origin)
        // The chat shows "기억했어요 · <text> [취소]" for this memory.
        assertEquals(com.phoneuse.chat.MemoryRef(saved.id, saved.text), r.remembered)
        assertTrue(r.output, r.output.startsWith("OK") && "${saved.id}" in r.output)
        assertFalse(r.deviceTool)

        // A guess is stored as one; a bad kind is an argument error and saves nothing.
        ex.execute(call("remember", """{"text":"선물 예산은 10만 원 안쪽","kind":"habit","confidence":"guess"}"""), null)
        assertEquals(com.phoneuse.memory.Confidence.GUESS, store.observe().first().first().confidence)
        val bad = ex.execute(call("remember", """{"text":"x y","kind":"pet"}"""), null)
        assertTrue(bad.output, bad.output.startsWith("ERROR: invalid arguments"))
        assertNull(bad.remembered)
        assertEquals(2, store.observe().first().size)

        // recall finds it, forget removes it.
        val recall = ex.execute(call("recall", """{"query":"엄마 생신"}"""), null)
        assertTrue(recall.output, "[${saved.id}]" in recall.output && saved.text in recall.output)
        val forget = ex.execute(call("forget", """{"id":${saved.id}}"""), null)
        assertTrue(forget.output, forget.output.startsWith("OK"))
        assertNull(store.get(saved.id))
    }

    @Test fun memoryToolsOnlyWhenEnabled() = runTest {
        val (_, _, noMemory) = setup()
        assertTrue(noMemory.specs.none { it.name in setOf("remember", "forget", "recall", "search_conversations") })
        val off = noMemory.execute(call("remember", """{"text":"a b","kind":"me"}"""), null)
        assertTrue(off.output, off.output.startsWith("ERROR"))

        val (_, on) = withMemory(search = com.phoneuse.chat.ConversationSearch { _, _, _ -> emptyList() })
        assertTrue(on.specs.map { it.name }.containsAll(listOf("remember", "forget", "recall", "search_conversations")))

        // Auto-remember off: no remember tool, and a stray call is refused.
        val (store, noAuto) = withMemory(allowRemember = false)
        assertTrue(noAuto.specs.none { it.name == "remember" })
        assertTrue(noAuto.specs.any { it.name == "recall" })
        noAuto.execute(call("remember", """{"text":"a b","kind":"me"}"""), null)
        assertTrue(store.observe().first().isEmpty())
    }

    @Test fun cardRunKeepsRememberAndForgetInTheToolListButRefusesThem() = runTest {
        val store = com.phoneuse.memory.InMemoryMemoryStore()
        val ex = ToolExecutor(
            FakeDeviceController(listOf(base)), FakeUserGate(),
            memory = MemoryAccess(
                store, null, allowRemember = false, allowForget = false, offerRemember = true, offerForget = true,
            ),
        )
        // Same tool list as a typed run (prompt cache), but the calls are refused.
        assertTrue(ex.specs.map { it.name }.containsAll(listOf("remember", "forget", "recall")))
        val r = ex.execute(call("remember", """{"text":"a b","kind":"me"}"""), null)
        assertTrue(r.output, r.output.startsWith("ERROR"))
        assertTrue(store.observe().first().isEmpty())
        val f = ex.execute(call("forget", """{"id":1}"""), null)
        assertTrue(f.output, f.output.startsWith("ERROR"))
    }

    @Test fun cardAndIdNumberFieldsAreLeftToTheUser() = runTest {
        // Like password fields, card numbers, CVCs and resident registration numbers are never typed by the agent.
        for (label in listOf("카드번호", "주민등록번호 앞자리", "CVC", "Card number", "보안코드 3자리")) {
            val field = UiNode(
                windowId = 0, path = listOf(1), className = "android.widget.EditText", text = null,
                contentDescription = null, hint = label, viewId = null, bounds = Bounds(100, 100, 900, 160),
                clickable = true, editable = true,
            )
            val s = ScreenSnapshot("com.shop", 1080, 2400, listOf(field, button("다음", 400)))
            val (dev, _, ex) = setup(s)
            val r = ex.execute(call("type_text", """{"index":1,"text":"1234"}"""), screenOf(s))
            assertTrue("$label: ${r.output}", r.output.startsWith("ERROR") && "hand_to_user" in r.output)
            assertTrue(dev.calls.none { it.startsWith("setText") })
        }
        // A draft that mentions a card number does not make a message box sensitive.
        val draft = UiNode(
            windowId = 0, path = listOf(1), className = "android.widget.EditText", text = "카드번호 나중에 알려줄게",
            contentDescription = null, hint = "메시지 입력", viewId = null, bounds = Bounds(100, 100, 900, 160),
            clickable = true, editable = true,
        )
        val chat = ScreenSnapshot("com.kakao.talk", 1080, 2400, listOf(draft, button("다음", 400)))
        val (chatDev, _, chatEx) = setup(chat)
        chatEx.execute(call("type_text", """{"index":1,"text":"응"}"""), screenOf(chat))
        assertTrue(chatDev.calls.contains("setText(응)"))
        // An ordinary field still takes text.
        val name = UiNode(
            windowId = 0, path = listOf(1), className = "android.widget.EditText", text = null,
            contentDescription = null, hint = "받는 분 이름", viewId = null, bounds = Bounds(100, 100, 900, 160),
            clickable = true, editable = true,
        )
        val ok = ScreenSnapshot("com.shop", 1080, 2400, listOf(name, button("다음", 400)))
        val (dev, _, ex) = setup(ok)
        ex.execute(call("type_text", """{"index":1,"text":"홍길동"}"""), screenOf(ok))
        assertTrue(dev.calls.contains("setText(홍길동)"))
    }

    @Test fun forgetInATaintedRunAsksFirst() = runTest {
        // Once third-party text is in the run (here: a screen read), forget is a DELETE that needs a card.
        val store = com.phoneuse.memory.InMemoryMemoryStore()
        val saved = store.add(com.phoneuse.memory.NewMemory("엄마 생신은 11월 20일", com.phoneuse.memory.MemoryKind.PERSON, com.phoneuse.memory.Confidence.SURE))
        val gate = FakeUserGate().apply { confirmResult = false }
        val ex = ToolExecutor(FakeDeviceController(listOf(base)), gate, memory = MemoryAccess(store, null))
        ex.observe()
        val denied = ex.execute(call("forget", """{"id":${saved.id}}"""), null)
        assertTrue(denied.output, denied.output.startsWith("CANCELLED"))
        assertEquals(RiskCategory.DELETE, gate.confirms.single().first)
        assertTrue(gate.confirms.single().second, "엄마 생신은 11월 20일" in gate.confirms.single().second)
        assertNotNull(store.get(saved.id))
        gate.confirmResult = true
        val ok = ex.execute(call("forget", """{"id":${saved.id}}"""), null)
        assertTrue(ok.output, ok.output.startsWith("OK"))
        assertNull(store.get(saved.id))

        // A tainted history asks too; a clean run of the user's own words does not.
        val again = store.add(com.phoneuse.memory.NewMemory("커피는 라떼", com.phoneuse.memory.MemoryKind.PERSON, com.phoneuse.memory.Confidence.SURE))
        val gate2 = FakeUserGate().apply { confirmResult = false }
        ToolExecutor(FakeDeviceController(listOf(base)), gate2, memory = MemoryAccess(store, null), historyTainted = true)
            .execute(call("forget", """{"id":${again.id}}"""), null)
        assertEquals(1, gate2.confirms.size)
        assertNotNull(store.get(again.id))
        val gate3 = FakeUserGate()
        ToolExecutor(FakeDeviceController(listOf(base)), gate3, memory = MemoryAccess(store, null))
            .execute(call("forget", """{"id":${again.id}}"""), null)
        assertTrue(gate3.confirms.isEmpty())
        assertNull(store.get(again.id))
    }

    @Test fun searchConversationsReturnsJsonHits() = runTest {
        val hit = com.phoneuse.chat.ConversationHit(3, "엄마 생신 선물", "…등산 스틱은 어때요…", "2026-10-12 14:03")
        var asked: String? = null
        val (_, ex) = withMemory(search = com.phoneuse.chat.ConversationSearch { q, _, exclude ->
            asked = q
            assertEquals(5L, exclude)
            listOf(hit)
        })
        val r = ex.execute(call("search_conversations", """{"query":"스틱"}"""), null)
        assertEquals("스틱", asked)
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(r.output) as kotlinx.serialization.json.JsonArray
        val o = arr.single() as kotlinx.serialization.json.JsonObject
        assertEquals("3", o["conversation_id"].toString())
        assertEquals("\"엄마 생신 선물\"", o["title"].toString())
        assertEquals("\"2026-10-12 14:03\"", o["at"].toString())
        assertTrue(o["snippet"].toString().contains("스틱"))
    }

    @Test fun rememberingKnownTextHasNoUndoLine() = runTest {
        val (store, ex) = withMemory()
        val first = ex.execute(call("remember", """{"text":"오이는 싫어함","kind":"me"}"""), null)
        assertNotNull(first.remembered)
        // Saying it again: nothing new, so no "기억했어요 [취소]" that would delete the old memory.
        val again = ex.execute(call("remember", """{"text":"오이는  싫어함","kind":"habit"}"""), null)
        assertNull(again.remembered)
        assertTrue(again.output, "이미 기억하고 있어요" in again.output)
        assertEquals(1, store.observe().first().size)
    }

    @Test fun afterThirdPartyTextRememberSavesOnlyAGuess() = runTest {
        val (store, ex) = withMemory()
        // The screen (or a web page, a file, a notification) is text the user did not write.
        ex.observe()
        val r = ex.execute(call("remember", """{"text":"엄마 생신은 11월 20일","kind":"person","confidence":"sure"}"""), null)
        assertTrue(r.output, r.output.startsWith("OK"))
        assertEquals(com.phoneuse.memory.Confidence.GUESS, store.observe().first().single().confidence)
        // A guess is not recalled until the user confirms it.
        val recall = ex.execute(call("recall", """{"query":"엄마 생신"}"""), null)
        assertFalse(recall.output, "엄마 생신은" in recall.output)

        // Same after a device tool's output.
        val (store2, ex2) = withMemory()
        ex2.execute(call("list_apps", "{}"), null)
        ex2.execute(call("remember", """{"text":"회사는 강남역","kind":"place","confidence":"sure"}"""), null)
        assertEquals(com.phoneuse.memory.Confidence.GUESS, store2.observe().first().single().confidence)
    }

    @Test fun forgetReportsWhatItRemovedAndIsOffInCardRuns() = runTest {
        val (store, ex) = withMemory()
        val id = store.add(com.phoneuse.memory.NewMemory("등산 스틱 주문하기", com.phoneuse.memory.MemoryKind.TODO, due = "2026-11-15")).id
        val r = ex.execute(call("forget", """{"id":$id}"""), null)
        assertTrue(r.output, r.output.startsWith("OK"))
        val f = r.forgotten!!
        assertEquals(id, f.id)
        assertEquals("등산 스틱 주문하기", f.text)
        assertEquals("TODO", f.kind)
        assertEquals("2026-11-15", f.due)
        assertNull(store.get(id))

        val noForget = ToolExecutor(
            FakeDeviceController(listOf(base)), FakeUserGate(),
            memory = MemoryAccess(store, null, allowRemember = false, allowForget = false),
        )
        assertTrue(noForget.specs.none { it.name == "forget" })
        val keep = store.add(com.phoneuse.memory.NewMemory("오이는 싫어함", com.phoneuse.memory.MemoryKind.ME)).id
        val refused = noForget.execute(call("forget", """{"id":$keep}"""), null)
        assertTrue(refused.output, refused.output.startsWith("ERROR"))
        assertNotNull(store.get(keep))
    }

    // ---- stale screen safety ------------------------------------------------------------------

    @Test fun tapOnAChangedScreenIsStoppedWithTheFreshScreen() = runTest {
        // The model saw base ([1] = "A"); the live screen now has "보내기" where A was.
        val live = snap("보내기", "B", "C")
        val (dev, gate, ex) = setup(live)
        val r = ex.execute(call("tap", """{"index":1}"""), screenOf(base))
        assertTrue(r.output, r.output.startsWith("STOPPED: the screen changed"))
        assertTrue(r.output, r.output.contains("\"보내기\""))
        assertNotNull(r.screen)
        assertTrue(dev.calls.toString(), dev.calls.none { it.startsWith("tap") })
        assertTrue(gate.confirms.isEmpty())
    }

    @Test fun longPressOnAChangedScreenIsStoppedToo() = runTest {
        val (dev, _, ex) = setup(snap("X", "Y"))
        val r = ex.execute(call("long_press", """{"index":2}"""), screenOf(base))
        assertTrue(r.output, r.output.startsWith("STOPPED: the screen changed"))
        assertTrue(dev.calls.none { it.startsWith("longPress") })
    }

    @Test fun tapFollowsTheSameElementWhenIndicesShift() = runTest {
        // A new element comes first in tree order, so "A" is [2] now, at the same place.
        val banner = UiNode(
            windowId = 0, path = listOf(0), className = "android.widget.Button", text = "새 배너",
            contentDescription = null, hint = null, viewId = null, bounds = Bounds(100, 2000, 300, 2060), clickable = true,
        )
        val live = ScreenSnapshot("com.app", 1080, 2400, listOf(banner) + base.roots)
        val (dev, _, ex) = setup(live)
        val r = ex.execute(call("tap", """{"index":1}"""), screenOf(base))
        assertTrue(r.output, r.output.startsWith("OK"))
        assertTrue(dev.calls.toString(), dev.calls.contains("tap(200,130)"))
    }

    @Test fun coordinateTapIsJudgedByTheLiveElementUnderThePoint() = runTest {
        // The model saw harmless "A" at (200,130); a payment button is there now.
        val live = snap("결제하기", "B")
        val (dev, gate, ex) = setup(live)
        gate.confirmResult = false
        val r = ex.execute(call("tap", """{"x":200,"y":130}"""), screenOf(base))
        assertEquals("CANCELLED by user", r.output)
        assertEquals(RiskCategory.MONEY, gate.confirms.single().first)
        assertTrue(dev.calls.none { it.startsWith("tap") })
    }

    @Test fun shortSwipeIsJudgedOnTheLiveScreen() = runTest {
        val live = snap("결제하기", "B")
        val (dev, gate, ex) = setup(live)
        gate.confirmResult = false
        val r = ex.execute(call("swipe", """{"x1":200,"y1":130,"x2":205,"y2":135}"""), screenOf(base))
        assertEquals("CANCELLED by user", r.output)
        assertEquals(RiskCategory.MONEY, gate.confirms.single().first)
        assertTrue(dev.calls.none { it.startsWith("swipe") })
    }

    @Test fun batchFirstTapOnAChangedScreenStopsTheBatch() = runTest {
        val live = snap("보내기", "B")
        val (dev, _, ex) = setup(live)
        val r = ex.execute(
            call("batch", """{"actions":[{"tool":"tap","args":{"index":1}},{"tool":"tap","args":{"index":2}}]}"""),
            screenOf(base),
        )
        assertTrue(r.output, r.output.startsWith("1. tap: STOPPED: the screen changed"))
        assertTrue(dev.calls.none { it.startsWith("tap") })
    }

    // ---- live element lookup for typing, scrolling and moved elements ---------------------------

    private fun editable(label: String, y: Int, hint: String? = null) =
        button(label, y, editable = true).copy(hint = hint, path = listOf(y))

    @Test fun typeTextFollowsTheSameFieldWhenIndicesShift() = runTest {
        // The model saw the search field as [1]; now a message box comes first, so the search field is [2].
        val search = editable("", 100, hint = "검색")
        val seen = ScreenSnapshot("com.app", 1080, 2400, listOf(search))
        val message = editable("메시지 입력", 900)
        val live = ScreenSnapshot("com.app", 1080, 2400, listOf(message, search))
        val (dev, gate, ex) = setup(live)
        val r = ex.execute(call("type_text", """{"index":1,"text":"cats","submit":true}"""), screenOf(seen))
        assertTrue(r.output, r.output.startsWith("OK"))
        // Typed into the live search field, not into whatever is [1] now; a search needs no SEND confirm.
        assertEquals(search.bounds, dev.setTextNodes.single().bounds)
        assertEquals("검색", dev.setTextNodes.single().hint)
        assertTrue(gate.confirms.isEmpty())
    }

    @Test fun typeTextWhereTheSearchFieldBecameAMessageBoxStops() = runTest {
        // Same place, but the search field is gone and a message box is there: no text, no send, no confirm.
        val seen = ScreenSnapshot("com.app", 1080, 2400, listOf(editable("", 100, hint = "검색")))
        val live = ScreenSnapshot("com.app", 1080, 2400, listOf(editable("메시지 입력", 100)))
        val (dev, gate, ex) = setup(live)
        val r = ex.execute(call("type_text", """{"index":1,"text":"안녕","submit":true}"""), screenOf(seen))
        assertTrue(r.output, r.output.startsWith("STOPPED: the screen changed"))
        assertTrue(dev.calls.none { it.startsWith("setText") || it.startsWith("imeEnter") })
        assertTrue(gate.confirms.isEmpty())
    }

    @Test fun scrollByIndexOnAChangedScreenStops() = runTest {
        val (dev, _, ex) = setup(snap("X", "Y"))
        val r = ex.execute(call("scroll", """{"index":1,"direction":"down"}"""), screenOf(base))
        assertTrue(r.output, r.output.startsWith("STOPPED: the screen changed"))
        assertTrue(dev.calls.none { it.startsWith("scroll") })
    }

    @Test fun tapFollowsAnElementThatMovedAFewPixels() = runTest {
        // The list scrolled by 7 px: same class, unique label, same size.
        val moved = ScreenSnapshot("com.app", 1080, 2400, base.roots.map { it.copy(bounds = it.bounds.shift(7)) })
        val (dev, _, ex) = setup(moved)
        val r = ex.execute(call("tap", """{"index":1}"""), screenOf(base))
        assertTrue(r.output, r.output.startsWith("OK"))
        assertTrue(dev.calls.toString(), dev.calls.contains("tap(200,137)"))
    }

    @Test fun movedElementWithADuplicateLabelStops() = runTest {
        val seen = snap("삭제", "삭제", "C")
        val moved = ScreenSnapshot("com.app", 1080, 2400, seen.roots.map { it.copy(bounds = it.bounds.shift(7)) })
        val (dev, _, ex) = setup(moved)
        val r = ex.execute(call("tap", """{"index":1}"""), screenOf(seen))
        assertTrue(r.output, r.output.startsWith("STOPPED: the screen changed"))
        assertTrue(dev.calls.none { it.startsWith("tap") })
    }

    @Test fun resizedElementStops() = runTest {
        val resized = ScreenSnapshot(
            "com.app", 1080, 2400,
            base.roots.map { it.copy(bounds = Bounds(it.bounds.left, it.bounds.top + 7, it.bounds.right + 40, it.bounds.bottom + 7)) },
        )
        val (dev, _, ex) = setup(resized)
        val r = ex.execute(call("tap", """{"index":1}"""), screenOf(base))
        assertTrue(r.output, r.output.startsWith("STOPPED: the screen changed"))
        assertTrue(dev.calls.none { it.startsWith("tap") })
    }

    @Test fun movedElementIsJudgedOnTheLiveScreen() = runTest {
        // "결제하기" moved a little; the tap follows it and the payment confirm still comes up.
        val moved = ScreenSnapshot("com.app", 1080, 2400, base.roots.map { it.copy(bounds = it.bounds.shift(5)) })
        val (dev, gate, ex) = setup(moved)
        gate.confirmResult = false
        val r = ex.execute(call("tap", """{"index":3}"""), screenOf(base))
        assertEquals("CANCELLED by user", r.output)
        assertEquals(RiskCategory.MONEY, gate.confirms.single().first)
        assertTrue(dev.calls.none { it.startsWith("tap") })
    }

    private fun Bounds.shift(dy: Int) = Bounds(left, top + dy, right, bottom + dy)

    @Test fun aBatchRunsAcrossScreensByTargetLabel() = runTest {
        // After the first tap the next screen is still loading for a while, then shows 검색.
        val loading = snap("로딩 중")
        val (dev, _, ex) = setup(base, base, loading, loading, loading, snap("X", "검색창 열기", "검색"))
        val r = ex.execute(
            call("batch", """{"actions":[{"tool":"tap","args":{"index":1}},{"tool":"tap","args":{"target":"검색"}}]}"""),
            screenOf(base),
        )
        assertTrue(r.output, r.output.startsWith("1. tap: OK\n2. tap: OK"))
        // The exact label wins over one that only contains it.
        assertEquals(listOf("tap(200,130)", "tap(200,330)"), dev.calls.filter { it.startsWith("tap") })
    }

    @Test fun aBatchStopsWhereATargetNeverAppears() = runTest {
        val (dev, _, ex) = setup(base, snap("X"))
        val r = ex.execute(
            call("batch", """{"actions":[{"tool":"tap","args":{"index":1}},{"tool":"tap","args":{"target":"검색"}},{"tool":"tap","args":{"index":1}}]}"""),
            screenOf(base),
        )
        assertTrue(r.output, r.output.contains("2. tap: STOPPED: no element \"검색\" appeared"))
        assertEquals(1, dev.calls.count { it.startsWith("tap") })
        assertTrue(r.output.contains("\"X\""))
    }

    @Test fun aRiskyTargetIsStillConfirmed() = runTest {
        val (dev, gate, ex) = setup(base, snap("X", "결제하기"))
        gate.confirmResult = false
        val r = ex.execute(
            call("batch", """{"actions":[{"tool":"tap","args":{"index":1}},{"tool":"tap","args":{"target":"결제하기"}}]}"""),
            screenOf(base),
        )
        assertTrue(r.output, r.output.contains("2. tap: CANCELLED by user"))
        assertEquals(1, dev.calls.count { it.startsWith("tap") })
    }

    @Test fun findLabelPrefersExactThenActionable() {
        val text = UiNode(
            windowId = 0, path = listOf(1), className = "TextView", text = "검색", contentDescription = null,
            hint = null, viewId = null, bounds = Bounds(0, 50, 100, 80),
        )
        val screen = TreeCompressor.compress(
            ScreenSnapshot("com.app", 1080, 2400, listOf(text, button("최근 검색", 100), button("검 색", 200))),
        )
        assertEquals(3, ToolExecutor.findLabel("검색", screen))
        assertEquals(2, ToolExecutor.findLabel("최근", screen))
        assertNull(ToolExecutor.findLabel("장바구니", screen))
    }
}
