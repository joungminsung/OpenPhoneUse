package com.phoneuse.agent

import com.phoneuse.risk.RiskCategory

class FakeUserGate : UserGate {
    var confirmResult = true
    var askResult = ""
    val confirms = mutableListOf<Pair<RiskCategory, String>>()
    val asks = mutableListOf<Pair<String, List<String>>>()
    val handoffs = mutableListOf<String>()

    override suspend fun confirm(category: RiskCategory, summary: String): Boolean {
        confirms += category to summary
        return confirmResult
    }

    override suspend fun ask(question: String, options: List<String>): String {
        asks += question to options
        return askResult
    }

    override suspend fun handToUser(reason: String) {
        handoffs += reason
    }
}
