package com.phoneuse.agent

/**
 * How a quick settle ended ([how], for the step timing log) and how many more ms the full settle could have
 * waited ([remainingMs]). Within that window a late change of the screen restarts the model call.
 */
data class QuickSettle(val how: String, val remainingMs: Long)

/**
 * The screen the model was just shown ([hash] of its text) and for how much longer ([windowMs]) the screen may
 * still change because of the last action. While it holds, the loop compares the live screen with [hash]: on a
 * change the model call is cancelled and sent again with the new screen. So the model starts thinking as soon as
 * the screen looks settled, and still never acts on a screen older than a full settle would have given it.
 *
 * [print] is the shown screen's [ScreenJournal.fingerprint] (digits left out): when the live text differs from
 * [hash] only in digits (a clock, a timer, "45%"), it is not a change. Null compares [hash] alone.
 */
data class ScreenWatch(val hash: Int, val windowMs: Long, val print: Int? = null)

/** Whether steps start the model before the screen has fully settled (a debug broadcast can switch it off to compare). */
object Speculation {
    @Volatile
    var enabled: Boolean = true

    /** Live screen checks while the model thinks, at most this often. */
    const val POLL_MS = 120L

    /** Restarts per step; after that the step waits like before. */
    const val MAX_RESTARTS = 2
}
