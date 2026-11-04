package com.phoneuse.assist

import com.phoneuse.i18n.tr
import com.phoneuse.chat.Conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The conversation the sheet's "이어서" chip offers: the most recently updated one, or null when there is none. */
fun recentConversation(conversations: List<Conversation>): Conversation? = conversations.maxByOrNull { it.updatedAt }

/** Where a sheet message goes: the chip's conversation when the chip is selected, otherwise a new one (null). */
fun targetConversation(chipSelected: Boolean, recent: Conversation?): Long? = if (chipSelected) recent?.id else null

/** Why an ASSIST run cannot start now, or null when it can: the overlay needs the accessibility service, and one run at a time. */
fun assistBlocker(serviceReady: Boolean, running: Boolean): String? = when {
    !serviceReady -> AssistSender.NO_SERVICE
    running -> AssistSender.BUSY
    else -> null
}

/** What [AssistSender.submit] did with a message. */
sealed interface SubmitResult {
    /** Blank text: nothing happened. */
    data object Empty : SubmitResult

    /** The run cannot start; the sheet stays open with the text and shows [reason]. */
    data class Blocked(val reason: String) : SubmitResult

    /** The sheet was hidden and the message is on its way. */
    data object Sent : SubmitResult
}

/**
 * Sends a sheet message: checks [blocker] first (a reason the run cannot start, or null), then hides the sheet,
 * waits [settleMs] for the screen to return to the app underneath, and hands the text to [send]
 * (AgentRunner.send with Source.ASSIST). [scope] must outlive the session.
 */
class AssistSender(
    private val scope: CoroutineScope,
    private val send: (conversationId: Long?, text: String) -> Boolean,
    private val blocker: () -> String? = { null },
    private val settleMs: Long = SETTLE_MS,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    /** A tapped "이 화면으로 할 일" row: its text came from the model, never as the user's authorization to send. */
    private val sendSuggested: (conversationId: Long?, text: String) -> Boolean = send,
) {
    /** [suggested]: [text] is a suggestion the user tapped, not words they wrote (see [sendSuggested]). */
    fun submit(text: String, conversationId: Long?, suggested: Boolean = false, hide: () -> Unit): SubmitResult {
        val message = text.trim()
        if (message.isEmpty()) return SubmitResult.Empty
        blocker()?.let { return SubmitResult.Blocked(it) }
        hide()
        scope.launch {
            wait(settleMs)
            // A false result (the state changed during the wait) is already reported by the runner's toast.
            if (suggested) sendSuggested(conversationId, message) else send(conversationId, message)
        }
        return SubmitResult.Sent
    }

    companion object {
        const val SETTLE_MS = 300L
        val NO_SERVICE: String get() = tr("Please turn on the accessibility service", "접근성 서비스를 켜주세요")
        val BUSY: String get() = tr("Already running. Send again when it's done", "이미 실행 중이에요. 끝난 뒤 다시 보내 주세요")
    }
}
