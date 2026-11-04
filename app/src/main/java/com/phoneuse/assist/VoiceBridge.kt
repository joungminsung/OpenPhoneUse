package com.phoneuse.assist

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hands recognised speech from [AssistVoiceActivity] back to a window that cannot receive activity results
 * (the overlay dock). One request at a time: a new request ends the previous one with null.
 * While a request is open the speech screen is in front, so device actions wait ([awaitIdle]).
 */
object VoiceBridge {
    private var callback: ((String?) -> Unit)? = null
    private val _listening = MutableStateFlow(false)

    /** True from [request] until the result (or cancellation) is delivered. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** Voice sessions started so far; the agent's element indices from before one are stale. */
    @Volatile
    var sessions: Int = 0
        private set

    /** Longest a device action waits for the speech screen; a stuck request never blocks the agent for good. */
    const val MAX_WAIT_MS = 60_000L

    /** After the speech screen closes, the app underneath needs a moment to be back in front. */
    const val SETTLE_MS = 400L

    @Synchronized
    fun request(onResult: (String?) -> Unit) {
        val old = callback
        callback = onResult
        sessions++
        _listening.value = true
        old?.invoke(null)
    }

    /** Delivers [text] (null: nothing recognised or cancelled) to the waiting request, once. */
    fun deliver(text: String?) {
        val cb = synchronized(this) {
            _listening.value = false
            callback.also { callback = null }
        }
        cb?.invoke(text)
    }

    /**
     * Suspends while the speech screen is up (at most [timeoutMs]; then the request is dropped so later calls
     * do not wait again), then lets the screen settle. Returns true if it had to wait.
     */
    suspend fun awaitIdle(timeoutMs: Long = MAX_WAIT_MS): Boolean {
        if (!_listening.value) return false
        if (withTimeoutOrNull(timeoutMs) { _listening.first { !it } } == null) deliver(null)
        delay(SETTLE_MS)
        return true
    }
}
