package com.phoneuse.ui.fold

import com.phoneuse.i18n.tr
import com.phoneuse.cards.CardAction
import com.phoneuse.cards.CardActions
import com.phoneuse.cards.CardSpec
import com.phoneuse.cards.DraftCard
import com.phoneuse.cards.EventCard
import com.phoneuse.cards.PermissionCard
import com.phoneuse.cards.RouteCard
import com.phoneuse.chat.ChatMessage
import com.phoneuse.chat.MessageRole

/** How the device is bent: [TABLETOP] is half opened with a horizontal hinge, [BOOK] with a vertical one. */
enum class Posture { FLAT, TABLETOP, BOOK }

enum class FoldMode {
    /** Folded (or any narrow window): the phone layout, list in the drawer. */
    PHONE,

    /** Unfolded, no card yet: the list + chat. */
    TWO_PANE,

    /** Unfolded with a card: chat on the left, the conversation's last card large on the right (board Unfolded). */
    RESULT_PANE,

    /** Tabletop: the card on the top half, its buttons and a big voice button on the bottom (board UnfoldedFlex). */
    FLEX,

    /** A small window beside the app being operated: progress only (board UnfoldedSplit). */
    SPLIT_PROGRESS,
}

/** [listRail]: in [FoldMode.RESULT_PANE], the conversation list stays on screen instead of in the drawer. */
data class FoldLayout(val mode: FoldMode, val listRail: Boolean = false)

/**
 * A card button for flex mode's bottom half. [replaces]: the label of the card's own button it stands in for,
 * left out of the card above (null when the card has no such button, e.g. later conflicts of an event).
 */
data class FlexButton(val label: String, val action: CardAction, val primary: Boolean = false, val replaces: String? = label)

/** The result pane's card: the [index]th card of message [messageId]. */
data class PaneCard(val messageId: Long, val index: Int, val card: CardSpec) {
    /** Names the card wherever it is drawn (chat, pane, flex), so its state is shared. */
    val key: String get() = keyOf(messageId, index)

    companion object {
        fun keyOf(messageId: Long, index: Int) = "card:$messageId:$index"
    }
}

/** Window size, posture and chat state → layout. Pure, so the rules are unit-tested. */
object FoldLayoutPolicy {
    /** From this width the chat gets a second pane (unfolded Fold, tablets). */
    const val WIDE_DP = 600

    /** From this width the list rail (300dp), the chat (372dp) and a usable result pane all fit. */
    const val RAIL_DP = 960

    /**
     * [asking]: a question or confirmation card is waiting; it lives in the chat, so flex mode steps aside.
     * [runningHere]: the agent is working on the conversation on screen.
     */
    fun decide(
        widthDp: Int,
        posture: Posture,
        multiWindow: Boolean,
        hasCard: Boolean,
        runningHere: Boolean,
        asking: Boolean,
    ): FoldLayout = when {
        posture == Posture.TABLETOP && hasCard && !multiWindow && !asking -> FoldLayout(FoldMode.FLEX)
        multiWindow && runningHere && widthDp < WIDE_DP -> FoldLayout(FoldMode.SPLIT_PROGRESS)
        widthDp >= WIDE_DP && hasCard -> FoldLayout(FoldMode.RESULT_PANE, listRail = widthDp >= RAIL_DP)
        widthDp >= WIDE_DP -> FoldLayout(FoldMode.TWO_PANE)
        else -> FoldLayout(FoldMode.PHONE)
    }

    /** The result pane shows the conversation's newest card. */
    fun resultCard(messages: List<ChatMessage>): PaneCard? {
        val m = messages.lastOrNull { it.cards.isNotEmpty() } ?: return null
        return PaneCard(m.id, m.cards.lastIndex, m.cards.last())
    }

    /**
     * Flex mode's reply line: the newest assistant message's text (errors are saved as replies too), else its
     * notice. Null when there is no reply yet.
     */
    fun latestReply(messages: List<ChatMessage>): String? {
        val m = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return null
        return m.text.trim().ifEmpty { m.extras.notice?.trim().orEmpty() }.ifEmpty { null }
    }

