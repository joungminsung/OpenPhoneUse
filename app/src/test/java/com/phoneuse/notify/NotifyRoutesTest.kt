package com.phoneuse.notify

import com.phoneuse.app.ChatCard
import com.phoneuse.app.ChatUserGate
import com.phoneuse.risk.RiskCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyRoutesTest {
    @Test
    fun everyNotificationHasATarget() {
        assertEquals(Tap.Conversation(7, reveal = true), Taps.result(7))
        assertEquals(Tap.Screen(AppScreen.HOME), Taps.result(null))
        assertEquals(Tap.Conversation(3, reveal = true), Taps.routine(3))
        assertEquals(Tap.Screen(AppScreen.ROUTINES), Taps.routine(null))
        assertEquals(Tap.Conversation(5), Taps.needsOk(5))
        assertEquals(Tap.Screen(AppScreen.HOME), Taps.dailySummary)
        assertEquals(Tap.Screen(AppScreen.DIGEST), Taps.digest)
    }

    @Test
    fun approveFromTheShadeOnlyForSendAndChange() {
        assertTrue(NeedsOk.inlineApprove(RiskCategory.SEND))
        assertTrue(NeedsOk.inlineApprove(RiskCategory.CHANGE))
        assertFalse(NeedsOk.inlineApprove(RiskCategory.MONEY))
        assertFalse(NeedsOk.inlineApprove(RiskCategory.SECURITY))
        assertFalse(NeedsOk.inlineApprove(RiskCategory.DELETE))
    }

    @Test
    fun buttonsAnswerTheirCardThroughTheChatAnswers() {
        val send = ChatCard.Confirm(RiskCategory.SEND, "엄마에게 문자 보내기", 4)
        val key = NeedsOk.key(send)
        assertEquals(ChatUserGate.CONFIRM_YES, NeedsOk.answer(send, key, NeedsOk.Button.Approve))
        assertEquals(ChatUserGate.CONFIRM_NO, NeedsOk.answer(send, key, NeedsOk.Button.Deny))
        assertNull(NeedsOk.answer(send, key, NeedsOk.Button.Option(0)))
    }

    @Test
    fun paymentsAreNeverApprovedFromANotification() {
        for (category in listOf(RiskCategory.MONEY, RiskCategory.SECURITY, RiskCategory.DELETE)) {
            val card = ChatCard.Confirm(category, "결제 29,900원", 9)
            assertNull(NeedsOk.answer(card, NeedsOk.key(card), NeedsOk.Button.Approve))
            // Refusing is always allowed.
            assertEquals(ChatUserGate.CONFIRM_NO, NeedsOk.answer(card, NeedsOk.key(card), NeedsOk.Button.Deny))
        }
    }

    @Test
    fun aStaleNotificationNeverAnswersAnotherCard() {
        val old = ChatCard.Confirm(RiskCategory.SEND, "문자 보내기", 1)
        val key = NeedsOk.key(old)
        // Same id after a restart, different request.
        val newer = ChatCard.Confirm(RiskCategory.SEND, "다른 사람에게 보내기", 1)
        assertNotEquals(key, NeedsOk.key(newer))
        assertNull(NeedsOk.answer(newer, key, NeedsOk.Button.Approve))
        assertNull(NeedsOk.answer(ChatCard.Confirm(RiskCategory.SEND, "문자 보내기", 2), key, NeedsOk.Button.Approve))
        assertNull(NeedsOk.answer(null, key, NeedsOk.Button.Deny))
    }

    @Test
    fun questionsGetOptionButtonsOnlyWhenTheyFit() {
        val ask = ChatCard.Ask("어느 쪽으로 할까요?", listOf("A", "B"), 2)
        assertEquals(listOf("A", "B"), NeedsOk.options(ask))
        assertEquals("B", NeedsOk.answer(ask, NeedsOk.key(ask), NeedsOk.Button.Option(1)))
        assertNull(NeedsOk.answer(ask, NeedsOk.key(ask), NeedsOk.Button.Option(5)))
        assertNull(NeedsOk.answer(ask, NeedsOk.key(ask), NeedsOk.Button.Approve))
        val many = ChatCard.Ask("골라 주세요", listOf("1", "2", "3", "4"), 3)
        assertTrue(NeedsOk.options(many).isEmpty())
        assertNull(NeedsOk.answer(many, NeedsOk.key(many), NeedsOk.Button.Option(0)))
        val handOff = ChatCard.HandOff("로그인해 주세요", 6)
        assertNull(NeedsOk.answer(handOff, NeedsOk.key(handOff), NeedsOk.Button.Approve))
    }

    @Test
    fun throttleWaitsFirstThenSpacesUpdates() {
        val t = UpdateThrottle(startedAt = 1_000, firstDelayMs = 2_500, intervalMs = 1_000)
        assertEquals(2_000L, t.waitFor("a", now = 1_500))
        assertEquals(0L, t.waitFor("a", now = 4_000))
        t.posted("a", now = 4_000)
        // The same line again is skipped.
        assertNull(t.waitFor("a", now = 9_000))
        assertEquals(600L, t.waitFor("b", now = 4_400))
        assertEquals(0L, t.waitFor("b", now = 5_200))
        t.posted("b", now = 5_200)
        // Taken away while the app was in front: the same line is posted again, still spaced.
        t.cleared()
        assertEquals(500L, t.waitFor("b", now = 5_700))
    }
}
