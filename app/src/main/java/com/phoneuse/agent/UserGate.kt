package com.phoneuse.agent

import com.phoneuse.risk.RiskCategory

interface UserGate {
    suspend fun confirm(category: RiskCategory, summary: String): Boolean
    suspend fun ask(question: String, options: List<String>): String

    /** Returns once the user presses the done button. */
    suspend fun handToUser(reason: String)
}