    /**
     * Flex mode's reply line. While the agent works here: the live answer so far (nothing yet hides the previous
     * reply). Otherwise the newest reply. Long text shows its end, where the answer is being written.
     */
    fun flexReply(messages: List<ChatMessage>, runningHere: Boolean, streaming: String): String? {
        val text = if (runningHere) streaming.trim().ifEmpty { null } else latestReply(messages)
        return text?.let { if (it.length > FLEX_REPLY_CHARS) "…" + it.takeLast(FLEX_REPLY_CHARS).trimStart() else it }
    }

    /** About four lines of the flex top half. */
    const val FLEX_REPLY_CHARS = 160

    /** Whether something newer than [pane]'s message was said (a new request or a reply without a card). */
    fun cardIsOutdated(messages: List<ChatMessage>, pane: PaneCard): Boolean =
        messages.lastOrNull()?.id != pane.messageId

    /** The chat column beside the result pane: the board's 372dp, but never more than half the window. */
    fun chatColumnDp(widthDp: Int): Int = minOf(CHAT_COLUMN_DP, widthDp / 2)

    const val CHAT_COLUMN_DP = 372

    /** WindowManager's FoldingFeature, reduced to what the layouts use. */
    fun posture(halfOpened: Boolean, horizontalHinge: Boolean): Posture = when {
        !halfOpened -> Posture.FLAT
        horizontalHinge -> Posture.TABLETOP
        else -> Posture.BOOK
    }

    /** The progress layout shows only the request being worked on and what followed it. */
    fun latestExchange(messages: List<ChatMessage>): List<ChatMessage> {
        val start = messages.indexOfLast { it.role == MessageRole.USER }
        return if (start <= 0) messages else messages.subList(start, messages.size)
    }

    /** The card's own button labels that flex mode draws in the bottom half instead. */
    fun movedButtons(card: CardSpec): Set<String> = flexButtons(card).mapNotNullTo(HashSet()) { it.replaces }

    /**
     * The card's own buttons, for flex mode's bottom half (the card above is then drawn without them).
     * Cards whose buttons belong to items or a selection (places, photos, results, digest) keep them: empty list.
     */
    fun flexButtons(card: CardSpec): List<FlexButton> = when (card) {
        is RouteCard -> listOf(
            FlexButton(
                tr("Remind at ${CardActions.prepareAt(card.departAt)}", "${CardActions.prepareAt(card.departAt)}에 알림"),
                CardActions.routeReminder(card),
                primary = true,
                replaces = tr("Remind me to get ready at ${CardActions.prepareAt(card.departAt)}", "${CardActions.prepareAt(card.departAt)}에 나갈 준비 알림"),
            ),
            FlexButton(tr("Open in maps app", "지도 앱에서 열기"), CardActions.openRoute(card)),
            FlexButton(tr("Share arrival time", "도착 시간 보내기"), CardActions.shareArrival(card)),
        )
        is EventCard -> buildList {
            add(FlexButton(tr("Edit", "고치기"), CardActions.editEvent()))
            add(FlexButton(tr("Save as is", "그대로 저장"), CardActions.saveEvent(), primary = true))
            // One per conflict (the card itself offers only the first); the first stands in for the card's button.
            card.conflicts.forEachIndexed { i, _ ->
                val label = if (card.conflicts.size == 1) tr("Decline", "불참 알리기") else tr("Decline event ${i + 1}", "${i + 1}번 일정 불참 알리기")
                add(FlexButton(label, CardActions.notifyConflict(i), replaces = if (i == 0) tr("Decline", "불참 알리기") else null))
            }
            if (card.conflicts.isNotEmpty()) add(FlexButton(tr("Find free time", "비는 시간 찾기"), CardActions.findFreeTime()))
        }
        is DraftCard -> listOf(
            FlexButton(tr("Copy", "복사"), CardAction.CopyText(card.body)),
            FlexButton(tr("Edit", "고치기"), CardActions.editDraft()),
            FlexButton(
                when (card.channel) {
                    "email" -> tr("Send as email", "메일로 보내기")
                    "note" -> tr("Save as note", "메모로 저장")
                    else -> tr("Send as text", "문자로 보내기")
                },
                CardActions.sendDraft(card),
                primary = true,
            ),
        )
        is PermissionCard -> listOf(FlexButton(tr("Allow permission", "권한 허용"), CardActions.requestPermission(card), primary = true))
        else -> emptyList()
    }
}
