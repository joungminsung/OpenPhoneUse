package com.phoneuse.service

import android.os.SystemClock

/**
 * One tree read's budget: it stops descending at [deadline] (uptime ms) or after [maxNodes] nodes, and is then
 * truncated. The deadline can be moved while the read runs (another caller joins it, or it is no longer wanted);
 * it is checked between binder calls, so a call already blocked in a busy app still finishes first.
 */
class ReadBudget(deadline: Long, private val maxNodes: Int = AccessibilityTreeReader.MAX_NODES) {
    @Volatile
    var deadline: Long = deadline
        private set

    @Volatile
    var nodes = 0
        private set

    @Volatile
    var truncated = false
        private set

    /** Lets the read run at least until [d]. */
    @Synchronized
    fun extendTo(d: Long) {
        if (d > deadline) deadline = d
    }

    /** Ends the read at its next node. */
    @Synchronized
    fun stop() {
        deadline = Long.MIN_VALUE
    }

    fun spent(): Boolean {
        if (truncated) return true
        if (++nodes > maxNodes || SystemClock.uptimeMillis() > deadline) truncated = true
        return truncated
    }
}
