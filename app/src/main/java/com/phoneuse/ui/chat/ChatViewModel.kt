package com.phoneuse.ui.chat

import com.phoneuse.app.AgentRunner
import com.phoneuse.app.ChatCard
import com.phoneuse.app.Source
import com.phoneuse.chat.Attachment
import com.phoneuse.chat.ChatMessage
import com.phoneuse.chat.ChatRepository
import com.phoneuse.chat.Conversation
import com.phoneuse.chat.MessageRole
import com.phoneuse.chat.StepLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the chat screen needs from [AgentRunner]; tests use a fake. */
interface ChatRunner {
    val running: StateFlow<Boolean>
    val activeConversation: StateFlow<Long?>
    val streamingText: StateFlow<String>
    val liveSteps: StateFlow<List<StepLog>>
    val card: StateFlow<ChatCard?>

    /** Web searches of the current run so far. */
    val liveSearches: StateFlow<Int> get() = NO_SEARCHES

    /**
     * Returns true when a run started. A null [conversationId] makes the runner create one. [fromCard] marks text
     * sent by a result-card button, which never authorizes a send by itself and never carries [attachments].
     */
    fun send(
        conversationId: Long?,
        text: String,
        fromCard: Boolean = false,
        attachments: List<Attachment> = emptyList(),
    ): Boolean
    fun steer(text: String)
    fun stop()

    /** Stops the run and returns once it has fully ended (its last writes done). */
    suspend fun stopAndWait() = stop()
    fun answer(cardId: Long, text: String)
}

private val NO_SEARCHES: StateFlow<Int> = MutableStateFlow(0)

fun AgentRunner.asChatRunner(): ChatRunner {
    val runner = this
    return object : ChatRunner {
        override val running = runner.running
        override val activeConversation = runner.activeConversation
        override val streamingText = runner.streamingText
        override val liveSteps = runner.liveSteps
        override val liveSearches = runner.liveSearches
        override val card = runner.chatGate.card
        override fun send(conversationId: Long?, text: String, fromCard: Boolean, attachments: List<Attachment>) =
            (if (fromCard) Source.CARD else Source.APP).let { source -> runner.send(conversationId, text, source, source.allowed(attachments)) }
        override fun steer(text: String) = runner.steer(text)
        override fun stop() = runner.stop()
        override suspend fun stopAndWait() = runner.stopAndWait()
        override fun answer(cardId: Long, text: String) = runner.chatGate.answer(cardId, text)
    }
}

