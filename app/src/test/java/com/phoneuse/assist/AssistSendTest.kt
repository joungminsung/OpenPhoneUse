package com.phoneuse.assist

import com.phoneuse.app.AssistAction
import com.phoneuse.chat.Conversation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistSendTest {
    @Test
    fun recentConversationIsTheNewestUpdated() {
        val list = listOf(Conversation(1, "a", 100), Conversation(2, "b", 300), Conversation(3, "c", 200))
        assertEquals(2L, recentConversation(list)?.id)
        assertNull(recentConversation(emptyList()))
    }

    @Test
    fun targetIsChipConversationOnlyWhenSelected() {
        val recent = Conversation(7, "t", 1)
        assertEquals(7L, targetConversation(true, recent))
        assertNull(targetConversation(false, recent))
        assertNull(targetConversation(true, null))
    }

    @Test
    fun hidesFirstThenSendsAfterSettleDelay() {
        val scope = TestScope()
        val events = mutableListOf<String>()
        val sender = AssistSender(scope, send = { id, text -> events += "send:$id:$text"; true })

        assertEquals(SubmitResult.Sent, sender.submit("  다크 모드 켜줘 ", 5L) { events += "hide" })
        assertEquals(listOf("hide"), events)

        scope.advanceTimeBy(299)
        scope.runCurrent()
        assertEquals(listOf("hide"), events)

        scope.advanceTimeBy(1)
        scope.runCurrent()
        assertEquals(listOf("hide", "send:5:다크 모드 켜줘"), events)
    }

    @Test
    fun tappedSuggestionGoesAsSuggested() {
        val scope = TestScope()
        val events = mutableListOf<String>()
        val sender = AssistSender(
            scope,
            send = { _, text -> events += "typed:$text"; true },
            sendSuggested = { _, text -> events += "suggested:$text"; true },
        )
        sender.submit("리뷰 요약해 줘", null, suggested = true) {}
        sender.submit("직접 쓴 말", null) {}
        scope.advanceTimeBy(AssistSender.SETTLE_MS)
        scope.runCurrent()
        assertEquals(listOf("suggested:리뷰 요약해 줘", "typed:직접 쓴 말"), events)
    }

    @Test
    fun blankTextDoesNothing() {
        val scope = TestScope()
        var hidden = false
        val sender = AssistSender(scope, send = { _, _ -> error("must not send") })
        assertEquals(SubmitResult.Empty, sender.submit("   ", null) { hidden = true })
        scope.advanceTimeBy(1000)
        scope.runCurrent()
        assertFalse(hidden)
    }

    @Test
    fun blockedSendKeepsTheSheetOpenAndSendsNothing() {
        val scope = TestScope()
        var hidden = false
        val sender = AssistSender(
            scope,
            send = { _, _ -> error("must not send") },
            blocker = { AssistSender.BUSY },
        )
        assertEquals(SubmitResult.Blocked(AssistSender.BUSY), sender.submit("다크 모드 켜줘", null) { hidden = true })
        scope.advanceTimeBy(1000)
        scope.runCurrent()
        assertFalse(hidden)
    }

    @Test
    fun sendReturningFalseAfterTheWaitIsTolerated() {
        val scope = TestScope()
        val attempts = mutableListOf<String>()
        val sender = AssistSender(scope, send = { _, text -> attempts += text; false })
        assertEquals(SubmitResult.Sent, sender.submit("hi", null) {})
        scope.advanceTimeBy(300)
        scope.runCurrent()
        assertEquals(listOf("hi"), attempts)
        // The sender stays usable for the next message.
        assertEquals(SubmitResult.Sent, sender.submit("again", null) {})
        scope.advanceTimeBy(300)
        scope.runCurrent()
        assertEquals(listOf("hi", "again"), attempts)
    }

    @Test
    fun blockerReasons() {
        assertEquals(AssistSender.NO_SERVICE, assistBlocker(serviceReady = false, running = false))
        assertEquals(AssistSender.NO_SERVICE, assistBlocker(serviceReady = false, running = true))
        assertEquals(AssistSender.BUSY, assistBlocker(serviceReady = true, running = true))
        assertNull(assistBlocker(serviceReady = true, running = false))
    }

    @Test
    fun injectedWaitReceivesSettleTime() {
        val scope = TestScope()
        val waits = mutableListOf<Long>()
        var sentTo: Long? = -1
        val sender = AssistSender(scope, send = { id, _ -> sentTo = id; true }, wait = { waits += it })
        sender.submit("hi", null) {}
        scope.runCurrent()
        assertEquals(listOf(300L), waits)
        assertNull(sentTo)
    }

    private fun listensOnOpen(
        action: AssistAction? = AssistAction.TYPE,
        openedBySystem: Boolean = true,
        micGranted: Boolean = true,
        inSheetRecognizer: Boolean = true,
        canStartTask: Boolean = true,
        keyguardLocked: Boolean = false,
        screenOn: Boolean = true,
        touchExploration: Boolean = false,
    ) = shouldListenOnOpen(
        action, openedBySystem, micGranted, inSheetRecognizer, canStartTask, keyguardLocked, screenOn, touchExploration,
    )

    @Test
    fun onlyASavedTalkRightAwayListensOnOpen() {
        assertTrue(listensOnOpen())
        assertFalse(listensOnOpen(action = AssistAction.SUGGEST))
        // The saved mode could not be read.
        assertFalse(listensOnOpen(action = null))
    }

    @Test
    fun listeningOnOpenWaitsForATapWhereSpeakingIsUnsafeOrImpossible() {
        assertFalse(listensOnOpen(keyguardLocked = true))
        // The screen went dark between the long press and the moment the microphone would open.
        assertFalse(listensOnOpen(screenOn = false))
        assertFalse(listensOnOpen(touchExploration = true))
        assertFalse(listensOnOpen(micGranted = false))
        assertFalse(listensOnOpen(inSheetRecognizer = false))
        assertFalse(listensOnOpen(canStartTask = false))
        // An app opened the sheet, not the side button or the assist gesture.
        assertFalse(listensOnOpen(openedBySystem = false))
    }
}
