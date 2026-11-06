package com.phoneuse.app

import android.util.Log
import com.phoneuse.agent.AgentEvent
import com.phoneuse.agent.AppInfo
import com.phoneuse.agent.DeviceController
import com.phoneuse.agent.GlobalAction
import com.phoneuse.agent.QuickSettle
import com.phoneuse.agent.ScreenInterruptedException
import com.phoneuse.agent.ScreenMark
import com.phoneuse.agent.ServiceUnavailableException
import com.phoneuse.agent.SettleKind
import com.phoneuse.agent.UserGate
import com.phoneuse.assist.VoiceBridge
import com.phoneuse.overlay.AgentPhase
import com.phoneuse.overlay.AgentUiState
import com.phoneuse.overlay.OverlayManager
import com.phoneuse.overlay.OverlayUserGate
import com.phoneuse.risk.RiskCategory
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode
import com.phoneuse.service.PhoneUseAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The overlay side of one run. Nothing is shown and no touches are blocked until [engage]; once engaged,
 * [release] always gives touches back and hides the overlay, whatever happened.
 */
internal class OverlaySession(private val onStop: () -> Unit, private val onSteer: (String) -> Unit) {
    @Volatile
    private var overlay: OverlayManager? = null

    @Volatile
    private var overlayGate: OverlayUserGate? = null

    val active: Boolean get() = overlay != null

    /** Routes cards here while engaged; [RoutingUserGate] only calls it then. */
    val gate: UserGate = object : UserGate {
        private fun target() = overlayGate ?: throw ServiceUnavailableException()
        override suspend fun confirm(category: RiskCategory, summary: String) = target().confirm(category, summary)
        override suspend fun ask(question: String, options: List<String>) = target().ask(question, options)
        override suspend fun handToUser(reason: String) = target().handToUser(reason)
    }

    /**
     * Shows the overlay and blocks touches. Returns false when the accessibility service is not available.
     * Runs on the main thread, so both are applied (not just posted) by the time it returns. Never throws.
     *
     * A window the system refuses (BadTokenException: "token null is not valid") means the service instance we
     * hold has lost its connection. Seen after the service was switched off and on within a second: the system
     * drops the old connection on the same instance after the new one came up, which clears its connection id
     * and window token without any callback. Each attempt waits for a live connected instance (a new one may
     * still connect) and builds a fresh overlay on it. If the connection stays dead, the service is reported
     * as off, so the app asks the user to switch it on again; nothing in the app can rebind it (switching our
     * own component off and on makes Android drop the service from the enabled list).
     */
    suspend fun engage(firstLine: String): Boolean = withContext(Dispatchers.Main.immediate) {
        if (overlay != null) return@withContext true
        for (attempt in 0 until ENGAGE_TRIES) {
            val service = awaitLiveService(if (attempt == 0) 0 else ENGAGE_WAIT_MS)
            if (service == null) {
                Log.w("PhoneUse", "accessibility connection lost (attempt ${attempt + 1})")
                continue
            }
            // A retry builds the overlay anew on the instance connected now.
            val manager = service.overlay?.takeUnless { it.isClosed || attempt > 0 } ?: service.rebuildOverlay()
            if (tryShow(manager, firstLine, attempt)) return@withContext true
            delay(ENGAGE_RETRY_MS)
        }
        PhoneUseAccessibilityService.instance?.let { if (!it.isConnectionLive()) it.reportConnectionLost() }
        false
    }

    private fun tryShow(manager: OverlayManager, firstLine: String, attempt: Int): Boolean {
        overlay = manager // recorded first so release() undoes even a half-finished engage
        overlayGate = OverlayUserGate(manager)
        manager.onStop = onStop
        manager.onSteer = onSteer
        manager.state.value = AgentUiState(AgentPhase.THINKING, listOf(firstLine), null)
        manager.setBlocking(true)
        return try {
            manager.show()
            true
        } catch (e: RuntimeException) {
            Log.w("PhoneUse", "overlay refused (attempt ${attempt + 1})", e)
            releaseNow()
            false
        }
    }

    /** The connected service instance whose connection is live, waiting up to [waitMs] for one. */
    private suspend fun awaitLiveService(waitMs: Long): PhoneUseAccessibilityService? {
        val until = android.os.SystemClock.uptimeMillis() + waitMs
        while (true) {
            val s = PhoneUseAccessibilityService.instance
            if (s != null && s.isConnectionLive()) return s
            if (android.os.SystemClock.uptimeMillis() >= until) return null
            delay(ENGAGE_POLL_MS)
        }
    }

