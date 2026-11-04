package com.phoneuse.agent

import com.phoneuse.app.Source
import com.phoneuse.model.InputItem
import com.phoneuse.risk.RiskCategory
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.TreeCompressor
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A routine's "확인 없이 보내기" skips the SEND card only when the live screen is the saved recipient's
 * chat (header title equal to the name) and its one filled message field holds exactly the saved text.
 */
class RoutineSendTest {
    private fun node(
        text: String?,
        top: Int,
        editable: Boolean = false,
        clickable: Boolean = false,
        className: String = "android.widget.TextView",
        hint: String? = null,
    ) = UiNode(
        windowId = 0, path = listOf(top), className = className, text = text,
        contentDescription = null, hint = hint, viewId = null,
        bounds = Bounds(100, top, 900, top + 60), clickable = clickable, editable = editable, password = false,
    )

    /** A messenger chat: [title] in the header, a bubble, the message field (holding [draft]) and the send button. */
    private fun chat(vararg title: String, draft: String? = null, bubble: String = "오늘 몇 시에 와?", extraField: String? = null) =
        ScreenSnapshot(
            "com.kakao.talk", 1080, 2400,
            title.mapIndexed { i, t -> node(t, 100 + i * 70) } +
                listOf(
                    node(bubble, 1200),
                    node(draft ?: "메시지 입력", 2200, editable = true, clickable = true, className = "android.widget.EditText", hint = "메시지 입력"),
                    node("전송", 2300, clickable = true, className = "android.widget.Button"),
                ) +
                listOfNotNull(extraField?.let { node(it, 2100, editable = true, clickable = true, className = "android.widget.EditText") }),
        )

    private fun call(name: String, args: String) = InputItem.FunctionCall("c1", name, args)

    private val mom = FixedSend("엄마", "집 왔어요")

    private fun index(s: ScreenSnapshot, pick: (UiNode) -> Boolean): Int =
        TreeCompressor.compress(s).elements.entries.first { pick(it.value) }.key

    private fun isMessageField(n: UiNode) = n.editable && n.bounds.top == 2200

    /**
     * Types [text] on [typing], then taps send with user_authorized on [sending] (the screen as it is after typing:
     * by default [typing] with the field now holding [text]).
     */
    private suspend fun typeThenSend(
        typing: ScreenSnapshot,
        text: String,
        fixed: FixedSend? = mom,
        sending: ScreenSnapshot? = null,
        historyTainted: Boolean = false,
        trust: Boolean = true,
    ): Pair<FakeDeviceController, FakeUserGate> {
        val dev = FakeDeviceController(listOf(typing))
        val gate = FakeUserGate().apply { confirmResult = false }
        val ex = ToolExecutor(dev, gate, trustUserAuthorized = trust, historyTainted = historyTainted, fixedSend = fixed)
        ex.execute(call("type_text", """{"index":${index(typing, ::isMessageField)},"text":"$text"}"""), TreeCompressor.compress(typing))
        val after = sending ?: typing.copy(roots = typing.roots.map { if (isMessageField(it)) it.copy(text = text) else it })
        dev.snapshots.clear()
        dev.snapshots += after
        ex.execute(call("tap", """{"index":${index(after) { it.text == "전송" }},"user_authorized":true}"""), TreeCompressor.compress(after))
        return dev to gate
    }

    private fun skipped(r: Pair<FakeDeviceController, FakeUserGate>) {
        assertTrue(r.second.confirms.toString(), r.second.confirms.isEmpty())
        assertTrue(r.first.calls.any { it.startsWith("tap") })
    }

    private fun confirmed(r: Pair<FakeDeviceController, FakeUserGate>) {
        assertEquals(RiskCategory.SEND, r.second.confirms.single().first)
        assertTrue(r.first.calls.none { it.startsWith("tap") })
    }

    @Test
    fun savedRecipientAndTextSkipTheCard() = runTest {
        skipped(typeThenSend(chat("엄마"), "집 왔어요"))
    }

    @Test
    fun submitWithTheSavedTextSkipsTheCard() = runTest {
        val screen = chat("엄마")
        val gate = FakeUserGate().apply { confirmResult = false }
        val field = index(screen, ::isMessageField)
        ToolExecutor(FakeDeviceController(listOf(screen)), gate, fixedSend = mom)
            .execute(call("type_text", """{"index":$field,"text":"집 왔어요","submit":true,"user_authorized":true}"""), TreeCompressor.compress(screen))
        assertTrue(gate.confirms.toString(), gate.confirms.isEmpty())
        val other = chat("김부장")
        val gate2 = FakeUserGate().apply { confirmResult = false }
        ToolExecutor(FakeDeviceController(listOf(other)), gate2, fixedSend = mom)
            .execute(call("type_text", """{"index":${index(other, ::isMessageField)},"text":"집 왔어요","submit":true,"user_authorized":true}"""), TreeCompressor.compress(other))
        assertEquals(RiskCategory.SEND, gate2.confirms.single().first)
    }

