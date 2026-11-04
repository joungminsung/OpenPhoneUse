package com.phoneuse.app

/**
 * After the ChatGPT plan's usage limit ([com.phoneuse.model.ModelException.PLAN_USAGE_LIMIT]), automatic requests on
 * the plan (routines, briefs, the briefing's weather, side-button suggestions) stay off the server for a while, as
 * OpenAI's errors page asks. The code names no reset time and the limit may be this app's alone, so what the user asks
 * for themself still goes out, and a run on the plan that finishes lifts the pause. The stored value is
 * [SettingsStore.planPausedUntil].
 */
object PlanPause {
    const val PAUSE_MS = 60 * 60_000L

    /** The value to store when the limit is hit at [now]. */
    fun until(now: Long): Long = now + PAUSE_MS

    /**
     * Whether automatic work holds back at [now]. Only on the plan ([usesPlan]: an API key has its own limits), and
     * never for longer than [PAUSE_MS]: a clock moved back must not stretch the pause.
     */
    fun holds(usesPlan: Boolean, pausedUntil: Long, now: Long): Boolean =
        usesPlan && now < pausedUntil && pausedUntil - now <= PAUSE_MS
}
