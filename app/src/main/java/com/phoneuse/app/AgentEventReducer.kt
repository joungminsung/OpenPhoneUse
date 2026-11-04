package com.phoneuse.app

import com.phoneuse.i18n.tr
import com.phoneuse.agent.AgentEvent
import com.phoneuse.overlay.AgentPhase
import com.phoneuse.overlay.AgentUiState

private const val MAX_LINES = 3

/** Appends [line], keeping only the most recent lines the dock can show. */
fun withLine(state: AgentUiState, line: String): AgentUiState =
    state.copy(lines = (state.lines + line).takeLast(MAX_LINES))

/** Maps one loop event onto the overlay state. Cancellation is not an event; the runner handles it. */
fun reduce(state: AgentUiState, event: AgentEvent): AgentUiState = when (event) {
    AgentEvent.Thinking -> state.copy(phase = AgentPhase.THINKING)
    is AgentEvent.Acting -> withLine(state.copy(phase = AgentPhase.ACTING), event.description)
    is AgentEvent.Log -> withLine(state, event.text)
    is AgentEvent.Usage -> state
    is AgentEvent.Card -> state
    is AgentEvent.Searching -> withLine(state, tr("Web search", "웹 검색") + (event.queries.firstOrNull()?.let { ": $it" } ?: ""))
    is AgentEvent.Sources -> state
    is AgentEvent.Remembered -> withLine(state, tr("Remembered: ${event.memory.text}", "기억: ${event.memory.text}"))
    is AgentEvent.Forgot -> withLine(state, tr("Forgot: ${event.memory.text}", "기억 지움: ${event.memory.text}"))
    is AgentEvent.DeviceTaskDone -> state
    is AgentEvent.TextDelta -> state
    AgentEvent.TextReset -> state
    AgentEvent.DeviceUse -> state
    is AgentEvent.Finished -> withLine(state.copy(phase = AgentPhase.IDLE, card = null), tr("Done: ${event.summary}", "완료: ${event.summary}"))
    is AgentEvent.Failed -> withLine(state.copy(phase = AgentPhase.IDLE, card = null), tr("Failed: ${event.message}", "실패: ${event.message}"))
    AgentEvent.Cancelled -> withLine(state.copy(phase = AgentPhase.IDLE, card = null), tr("Stopped", "중단됨"))
}
