package com.phoneuse.ui.chat

import com.phoneuse.app.ChatCard
import com.phoneuse.chat.Attachment
import com.phoneuse.chat.ChatRepository
import com.phoneuse.chat.InMemoryChatRepository
import com.phoneuse.chat.MessageRole
import com.phoneuse.chat.StepLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mimics AgentRunner: creates the conversation when given none, stores the user message, then (on [finish]) the reply. */
private class FakeRunner(private val repo: ChatRepository, private val scope: CoroutineScope) : ChatRunner {
    override val running = MutableStateFlow(false)
    override val activeConversation = MutableStateFlow<Long?>(null)
    override val streamingText = MutableStateFlow("")
    override val liveSteps: StateFlow<List<StepLog>> = MutableStateFlow(emptyList())
    override val card = MutableStateFlow<ChatCard?>(null)

    val sent = mutableListOf<Pair<Long?, String>>()
    val fromCard = mutableListOf<Boolean>()
    val attached = mutableListOf<List<Attachment>>()
    val steered = mutableListOf<String>()
    val answers = mutableListOf<Pair<Long, String>>()
    var stops = 0

    var refuse = false

    /** False: the run stays before conversation creation (e.g. it fails early, or is still starting). */
    var createsConversation = true

    fun endWithoutReply() {
        running.value = false
    }

    override fun send(conversationId: Long?, text: String, fromCard: Boolean, attachments: List<Attachment>): Boolean {
        if (running.value || refuse) return false
        running.value = true
        sent += conversationId to text
        this.fromCard += fromCard
        attached += attachments
        if (!createsConversation) return true
        scope.launch {
            val id = conversationId ?: repo.createConversation(text.take(30))
            activeConversation.value = id
            repo.addMessage(id, MessageRole.USER, text)
        }
        return true
    }

    fun finish(reply: String) {
        scope.launch {
            repo.addMessage(activeConversation.value!!, MessageRole.ASSISTANT, reply)
            running.value = false
        }
    }

    override fun steer(text: String) {
        steered += text
    }

    override fun stop() {
        stops++
    }

