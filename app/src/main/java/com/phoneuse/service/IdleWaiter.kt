package com.phoneuse.service

import com.phoneuse.screen.TreeCompressor
import kotlinx.coroutines.delay

/**
 * How a settle wait ended (logged with the step timings). ANIMATED: the app keeps animating (a map, a player)
 * but nothing is loading, so the shorter [SettlePolicy.animatedCapMs] ended the wait.
 */
enum class Settled { NO_CHANGE, QUIET, STABLE, ANIMATED, CAP }

/**
 * When the UI counts as settled. Animated or live-updating apps never go quiet, so waiting for silence alone
 * would burn the whole cap on every step; the wait ends as soon as one of these holds:
 * - after an action, no UI event came within [firstEventMs]: the action had no visible effect;
 * - no UI event for [quietMs] since the latest one;
 * - two screen samples [stableGapMs] apart are identical (see [IdleWaiter.settle]);
 * - the events are a running animation, the screen is not loading and [animatedCapMs] passed;
 * - [capMs] passed.
 * [sampleFromMs]: samples start only this long into the wait (opening an app: a splash screen holds still too,
 * so it must not count as stable early).
 */
data class SettlePolicy(
    val firstEventMs: Long = 600,
    val quietMs: Long = 150,
    val capMs: Long = 1500,
    val stableGapMs: Long = 120,
    val animatedCapMs: Long = 700,
    val sampleFromMs: Long = 0,
) {
    /**
     * The decision at [now] for a wait that began at [start] (one clock, ms); null = keep waiting. [lastEvent] is
     * the latest UI event at or after [start], or null when none came. With [waitForChange] (after an action) a
     * wait without events lasts [firstEventMs]; without it (a plain screen read) the start counts as an event.
     */
    fun decide(start: Long, now: Long, lastEvent: Long?, waitForChange: Boolean): Settled? {
        val elapsed = now - start
        if (elapsed >= capMs) return Settled.CAP
        if (lastEvent == null) {
            return when {
                waitForChange -> if (elapsed >= firstEventMs) Settled.NO_CHANGE else null
                else -> if (elapsed >= quietMs) Settled.QUIET else null
            }
        }
        return if (now - lastEvent >= quietMs) Settled.QUIET else null
    }

    /** This policy scaled for Settings "조작 속도" (0.7, 1 or 1.4): caps by [scale], settle gaps only when slower. */
    fun scaled(scale: Double): SettlePolicy {
        if (scale == 1.0) return this
        fun ms(v: Long, s: Double) = Math.round(v * s)
        // "빠르게" only gives up sooner; the quiet and stable gaps that decide "the screen has settled" never
        // shrink, so a fast setting cannot read a screen that is still moving. "느리게" stretches every wait.
        val gaps = maxOf(scale, 1.0)
        return SettlePolicy(
            ms(firstEventMs, scale), ms(quietMs, gaps), ms(capMs, scale), ms(stableGapMs, gaps),
            ms(animatedCapMs, scale), ms(sampleFromMs, scale),
        )
    }
}

/** Settings "조작 속도", kept in step with the setting by AppGraph; read on every settle. */
object SettleSpeed {
    @Volatile
    var scale: Double = 1.0
}

/**
 * One screen sample taken while settling: a structural hash of the app windows (see
 * [TreeCompressor.stabilityHash]), whether it shows loading, and whether the read ran out of time ([truncated]:
 * then it proves nothing).
 */
data class ScreenSample(val hash: Int, val busy: Boolean, val truncated: Boolean = false)

/**
 * Tracks UI activity (accessibility events) and waits for the UI to settle.
 *
 * Not every event counts. Ignored: our own app and overlay (it toggles its window flags around every gesture),
 * windows the service marks as noise ([ignoredWindows]: status bar, navigation bar, keyboard), views that draw
 * continuously (map, video and camera surfaces), and content changes from any source that has fired without a
 * break for [ANIMATION_STREAK_MS] (a map camera, a player's clock, a banner): it is animating, not loading.
 */
class IdleWaiter(private val clock: () -> Long) {
    @Volatile
    private var lastEvent = Long.MIN_VALUE

    /** When the latest counted UI event came (clock ms), or [Long.MIN_VALUE] when none has. */
    val lastEventAt: Long get() = lastEvent

    /** Window ids whose events never count (system bars, keyboard, our overlay); kept up to date by the service. */
    @Volatile
    var ignoredWindows: Set<Int> = emptySet()

    @Volatile
    private var ownChangeAt = Long.MIN_VALUE

    /** Our overlay just changed its windows; the "windows changed" events right after are ours, not the app's. */
    fun ownWindowsChanged() {
        ownChangeAt = clock()
    }

    private class Streak(val start: Long, var last: Long)

    /** Per event source (window, class, type): since when it has fired without a gap. Guarded by itself. */
    private val streaks = HashMap<Triple<Int, String?, Int>, Streak>()

    @Volatile
    private var lastAnimated = Long.MIN_VALUE

