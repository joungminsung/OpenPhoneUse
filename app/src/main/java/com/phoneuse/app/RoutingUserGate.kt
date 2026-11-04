package com.phoneuse.app

import com.phoneuse.agent.UserGate
import com.phoneuse.risk.RiskCategory

/** Sends each request to the overlay while it is up, and to the chat screen otherwise. */
class RoutingUserGate(
    private val overlay: UserGate,
    private val chat: UserGate,
    private val overlayActive: () -> Boolean,
) : UserGate {
    private fun target(): UserGate = if (overlayActive()) overlay else chat

    override suspend fun confirm(category: RiskCategory, summary: String): Boolean = target().confirm(category, summary)

    override suspend fun ask(question: String, options: List<String>): String = target().ask(question, options)

    override suspend fun handToUser(reason: String) = target().handToUser(reason)
}