    override fun answer(cardId: Long, text: String) {
        answers += cardId to text
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private fun TestScope.setup(): Triple<InMemoryChatRepository, FakeRunner, ChatViewModel> {
        val repo = InMemoryChatRepository()
        val runner = FakeRunner(repo, backgroundScope)
        val vm = ChatViewModel(repo, runner, backgroundScope)
        return Triple(repo, runner, vm)
    }

    @Test
    fun sendWithoutConversationCreatesOne() = runTest(UnconfinedTestDispatcher()) {
        val (_, runner, vm) = setup()
        assertNull(vm.current.value)

        vm.send("  다크 모드 켜줘 ")
        advanceUntilIdle()

        assertEquals(listOf<Pair<Long?, String>>(null to "다크 모드 켜줘"), runner.sent)
        val id = vm.current.value
        assertEquals(runner.activeConversation.value, id)
        assertEquals(listOf("다크 모드 켜줘"), vm.messages.value.map { it.text })
        assertEquals(listOf(id), vm.conversations.value.map { it.id })
        assertTrue(vm.live.value)

        runner.finish("켰어요")
        advanceUntilIdle()
        assertEquals(id, vm.current.value)
        assertEquals(listOf("다크 모드 켜줘", "켰어요"), vm.messages.value.map { it.text })
        assertFalse(vm.live.value)
    }

    @Test
    fun routinesSentenceStartsItsOwnRunAndNeverAnswersOrSteers() = runTest(UnconfinedTestDispatcher()) {
        // The routines screen's sentence is the user's own words, in a new conversation, without the composer's
        // attachments; while a run or a question card is open it is refused (the screen keeps the sentence).
        val (repo, runner, vm) = setup()
        val old = repo.createConversation("t")
        vm.select(old)
        vm.attach(listOf(Attachment.image("a.jpg")))
        assertTrue(vm.startTyped("루틴 만들어줘: 매일 8시에 날씨"))
        assertEquals(listOf<Pair<Long?, String>>(null to "루틴 만들어줘: 매일 8시에 날씨"), runner.sent)
        assertEquals(listOf(false), runner.fromCard)
        assertEquals(listOf(emptyList<Attachment>()), runner.attached)
        assertEquals(1, vm.pending.value.size)
        // A run is going: refused, not steered.
        assertFalse(vm.startTyped("또"))
        assertTrue(runner.steered.isEmpty())
        runner.finish("네")
        advanceUntilIdle()
        // A question card is open: refused, never taken as its answer.
        runner.card.value = ChatCard.Ask("어느 쪽?", listOf("A", "B"), 9)
        assertFalse(vm.startTyped("A"))
        assertTrue(runner.answers.isEmpty())
    }

    @Test
    fun cardPromptIsMarkedAndNeverSteers() = runTest(UnconfinedTestDispatcher()) {
        val (repo, runner, vm) = setup()
        val id = repo.createConversation("t")
        vm.select(id)
        assertTrue(vm.sendCardPrompt("이 일정 그대로 저장해줘"))
        assertEquals(listOf<Pair<Long?, String>>(id to "이 일정 그대로 저장해줘"), runner.sent)
        assertEquals(listOf(true), runner.fromCard)
        // While a run is going, a card prompt is refused instead of becoming a steering instruction.
        assertFalse(vm.sendCardPrompt("또"))
        assertTrue(runner.steered.isEmpty())
        // Typed text stays unmarked.
        runner.finish("네")
        advanceUntilIdle()
        vm.send("고마워")
        assertEquals(listOf(true, false), runner.fromCard)
    }

    @Test
    fun screenPromptStartsANewCardConversation() = runTest(UnconfinedTestDispatcher()) {
        val (repo, runner, vm) = setup()
        val old = repo.createConversation("t")
        vm.select(old)
        assertTrue(vm.startCardPrompt("알림 요약의 '답장 필요' 1번 알림에 보낼 답장 초안 써줘"))
        advanceUntilIdle()
        assertEquals(null, runner.sent.single().first)
        assertEquals(listOf(true), runner.fromCard)
        val id = vm.current.value
        assertTrue(id != null && id != old)
        // Refused while a run is going.
        assertFalse(vm.startCardPrompt("또"))
        assertEquals(1, runner.sent.size)
    }

    @Test
    fun attachmentsGoWithTheNextMessageAndCannotSteer() = runTest(UnconfinedTestDispatcher()) {
        val (_, runner, vm) = setup()
        val photos = (1..5).map { Attachment.image("/a/$it.jpg") }
        // Only four fit; the rest come back so their files can be deleted.
        assertEquals(photos.drop(4), vm.attach(photos))
        assertEquals(photos.take(4), vm.pending.value)
        vm.detach(photos[3])
        assertEquals(photos.take(3), vm.pending.value)

        // A photo alone (no text) can be sent.
        assertTrue(vm.send("  "))
        assertEquals(listOf(photos.take(3)), runner.attached)
        assertTrue(vm.pending.value.isEmpty())

        // While the run goes, text with a photo is not taken as a steering instruction.
        vm.attach(listOf(photos[4]))
        assertFalse(vm.send("이것도"))
        assertTrue(runner.steered.isEmpty())
        assertEquals(listOf(photos[4]), vm.pending.value)
        assertFalse(vm.send(""))
    }

    @Test
    fun selectSwitchesMessages() = runTest(UnconfinedTestDispatcher()) {
        val (repo, _, vm) = setup()
        val a = repo.createConversation("a")
        repo.addMessage(a, MessageRole.USER, "a1")
        val b = repo.createConversation("b")
        repo.addMessage(b, MessageRole.USER, "b1")
        repo.addMessage(b, MessageRole.ASSISTANT, "b2")

        vm.select(a)
        advanceUntilIdle()
        assertEquals(a, vm.current.value)
        assertEquals(listOf("a1"), vm.messages.value.map { it.text })

        vm.select(b)
        advanceUntilIdle()
        assertEquals(listOf("b1", "b2"), vm.messages.value.map { it.text })

        vm.newChat()
        advanceUntilIdle()
        assertNull(vm.current.value)
        assertTrue(vm.messages.value.isEmpty())
    }

    @Test
    fun deleteCurrentFallsBackToNewChat() = runTest(UnconfinedTestDispatcher()) {
        val (repo, _, vm) = setup()
        val a = repo.createConversation("a")
        repo.addMessage(a, MessageRole.USER, "a1")
        val b = repo.createConversation("b")

        vm.select(a)
        vm.delete(a)
        advanceUntilIdle()

        assertNull(vm.current.value)
        assertTrue(vm.messages.value.isEmpty())
        assertEquals(listOf(b), vm.conversations.value.map { it.id })
    }

    @Test
    fun deleteReportsTheConversationsGone() = runTest(UnconfinedTestDispatcher()) {
        val repo = InMemoryChatRepository()
        val gone = mutableListOf<Set<Long>>()
        val vm = ChatViewModel(repo, FakeRunner(repo, backgroundScope), backgroundScope, onDeleted = { gone += it })
        val a = repo.createConversation("a")
        val b = repo.createConversation("b")
        vm.delete(a)
        advanceUntilIdle()
        vm.deleteAll()
        advanceUntilIdle()
        assertEquals(listOf(setOf(a), setOf(b)), gone)
    }

    @Test
    fun deleteOtherKeepsCurrent() = runTest(UnconfinedTestDispatcher()) {
        val (repo, _, vm) = setup()
        val a = repo.createConversation("a")
        val b = repo.createConversation("b")
        vm.select(a)
        vm.delete(b)
        advanceUntilIdle()
        assertEquals(a, vm.current.value)
        assertEquals(listOf(a), vm.conversations.value.map { it.id })
    }

    @Test
    fun sendWhileRunningSteers() = runTest(UnconfinedTestDispatcher()) {
        val (_, runner, vm) = setup()
        vm.send("사진 정리해줘")
        advanceUntilIdle()

        vm.send("  날짜별로 ")
        advanceUntilIdle()

        assertEquals(1, runner.sent.size)
        assertEquals(listOf("날짜별로"), runner.steered)
    }

    @Test
    fun typedTextAnswersOpenAskCard() = runTest(UnconfinedTestDispatcher()) {
        val (_, runner, vm) = setup()
        vm.send("사진 옮겨줘")
        runner.card.value = ChatCard.Ask("어느 앨범?", listOf("카메라"), id = 7)

        vm.send("새 앨범")

        assertEquals(listOf(7L to "새 앨범"), runner.answers)
        assertTrue(runner.steered.isEmpty())
    }

    @Test
    fun refusedSendReportsFalseAndDoesNotFollow() = runTest(UnconfinedTestDispatcher()) {
        val (_, runner, vm) = setup()
        runner.refuse = true
        assertFalse(vm.send("작업"))
        assertNull(vm.current.value)
        runner.refuse = false
        assertTrue(vm.send("작업"))
    }

    @Test
    fun blankTextIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val (_, runner, vm) = setup()
        assertFalse(vm.send("   "))
        assertTrue(runner.sent.isEmpty())
    }

