package com.phoneuse.overlay

import com.phoneuse.agent.ServiceUnavailableException
import com.phoneuse.agent.UserGate

import com.phoneuse.risk.RiskCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** What [OverlayUserGate] needs from the overlay; [OverlayManager] implements it. */
interface CardSink {
    val state: MutableStateFlow<AgentUiState>
    var onCardResult: (id: Long, result: String) -> Unit
    fun setBlocking(enabled: Boolean)

    /** True once the overlay is gone for good (service lost); card requests then fail. */
    val isClosed: Boolean

    /** Invoked once when the overlay closes for good, from any thread. */
    var onClosed: () -> Unit
}

/**
 * Shows a card in the dock and suspends until the user answers. One request at a time.
 * If the overlay closes, a waiting or new request throws [ServiceUnavailableException].
 */
class OverlayUserGate(private val overlay: CardSink) : UserGate {
    private val mutex = Mutex()

    private class Pending(val id: Long, val answer: CompletableDeferred<String>)

    @Volatile
    private var pending: Pending? = null
    private var nextId = 0L

    init {
        // Taps from an old or already-answered card carry a different id and are ignored.
        overlay.onCardResult = { id, result -> pending?.takeIf { it.id == id }?.answer?.complete(result) }
        overlay.onClosed = { pending?.answer?.completeExceptionally(ServiceUnavailableException()) }
    }

    override suspend fun confirm(category: RiskCategory, summary: String): Boolean =
        request { DockCard.Confirm(category, summary, it) } == CONFIRM_YES

    override suspend fun ask(question: String, options: List<String>): String =
        request { DockCard.Ask(question, options, it) }

    override suspend fun handToUser(reason: String) {
        overlay.setBlocking(false)
        try {
            request { DockCard.HandOff(reason, it) }
        } finally {
            overlay.setBlocking(true)
        }
    }

    private suspend fun request(makeCard: (Long) -> DockCard): String = mutex.withLock {
        if (overlay.isClosed) throw ServiceUnavailableException()
        val request = Pending(++nextId, CompletableDeferred())
        pending = request
        // The overlay may have closed between the check and publishing the request.
        if (overlay.isClosed) request.answer.completeExceptionally(ServiceUnavailableException())
        val previousPhase = overlay.state.value.phase
        overlay.state.update { it.copy(phase = AgentPhase.WAITING_USER, card = makeCard(request.id)) }
        try {
            request.answer.await()
        } finally {
            if (pending?.id == request.id) pending = null
            overlay.state.update {
                // Restore only if nobody changed the phase while we waited.
                it.copy(phase = if (it.phase == AgentPhase.WAITING_USER) previousPhase else it.phase, card = null)
            }
        }
    }

    companion object {
        const val CONFIRM_YES = "실행"
        const val CONFIRM_NO = "취소"
        const val HAND_OFF_DONE = "완료"
    }
}
