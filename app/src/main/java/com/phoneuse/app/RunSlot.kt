package com.phoneuse.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one-run-at-a-time slot. [claim] marks the slot running and records the run's token in one atomic step, and
 * [release] frees it only for the token that holds it, so an old run's completion can never free (or reset the
 * state of) a newer run that started in between. [running] is written only here.
 */
class RunSlot {
    private val lock = Any()
    private var holder: Any? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Makes the run's token with [make] and takes the slot; null (and [make] not called) while a run holds it. */
    fun <T : Any> claim(make: () -> T): T? = synchronized(lock) {
        if (holder != null) return null
        val token = make()
        holder = token
        _running.value = true
        token
    }

    /**
     * Frees the slot when [token] holds it, running [onRelease] first under the same lock; false (nothing run)
     * when another run holds it or it is free.
     */
    fun release(token: Any, onRelease: () -> Unit = {}): Boolean = synchronized(lock) {
        if (holder !== token) return false
        try {
            onRelease()
        } finally {
            holder = null
            _running.value = false
        }
        true
    }
}