    @Test
    fun runInOtherConversationIsNotLiveHere() = runTest(UnconfinedTestDispatcher()) {
        val (repo, runner, vm) = setup()
        val a = repo.createConversation("a")
        val b = repo.createConversation("b")
        vm.select(a)
        vm.send("작업")
        advanceUntilIdle()
        assertTrue(vm.live.value)

        // Switching away mid-run must not be undone by the run.
        vm.select(b)
        advanceUntilIdle()
        assertFalse(vm.live.value)
        runner.finish("끝")
        advanceUntilIdle()
        assertEquals(b, vm.current.value)
    }

    @Test
    fun deletingRunningConversationStopsRun() = runTest(UnconfinedTestDispatcher()) {
        val (_, runner, vm) = setup()
        vm.send("작업")
        advanceUntilIdle()
        vm.delete(vm.current.value!!)
        advanceUntilIdle()
        assertEquals(1, runner.stops)
        assertNull(vm.current.value)
    }

    @Test
    fun deleteAllClearsEverything() = runTest(UnconfinedTestDispatcher()) {
        val (repo, _, vm) = setup()
        val a = repo.createConversation("a")
        repo.createConversation("b")
        vm.select(a)
        vm.deleteAll()
        advanceUntilIdle()
        assertNull(vm.current.value)
        assertTrue(vm.conversations.value.isEmpty())
    }

    @Test
    fun homeIsNotRunningHereWhileOtherConversationRuns() = runTest(UnconfinedTestDispatcher()) {
        val (repo, runner, vm) = setup()
        val a = repo.createConversation("a")
        vm.select(a)
        vm.send("작업")
        advanceUntilIdle()
        assertTrue(vm.runningHere.value)

        vm.newChat()
        advanceUntilIdle()
        assertFalse(vm.runningHere.value)
        // Typing on home must not silently steer the other conversation's run.
        assertFalse(vm.send("새 일"))
        assertTrue(runner.steered.isEmpty())
    }

    @Test
    fun newChatRunIsHereBeforeItsConversationExists() = runTest(UnconfinedTestDispatcher()) {
        val (_, runner, vm) = setup()
        runner.createsConversation = false
        vm.send("작업")
        advanceUntilIdle()
        assertNull(vm.current.value)
        assertTrue(vm.runningHere.value)
        assertTrue(vm.send("날짜별로"))
        assertEquals(listOf("날짜별로"), runner.steered)
    }

    @Test
    fun otherConversationRunIsNotHere() = runTest(UnconfinedTestDispatcher()) {
        val (repo, runner, vm) = setup()
        val a = repo.createConversation("a")
        val b = repo.createConversation("b")
        vm.select(a)
        vm.send("작업")
        vm.select(b)
        advanceUntilIdle()
        assertFalse(vm.runningHere.value)
        assertFalse(vm.send("지시"))
        assertTrue(runner.steered.isEmpty())
    }