    fun update(event: AgentEvent) {
        overlay?.state?.update { reduce(it, event) }
    }

    fun addLine(line: String) {
        overlay?.state?.update { withLine(it, line) }
    }

    /**
     * Releases touches right away, leaves the last line up for [lingerMs], then hides the overlay.
     * Returns the last line shown, or null when the overlay was never engaged. Never throws.
     */
    suspend fun release(lingerMs: Long): String? {
        val manager = overlay ?: return null
        val last = manager.state.value.lines.lastOrNull()
        releaseTouches()
        if (lingerMs > 0) delay(lingerMs)
        releaseNow()
        return last
    }

    /** Gives touches back without hiding the overlay; never throws. */
    fun releaseTouches() {
        val manager = overlay ?: return
        guarded("setBlocking") { manager.setBlocking(false) }
    }

    /** Releases without lingering; safe from any thread and safe to call twice. */
    fun releaseNow() {
        val manager = overlay ?: return
        guarded("setBlocking") { manager.setBlocking(false) }
        guarded("hide") { manager.hide() }
        manager.onStop = {}
        manager.onSteer = {}
        overlay = null
        overlayGate = null
    }

    private companion object {
        const val ENGAGE_TRIES = 3
        const val ENGAGE_RETRY_MS = 300L

        /** How long a retry waits for a live connection (a toggled service reconnects within about 2 s). */
        const val ENGAGE_WAIT_MS = 2000L
        const val ENGAGE_POLL_MS = 100L
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e("PhoneUse", "cleanup step failed: $what", e)
        }
    }

}

/**
 * Forwards to the live accessibility service's controller, which may connect after the run started.
 * Screen reads and actions wait while the dock's voice input has the speech screen in front.
 */
internal object LiveDeviceController : DeviceController {
    private fun device(): DeviceController =
        PhoneUseAccessibilityService.instance?.controller ?: throw ServiceUnavailableException()

    /** For reads: waits out voice input, then reads the screen as it is now. */
    private suspend fun ready(): DeviceController {
        VoiceBridge.awaitIdle()
        return device()
    }

    /** For actions: one that had to wait for voice input is aimed at a screen that may be gone, so it fails. */
    private suspend fun readyToAct(): DeviceController {
        if (VoiceBridge.awaitIdle()) throw ScreenInterruptedException()
        return device()
    }

    override fun interruptions(): Int = VoiceBridge.sessions
    override suspend fun snapshot(): ScreenSnapshot = ready().snapshot()
    override suspend fun screenshot(): ByteArray? = ready().screenshot()
    override suspend fun markedScreenshot(marks: List<ScreenMark>): ByteArray? = ready().markedScreenshot(marks)
    override suspend fun tap(x: Int, y: Int): Boolean = readyToAct().tap(x, y)
    override suspend fun longPress(x: Int, y: Int): Boolean = readyToAct().longPress(x, y)
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        readyToAct().swipe(x1, y1, x2, y2, durationMs)
    override suspend fun pinch(x: Int, y: Int, zoomIn: Boolean, durationMs: Long): Boolean =
        readyToAct().pinch(x, y, zoomIn, durationMs)
    override suspend fun setText(node: UiNode, text: String): Boolean = readyToAct().setText(node, text)
    override suspend fun imeEnter(node: UiNode): Boolean = readyToAct().imeEnter(node)
    override suspend fun scroll(node: UiNode?, direction: String): Boolean = readyToAct().scroll(node, direction)
    override suspend fun globalAction(action: GlobalAction): Boolean = ready().globalAction(action)
    override suspend fun launchApp(packageName: String, fresh: Boolean): Boolean = ready().launchApp(packageName, fresh)
    override suspend fun openUri(uri: String): Boolean = ready().openUri(uri)
    override suspend fun installedApps(): List<AppInfo> = device().installedApps()
    override suspend fun waitForIdle(timeoutMs: Long) = ready().waitForIdle(timeoutMs)
    override fun playingOrInCall(): Boolean = PhoneUseAccessibilityService.instance?.controller?.playingOrInCall() == true
    override suspend fun settleAfterAction(baseline: String?, kind: SettleKind): String =
        ready().settleAfterAction(baseline, kind)
    override suspend fun settleQuick(baseline: String?, kind: SettleKind): QuickSettle =
        ready().settleQuick(baseline, kind)

    /** Read without waiting for voice input: the speculation watch must not hold up while the user speaks. */
    override suspend fun screenHash(): Int? = device().screenHash()
}