/**
 * State of the chat screen: the conversation list, the shown conversation and its messages.
 * A plain class kept by the activity's composition; [scope] bounds its collectors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val repo: ChatRepository,
    private val runner: ChatRunner,
    private val scope: CoroutineScope,
    private val onTypedAsk: (text: String, conversationId: Long) -> Unit = { _, _ -> },
    /** Conversations the user deleted (their asks.json entries go too). */
    private val onDeleted: (Set<Long>) -> Unit = {},
) {
    private val _current = MutableStateFlow<Long?>(null)

    /** The shown conversation; null is a new, not yet created chat (the home screen). */
    val current: StateFlow<Long?> = _current.asStateFlow()

    val conversations: StateFlow<List<Conversation>> =
        repo.conversations().stateIn(scope, SharingStarted.Eagerly, emptyList())

    val messages: StateFlow<List<ChatMessage>> = _current
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.messages(id) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val running: StateFlow<Boolean> get() = runner.running
    val activeConversation: StateFlow<Long?> get() = runner.activeConversation
    val streamingText: StateFlow<String> get() = runner.streamingText
    val liveSteps: StateFlow<List<StepLog>> get() = runner.liveSteps
    val liveSearches: StateFlow<Int> get() = runner.liveSearches
    val card: StateFlow<ChatCard?> get() = runner.card

    /**
     * True while a run of the shown conversation has not stored its reply yet, i.e. the in-progress
     * answer should be drawn. The runner keeps running briefly after saving the reply; that tail is excluded.
     */
    val live: StateFlow<Boolean> =
        combine(runner.running, runner.activeConversation, _current, messages) { running, active, current, msgs ->
            running && current != null && active == current && msgs.lastOrNull()?.role == MessageRole.USER
        }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _pending = MutableStateFlow<List<Attachment>>(emptyList())

    /** Attachments of the composer draft; they go with the next message. Kept across conversations, like the text. */
    val pending: StateFlow<List<Attachment>> = _pending.asStateFlow()

    /** Adds to the draft up to [Attachment.MAX_PER_MESSAGE]; returns the ones that did not fit (their files can go). */
    fun attach(list: List<Attachment>): List<Attachment> {
        val room = (Attachment.MAX_PER_MESSAGE - _pending.value.size).coerceAtLeast(0)
        _pending.value = _pending.value + list.take(room)
        return list.drop(room)
    }

    fun detach(attachment: Attachment) {
        _pending.value = _pending.value - attachment
    }

    /** Set when a send started a new conversation; the screen follows it once the runner has created it. */
    private val following = MutableStateFlow(false)
    @Volatile private var staleActive: Long? = null

    /**
     * What the user typed to start the new conversation being followed; handed to [onTypedAsk] with its id once the
     * runner has created it. Card prompts never set it.
     */
    @Volatile private var typedAsk: String? = null

    /** Set while a run is seen running, so its end (and only its end) clears [following]. */
    @Volatile private var sawRunning = false

    /**
     * The conversation a run was sent to from here. When it was gone by then, the runner moves the run to a new
     * conversation; the screen goes along.
     */
    @Volatile private var sentTo: Long? = null

    /**
     * Whether the in-progress run belongs to the shown conversation: it runs there, or this screen is the
     * new chat that started it and its conversation does not exist yet. Stop and steering apply only then.
     */
    val runningHere: StateFlow<Boolean> =
        combine(runner.running, runner.activeConversation, _current, following) { running, active, current, follow ->
            isHere(running, active, current, follow)
        }.stateIn(scope, SharingStarted.Eagerly, false)

    private fun isHere(running: Boolean, active: Long?, current: Long?, follow: Boolean) =
        running && (if (current == null) follow else active == current)

    /**
     * The question card of the run shown here, or null; a card of another conversation's run (or a routine's) is
     * never drawn here or answered from this composer: the "working in another chat" banner leads to it.
     */
    val cardHere: StateFlow<ChatCard?> =
        combine(runner.card, _current) { card, current -> card?.takeIf { it.belongsTo(current) } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Whether the screen is following a new conversation it started (kept across activity recreation). */
    val followingNew: StateFlow<Boolean> = following.asStateFlow()

    /** The run's conversation when [followingNew] was set: not the one to follow. */
    val followStale: Long? get() = staleActive

    /**
     * Back after activity recreation while a new chat was being followed: follow it again, so its run stays
     * "here" (Stop and steering) and the screen opens its conversation once the runner has created it.
     */
    fun resumeFollowing(stale: Long?) {
        if (_current.value != null) return
        staleActive = stale
        following.value = true
        follow(runner.activeConversation.value)
        if (!runner.running.value) following.value = false
    }

    init {
        // The shown conversation was deleted (retention, another screen, a restored id that is gone): back to the
        // new chat, never a screen whose messages can no longer be stored.
        scope.launch {
            combine(repo.conversations(), _current) { list, shown -> shown?.takeIf { s -> list.none { it.id == s } } }
                .collect { gone ->
                    if (gone == null || runner.activeConversation.value == gone) return@collect
                    // A run sent from here moves to a new conversation by itself (see [moved]).
                    if (sentTo == gone && runner.running.value) return@collect
                    if (_current.value == gone) newChat()
                }
        }
        scope.launch {
            runner.activeConversation.collect { id ->
                follow(id)
                moved(id)
            }
        }
        scope.launch {
            runner.running.collect { running ->
                if (running) {
                    sawRunning = true
                } else if (sawRunning) {
                    sawRunning = false
                    follow(runner.activeConversation.value)
                    // A run that ended without creating a conversation must not leave a stale follow behind.
                    following.value = false
                    typedAsk = null
                    sentTo = null
                }
            }
        }
    }

    private fun moved(active: Long?) {
        val from = sentTo ?: return
        if (active == null || active == from) return
        sentTo = null
        if (_current.value == from) _current.value = active
    }

    private fun follow(active: Long?) {
        if (!following.value || active == null || active == staleActive) return
        following.value = false
        typedAsk?.let { text ->
            typedAsk = null
            onTypedAsk(text, active)
        }
        if (_current.value == null) _current.value = active
    }

    /**
     * Text from the composer: answers an open question card, steers a running agent, or starts a run in
     * the shown conversation (a new one when none is shown). Returns false when nothing took the text,
     * so the composer can keep it. With [pending] attachments only a new run takes it (they are sent with it).
     */
    fun send(text: String): Boolean {
        val t = text.trim()
        val files = _pending.value
        if (t.isEmpty() && files.isEmpty()) return false
        val open = runner.card.value
        if (files.isNotEmpty() && (open != null || runner.running.value)) return false
        return when {
            // Only a question of the run shown here; elsewhere the text is a steer or stays in the composer.
            open is ChatCard.Ask && open.belongsTo(_current.value) -> {
                runner.answer(open.id, t)
                true
            }
            // Only the run shown here can be steered; text typed elsewhere stays in the composer.
            runner.running.value -> {
                val here = isHere(true, runner.activeConversation.value, _current.value, following.value)
                if (here) runner.steer(t)
                here
            }
            else -> {
                val target = _current.value
                if (target == null) {
                    staleActive = runner.activeConversation.value
                    following.value = true
                    typedAsk = t.takeIf { it.isNotEmpty() }
                }
                if (target != null) sentTo = target
                runner.send(target, t, attachments = files).also { started ->
                    if (started) {
                        _pending.value = _pending.value - files.toSet()
                    } else {
                        sentTo = null
                        following.value = false
                        typedAsk = null
                    }
                }
            }
        }
    }

    /**
     * A result-card button's text, sent as the next message of the shown conversation and marked as card-originated.
     * Refused (false) while any run is going: it must not become a steering instruction.
     */
    fun sendCardPrompt(text: String): Boolean {
        val target = _current.value ?: return false
        if (runner.running.value || runner.card.value != null) return false
        return runner.send(target, text, fromCard = true)
    }

    /**
     * A prompt from a screen outside the chat (알림 요약's buttons), started as a new conversation and marked as
     * card-originated like [sendCardPrompt]. Refused (false) while any run is going.
     */
    fun startCardPrompt(text: String): Boolean {
        if (runner.running.value || runner.card.value != null) return false
        _current.value = null
        staleActive = runner.activeConversation.value
        following.value = true
        typedAsk = null
        return runner.send(null, text, fromCard = true).also { started -> if (!started) following.value = false }
    }

    /**
     * The user's own sentence from a screen outside the chat (the routines screen), started as a new conversation:
     * typed, not card-originated, and without the composer's attachments. Refused (false) while a run is going or a
     * question card is open, so it never becomes that card's answer or a steering instruction.
     */
    fun startTyped(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || runner.running.value || runner.card.value != null) return false
        _current.value = null
        staleActive = runner.activeConversation.value
        following.value = true
        typedAsk = null
        return runner.send(null, t).also { started -> if (!started) following.value = false }
    }

    fun answer(cardId: Long, text: String) = runner.answer(cardId, text)

    fun stop() = runner.stop()

    fun newChat() {
        following.value = false
        typedAsk = null
        sentTo = null
        _current.value = null
    }

    fun select(id: Long) {
        following.value = false
        typedAsk = null
        sentTo = null
        _current.value = id
    }

    /** Deletes a conversation; a run inside it is stopped, and the screen falls back to a new chat. */
    fun delete(id: Long) {
        if (runner.running.value && runner.activeConversation.value == id) runner.stop()
        if (_current.value == id) newChat()
        scope.launch {
            repo.delete(id)
            onDeleted(setOf(id))
        }
    }

    fun deleteAll() {
        newChat()
        scope.launch {
            // The run ends (and makes its last writes) before anything is deleted.
            if (runner.running.value) runner.stopAndWait()
            val ids = repo.conversations().first().map { it.id }
            for (id in ids) repo.delete(id)
            if (ids.isNotEmpty()) onDeleted(ids.toSet())
        }
    }
}
