package com.phoneuse.app

import com.phoneuse.agent.UserGate
import com.phoneuse.risk.RiskCategory
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A routine run's gate: the user may not be looking, so each question first calls [onWaiting] (a "확인이
 * 필요해요" notification) and an unanswered one ends after [timeoutMs] as a refusal, so the runner is not held
 * forever by a routine nobody is watching.
 */
class TimedUserGate(
    private val inner: UserGate,
    private val timeoutMs: Long,
    private val onWaiting: (String) -> Unit = {},
) : UserGate {
    override suspend fun confirm(category: RiskCategory, summary: String): Boolean {
        onWaiting(summary)
        return withTimeoutOrNull(timeoutMs) { inner.confirm(category, summary) } ?: false
    }

    override suspend fun ask(question: String, options: List<String>): String {
        onWaiting(question)
        return withTimeoutOrNull(timeoutMs) { inner.ask(question, options) } ?: NO_ANSWER
    }

    override suspend fun handToUser(reason: String) {
        onWaiting(reason)
        withTimeoutOrNull(timeoutMs) { inner.handToUser(reason) }
    }

    companion object {
        const val NO_ANSWER = "(The user did not answer. Do not ask again: do what you can without them, then call finish.)"

        /** How long a routine run waits for an answer. */
        const val ROUTINE_TIMEOUT_MS = 10 * 60_000L
    }
}
