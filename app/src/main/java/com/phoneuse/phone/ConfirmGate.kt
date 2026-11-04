package com.phoneuse.phone

import com.phoneuse.cards.CardActions

/** Who started the run; a write confirmed on a card (calendar_add / photos_album with confirmed=true) needs the user. */
sealed interface RunTrigger {
    /** The user typed (or dictated) the message in the chat. */
    data object Typed : RunTrigger

    /** A card button sent [prompt] (card-originated runs). */
    data class CardButton(val prompt: String) : RunTrigger

    /** Anything else: the assistant overlay, a routine, a notification. */
    data object Other : RunTrigger
}

enum class ConfirmKind { EVENT, ALBUM }

/**
 * Whether a confirmed write may go ahead, before the card itself is compared. Pure.
 *
 * Allowed only when the user started this run, by typing or by that card's own button, and no third-party text
 * has entered the run (a web page or a screen could otherwise ask for the save). The matching card must also be in
 * the stored history, which the tools check: cards shown in the current run are not stored yet, so a card the
 * model showed with show_card in this run never confirms itself.
 */
object ConfirmGate {
    private val ALBUM_SOME = Regex("""사진 \d+(?:, \d+)*번으로 앨범 만들어줘|Make an album from photos \d+(?:, \d+)*""")

    /** Null when allowed, else the reason given back to the model. */
    /**
     * [historyTainted]: an earlier run of this conversation read third-party text, so the history may carry it
     * A typed confirmed write is then refused; the card's own button still saves, since the user tapped it.
     */
    fun refusal(kind: ConfirmKind, trigger: RunTrigger, tainted: Boolean, historyTainted: Boolean = false): String? {
        val byUser = when (trigger) {
            RunTrigger.Typed -> true
            is RunTrigger.CardButton -> isOwnButton(kind, trigger.prompt)
            RunTrigger.Other -> false
        }
        val what = if (kind == ConfirmKind.EVENT) "[그대로 저장]" else "[앨범 만들기]"
        return when {
            !byUser -> "사용자가 카드의 $what 버튼을 누르거나 직접 말해야 저장해요. confirmed 없이 불러 카드를 보여주고 기다리세요."
            tainted -> "이 작업 중에 다른 곳(웹·화면·사진 등)의 글을 읽어서 확인 없이 저장할 수 없어요. confirmed 없이 다시 불러 카드를 보여주세요."
            historyTainted && trigger !is RunTrigger.CardButton ->
                "이 대화에서 앞서 알림·문자·웹 같은 다른 곳의 글을 읽어서 확인 없이 저장할 수 없어요. confirmed 없이 불러 카드를 보여주세요."
            else -> null
        }
    }

    private fun isOwnButton(kind: ConfirmKind, prompt: String): Boolean = when (kind) {
        ConfirmKind.EVENT -> prompt in CardActions.saveEventTexts
        ConfirmKind.ALBUM -> prompt == CardActions.ALBUM_ALL_KO || prompt == CardActions.ALBUM_ALL_EN || ALBUM_SOME.matches(prompt)
    }
}
