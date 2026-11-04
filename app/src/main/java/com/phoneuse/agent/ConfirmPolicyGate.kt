package com.phoneuse.agent

import com.phoneuse.risk.RiskCategory

/**
 * The confirmations Settings "메시지 보내기 전 확인" can switch off: SEND (messages, mail, posts) and CHANGE (alarm,
 * wallpaper). MONEY, SECURITY and DELETE are the user's own always-confirm scope and always ask here; the one
 * exception is upstream: ToolExecutor lets a DELETE through without reaching the gate when the user's own words
 * asked for it and the AI judge calls it that single recoverable item (see ToolExecutor.deleteSkips).
 */
object ConfirmPolicy {
    val SWITCHABLE: Set<RiskCategory> = setOf(RiskCategory.SEND, RiskCategory.CHANGE)

    /** Whether [category] asks the user, given whether confirmations are required ([confirmSend]). */
    fun asks(category: RiskCategory, confirmSend: Boolean): Boolean = confirmSend || category !in SWITCHABLE
}

/**
 * [inner], except that a confirmation [ConfirmPolicy] does not ask for passes without a card. [confirmSend] is read
 * at each confirmation, so taint a run picks up on the way (third-party text) turns the cards back on at once.
 *
 * A SEND is skipped only for a call that claimed user_authorized ([claimed], see [forClaim]): the user's own message
 * fixed the recipient and the content. A send whose content the model wrote always asks, switch or not.
 */
class ConfirmPolicyGate(
    private val inner: UserGate,
    private val claimed: Boolean = false,
    private val confirmSend: () -> Boolean,
) : UserGate {
    /** This gate for one call: [userAuthorized] is whether that call claimed user_authorized. */
    fun forClaim(userAuthorized: Boolean): ConfirmPolicyGate =
        if (userAuthorized == claimed) this else ConfirmPolicyGate(inner, userAuthorized, confirmSend)

    override suspend fun confirm(category: RiskCategory, summary: String): Boolean = when {
        category == RiskCategory.SEND && !claimed -> inner.confirm(category, summary)
        ConfirmPolicy.asks(category, confirmSend()) -> inner.confirm(category, summary)
        else -> true
    }

    override suspend fun ask(question: String, options: List<String>): String = inner.ask(question, options)

    override suspend fun handToUser(reason: String) = inner.handToUser(reason)
}
