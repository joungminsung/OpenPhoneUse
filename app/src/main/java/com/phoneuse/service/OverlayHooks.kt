package com.phoneuse.service

/** Implemented by the overlay manager so gestures and screenshots are not blocked or polluted by it. */
interface OverlayHooks {
    suspend fun <T> passThrough(x: Int, y: Int, block: suspend () -> T): T
    suspend fun <T> hiddenForCapture(block: suspend () -> T): T

    /** Takes input focus away from the dock so keys and the IME go to the app underneath. */
    suspend fun releaseInputFocus()

    /** Shows where the agent touches: a ripple at ([x], [y]), a trail to ([x2], [y2]) for a swipe. Visual only. */
    fun showTouch(x: Int, y: Int, x2: Int? = null, y2: Int? = null, long: Boolean = false) = Unit
}
