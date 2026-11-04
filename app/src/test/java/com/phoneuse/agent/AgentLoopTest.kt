package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.model.ModelClient
import com.phoneuse.model.ModelException
import com.phoneuse.model.ModelTurn
import com.phoneuse.model.ToolSpec
import com.phoneuse.model.Usage
import com.phoneuse.risk.RiskCategory
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeModelClient(
    script: List<Any>,
    val delayMs: Long = 0,
    val deltas: Map<Int, List<String>> = emptyMap(),
    val onCall: (Int) -> Unit = {},
) : ModelClient {
    private val queue = ArrayDeque(script)
    val inputs = mutableListOf<List<InputItem>>()
    val toolNames = mutableListOf<List<String>>()
    val specs = mutableListOf<List<ToolSpec>>()
    val calls get() = inputs.size

    override suspend fun next(
        instructions: String,
        input: List<InputItem>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
    ): ModelTurn {
        inputs += input.toList()
        toolNames += tools.map { it.name }
        specs += tools.toList()
        onCall(inputs.size)
        deltas[inputs.size]?.forEach(onTextDelta)
        if (delayMs > 0) delay(delayMs)
        return when (val item = queue.removeFirst()) {
            is ModelTurn -> item
            is Throwable -> throw item
            else -> error("bad script item")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopTest {
    private fun button(text: String, y: Int) = UiNode(
        windowId = 0, path = listOf(y), className = "android.widget.Button", text = text,
        contentDescription = null, hint = null, viewId = null,
        bounds = Bounds(100, y, 300, y + 60), clickable = true,
    )

    private val snap = ScreenSnapshot(
        "com.app", 1080, 2400,
        listOf("A", "B", "C", "D").mapIndexed { i, l -> button(l, 100 + i * 100) },
    )

    private var seq = 0
    private fun call(name: String, args: String = "{}") = InputItem.FunctionCall("c${seq++}", name, args)
    private fun turn(vararg calls: InputItem.FunctionCall, text: String? = null, usage: Usage? = null) =
        ModelTurn(text, calls.toList(), usage)

    private fun tap() = turn(call("tap", """{"index":1}"""))
    private fun finish() = turn(call("finish", """{"summary":"완료"}"""))

    private class Rig(val dev: FakeDeviceController, val steering: SteeringQueue, val loop: AgentLoop)

    private fun rig(
        model: ModelClient,
        maxSteps: Int = 60,
        dev: FakeDeviceController = FakeDeviceController(listOf(snap)),
        helper: ModelClient? = null,
        gate: FakeUserGate = FakeUserGate(),
    ): Rig {
        val steering = SteeringQueue()
        return Rig(dev, steering, AgentLoop(model, ToolExecutor(dev, gate), steering, maxSteps, helperModel = helper))
    }

    @Test fun modelCallRestartsWhenScreenChangesWhileItThinks() = runTest {
        val changed = ScreenSnapshot("com.app", 1080, 2400, listOf(button("New page", 100)))
        var polled = false
        val dev = FakeDeviceController(listOf(snap)).apply {
            quickWindowMs = 1000
            liveHash = { polled = true; 42 } // never the hash of the screen the model was sent
            // What the watch reads after a differing hash: another page (not just other digits).
            snapshotHook = { s -> if (polled) changed.also { polled = false } else s }
        }
        val model = FakeModelClient(listOf(tap(), tap(), finish()), delayMs = 500)
        val events = rig(model, dev = dev).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(AgentEvent.Finished("완료"), events.last())
        // Each tap's step is sent, cancelled when the screen differs, then sent again with the new screen.
        assertEquals(5, model.calls)
        val retried = model.inputs[2].last() as InputItem.UserText
        assertTrue(retried.text, retried.text.startsWith("# Screen changed while you were deciding"))
    }

    @Test fun planUpdateRunsAlongWithOneActionAndExtraActionsAreSentBack() = runTest {
        val plan = call("update_plan", """{"steps":[{"text":"Tap A","status":"in_progress"}]}""")
        val first = call("tap", """{"index":1}""")
        val second = call("tap", """{"index":2}""")
        val model = FakeModelClient(listOf(turn(plan, first, second), finish()))
        val r = rig(model)
        r.loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(1, r.dev.calls.count { it.startsWith("tap") || it.startsWith("click") })
        val outputs = model.inputs[1].filterIsInstance<InputItem.FunctionOutput>().associate { it.callId to it.output }
        assertTrue(outputs.getValue(plan.callId).startsWith("OK: plan updated"))
        assertTrue(outputs.getValue(first.callId).startsWith("OK"))
        assertTrue(outputs.getValue(second.callId).startsWith("NOT RUN: one phone action per turn"))
    }

    @Test fun independentDataToolsInOneTurnAllRunButOnlyOnePhoneAction() = runTest {
        val plan = call("update_plan", """{"steps":[{"text":"Look up","status":"in_progress"}]}""")
        val recall1 = call("recall", """{"query":"a"}""")
        val first = call("tap", """{"index":1}""")
        val recall2 = call("recall", """{"query":"b"}""")
        val second = call("scroll", """{"direction":"down"}""")
        val ask = call("ask_user", """{"question":"q"}""")
        val model = FakeModelClient(listOf(turn(plan, recall1, first, recall2, second, ask), finish()))
        val r = rig(model)
        r.loop.run(emptyList(), "t", observeFirst = true).toList()
        val outputs = model.inputs[1].filterIsInstance<InputItem.FunctionOutput>().associate { it.callId to it.output }
        for (c in listOf(recall1, recall2)) assertTrue(outputs.getValue(c.callId), !outputs.getValue(c.callId).startsWith("NOT RUN"))
        assertTrue(outputs.getValue(first.callId).startsWith("OK"))
        assertTrue(outputs.getValue(second.callId).startsWith("NOT RUN: one phone action per turn"))
        assertTrue(outputs.getValue(ask.callId).startsWith("NOT RUN: one phone action per turn"))
        assertTrue(r.dev.calls.none { it.startsWith("scroll") })
        // Every call has its output, in the order of the calls.
        val order = model.inputs[1].filterIsInstance<InputItem.FunctionOutput>().map { it.callId }.takeLast(6)
        assertEquals(listOf(plan, recall1, first, recall2, second, ask).map { it.callId }, order)
    }

    @Test fun aChangeOnlyInDigitsDoesNotRestartTheModelCall() = runTest {
        val clock = { t: String -> ScreenSnapshot("com.app", 1080, 2400, listOf(button("A", 100), button("남은 시간 $t", 200))) }
        var polled = false
        var tick = 0
        val dev = FakeDeviceController(listOf(clock("10:00"))).apply {
            quickWindowMs = 1000
            liveHash = { polled = true; 1000 + tick++ } // the raw text changes on every look
            snapshotHook = { s -> if (polled) clock("10:0${tick % 10}").also { polled = false } else s }
        }
        val model = FakeModelClient(listOf(tap(), tap(), finish()), delayMs = 500)
        rig(model, dev = dev).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(3, model.calls)
    }

    @Test fun modelCallGoesOnWhenScreenStaysTheSame() = runTest {
        val shown = com.phoneuse.screen.TreeCompressor.compress(snap).text.hashCode()
        val dev = FakeDeviceController(listOf(snap)).apply {
            quickWindowMs = 1000
            liveHash = { shown }
        }
        val model = FakeModelClient(listOf(tap(), finish()), delayMs = 500)
        rig(model, dev = dev).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(2, model.calls)
        assertTrue(dev.calls.contains("settleQuick()"))
    }

    @Test fun lateScreenChangeAfterWindowIsIgnored() = runTest {
        var hash: Int? = com.phoneuse.screen.TreeCompressor.compress(snap).text.hashCode()
        val dev = FakeDeviceController(listOf(snap)).apply {
            quickWindowMs = 300
            liveHash = { hash }
        }
        // The model thinks for 2 s; the screen changes only after the 300 ms window.
        val model = FakeModelClient(listOf(tap(), finish()), delayMs = 2000) { n -> if (n == 2) launch { delay(1000); hash = 7 } }
        rig(model, dev = dev).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(2, model.calls)
    }

    @Test fun speculationOffUsesTheFullSettle() = runTest {
        val dev = FakeDeviceController(listOf(snap)).apply { quickWindowMs = 1000 }
        Speculation.enabled = false
        try {
            rig(FakeModelClient(listOf(tap(), finish())), dev = dev).loop.run(emptyList(), "t", observeFirst = true).toList()
        } finally {
            Speculation.enabled = true
        }
        assertTrue(dev.calls.none { it == "settleQuick()" })
    }

    @Test fun finishCallIsReportedOnceAsFinished() = runTest {
        val model = FakeModelClient(listOf(finish()))
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertTrue(events.toString(), events.none { it is AgentEvent.Acting })
        assertEquals(AgentEvent.Finished("완료"), events.last())
    }

    @Test fun finishesOnFinishCall() = runTest {

        val model = FakeModelClient(listOf(tap(), finish()))
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(listOf(AgentEvent.DeviceUse, AgentEvent.Thinking), events.take(2))
        assertTrue(events.contains(AgentEvent.Acting("탭: A")))
        assertEquals(AgentEvent.Finished("완료"), events.last())
    }

    @Test fun textOnlyTurnFinishes() = runTest {
        val model = FakeModelClient(listOf(ModelTurn("다 했어요", emptyList(), Usage(5, 6))))
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(1, model.calls)
        assertTrue(events.any { it is AgentEvent.Usage && it.step == 1 && it.inputTokens == 5 && it.outputTokens == 6 })
        assertEquals(AgentEvent.Finished("다 했어요"), events.last())
    }

    @Test fun initialInputHasTaskAndScreen() = runTest {
        val model = FakeModelClient(listOf(finish()))
        rig(model).loop.run(emptyList(), "다크 모드 켜줘", observeFirst = true).toList()
        val first = model.inputs[0]
        assertEquals(InputItem.UserText("다크 모드 켜줘"), first[0])
        assertTrue((first[1] as InputItem.UserText).text.startsWith("# Screen"))
    }

    @Test fun textWithCallsEmitsLogAndKeepsOrder() = runTest {
        val model = FakeModelClient(listOf(turn(call("tap", """{"index":1}"""), text = "눌러볼게요"), finish()))
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertTrue(events.contains(AgentEvent.Log("눌러볼게요")))
        val second = model.inputs[1]
        val at = second.indexOf(InputItem.AssistantText("눌러볼게요"))
        assertTrue(at >= 0 && second[at + 1] is InputItem.FunctionCall && second[at + 2] is InputItem.FunctionOutput)
    }

    @Test fun steeringInjectedNextStep() = runTest {
        lateinit var r: Rig
        val model = FakeModelClient(listOf(tap(), finish())) { n -> if (n == 1) r.steering.post("스크린샷은 남겨") }
        r = rig(model)
        r.loop.run(emptyList(), "t", observeFirst = true).toList()
        assertTrue(InputItem.UserText("사용자 추가 지시: 스크린샷은 남겨") in model.inputs[1])
        assertTrue(model.inputs[0].none { it is InputItem.UserText && it.text.startsWith("사용자 추가 지시") })
    }

    @Test fun olderScreenshotsReplaced() = runTest {
        val model = FakeModelClient(listOf(turn(call("screenshot")), turn(call("screenshot")), finish()))
        rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        val third = model.inputs[2]
        assertEquals(1, third.count { it is InputItem.UserImage })
        // The first screen's and the first screenshot's pictures (every screen read comes with one).
        assertEquals(2, third.count { it == InputItem.UserText("[이전 스크린샷 생략]") })
        assertTrue(third.last() is InputItem.UserImage)
    }

    @Test fun attachmentsMakeRememberSaveOnlyAGuess() = runTest {
        suspend fun saved(images: List<InputItem.UserImage>, attached: Boolean): com.phoneuse.memory.Confidence {
            val store = com.phoneuse.memory.InMemoryMemoryStore()
            val executor = ToolExecutor(FakeDeviceController(listOf(snap)), FakeUserGate(), memory = MemoryAccess(store, null))
            val model = FakeModelClient(listOf(turn(call("remember", """{"text":"회사는 강남역","kind":"place","confidence":"sure"}""")), finish()))
            AgentLoop(model, executor, SteeringQueue()).run(emptyList(), "이거 기억해", observeFirst = false, images = images, attached = attached).toList()
            return store.observe().first().single().confidence
        }
        assertEquals(com.phoneuse.memory.Confidence.SURE, saved(emptyList(), attached = false))
        // A photo, or a file's text (no image), is content the user did not write.
        assertEquals(com.phoneuse.memory.Confidence.GUESS, saved(listOf(InputItem.UserImage("UEhPVE8=")), attached = true))
        assertEquals(com.phoneuse.memory.Confidence.GUESS, saved(emptyList(), attached = true))
    }

    @Test fun attachedPhotosGoWithTheFirstModelCallOnly() = runTest {
        val model = FakeModelClient(listOf(turn(call("screenshot")), turn(call("screenshot")), finish()))
        val photo = InputItem.UserImage("UEhPVE8=")
        val old = InputItem.UserText("", listOf(InputItem.UserImage("T0xE")))
        rig(model).loop.run(listOf(old), "이 잎 왜 이래?", observeFirst = false, images = listOf(photo, photo)).toList()
        assertEquals(listOf(old, InputItem.UserText("이 잎 왜 이래?", listOf(photo, photo))), model.inputs[0].take(2))
        // Later steps keep a mark per photo; screenshots are still handled on their own.
        assertEquals(listOf(InputItem.UserText("[사진]"), InputItem.UserText("[사진] [사진] 이 잎 왜 이래?")), model.inputs[1].take(2))
        assertEquals(model.inputs[1].take(2), model.inputs[2].take(2))
        assertEquals(1, model.inputs[2].count { it is InputItem.UserImage })
    }

    @Test fun unauthorizedFailsWithoutRetry() = runTest {
        val model = FakeModelClient(listOf(ModelException(401, "로그인이 만료됐어요")))
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(1, model.calls)
        val failed = events.last() as AgentEvent.Failed
        assertTrue(failed.message.contains("로그인이 만료됐어요"))
    }

    @Test fun serverErrorRetriesWithBackoffThenFails() = runTest {
        val model = FakeModelClient(List(5) { ModelException(500, "boom") })
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(5, model.calls)
        // 1 s, 2 s, 4 s, 8 s plus jitter.
        assertTrue(currentTime.toString(), currentTime in 15_000..22_500)
        assertEquals(AgentEvent.Failed("모델 호출 실패: boom"), events.last())
    }

    @Test fun rateLimitWaitsWhatTheServerAsksForUpToThirtySeconds() = runTest {
        val model = FakeModelClient(listOf(ModelException(429, "slow down", retryAfterMs = 12_000), finish()))
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(AgentEvent.Finished("완료"), events.last())
        assertTrue(currentTime.toString(), currentTime in 12_000..13_000)
        val capped = FakeModelClient(listOf(ModelException(429, "slow down", retryAfterMs = 120_000), finish()))
        val start = currentTime
        rig(capped).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(30_000L, currentTime - start)
    }

    @Test fun usageLimitIsNotRetriedAndSaysSo() = runTest {
        val model = FakeModelClient(listOf(ModelException(429, """{"error":{"type":"usage_limit_reached"}}""", code = "usage_limit_reached")))
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(1, model.calls)
        val failed = events.last() as AgentEvent.Failed
        assertTrue(failed.message, failed.message.contains("사용 한도"))
    }

    @Test fun thePlanUsageLimitKeepsItsOwnMessageAndCarriesItsCode() = runTest {
        // English and Korean alike: neither may turn into the generic "after it resets" or "busy" text.
        for (text in listOf("Usage limit reached for your ChatGPT plan or this app. See chatgpt.com/settings/usage", "ChatGPT 요금제 또는 이 앱의 사용량 한도에 도달했어요. chatgpt.com/settings/usage 에서 확인해 주세요")) {
            val model = FakeModelClient(listOf(ModelException(429, text, code = ModelException.PLAN_USAGE_LIMIT, retryable = false)))
            val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
            assertEquals(1, model.calls)
            assertEquals(AgentEvent.Failed(text, ModelException.PLAN_USAGE_LIMIT), events.last())
        }
    }

    @Test fun aTimeoutAfterOutputBeganIsRetriedOnlyOnce() = runTest {
        val model = FakeModelClient(List(3) { ModelException(null, "Network error: timeout", afterOutput = true) })
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(2, model.calls)
        assertTrue(events.last() is AgentEvent.Failed)
        val before = FakeModelClient(List(2) { ModelException(null, "Network error: timeout") } + listOf(finish()))
        rig(before).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(3, before.calls)
    }

    @Test fun aStoppedEarlyResponseIsNotRetried() = runTest {
        val model = FakeModelClient(listOf(ModelException(200, "The model stopped early (content_filter)", retryable = false)))
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(1, model.calls)
        assertTrue((events.last() as AgentEvent.Failed).message.contains("content_filter"))
    }

    @Test fun serverErrorThenSuccessRecovers() = runTest {
        val model = FakeModelClient(listOf(ModelException(null, "io"), finish()))
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(2, model.calls)
        assertEquals(AgentEvent.Finished("완료"), events.last())
    }

    @Test fun otherClientErrorFailsImmediately() = runTest {
        val model = FakeModelClient(listOf(ModelException(400, "bad request")))
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(1, model.calls)
        assertEquals(AgentEvent.Failed("모델 호출 실패: bad request"), events.last())
    }

    @Test fun serviceLossFailsWithMessage() = runTest {
        val dev = FakeDeviceController(listOf(snap))
        val model = FakeModelClient(listOf(tap()))
        dev.throwOnTap = ServiceUnavailableException()
        val events = rig(model, dev = dev).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(AgentEvent.Failed("접근성 서비스가 꺼졌어요"), events.last())
    }

    @Test fun stepLimit() = runTest {
        val model = FakeModelClient(List(10) { tap() })
        val events = rig(model, maxSteps = 3).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(3, model.calls)
        assertEquals(AgentEvent.Failed("단계 한도 초과"), events.last())
    }

    @Test fun observeFirstReportsDeviceUseBeforeObserving() = runTest {
        val r = rig(FakeModelClient(listOf(finish())))
        var callsAtDeviceUse = -1
        val events = r.loop.run(emptyList(), "t", observeFirst = true)
            .onEach { if (it == AgentEvent.DeviceUse) callsAtDeviceUse = r.dev.calls.size }
            .toList()
        assertEquals(1, events.count { it == AgentEvent.DeviceUse })
        assertEquals(AgentEvent.DeviceUse, events.first())
        assertEquals(0, callsAtDeviceUse)
    }

    @Test fun answersWithoutToolsNeverReportsDeviceUse() = runTest {
        val model = FakeModelClient(listOf(ModelTurn("안녕하세요", emptyList(), null)))
        val r = rig(model)
        val events = r.loop.run(emptyList(), "안녕", observeFirst = false).toList()
        assertTrue(events.toString(), events.none { it == AgentEvent.DeviceUse })
        assertEquals(AgentEvent.Finished("안녕하세요"), events.last())
        assertTrue(r.dev.calls.toString(), r.dev.calls.isEmpty())
    }

    @Test fun deviceUseReportedOnlyOnFirstDeviceTool() = runTest {
        val model = FakeModelClient(
            listOf(
                turn(call("list_dir", """{"path":"Download"}""")),
                turn(call("tap", """{"index":1}""")),
                turn(call("tap", """{"index":2}""")),
                finish(),
            ),
        )
        val r = rig(model)
        var callsAtDeviceUse = -1
        val events = r.loop.run(emptyList(), "t", observeFirst = false)
            .onEach { if (it == AgentEvent.DeviceUse) callsAtDeviceUse = r.dev.calls.size }
            .toList()
        assertEquals(events.toString(), 1, events.count { it == AgentEvent.DeviceUse })
        val at = events.indexOf(AgentEvent.DeviceUse)
        val firstTap = events.indexOfFirst { it is AgentEvent.Acting && it.description.startsWith("탭") }
        val listDir = events.indexOfFirst { it is AgentEvent.Acting && !it.description.startsWith("탭") }
        assertTrue(events.toString(), listDir in 0 until at)
        assertEquals(events.toString(), at + 1, firstTap)
        // Nothing touched the device before DeviceUse was reported.
        assertEquals(0, callsAtDeviceUse)
        assertEquals(AgentEvent.Finished("완료"), events.last())
    }

    @Test fun deviceUseBeforeTapWithinOneTurn() = runTest {
        val model = FakeModelClient(
            listOf(turn(call("update_plan", """{"steps":[{"text":"Tap A","status":"in_progress"}]}"""), call("tap", """{"index":1}""")), finish()),
        )
        val r = rig(model)
        var callsAtDeviceUse = -1
        val events = r.loop.run(emptyList(), "t", observeFirst = false)
            .onEach { if (it == AgentEvent.DeviceUse) callsAtDeviceUse = r.dev.calls.size }
            .toList()
        assertEquals(events.toString(), 1, events.count { it == AgentEvent.DeviceUse })
        val at = events.indexOf(AgentEvent.DeviceUse)
        val acting = events.withIndex().filter { it.value is AgentEvent.Acting }.map { it.index }
        assertEquals(events.toString(), 2, acting.size)
        assertTrue(events.toString(), acting[0] < at)
        assertEquals(events.toString(), at + 1, acting[1])
        assertTrue((events[acting[1]] as AgentEvent.Acting).description.startsWith("탭"))
        assertEquals(0, callsAtDeviceUse)
    }

    @Test fun retryAfterDeltasEmitsTextReset() = runTest {
        val model = FakeModelClient(
            listOf(ModelException(null, "io"), ModelTurn("final", emptyList(), null)),
            deltas = mapOf(1 to listOf("partial"), 2 to listOf("fi", "nal")),
        )
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = false).toList()
        assertEquals(
            listOf(
                AgentEvent.Thinking,
                AgentEvent.TextDelta("partial"),
                AgentEvent.TextReset,
                AgentEvent.TextDelta("fi"),
                AgentEvent.TextDelta("nal"),
                AgentEvent.Finished("final"),
            ),
            events,
        )
    }

    @Test fun retryWithoutDeltasEmitsNoTextReset() = runTest {
        val model = FakeModelClient(listOf(ModelException(500, "boom"), ModelTurn("done", emptyList(), null)))
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = false).toList()
        assertTrue(events.toString(), events.none { it == AgentEvent.TextReset })
        assertEquals(AgentEvent.Finished("done"), events.last())
    }

    @Test fun cancellationDuringDeltasStopsPromptly() = runTest {
        val model = FakeModelClient(listOf(finish()), delayMs = 10_000, deltas = mapOf(1 to listOf("a", "b")))
        val seen = mutableListOf<AgentEvent>()
        var cause: Throwable? = null
        val job = launch {
            rig(model).loop.run(emptyList(), "t", observeFirst = false)
                .onCompletion { cause = it }
                .collect { seen += it }
        }
        delay(100)
        assertTrue(seen.toString(), AgentEvent.TextDelta("b") in seen)
        job.cancel()
        job.join()
        assertTrue(currentTime < 1000)
        assertTrue(cause is CancellationException)
        assertEquals(1, model.calls)
        assertTrue(seen.toString(), seen.none { it is AgentEvent.Finished })
    }

    @Test fun observeFirstFalseSendsNoScreen() = runTest {
        val model = FakeModelClient(listOf(finish()))
        val r = rig(model)
        r.loop.run(emptyList(), "메시지", observeFirst = false).toList()
        val first = model.inputs[0]
        assertEquals(listOf(InputItem.UserText("메시지")), first)
        assertTrue(first.none { it is InputItem.UserText && it.text.contains("# Screen") })
        assertTrue(first.none { it is InputItem.UserImage })
        assertTrue(r.dev.calls.toString(), r.dev.calls.isEmpty())
    }

    @Test fun historyPrependedBeforeMessage() = runTest {
        val model = FakeModelClient(listOf(finish()))
        val history = listOf(InputItem.UserText("이전 질문"), InputItem.AssistantText("이전 답"))
        rig(model).loop.run(history, "새 질문", observeFirst = true).toList()
        val first = model.inputs[0]
        assertEquals(history + InputItem.UserText("새 질문"), first.take(3))
        assertTrue((first[3] as InputItem.UserText).text.startsWith("# Screen"))
    }

    @Test fun textDeltasForwarded() = runTest {
        val model = FakeModelClient(
            listOf(turn(call("tap", """{"index":1}"""), text = "눌러요"), ModelTurn("끝났어요", emptyList(), null)),
            deltas = mapOf(1 to listOf("눌러", "요"), 2 to listOf("끝", "났어요")),
        )
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = false).toList()
        val deltas = events.filterIsInstance<AgentEvent.TextDelta>().map { it.text }
        assertEquals(listOf("눌러", "요", "끝", "났어요"), deltas)
        // Each turn's deltas arrive before that turn's actions and the final answer.
        assertTrue(events.indexOf(AgentEvent.TextDelta("요")) < events.indexOfFirst { it is AgentEvent.Acting })
        assertTrue(events.indexOfFirst { it is AgentEvent.Acting } < events.indexOf(AgentEvent.TextDelta("끝")))
        assertEquals(AgentEvent.Finished("끝났어요"), events.last())
    }

    @Test fun cancellationStopsPromptly() = runTest {
        val model = FakeModelClient(listOf(finish()), delayMs = 10_000)
        var cause: Throwable? = null
        val job = launch {
            rig(model).loop.run(emptyList(), "t", observeFirst = true).onCompletion { cause = it }.toList()
        }
        delay(100)
        job.cancel()
        job.join()
        assertTrue(currentTime < 1000)
        assertTrue(cause is CancellationException)
        assertEquals(1, model.calls)
    }

    @Test fun webSearchesAndCitationsBecomeEvents() = runTest {
        val model = object : ModelClient {
            override suspend fun next(instructions: String, input: List<InputItem>, tools: List<ToolSpec>, onTextDelta: (String) -> Unit) =
                error("the loop should report searches")

            override suspend fun next(
                instructions: String,
                input: List<InputItem>,
                tools: List<ToolSpec>,
                onTextDelta: (String) -> Unit,
                onSearch: (com.phoneuse.model.WebSearchCall) -> Unit,
            ): ModelTurn {
                onSearch(com.phoneuse.model.WebSearchCall("ws_1", listOf("전시")))
                onTextDelta("답")
                return ModelTurn("답", emptyList(), null, listOf(com.phoneuse.model.Citation("A", "https://a.example")))
            }
        }
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = false).toList()
        assertEquals(
            listOf(
                AgentEvent.Thinking,
                AgentEvent.Searching(listOf("전시"), "ws_1"),
                AgentEvent.TextDelta("답"),
                AgentEvent.Sources(listOf(com.phoneuse.model.Citation("A", "https://a.example"))),
                AgentEvent.Finished("답"),
            ),
            events,
        )
    }

    @Test fun retryAfterSearchResetsTheTurn() = runTest {
        var call = 0
        val model = object : ModelClient {
            override suspend fun next(instructions: String, input: List<InputItem>, tools: List<ToolSpec>, onTextDelta: (String) -> Unit) =
                error("unused")

            override suspend fun next(
                instructions: String,
                input: List<InputItem>,
                tools: List<ToolSpec>,
                onTextDelta: (String) -> Unit,
                onSearch: (com.phoneuse.model.WebSearchCall) -> Unit,
            ): ModelTurn {
                call++
                onSearch(com.phoneuse.model.WebSearchCall("ws_$call", listOf("q")))
                if (call == 1) throw ModelException(500, "boom")
                return ModelTurn("답", emptyList(), null)
            }
        }
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = false).toList()
        val searchAndReset = events.filter { it is AgentEvent.Searching || it == AgentEvent.TextReset }
        assertEquals(
            listOf(AgentEvent.Searching(listOf("q"), "ws_1"), AgentEvent.TextReset, AgentEvent.Searching(listOf("q"), "ws_2")),
            searchAndReset,
        )
    }

    @Test fun appTipsAreAddedOncePerAppAndASuccessfulDeviceTaskIsReported() = runTest {
        val model = FakeModelClient(listOf(tap(), tap(), finish()))
        val dev = FakeDeviceController(listOf(snap))
        val asked = mutableListOf<String>()
        val loop = AgentLoop(
            model, ToolExecutor(dev, FakeUserGate()), SteeringQueue(),
            instructions = "custom prompt",
            appTips = { pkg ->
                asked += pkg
                "[앱 조작 요령 · $pkg] 검색 → 탭"
            },
        )
        val events = loop.run(emptyList(), "t", observeFirst = true).toList()
        // Looked up once for com.app, and the note is in the very first model input.
        assertEquals(listOf("com.app"), asked)
        assertTrue(model.inputs[0].any { it is InputItem.UserText && it.text.startsWith("[앱 조작 요령 · com.app]") })
        val done = events.filterIsInstance<AgentEvent.DeviceTaskDone>().single()
        assertEquals("com.app", done.packageName)
        assertEquals(listOf("탭: A", "탭: A"), done.actions)
        assertTrue(events.indexOf(done) < events.indexOfFirst { it is AgentEvent.Finished })
    }

    @Test fun failedFinishIsNotATip() = runTest {
        val model = FakeModelClient(listOf(tap(), turn(call("finish", """{"summary":"못 했어요","success":false}"""))))
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertTrue(events.none { it is AgentEvent.DeviceTaskDone })
        assertEquals(AgentEvent.Finished("못 했어요"), events.last())
    }

    private fun field(hint: String, y: Int) = UiNode(
        windowId = 0, path = listOf(y), className = "android.widget.EditText", text = null,
        contentDescription = null, hint = hint, viewId = null,
        bounds = Bounds(100, y, 900, y + 60), clickable = true, editable = true,
    )

    @Test fun tipStepsNeverKeepTypedTextAndSkipCodeFields() = runTest {
        val form = ScreenSnapshot("com.app", 1080, 2400, listOf(field("받는 사람", 100), field("인증번호", 200)))
        val model = FakeModelClient(
            listOf(
                turn(call("type_text", """{"index":1,"text":"엄마 010-1234-5678"}""")),
                turn(call("type_text", """{"index":2,"text":"482913"}""")),
                turn(call("finish", """{"summary":"엄마에게 \"사랑해요\" 보냈어요. 끝"}""")),
            ),
        )
        val dev = FakeDeviceController(listOf(form))
        val loop = AgentLoop(model, ToolExecutor(dev, FakeUserGate()), SteeringQueue(), appTips = { null })
        val events = loop.run(emptyList(), "t", observeFirst = true).toList()
        val done = events.filterIsInstance<AgentEvent.DeviceTaskDone>().single()
        assertEquals(listOf("입력: 받는 사람"), done.actions)
        assertTrue(done.toString(), done.actions.none { "010" in it || "482913" in it })
        assertTrue(done.summary.startsWith("엄마에게"))
    }

    @Test fun noDeviceTaskDoneWhenTipsAreOff() = runTest {
        // An AgentLoop without appTips (memory off) neither records nor reports a tip.
        val model = FakeModelClient(listOf(tap(), finish()))
        val events = rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertTrue(events.none { it is AgentEvent.DeviceTaskDone })
        assertEquals(AgentEvent.Finished("완료"), events.last())
    }

    // ---- prompt cache and history weight ------------------------------------------------------

    @Test fun memoryNoteGoesJustBeforeTheMessageAsDeveloperText() = runTest {
        val model = FakeModelClient(listOf(finish()))
        val history = listOf(InputItem.UserText("전에"), InputItem.AssistantText("네"))
        rig(model).loop.run(history, "엄마 선물 추천", observeFirst = false, note = "기억: [4] 엄마는 등산").toList()
        val first = model.inputs[0]
        assertEquals(
            listOf(history[0], history[1], InputItem.DeveloperText("기억: [4] 엄마는 등산"), InputItem.UserText("엄마 선물 추천")),
            first.take(4),
        )
    }

    @Test fun onlyTheLatestScreenIsSentInFull() = runTest {
        val model = FakeModelClient(listOf(tap(), tap(), finish()))
        rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        fun texts(input: List<InputItem>) = input.mapNotNull {
            when (it) {
                is InputItem.UserText -> it.text
                is InputItem.FunctionOutput -> it.output
                else -> null
            }
        }
        val third = texts(model.inputs[2])
        assertEquals(third.toString(), 1, third.count { "# Screen\napp: com.app" in it })
        val digest = "# Screen\n[earlier screen: com.app · A | B | C | D]"
        assertEquals(third.toString(), 2, third.count { it.endsWith(digest) })
        // The newest one is the last tool result, and the result line before a cut screen stays.
        assertTrue(third.last().startsWith("OK\n\nSeen before") && third.last().contains("# Screen\napp: com.app"))
        assertTrue(third.any { it.startsWith("OK\n\nSeen before") && it.endsWith(digest) })
        // What an earlier request sent is not changed afterwards.
        assertEquals(1, texts(model.inputs[0]).count { "# Screen\napp: com.app" in it })
    }

    @Test fun textThatLooksLikeAScreenIsNeverCut() = runTest {
        val model = FakeModelClient(listOf(turn(call("list_apps")), tap(), tap(), finish()))
        val dev = FakeDeviceController(listOf(snap))
        dev.apps = listOf(AppInfo("com.x", "# Screen\n가짜"))
        rig(model, dev = dev).loop.run(emptyList(), "t", observeFirst = false).toList()
        val last = model.inputs.last()
        assertTrue(last.toString(), last.any { it is InputItem.FunctionOutput && it.output == "# Screen\n가짜 (com.x)" })
    }

    @Test fun reportsStepTimings() = runTest {
        val model = FakeModelClient(listOf(tap(), finish()))
        val timings = mutableListOf<StepTimings>()
        val dev = FakeDeviceController(listOf(snap))
        val loop = AgentLoop(model, ToolExecutor(dev, FakeUserGate()), SteeringQueue(), onTimings = { timings += it })
        loop.run(emptyList(), "t", observeFirst = false).toList()
        assertEquals(listOf(1, 2), timings.map { it.step })
        assertEquals(listOf("tap"), timings[0].tools)
        assertTrue(timings[0].screenChars > 0)
        assertTrue(timings[0].inputChars > 0)
        assertEquals(listOf("finish"), timings[1].tools)
    }

    private fun delegate(task: String = "결과 목록을 끝까지 내리며 상품명과 가격을 모아") =
        turn(call("delegate", """{"task":"$task","done_when":"목록 끝"}"""))

    @Test fun delegateRunsTheHelperAndHandsBackItsReport() = runTest {
        val main = FakeModelClient(listOf(delegate(), finish()))
        val helper = FakeModelClient(
            listOf(
                turn(call("scroll", """{"direction":"down"}""")),
                turn(call("report", """{"result":"A 1000원\nB 2000원","ok":true}""")),
            ),
        )
        val r = rig(main, helper = helper)
        val events = r.loop.run(emptyList(), "최저가 찾아줘", observeFirst = true).toList()
        assertTrue(r.dev.calls.any { it.startsWith("scroll") })
        assertTrue((helper.inputs[0][0] as InputItem.UserText).text.startsWith("Task: 결과 목록"))
        assertTrue("helper offers only in-app tools", helper.toolNames[0].containsAll(listOf("scroll", "tap", "report")))
        assertTrue(helper.toolNames[0].none { it == "open_app" || it == "delegate" || it == "finish" })
        val back = main.inputs[1].last() as InputItem.FunctionOutput
        assertTrue(back.output, back.output.startsWith("DELEGATE DONE (helper steps: 2, scrolls: 1)\nA 1000원"))
        assertTrue(back.output.contains("# Screen"))
        assertTrue(events.contains(AgentEvent.Acting("빠른 도우미: 결과 목록을 끝까지 내리며 상품명과 가격을 모아")))
        assertTrue(events.any { it is AgentEvent.Acting && it.description.startsWith("⚡ ") })
        assertEquals(AgentEvent.Finished("완료"), events.last())
    }

    @Test fun delegateIsOfferedOnlyWithAHelper() = runTest {
        val main = FakeModelClient(listOf(finish()))
        rig(main).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertTrue(main.toolNames[0].none { it == "delegate" })
        val withHelper = FakeModelClient(listOf(finish()))
        rig(withHelper, helper = FakeModelClient(emptyList())).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertTrue(withHelper.toolNames[0].contains("delegate"))
    }

    @Test fun aFailingHelperHandsTheWorkBackAndTheToolListStaysTheSame() = runTest {
        val main = FakeModelClient(listOf(delegate(), delegate(), finish()))
        val helper = FakeModelClient(listOf(ModelException(400, "unknown model")))
        rig(main, helper = helper).loop.run(emptyList(), "찾아줘", observeFirst = true).toList()
        val back = main.inputs[1].last() as InputItem.FunctionOutput
        assertTrue(back.output, back.output.startsWith("DELEGATE INCOMPLETE") && back.output.contains("helper unavailable"))
        // delegate stays listed (the prompt cache), and answers with an error without calling the helper again.
        assertEquals(main.toolNames[0], main.toolNames[1])
        val again = main.inputs[2].last() as InputItem.FunctionOutput
        assertTrue(again.output, again.output.startsWith("ERROR: the helper is unavailable"))
        assertEquals(1, helper.calls)
    }

    @Test fun aHelperNetworkBlipIsRetried() = runTest {
        val main = FakeModelClient(listOf(delegate(), finish()))
        val helper = FakeModelClient(
            listOf(ModelException(null, "io"), ModelException(429, "slow"), turn(call("report", """{"result":"A 1000원","ok":true}"""))),
        )
        rig(main, helper = helper).loop.run(emptyList(), "찾아줘", observeFirst = true).toList()
        val back = main.inputs[1].last() as InputItem.FunctionOutput
        assertTrue(back.output, back.output.startsWith("DELEGATE DONE"))
        assertEquals(3, helper.calls)
    }

    @Test fun theHelperRunsOnlyItsFirstCallPerTurn() = runTest {
        val main = FakeModelClient(listOf(delegate(), finish()))
        val first = call("tap", """{"index":1}""")
        val second = call("tap", """{"index":2}""")
        val helper = FakeModelClient(listOf(turn(first, second), turn(call("report", """{"result":"done"}"""))))
        val r = rig(main, helper = helper)
        r.loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(r.dev.calls.toString(), 1, r.dev.calls.count { it.startsWith("tap") || it.startsWith("click") })
        val outs = helper.inputs[1].filterIsInstance<InputItem.FunctionOutput>().associate { it.callId to it.output }
        assertTrue(outs.getValue(first.callId).startsWith("OK"))
        assertTrue(outs.getValue(second.callId).startsWith("NOT RUN"))
    }

    @Test fun theHelperIsToldOnItsLastStepToReport() = runTest {
        val main = FakeModelClient(listOf(turn(call("delegate", """{"task":"모아","max_steps":2}""")), finish()))
        val helper = FakeModelClient(listOf(turn(call("scroll", """{"direction":"down"}""")), turn(call("report", """{"result":"A, B","ok":false}"""))))
        rig(main, helper = helper).loop.run(emptyList(), "t", observeFirst = true).toList()
        assertTrue(helper.inputs[0].none { it is InputItem.UserText && it.text.startsWith("This is your last step") })
        assertTrue(helper.inputs[1].any { it is InputItem.UserText && it.text.startsWith("This is your last step") })
        val back = main.inputs[1].last() as InputItem.FunctionOutput
        assertTrue(back.output, back.output.contains("A, B"))
    }

    @Test fun theHelpersUserAuthorizedNeverSkipsASendCard() = runTest {
        val chat = ScreenSnapshot("com.app", 1080, 2400, listOf(button("보내기", 100)))
        val gate = FakeUserGate().apply { confirmResult = false }
        val main = FakeModelClient(listOf(delegate("답장 보내"), finish()))
        val helper = FakeModelClient(listOf(turn(call("tap", """{"index":1,"user_authorized":true}"""))))
        val r = rig(main, dev = FakeDeviceController(listOf(chat)), helper = helper, gate = gate)
        r.loop.run(emptyList(), "답장 보내", observeFirst = true).toList()
        assertEquals(RiskCategory.SEND, gate.confirms.single().first)
        assertTrue(r.dev.calls.none { it.startsWith("tap") })
        // Nor is the flag offered to it.
        val tapSpec = helper.specs[0].first { it.name == "tap" }
        assertTrue(tapSpec.parameters.toString(), "user_authorized" !in tapSpec.parameters.toString())
    }

    @Test fun delegateAsTheFirstDeviceCallSignalsDeviceUseBeforeTheHelperLooks() = runTest {
        val main = FakeModelClient(listOf(delegate(), finish()))
        val helper = FakeModelClient(listOf(turn(call("report", """{"result":"x"}"""))))
        val r = rig(main, helper = helper)
        var snapshotsAtDeviceUse = -1
        val events = r.loop.run(emptyList(), "t", observeFirst = false)
            .onEach { if (it == AgentEvent.DeviceUse) snapshotsAtDeviceUse = r.dev.calls.count { c -> c == "snapshot()" } }
            .toList()
        assertTrue(events.contains(AgentEvent.DeviceUse))
        assertEquals(0, snapshotsAtDeviceUse)
    }

    @Test fun aMessageSentWhileTheHelperWorksStopsIt() = runTest {
        val main = FakeModelClient(listOf(delegate(), finish()))
        lateinit var r: Rig
        val helper = FakeModelClient(List(5) { turn(call("scroll", """{"direction":"down"}""")) }, onCall = { n -> if (n == 2) r.steering.post("그만, 첫 번째로") })
        r = rig(main, helper = helper)
        r.loop.run(emptyList(), "t", observeFirst = true).toList()
        assertEquals(2, helper.calls)
        val back = main.inputs[1].filterIsInstance<InputItem.FunctionOutput>().last()
        assertTrue(back.output, back.output.contains("the user sent a new instruction"))
        assertTrue(main.inputs[1].any { it is InputItem.UserText && it.text == "사용자 추가 지시: 그만, 첫 번째로" })
    }

    @Test fun aMessageSentWhileTheFinalAnswerIsWrittenIsFollowed() = runTest {
        lateinit var r: Rig
        val model = FakeModelClient(
            listOf(ModelTurn("3만원대 추천: A", emptyList(), null), ModelTurn("3만원 이하: B", emptyList(), null)),
            onCall = { n -> if (n == 1) r.steering.post("아니 3만원 이하로만") },
        )
        r = rig(model)
        val events = r.loop.run(emptyList(), "추천해줘", observeFirst = false).toList()
        assertEquals(AgentEvent.Finished("3만원 이하: B"), events.last())
        assertEquals(1, events.count { it is AgentEvent.Finished })
        val second = model.inputs[1]
        assertTrue(second.any { it is InputItem.AssistantText && it.text == "3만원대 추천: A" })
        assertTrue(second.last().let { it is InputItem.UserText && it.text == "사용자 추가 지시: 아니 3만원 이하로만" })
    }

    @Test fun aMessageSentWhileFinishingOverridesTheFinish() = runTest {
        lateinit var r: Rig
        val model = FakeModelClient(
            listOf(turn(call("finish", """{"summary":"끝"}"""), call("recall", """{"query":"x"}""")), finish()),
            onCall = { n -> if (n == 1) r.steering.post("하나 더") },
        )
        r = rig(model)
        val events = r.loop.run(emptyList(), "t", observeFirst = false).toList()
        assertEquals(AgentEvent.Finished("완료"), events.last())
        assertEquals(1, events.count { it is AgentEvent.Finished })
        val outs = model.inputs[1].filterIsInstance<InputItem.FunctionOutput>().map { it.output }
        assertTrue(outs.toString(), outs[0].startsWith("NOT FINISHED"))
        assertTrue(outs.toString(), outs[1].startsWith("NOT RUN: the user sent a new instruction"))
    }

    @Test fun longRunsAreTrimmedInOneCutAndOverflowCompactsAndRetriesOnce() = runTest {
        val big = "x".repeat(5_000)
        val dev = FakeDeviceController(listOf(snap)).apply { apps = List(1) { AppInfo("p.$big", "L") } }
        val thought = { n: Int -> InputItem.Reasoning(kotlinx.serialization.json.JsonObject(mapOf("encrypted_content" to kotlinx.serialization.json.JsonPrimitive("r$n".padEnd(2_000, 'r'))))) }
        val script = ArrayList<Any>()
        for (n in 1..20) script += ModelTurn(null, listOf(call("list_apps")), null, reasoning = listOf(thought(n)))
        script += ModelException(400, """{"error":{"code":"context_length_exceeded","message":"too long"}}""", code = "context_length_exceeded")
        script += finish()
        val model = FakeModelClient(script)
        val steering = SteeringQueue()
        val loop = AgentLoop(model, ToolExecutor(dev, FakeUserGate()), steering, 60, inputBudgetChars = 60_000)
        val events = loop.run(emptyList(), "t", observeFirst = false).toList()
        assertEquals(AgentEvent.Finished("완료"), events.last())
        fun size(input: List<InputItem>) = input.sumOf {
            when (it) {
                is InputItem.FunctionOutput -> it.output.length
                is InputItem.Reasoning -> it.raw.toString().length
                else -> 0
            }
        }
        assertTrue(model.inputs.all { size(it) <= 60_000 + 8_000 })
        // Inputs only shrink at a cut; between cuts each input starts with the one before (the cached prefix).
        val cuts = model.inputs.zipWithNext().count { (a, b) -> b.size < a.size || b.subList(0, a.size) != a }
        assertTrue("cuts: $cuts", cuts in 2..5)
        val last = model.inputs.last()
        assertTrue(last.filterIsInstance<InputItem.FunctionOutput>().first().output.endsWith("[…trimmed]"))
        assertEquals(2, last.count { it is InputItem.Reasoning })
    }

    @Test fun theHelperCannotLeaveTheAppAndStillHitsTheRiskGate() = runTest {
        val pay = ScreenSnapshot("com.app", 1080, 2400, listOf(button("결제하기", 100)))
        val gate = FakeUserGate().apply { confirmResult = false }
        val main = FakeModelClient(listOf(delegate("버튼 눌러"), finish()))
        val helper = FakeModelClient(
            listOf(
                turn(call("open_app", """{"name":"x"}""")),
                turn(call("tap", """{"index":1}""")),
            ),
        )
        val r = rig(main, dev = FakeDeviceController(listOf(pay)), helper = helper, gate = gate)
        r.loop.run(emptyList(), "t", observeFirst = true).toList()
        assertTrue(r.dev.calls.none { it.startsWith("launchApp") || it.startsWith("tap") })
        assertEquals(1, gate.confirms.size)
        val back = main.inputs[1].last() as InputItem.FunctionOutput
        assertTrue(back.output, back.output.startsWith("DELEGATE INCOMPLETE") && back.output.contains("cancelled"))
    }

    @Test fun theModelsReasoningGoesBackBeforeItsCalls() = runTest {
        val thought = InputItem.Reasoning(kotlinx.serialization.json.JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("reasoning"))))
        val first = ModelTurn(null, listOf(call("tap", """{"index":1}""")), null, reasoning = listOf(thought))
        val model = FakeModelClient(listOf(first, finish()))
        rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        val next = model.inputs[1]
        val at = next.indexOf(thought)
        assertTrue(at >= 0)
        assertTrue(next[at + 1] is InputItem.FunctionCall)
    }

    @Test fun aRevisitedScreenSaysSoAndOlderScreensKeepADigest() = runTest {
        val model = FakeModelClient(listOf(tap(), tap(), finish()))
        rig(model).loop.run(emptyList(), "t", observeFirst = true).toList()
        val firstTap = model.inputs[1].filterIsInstance<InputItem.FunctionOutput>().last()
        assertTrue(firstTap.output, firstTap.output.contains("Seen before: this screen looks the same as after step 0 (start) (2 visits)."))
        val later = model.inputs[2]
        val earlier = later.filterIsInstance<InputItem.FunctionOutput>().first()
        assertTrue(earlier.output, earlier.output.endsWith("[earlier screen: com.app · A | B | C | D]"))
        val second = later.filterIsInstance<InputItem.FunctionOutput>().last()
        assertTrue(second.output, second.output.contains("after step 0 (start), step 1 (탭: A) (3 visits)"))
    }

    // ---- request scenarios: the harness lets each request take its own shape ------------------

    @Test fun aWeatherQuestionNeverTouchesThePhone() = runTest {
        val model = FakeModelClient(listOf(ModelTurn("지금 강남구는 맑고 12도예요.", emptyList(), null)))
        val r = rig(model)
        val events = r.loop.run(emptyList(), "날씨 알려줘", observeFirst = false).toList()
        assertEquals(1, model.calls)
        assertTrue(r.dev.calls.isEmpty())
        assertTrue(events.none { it == AgentEvent.DeviceUse })
        assertEquals(AgentEvent.Finished("지금 강남구는 맑고 12도예요."), events.last())
    }

    @Test fun aSimpleTaskMayFinishOnALongListWithoutBeingSentBack() = runTest {
        val list = UiNode(
            windowId = 0, path = listOf(9), className = "RecyclerView", text = "설정", contentDescription = null,
            hint = null, viewId = null, bounds = Bounds(0, 500, 1080, 2400), scrollable = true, moreAfter = true,
        )
        val model = FakeModelClient(listOf(tap(), finish()))
        val events = rig(model, dev = FakeDeviceController(listOf(snap.copy(roots = snap.roots + list))))
            .loop.run(emptyList(), "와이파이 꺼줘", observeFirst = true).toList()
        assertEquals(2, model.calls)
        assertEquals(AgentEvent.Finished("완료"), events.last())
    }

    @Test fun shoppingComparesThroughTheHelperAndAddsToCartWithoutAPayConfirm() = runTest {
        fun node(text: String, y: Int, scrollable: Boolean = false) = UiNode(
            windowId = 0, path = listOf(y), className = if (scrollable) "RecyclerView" else "View", text = text,
            contentDescription = null, hint = null, viewId = null, bounds = Bounds(0, y, 1080, y + 150),
            clickable = !scrollable, scrollable = scrollable, moreAfter = scrollable,
        )
        val coupang = ScreenSnapshot(
            "com.coupang.mobile", 1080, 2400,
            listOf(
                node("세라비 로션 473ml 19,900원 4.8 (12,345) 1만명 이상 구매", 200),
                node("장바구니 담기", 400),
                node("바로구매", 600),
                node("결과", 800, scrollable = true),
            ),
        )
        val gate = FakeUserGate()
        val main = FakeModelClient(
            listOf(
                turn(call("delegate", """{"task":"결과를 끝까지 내리며 이름, 가격, 평점, 리뷰 수를 모아","done_when":"목록 끝"}""")),
                turn(call("tap", """{"index":1}""")),
                turn(call("tap", """{"index":2}""")),
                turn(call("finish", """{"summary":"세라비 로션 473ml를 담았어요 (평점 4.8, 리뷰 1.2만, ml당 42원)."}""")),
            ),
        )
        val helper = FakeModelClient(
            listOf(
                turn(call("scroll", """{"direction":"down"}""")),
                turn(call("scroll", """{"direction":"down"}""")),
                turn(call("report", """{"result":"세라비 473ml 19,900원 4.8 (12,345)\nA 로션 400ml 15,900원 4.5 (2,100)"}""")),
            ),
        )
        val r = rig(main, dev = FakeDeviceController(listOf(coupang)), helper = helper, gate = gate)
        val events = r.loop.run(emptyList(), "쿠팡에서 로션 담아줘", observeFirst = true).toList()
        // The helper scrolled; the product card and the cart button went through without a pay confirmation.
        assertEquals(2, r.dev.calls.count { it.startsWith("scroll") })
        assertEquals(2, r.dev.calls.count { it.startsWith("tap") })
        assertTrue(gate.confirms.toString(), gate.confirms.isEmpty())
        val report = main.inputs[1].last() as InputItem.FunctionOutput
        assertTrue(report.output, report.output.startsWith("DELEGATE DONE (helper steps: 3, scrolls: 2)"))
        assertTrue(events.last().toString(), events.last() is AgentEvent.Finished)
    }

    @Test fun buyingStillAsksFirst() = runTest {
        val coupang = ScreenSnapshot("com.coupang.mobile", 1080, 2400, listOf(button("바로구매", 600)))
        val gate = FakeUserGate().apply { confirmResult = false }
        val main = FakeModelClient(listOf(tap(), finish()))
        val r = rig(main, dev = FakeDeviceController(listOf(coupang)), gate = gate)
        r.loop.run(emptyList(), "로션 사줘", observeFirst = true).toList()
        assertEquals(1, gate.confirms.size)
        assertTrue(r.dev.calls.none { it.startsWith("tap") })
    }
}