    /**
     * Records UI activity of [eventType] from [packageName], window [windowId] (-1 = unknown), source class
     * [className]. Only changes of content, window state, windows or scroll position count: a click, focus or
     * selection event fires as soon as a view is touched, before anything changes, so it would end the wait on
     * the old screen. See the class comment for the sources that are ignored.
     */
    fun onEvent(
        packageName: CharSequence? = null,
        eventType: Int = TYPE_WINDOW_CONTENT_CHANGED,
        windowId: Int = -1,
        className: CharSequence? = null,
    ) {
        if (eventType and CHANGE_TYPES == 0) return
        if (packageName?.toString() == OWN_PACKAGE) return
        if (windowId != -1 && windowId in ignoredWindows) return
        val now = clock()
        val own = ownChangeAt
        if (eventType == TYPE_WINDOWS_CHANGED && packageName == null &&
            own != Long.MIN_VALUE && now - own in 0..OWN_CHANGE_MS
        ) return
        // A new window or page always counts, and so does scrolling (a fling ends, and the list must have stopped
        // before it is read); content changes may be an animation.
        if (eventType != TYPE_WINDOW_CONTENT_CHANGED) {
            lastEvent = now
            return
        }
        val cls = className?.toString()
        if (TreeCompressor.isSurface(cls) || animating(windowId, cls, eventType, now)) {
            lastAnimated = now
            return
        }
        lastEvent = now
    }

    /** Updates the streak of this source; true once it has fired with no gap over [STREAK_GAP_MS] for long. */
    private fun animating(windowId: Int, cls: String?, type: Int, now: Long): Boolean = synchronized(streaks) {
        val key = Triple(windowId, cls, type)
        val s = streaks[key]
        if (s == null || now - s.last > STREAK_GAP_MS) {
            if (streaks.size >= MAX_STREAKS) streaks.entries.removeAll { now - it.value.last > STREAK_GAP_MS }
            streaks[key] = Streak(now, now)
            return false
        }
        s.last = now
        now - s.start >= ANIMATION_STREAK_MS
    }

    /**
     * Waits per [policy]. Once a UI event came, [sample] is taken about every [SettlePolicy.stableGapMs], and a
     * QUIET, STABLE or ANIMATED end needs the newest sample to agree:
     * - it must not show loading (a spinner or "로딩"/"loading" text); then the wait goes on to the cap;
     * - before [SettlePolicy.firstEventMs] it must differ from [baseline] (the hash of the screen the action was
     *   aimed at): an unchanged screen that soon more likely means the change has not shown yet;
     * - it must be complete (not cut short by its deadline).
     * STABLE = two samples in a row with the same hash.
     *
     * The cap is a deadline: [sample] is given it and must return by then (a read stops early, marked
     * truncated), and no sample starts when the previous one took longer than the time left.
     */
    suspend fun settle(
        policy: SettlePolicy,
        waitForChange: Boolean,
        baseline: Int? = null,
        sample: (suspend (deadline: Long) -> ScreenSample)? = null,
    ): Settled {
        val start = clock()
        val deadline = start + policy.capMs
        var last: ScreenSample? = null
        var previous: ScreenSample? = null
        var lastAt = Long.MIN_VALUE
        var cost = 0L
        while (true) {
            val now = clock()
            val event = lastEvent.takeIf { it != Long.MIN_VALUE && it >= start }
            val decision = policy.decide(start, now, event, waitForChange)
            if (decision == Settled.CAP || decision == Settled.NO_CHANGE) return decision
            if (sample == null || event == null || now - start < policy.sampleFromMs) {
                if (decision != null) return decision
                delay(pause(now, deadline))
                continue
            }
            // A sample is due every stableGapMs, and a QUIET end needs one taken after the latest event.
            val due = lastAt == Long.MIN_VALUE || now - lastAt >= policy.stableGapMs ||
                (decision == Settled.QUIET && lastAt < event)
            if (due && now + cost < deadline) {
                previous = last
                val t0 = clock()
                last = sample(deadline)
                lastAt = clock()
                cost = lastAt - t0
                continue
            }
            val s = last
            if (s != null && !s.busy && !s.truncated) {
                val tooEarly = baseline != null && s.hash == baseline && lastAt - start < policy.firstEventMs
                if (!tooEarly) {
                    if (decision == Settled.QUIET && lastAt >= event) return Settled.QUIET
                    val p = previous
                    if (p != null && !p.truncated && p.hash == s.hash) return Settled.STABLE
                    if (now - start >= policy.animatedCapMs && animatingSince(now - ANIMATED_RECENT_MS)) {
                        return Settled.ANIMATED
                    }
                }
            }
            delay(pause(now, deadline))
        }
    }

    private fun pause(now: Long, deadline: Long): Long = minOf(POLL_MS, deadline - now).coerceAtLeast(1)

    private fun animatingSince(t: Long): Boolean = lastAnimated.let { it != Long.MIN_VALUE && it >= t }

    companion object {
        private const val POLL_MS = 30L
        private const val OWN_PACKAGE = "com.phoneuse"

        /** Our overlay's own window changes arrive this soon after it made them. */
        const val OWN_CHANGE_MS = 150L

        /** A source that fires with gaps of at most [STREAK_GAP_MS] for this long is animating. */
        const val ANIMATION_STREAK_MS = 800L
        const val STREAK_GAP_MS = 250L

        /** "Still animating" = an animating source fired this recently. */
        private const val ANIMATED_RECENT_MS = 300L
        private const val MAX_STREAKS = 64

        // AccessibilityEvent types (the values are part of the platform API).
        const val TYPE_VIEW_CLICKED = 0x1
        const val TYPE_VIEW_FOCUSED = 0x8
        const val TYPE_WINDOW_STATE_CHANGED = 0x20
        const val TYPE_WINDOW_CONTENT_CHANGED = 0x800
        const val TYPE_VIEW_SCROLLED = 0x1000
        const val TYPE_WINDOWS_CHANGED = 0x400000
        private const val CHANGE_TYPES =
            TYPE_WINDOW_STATE_CHANGED or TYPE_WINDOW_CONTENT_CHANGED or TYPE_VIEW_SCROLLED or TYPE_WINDOWS_CHANGED
    }
}
