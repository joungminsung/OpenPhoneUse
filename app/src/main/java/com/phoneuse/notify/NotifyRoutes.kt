package com.phoneuse.notify

import com.phoneuse.app.ChatCard
import com.phoneuse.app.ChatUserGate
import com.phoneuse.risk.RiskCategory

/** The app screens a notification can open besides a conversation (MainActivity maps them onto its own). */
enum class AppScreen { HOME, DIGEST, ROUTINES }

/** Where tapping a notification goes. Every notification has one: nothing taps into nowhere. */
sealed interface Tap {
    /** [reveal]: scroll so the newest answer's start is in view (a long answer would otherwise show its end). */
    data class Conversation(val id: Long, val reveal: Boolean = false) : Tap
    data class Screen(val screen: AppScreen) : Tap
}

object Taps {
    /** A run's result: its conversation at the answer; a run without one (it failed before starting) the home screen. */
    fun result(conversationId: Long?): Tap = conversationId?.let { Tap.Conversation(it, reveal = true) } ?: Tap.Screen(AppScreen.HOME)

    /** A routine's own notification: its conversation once it has run, else the routines list. */
    fun routine(conversationId: Long?): Tap = conversationId?.let { Tap.Conversation(it, reveal = true) } ?: Tap.Screen(AppScreen.ROUTINES)

    /** A card waiting for the user: the conversation, where the card is. */
    fun needsOk(conversationId: Long?): Tap = conversationId?.let { Tap.Conversation(it) } ?: Tap.Screen(AppScreen.HOME)

    /** The daily summary: the home screen's "Today" tiles; its button opens the notification summary. */
    val dailySummary: Tap = Tap.Screen(AppScreen.HOME)
    val digest: Tap = Tap.Screen(AppScreen.DIGEST)
}

/**
 * The buttons of a "needs your OK" notification, and which card they may answer. A button answers through the
 * same [ChatUserGate.answer] the chat card's buttons use, so a tap there is the user's own tap; nothing is ever
 * answered without one.
 */
object NeedsOk {
    /** At most this many option buttons for a question; one with more opens the app instead. */
    const val MAX_OPTIONS = 3

    sealed interface Button {
        data object Approve : Button
        data object Deny : Button
        data class Option(val index: Int) : Button
    }

    /**
     * Whether Approve may be a button in the notification. Money, security and delete are not: the notification
     * shows a one-line summary on what may be a locked phone, and a payment or a deletion deserves the whole card
     * in the app (which a "Review" button opens). Deny is always offered, since refusing is always safe.
     */
    fun inlineApprove(category: RiskCategory): Boolean = when (category) {
        RiskCategory.SEND, RiskCategory.CHANGE -> true
        RiskCategory.MONEY, RiskCategory.SECURITY, RiskCategory.DELETE -> false
    }

    /** The option buttons a question gets: none when it has too many to fit (the app shows them all). */
    fun options(card: ChatCard.Ask): List<String> = card.options.takeIf { it.size in 1..MAX_OPTIONS }.orEmpty()

    /**
     * Ties a button to the exact card it was made for: the card id restarts with the process, so its text is
     * part of the key, and a stale notification can never answer a newer card.
     */
    fun key(card: ChatCard): String = "${card.id}-${Integer.toHexString(text(card).hashCode())}"

    fun text(card: ChatCard): String = when (card) {
        is ChatCard.Confirm -> card.summary
        is ChatCard.Ask -> card.question
        is ChatCard.HandOff -> card.reason
    }

    /** The answer [button] gives the card waiting now, or null when it is gone, is another card, or the button may not answer it. */
    fun answer(waiting: ChatCard?, key: String, button: Button): String? {
        if (waiting == null || key(waiting) != key) return null
        return when (waiting) {
            is ChatCard.Confirm -> when (button) {
                Button.Approve -> ChatUserGate.CONFIRM_YES.takeIf { inlineApprove(waiting.category) }
                Button.Deny -> ChatUserGate.CONFIRM_NO
                is Button.Option -> null
            }
            is ChatCard.Ask -> (button as? Button.Option)?.let { options(waiting).getOrNull(it.index) }
            is ChatCard.HandOff -> null
        }
    }
}

/**
 * Throttles the running-task notification: the first post waits [firstDelayMs] from the run's start (a quick
 * answer never flashes one), later ones come at most every [intervalMs], and a post equal to the last is skipped.
 * Times are a monotonic clock in milliseconds.
 */
class UpdateThrottle(private val startedAt: Long, private val firstDelayMs: Long, private val intervalMs: Long) {
    private var lastAt: Long? = null
    private var last: Any? = null

    /** How long to wait before posting [content] at [now]; null when it is what is already showing. */
    fun waitFor(content: Any, now: Long): Long? {
        val at = lastAt
        if (at != null && content == last) return null
        val earliest = if (at == null) startedAt + firstDelayMs else at + intervalMs
        return (earliest - now).coerceAtLeast(0)
    }

    fun posted(content: Any, now: Long) {
        lastAt = now
        last = content
    }

    /** The notification was taken away (the app came to the front): the next post is never skipped as unchanged. */
    fun cleared() {
        last = null
    }
}
