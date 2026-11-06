package com.phoneuse.agent

import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode

enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS }

/**
 * What the action before a settle was: [ACTION] a touch, key or text (short settle); [APP] an app was opened
 * (a cold start can sit on a quiet splash screen, so it waits longer); [LINK] a link or intent was opened (waits
 * like [APP], but gives up sooner when nothing happens).
 */
enum class SettleKind { ACTION, APP, LINK }

/** Element [index] of the screen list, drawn at [bounds] (display pixels) on a marked screenshot. */
data class ScreenMark(val index: Int, val bounds: Bounds)

data class AppInfo(val packageName: String, val label: String)

class ServiceUnavailableException : Exception("accessibility service unavailable")

/** An action waited while the user used voice input, so the screen it was aimed at may be gone. */
class ScreenInterruptedException : Exception("screen changed during voice input")

interface DeviceController {
    suspend fun snapshot(): ScreenSnapshot
    suspend fun screenshot(): ByteArray? // JPEG. null on a secure screen

    /**
     * A screenshot with each of [marks] outlined and labelled with its element number (Set-of-Mark), so the model
     * can match what it sees to the numbered screen list. JPEG; null on a secure screen.
     */
    suspend fun markedScreenshot(marks: List<ScreenMark>): ByteArray? = screenshot()
    suspend fun tap(x: Int, y: Int): Boolean
    suspend fun longPress(x: Int, y: Int): Boolean

    /**
     * Clicks [node] (or its nearest clickable parent) as an accessibility action, with no touch: it works under the
     * overlay that blocks the user's touches, so nothing has to be lifted. False when it cannot; tap instead.
     */
    suspend fun click(node: UiNode, long: Boolean = false): Boolean = false
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean

    /** Two fingers around (x, y): spreading apart when [zoomIn] (a map or photo grows), closing in otherwise. */
    suspend fun pinch(x: Int, y: Int, zoomIn: Boolean, durationMs: Long = 400): Boolean = false
    suspend fun setText(node: UiNode, text: String): Boolean
    suspend fun imeEnter(node: UiNode): Boolean

    /** direction: "up"|"down"|"left"|"right". A null node swipes across the screen center. */
    suspend fun scroll(node: UiNode?, direction: String): Boolean
    suspend fun globalAction(action: GlobalAction): Boolean
    /** [fresh] starts the app over at its start screen instead of resuming wherever it was last left. */
    suspend fun launchApp(packageName: String, fresh: Boolean = false): Boolean
    suspend fun openUri(uri: String): Boolean
    suspend fun installedApps(): List<AppInfo>
    suspend fun waitForIdle(timeoutMs: Long = 3000)

    /** Whether the phone is playing sound or in a call right now: a run's result can be that the user stays with it. */
    fun playingOrInCall(): Boolean = false

    /**
     * After an action: waits until the UI settles (first UI event, then a short quiet or a stable tree; capped).
     * [baseline] is the compressed text of the screen the action was aimed at, or null; [kind] what the action
     * was. Returns how the wait ended, for the step timing log.
     */
    suspend fun settleAfterAction(baseline: String?, kind: SettleKind = SettleKind.ACTION): String {
        waitForIdle()
        return "idle"
    }

    /**
     * A shorter settle for a speculative step: it returns at the first quiet point and reports how long the full
     * settle could still have lasted, so the loop can keep watching the screen while the model thinks (see
     * [ScreenWatch]). The default waits the full settle and leaves nothing to watch.
     */
    suspend fun settleQuick(baseline: String?, kind: SettleKind = SettleKind.ACTION): QuickSettle =
        QuickSettle(settleAfterAction(baseline, kind), 0)

    /** The hash of the screen text the model would see now, read without waiting; null when unknown. */
    suspend fun screenHash(): Int? = null

    /** Times the user took the screen over (dock voice input); a change means element indices are stale. */
    fun interruptions(): Int = 0
}
