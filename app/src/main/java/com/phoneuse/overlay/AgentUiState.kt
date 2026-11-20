package com.phoneuse.overlay

import com.phoneuse.i18n.tr
import com.phoneuse.risk.RiskCategory

enum class AgentPhase { IDLE, THINKING, ACTING, WAITING_USER }

/** [id] identifies one request; taps carry it so a stale card cannot answer a newer request. */
sealed interface DockCard {
    val id: Long

    data class Confirm(val category: RiskCategory, val summary: String, override val id: Long = 0) : DockCard
    data class Ask(val question: String, val options: List<String>, override val id: Long = 0) : DockCard
    data class HandOff(val reason: String, override val id: Long = 0) : DockCard
}

/** [lines] holds the most recent progress lines (the dock shows at most the last three). */
data class AgentUiState(
    val phase: AgentPhase = AgentPhase.IDLE,
    val lines: List<String> = emptyList(),
    val card: DockCard? = null,
)

/** Sends to the displayed question or running task. A refused submission stays in the composer. */
fun submitDockText(
    card: DockCard?,
    text: String,
    answer: (id: Long, text: String) -> Boolean,
    steer: (String) -> Boolean,
): Boolean {
    val t = text.trim()
    if (t.isEmpty()) return false
    return if (card is DockCard.Ask) answer(card.id, t) else steer(t)
}

fun RiskCategory.koreanName(): String = when (this) {

    RiskCategory.MONEY -> tr("Payment", "결제")
    RiskCategory.SECURITY -> tr("Security", "보안")
    RiskCategory.DELETE -> tr("Delete", "삭제")
    RiskCategory.SEND -> tr("Send", "전송")
    RiskCategory.CHANGE -> tr("Change", "변경")
}
