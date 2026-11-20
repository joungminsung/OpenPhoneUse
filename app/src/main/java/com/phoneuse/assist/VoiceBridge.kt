package com.phoneuse.assist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Hands a dock dictation back to its owner, even if the overlay or speech activity goes away first. */
object VoiceBridge {
    private class Request(val id: Int, val onResult: (String?) -> Unit) {
        var partial: String? = null
        var externalUi = false
    }

    private var current: Request? = null
    private val _activeSession = MutableStateFlow<Int?>(null)
    val activeSession = _activeSession.asStateFlow()
    private val revision = MutableStateFlow(0L)

    /** Voice sessions started so far; the agent's element indices from before one are stale. */
    @Volatile
    var sessions: Int = 0
        private set

    const val MAX_WAIT_MS = 60_000L
    const val SETTLE_MS = 400L

    fun request(onResult: (String?) -> Unit): Int {
        val old: Request?
        val id: Int
        synchronized(this) {
            old = current
            id = ++sessions
            current = Request(id, onResult)
            _activeSession.value = id
            revision.value++
        }
        old?.let { it.onResult(it.partial) }
        return id
    }

    /** Recognizers revise a partial for the same phrase and may emit an empty one before the final result. */
    @Synchronized
    fun update(id: Int, partial: String) {
        val request = current ?: return
        if (request.id == id && partial.isNotBlank() && partial != request.partial) {
            request.partial = partial
            if (!request.externalUi) revision.value++
        }
    }

    /** Android owns the microphone permission prompt or a speech dialog which only returns a final result. */
    @Synchronized
    fun setExternalUi(id: Int, visible: Boolean) {
        val request = current ?: return
        if (request.id == id && request.externalUi != visible) {
            request.externalUi = visible
            revision.value++
        }
    }

    /** Some in-app providers report speech activity but do not provide partial text. */
    @Synchronized
    fun heardAudio(id: Int) {
        val request = current ?: return
        if (request.id == id && !request.externalUi) revision.value++
    }

    /** A null result is an explicit cancellation, including when the recognizer already had some words. */
    fun deliver(id: Int, text: String?) {
        val request = take(id) ?: return
        request.onResult(text?.takeIf { it.isNotBlank() })
    }

    /** A lifecycle interruption or timeout keeps the most recent usable transcript. */
    fun interrupt(id: Int) {
        val request = take(id) ?: return
        request.onResult(request.partial)
    }

    /** Give this run's final phrase a bounded wait, even if a recognizer keeps reporting audio. */
    internal suspend fun finishForRun(id: Int, runJob: Job) {
        try {
            withContext(runJob) {
                withTimeoutOrNull(MAX_WAIT_MS) { activeSession.first { it != id } }
            }
        } catch (stopped: CancellationException) {
            if (!runJob.isCancelled) throw stopped
        }
        // Timeout or Stop preserves the partial. A completed or replaced request is already delivered.
        interrupt(id)
    }

    @Synchronized
    private fun take(id: Int, expectedRevision: Long? = null): Request? {
        val request = current?.takeIf { it.id == id } ?: return null
        if (expectedRevision != null && expectedRevision != revision.value) return null
        current = null
        _activeSession.value = null
        revision.value++
        return request
    }

    /** Live native speech extends the wait. An abandoned external dialog or silent mic returns its last words. */
    suspend fun awaitIdle(timeoutMs: Long = MAX_WAIT_MS): Boolean {
        if (activeSession.value == null) return false
        while (true) {
            val seen = revision.value
            val id = activeSession.value
            if (id == null) {
                delay(SETTLE_MS)
                if (revision.value == seen) return true
                continue
            }
            val changed = withTimeoutOrNull(timeoutMs) {
                revision.first { it != seen }
            }
            if (changed == null) {
                withContext(Dispatchers.Main.immediate) {
                    // A later partial may already have arrived while the timeout was being dispatched.
                    take(id, seen)?.let { it.onResult(it.partial) }
                }
            }
        }
    }
}