    @Test
    fun staleFollowIsClearedWhenRunEndsWithoutConversation() = runTest(UnconfinedTestDispatcher()) {
        val (repo, runner, vm) = setup()
        runner.createsConversation = false
        vm.send("작업")
        advanceUntilIdle()
        runner.endWithoutReply()
        advanceUntilIdle()

        // A later, unrelated run (e.g. the side button) must not pull the home screen into its conversation.
        val other = repo.createConversation("assist")
        runner.running.value = true
        runner.activeConversation.value = other
        advanceUntilIdle()
        assertNull(vm.current.value)
    }

    /** The repeated-ask nudge counts only what the user typed to start a conversation, never card prompts. */
    @Test
    fun onlyTypedNewConversationsAreAsks() = runTest(UnconfinedTestDispatcher()) {
        val repo = InMemoryChatRepository()
        val runner = FakeRunner(repo, backgroundScope)
        val asks = mutableListOf<Pair<String, Long>>()
        val vm = ChatViewModel(repo, runner, backgroundScope, onTypedAsk = { text, id -> asks += text to id })

        vm.send("  오늘 날씨 알려줘 ")
        advanceUntilIdle()
        val first = runner.activeConversation.value!!
        assertEquals(listOf("오늘 날씨 알려줘" to first), asks)

        // A follow-up in the same conversation is not a new ask.
        runner.finish("맑아요")
        advanceUntilIdle()
        vm.send("고마워")
        advanceUntilIdle()
        runner.finish("네")
        advanceUntilIdle()

        // A card or nudge prompt starting a conversation never counts.
        vm.newChat()
        assertTrue(vm.startCardPrompt("루틴 만들어줘: 매일 9시에 뭔가"))
        advanceUntilIdle()
        runner.finish("네")
        advanceUntilIdle()

        assertEquals(1, asks.size)
    }

    /** A question of the run in chat A (or a routine's) is never answered by text typed in chat B. */
    @Test
    fun askCardOfAnotherConversationIsNotAnsweredHere() = runTest(UnconfinedTestDispatcher()) {
        val (repo, runner, vm) = setup()
        val a = repo.createConversation("a")
        val b = repo.createConversation("b")
        vm.select(a)
        assertTrue(vm.send("사진 옮겨줘"))
        runner.card.value = ChatCard.Ask("어느 앨범?", listOf("카메라"), id = 3, conversationId = a)
        vm.select(b)
        assertNull(vm.cardHere.value)
        assertFalse("kept in the composer", vm.send("새 작업"))
        assertTrue(runner.answers.isEmpty())
        assertTrue(runner.steered.isEmpty())
        vm.newChat()
        assertFalse(vm.send("홈에서 쓴 글"))
        assertTrue(runner.answers.isEmpty())

        vm.select(a)
        assertEquals(3L, vm.cardHere.value?.id)
        assertTrue(vm.send("새 앨범"))
        assertEquals(listOf(3L to "새 앨범"), runner.answers)
    }

    @Test
    fun aDeletedShownConversationFallsBackToTheNewChat() = runTest(UnconfinedTestDispatcher()) {
        val (repo, _, vm) = setup()
        val a = repo.createConversation("a")
        vm.select(a)
        repo.delete(a)
        advanceUntilIdle()
        assertNull(vm.current.value)
        // A restored id that is already gone.
        vm.select(a)
        advanceUntilIdle()
        assertNull(vm.current.value)
    }

    /** The runner moved a run to a new conversation (its own was deleted meanwhile): the screen goes along. */
    @Test
    fun theScreenFollowsARunMovedToANewConversation() = runTest(UnconfinedTestDispatcher()) {
        val (repo, runner, vm) = setup()
        val a = repo.createConversation("a")
        vm.select(a)
        assertTrue(vm.send("작업"))
        repo.delete(a)
        advanceUntilIdle()
        assertEquals("kept while its run is moving", a, vm.current.value)
        val moved = repo.createConversation("작업")
        runner.activeConversation.value = moved
        advanceUntilIdle()
        assertEquals(moved, vm.current.value)
    }

    /** Activity recreation between send and follow: the new screen follows the run again (Stop stays here). */
    @Test
    fun followingANewChatSurvivesRecreation() = runTest(UnconfinedTestDispatcher()) {
        val (repo, runner, vm) = setup()
        runner.createsConversation = false
        assertTrue(vm.send("새 작업"))
        assertTrue(vm.followingNew.value)
        val stale = vm.followStale

        val recreated = ChatViewModel(repo, runner, backgroundScope)
        assertFalse(recreated.runningHere.value)
        recreated.resumeFollowing(stale)
        assertTrue(recreated.runningHere.value)
        val id = repo.createConversation("새 작업")
        runner.activeConversation.value = id
        advanceUntilIdle()
        assertEquals(id, recreated.current.value)
    }
}