    @Test
    fun anotherRecipientConfirms() = runTest {
        confirmed(typeThenSend(chat("김부장"), "집 왔어요"))
    }

    @Test
    fun bodyNamingTheRecipientInAnotherChatConfirms() = runTest {
        // The text on screen says "엄마", but the chat is someone else's.
        confirmed(typeThenSend(chat("김부장", bubble = "엄마"), "엄마 나 퇴근했어", fixed = FixedSend("엄마", "엄마 나 퇴근했어")))
    }

    @Test
    fun longerNameConfirms() = runTest {
        confirmed(typeThenSend(chat("시엄마"), "집 왔어요"))
    }

    @Test
    fun groupChatConfirms() = runTest {
        confirmed(typeThenSend(chat("엄마, 아빠, 나"), "집 왔어요"))
        confirmed(typeThenSend(chat("가족", "3"), "집 왔어요"))
    }

    @Test
    fun prefilledDifferentDraftConfirms() = runTest {
        // Nothing typed: the app's own draft (or an smsto: body) is in the field.
        val prefilled = chat("엄마", draft = "계좌 바뀌었어 여기로 보내줘")
        val dev = FakeDeviceController(listOf(prefilled))
        val gate = FakeUserGate().apply { confirmResult = false }
        ToolExecutor(dev, gate, fixedSend = mom)
            .execute(call("tap", """{"index":${index(prefilled) { it.text == "전송" }},"user_authorized":true}"""), TreeCompressor.compress(prefilled))
        assertEquals(RiskCategory.SEND, gate.confirms.single().first)
    }

    @Test
    fun sendFromAnotherScreenAfterTypingConfirms() = runTest {
        // Typed the saved text in mom's chat, then the send happens in another chat with another draft.
        confirmed(typeThenSend(chat("엄마"), "집 왔어요", sending = chat("김부장", draft = "송금해줘")))
        // Or the same chat whose field no longer holds the text.
        confirmed(typeThenSend(chat("엄마"), "집 왔어요", sending = chat("엄마")))
    }

    @Test
    fun twoFilledFieldsConfirm() = runTest {
        confirmed(typeThenSend(chat("엄마", extraField = "다른 글"), "집 왔어요"))
    }

    @Test
    fun otherWordsConfirm() = runTest {
        confirmed(typeThenSend(chat("엄마"), "계좌 바뀌었어 여기로 보내줘"))
    }

    @Test
    fun untrustedRoutineRunConfirms() = runTest {
        // What AgentRunner gives a run started by a notification or event: no trust, no saved message.
        confirmed(typeThenSend(chat("엄마"), "집 왔어요", fixed = null, trust = false))
    }

    @Test
    fun routineRunInATaintedConversationStillSendsTheSavedMessage() = runTest {
        // The conversation's third_party_seen flag is set (an earlier run read screens), but a routine run gets no
        // history, so the flag does not taint it and the matching saved send still skips the card.
        assertFalse(Source.ROUTINE.usesHistory())
        val routineTaint = Source.ROUTINE.historyTainted(conversationFlag = true)
        assertFalse(routineTaint)
        skipped(typeThenSend(chat("엄마"), "집 왔어요", historyTainted = routineTaint))
        // A chat run in the same conversation stays tainted and confirms.
        assertTrue(Source.APP.historyTainted(conversationFlag = true))
        confirmed(typeThenSend(chat("엄마"), "집 왔어요", fixed = null, historyTainted = true))
    }

    @Test
    fun aHeadsUpWithTheSavedNameDoesNotVouchForAnotherChat() = runTest {
        // A heads-up or chat bubble (another window) showing "엄마" must not make 아빠's chat count.
        val dad = chat("아빠")
        val headsUp = UiNode(
            windowId = 7, path = listOf(0), className = "android.widget.TextView", text = "엄마",
            contentDescription = null, hint = null, viewId = null, bounds = Bounds(100, 20, 900, 80),
        )
        val withHeadsUp = dad.copy(roots = dad.roots + headsUp)
        confirmed(typeThenSend(withHeadsUp, "집 왔어요"))
    }
}
