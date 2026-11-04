package com.phoneuse.ui.fold

import com.phoneuse.cards.CardAction
import com.phoneuse.cards.CardActions
import com.phoneuse.cards.DraftCard
import com.phoneuse.cards.EventCard
import com.phoneuse.cards.EventConflict
import com.phoneuse.cards.PermissionCard
import com.phoneuse.cards.PhotosCard
import com.phoneuse.cards.RouteCard
import com.phoneuse.cards.RouteDest
import com.phoneuse.cards.StatCard
import com.phoneuse.chat.ChatMessage
import com.phoneuse.chat.MessageRole
import com.phoneuse.chat.ReplyExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldLayoutPolicyTest {
    private fun decide(
        width: Int,
        posture: Posture = Posture.FLAT,
        multiWindow: Boolean = false,
        hasCard: Boolean = false,
        runningHere: Boolean = false,
        asking: Boolean = false,
    ) = FoldLayoutPolicy.decide(width, posture, multiWindow, hasCard, runningHere, asking)

    // --- width ---

    @Test fun foldedPhoneKeepsThePhoneLayout() {
        assertEquals(FoldMode.PHONE, decide(360).mode)
        assertEquals(FoldMode.PHONE, decide(599, hasCard = true).mode)
    }

    @Test fun unfoldedWithoutACardIsTheM2TwoPane() {
        assertEquals(FoldLayout(FoldMode.TWO_PANE), decide(600))
        assertEquals(FoldMode.TWO_PANE, decide(832).mode)
    }

    @Test fun unfoldedWithACardShowsTheResultPaneWithTheListInTheDrawer() {
        assertEquals(FoldLayout(FoldMode.RESULT_PANE, listRail = false), decide(832, hasCard = true))
        assertEquals(FoldLayout(FoldMode.RESULT_PANE, listRail = false), decide(600, hasCard = true))
    }

    @Test fun veryWideScreensKeepTheListAsARailBesideTheResultPane() {
        assertEquals(FoldLayout(FoldMode.RESULT_PANE, listRail = true), decide(FoldLayoutPolicy.RAIL_DP, hasCard = true))
        assertFalse(decide(FoldLayoutPolicy.RAIL_DP - 1, hasCard = true).listRail)
    }

    // --- tabletop (half opened, horizontal hinge) ---

    @Test fun tabletopWithACardIsFlexMode() {
        assertEquals(FoldMode.FLEX, decide(832, Posture.TABLETOP, hasCard = true).mode)
        // The hinge splits the screen top/bottom, so the width rule does not matter.
        assertEquals(FoldMode.FLEX, decide(500, Posture.TABLETOP, hasCard = true).mode)
    }

    @Test fun tabletopStaysInFlexWhileTheAgentWorks() {
        assertEquals(FoldMode.FLEX, decide(832, Posture.TABLETOP, hasCard = true, runningHere = true).mode)
    }

    @Test fun tabletopWithoutACardFallsBackToTheWidthLayout() {
        assertEquals(FoldMode.TWO_PANE, decide(832, Posture.TABLETOP).mode)
    }

    @Test fun aQuestionOrConfirmationLeavesFlexForTheChat() {
        assertEquals(FoldMode.RESULT_PANE, decide(832, Posture.TABLETOP, hasCard = true, asking = true).mode)
    }

    @Test fun bookPostureIsNotFlex() {
        assertEquals(FoldMode.RESULT_PANE, decide(832, Posture.BOOK, hasCard = true).mode)
    }

    @Test fun tabletopInMultiWindowIsNotFlex() {
        assertEquals(FoldMode.PHONE, decide(400, Posture.TABLETOP, multiWindow = true, hasCard = true).mode)
    }

    // --- side by side with another app ---

    @Test fun smallWindowBesideAnotherAppWhileWorkingShowsProgress() {
        assertEquals(FoldMode.SPLIT_PROGRESS, decide(360, multiWindow = true, runningHere = true).mode)
        assertEquals(FoldMode.SPLIT_PROGRESS, decide(360, multiWindow = true, hasCard = true, runningHere = true).mode)
    }

    @Test fun smallWindowBesideAnotherAppWhenIdleIsThePhoneLayout() {
        assertEquals(FoldMode.PHONE, decide(360, multiWindow = true).mode)
    }

    @Test fun wideWindowInMultiWindowUsesTheWidthLayout() {
        assertEquals(FoldMode.TWO_PANE, decide(700, multiWindow = true, runningHere = true).mode)
    }

    @Test fun fullScreenPhoneWhileWorkingIsThePhoneLayout() {
        assertEquals(FoldMode.PHONE, decide(360, runningHere = true).mode)
    }

    // --- result pane content ---

    private val route = RouteCard("18:12", "18:52", dest = RouteDest("강남역"))
    private val stat = StatCard("이번 달 배달", "₩186,400")

    private fun msg(id: Long, role: MessageRole, vararg cards: com.phoneuse.cards.CardSpec) =
        ChatMessage(id, 1, role, "t$id", emptyList(), id, cards = cards.toList())

    @Test fun resultCardIsTheLastCardOfTheConversation() {
        val messages = listOf(
            msg(1, MessageRole.USER),
            msg(2, MessageRole.ASSISTANT, stat, route),
            msg(3, MessageRole.USER),
            msg(4, MessageRole.ASSISTANT),
        )
        val pane = FoldLayoutPolicy.resultCard(messages)!!
        assertSame(route, pane.card)
        assertEquals(2L, pane.messageId)
        assertEquals(1, pane.index)
        // The same key the chat gives that card, so its state is shared between the two places.
        assertEquals(PaneCard.keyOf(2, 1), pane.key)
    }

    @Test fun resultCardFollowsTheNewestCard() {
        val photos = PhotosCard("바다", listOf("content://media/external/images/media/1"))
        val messages = listOf(msg(2, MessageRole.ASSISTANT, route), msg(3, MessageRole.USER), msg(4, MessageRole.ASSISTANT, photos))
        assertEquals(PaneCard(4, 0, photos), FoldLayoutPolicy.resultCard(messages))
    }

    @Test fun cardIsOutdatedOnceSomethingNewerWasSaid() {
        val withCard = listOf(msg(1, MessageRole.USER), msg(2, MessageRole.ASSISTANT, route))
        val pane = FoldLayoutPolicy.resultCard(withCard)!!
        assertFalse(FoldLayoutPolicy.cardIsOutdated(withCard, pane))
        // A new spoken request, then a text-only answer (or an error reply).
        assertTrue(FoldLayoutPolicy.cardIsOutdated(withCard + msg(3, MessageRole.USER), pane))
        assertTrue(FoldLayoutPolicy.cardIsOutdated(withCard + msg(3, MessageRole.USER) + msg(4, MessageRole.ASSISTANT), pane))
    }

    @Test fun latestReplyIsTheNewestAssistantTextOrNotice() {
        assertNull(FoldLayoutPolicy.latestReply(listOf(msg(1, MessageRole.USER))))
        val reply = listOf(msg(1, MessageRole.USER), msg(2, MessageRole.ASSISTANT, route), msg(3, MessageRole.USER))
        assertEquals("t2", FoldLayoutPolicy.latestReply(reply))
        val error = ChatMessage(4, 1, MessageRole.ASSISTANT, "문제가 생겼어요. 다시 해 주세요.", emptyList(), 4)
        assertEquals("문제가 생겼어요. 다시 해 주세요.", FoldLayoutPolicy.latestReply(reply + error))
        val noticeOnly = ChatMessage(5, 1, MessageRole.ASSISTANT, " ", emptyList(), 5, extras = ReplyExtras(notice = "빠른 모드로 끊었어요"))
        assertEquals("빠른 모드로 끊었어요", FoldLayoutPolicy.latestReply(reply + noticeOnly))
    }

    @Test fun flexReplyIsTheLiveAnswerWhileWorkingAndHidesTheOldReply() {
        val done = listOf(msg(1, MessageRole.USER), msg(2, MessageRole.ASSISTANT, route))
        assertEquals("t2", FoldLayoutPolicy.flexReply(done, runningHere = false, streaming = ""))
        // A new spoken request is running and nothing has streamed yet: the previous reply is not shown.
        assertNull(FoldLayoutPolicy.flexReply(done + msg(3, MessageRole.USER), runningHere = true, streaming = "  "))
        assertEquals("지하철로", FoldLayoutPolicy.flexReply(done, runningHere = true, streaming = "지하철로 "))
    }

    @Test fun longFlexReplyShowsItsEnd() {
        val long = "가".repeat(300) + "끝"
        val shown = FoldLayoutPolicy.flexReply(emptyList(), runningHere = true, streaming = long)!!
        assertTrue(shown.startsWith("…"))
        assertTrue(shown.endsWith("끝"))
        assertEquals(FoldLayoutPolicy.FLEX_REPLY_CHARS + 1, shown.length)
    }

    @Test fun chatColumnIsTheBoards372dpButAtMostHalfTheWindow() {
        assertEquals(372, FoldLayoutPolicy.chatColumnDp(832))
        assertEquals(300, FoldLayoutPolicy.chatColumnDp(600))
    }

    @Test fun postureFromTheFoldingFeature() {
        assertEquals(Posture.FLAT, FoldLayoutPolicy.posture(halfOpened = false, horizontalHinge = true))
        assertEquals(Posture.TABLETOP, FoldLayoutPolicy.posture(halfOpened = true, horizontalHinge = true))
        assertEquals(Posture.BOOK, FoldLayoutPolicy.posture(halfOpened = true, horizontalHinge = false))
    }

    @Test fun noCardsMeansNoResultPane() {
        assertNull(FoldLayoutPolicy.resultCard(emptyList()))
        assertNull(FoldLayoutPolicy.resultCard(listOf(msg(1, MessageRole.USER), msg(2, MessageRole.ASSISTANT))))
    }

    @Test fun latestExchangeStartsAtTheLastUserMessage() {
        val messages = listOf(msg(1, MessageRole.USER), msg(2, MessageRole.ASSISTANT), msg(3, MessageRole.USER), msg(4, MessageRole.ASSISTANT))
        assertEquals(listOf(3L, 4L), FoldLayoutPolicy.latestExchange(messages).map { it.id })
        assertEquals(emptyList<ChatMessage>(), FoldLayoutPolicy.latestExchange(emptyList()))
        // No user message at all: everything.
        assertEquals(listOf(2L), FoldLayoutPolicy.latestExchange(listOf(msg(2, MessageRole.ASSISTANT))).map { it.id })
    }

    // --- flex mode bottom buttons ---

    @Test fun routeFlexButtonsMatchTheBoard() {
        val b = FoldLayoutPolicy.flexButtons(route)
        assertEquals(listOf("18:05에 알림", "지도 앱에서 열기", "도착 시간 보내기"), b.map { it.label })
        assertEquals(CardActions.routeReminder(route), b[0].action)
        assertEquals(CardActions.openRoute(route), b[1].action)
        assertEquals(CardActions.shareArrival(route), b[2].action)
        assertTrue(b[0].primary)
        assertFalse(b[1].primary)
    }

    @Test fun eventFlexButtonsAddConflictActionsOnlyWithConflicts() {
        val plain = EventCard("치과", "2026-11-03T10:00", "2026-11-03T11:00")
        assertEquals(listOf("고치기", "그대로 저장"), FoldLayoutPolicy.flexButtons(plain).map { it.label })
        val meeting = EventConflict("회의", "2026-11-03T10:30", "2026-11-03T11:30")
        val clash = plain.copy(conflicts = listOf(meeting))
        val b = FoldLayoutPolicy.flexButtons(clash)
        assertEquals(listOf("고치기", "그대로 저장", "불참 알리기", "비는 시간 찾기"), b.map { it.label })
        assertEquals(CardActions.notifyConflict(0), b[2].action)
    }

    @Test fun eventFlexButtonsCoverEveryConflict() {
        val meeting = EventConflict("회의", "2026-11-03T10:30", "2026-11-03T11:30")
        val card = EventCard("치과", "2026-11-03T10:00", "2026-11-03T11:00", conflicts = listOf(meeting, meeting, meeting))
        val b = FoldLayoutPolicy.flexButtons(card)
        assertEquals(
            listOf("고치기", "그대로 저장", "1번 일정 불참 알리기", "2번 일정 불참 알리기", "3번 일정 불참 알리기", "비는 시간 찾기"),
            b.map { it.label },
        )
        assertEquals((0..2).map { CardActions.notifyConflict(it) }, b.subList(2, 5).map { it.action })
        // Only the card's own buttons leave the card: its one "불참 알리기" (the first conflict) and the rest.
        assertEquals(setOf("고치기", "그대로 저장", "불참 알리기", "비는 시간 찾기"), FoldLayoutPolicy.movedButtons(card))
    }

    @Test fun movedButtonsAreTheCardsOwnLabels() {
        // The route reminder is shortened in flex; the card's longer label is the one left out.
        assertEquals(setOf("18:05에 나갈 준비 알림", "지도 앱에서 열기", "도착 시간 보내기"), FoldLayoutPolicy.movedButtons(route))
        assertEquals(emptySet<String>(), FoldLayoutPolicy.movedButtons(stat))
    }

    @Test fun draftFlexButtonsNameTheChannel() {
        val sms = DraftCard("sms", body = "곧 도착해")
        val b = FoldLayoutPolicy.flexButtons(sms)
        assertEquals(listOf("복사", "고치기", "문자로 보내기"), b.map { it.label })
        assertEquals(CardAction.CopyText("곧 도착해"), b[0].action)
        assertEquals("메일로 보내기", FoldLayoutPolicy.flexButtons(sms.copy(channel = "email")).last().label)
        assertEquals("메모로 저장", FoldLayoutPolicy.flexButtons(sms.copy(channel = "note")).last().label)
    }

    @Test fun permissionFlexButtonAsksForIt() {
        val card = PermissionCard("위치", listOf("android.permission.ACCESS_FINE_LOCATION"))
        assertEquals(listOf(CardActions.requestPermission(card)), FoldLayoutPolicy.flexButtons(card).map { it.action })
    }

    @Test fun cardsWithPerItemButtonsKeepThemInTheCard() {
        assertTrue(FoldLayoutPolicy.flexButtons(PhotosCard("바다", listOf("content://media/external/images/media/1"))).isEmpty())
        assertTrue(FoldLayoutPolicy.flexButtons(stat).isEmpty())
    }
}
