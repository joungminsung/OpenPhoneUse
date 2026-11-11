package com.phoneuse.service

import android.Manifest
import android.accessibilityservice.AccessibilityService

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.phoneuse.BuildConfig
import com.phoneuse.overlay.AgentPhase
import com.phoneuse.overlay.OverlayManager
import com.phoneuse.overlay.OverlayUserGate
import com.phoneuse.risk.RiskCategory
import com.phoneuse.routine.RoutineDeviceWatcher
import com.phoneuse.screen.TreeCompressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class PhoneUseAccessibilityService : AccessibilityService() {

    val idleWaiter = IdleWaiter { SystemClock.uptimeMillis() }
    private var debugReceiver: BroadcastReceiver? = null
    private val probes by lazy { DebugProbes(this) }

    /** Routine device triggers live here: this service stays alive, so its receivers keep arriving. */
    private var routineWatcher: RoutineDeviceWatcher? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Created on connect; null while the service is not connected. */
    var overlay: OverlayManager? = null
        private set

    /** The controller shared by everything in the process; it routes gestures/screenshots through [overlay]. */
    val controller = AccessibilityDeviceController(this)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        rebuildOverlay()
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugReceiver = null
        if (BuildConfig.DEBUG) registerDebugReceiver()
        routineWatcher?.stop()
        routineWatcher = RoutineDeviceWatcher(this).also { it.start() }
        refreshIgnoredWindows()
        connections.incrementAndGet()
    }

    /**
     * Replaces the overlay with a new one made on this service as it is connected now (its window token comes
     * from the current connection). Main thread.
     */
    fun rebuildOverlay(): OverlayManager {
        overlay?.destroy()
        val manager = OverlayManager(this)
        overlay = manager
        controller.overlay = manager
        return manager
    }

    /**
     * Whether the system's connection to this instance is up. After the service is switched off and on quickly,
     * an instance can be left with its connection dropped (getServiceInfo() null, no window token) while it is
     * still the one we hold: then every overlay window is refused with BadTokenException.
     */
    fun isConnectionLive(): Boolean = try {
        serviceInfo != null
    } catch (_: RuntimeException) {
        false
    }

    /**
     * This instance's connection is gone for good (see [isConnectionLive]): the service counts as off, so the app
     * asks the user to switch it on again, which binds it afresh.
     */
    fun reportConnectionLost() {
        Log.w(TAG, "accessibility connection lost; the service has to be switched on again")
        if (instance === this) _connected.value = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val type = e.eventType
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            refreshIgnoredWindows()
        }
        // IdleWaiter keeps only the event types that mean the app's screen changed.
        idleWaiter.onEvent(e.packageName, type, e.windowId, e.className)
    }

    /**
     * Marks the windows whose events never mean the app's screen changed: the keyboard, our overlay, and small
     * system windows (status bar with its clock and speed meter, navigation bar). A large system window (the
     * notification shade pulled down) still counts.
     */
    private fun refreshIgnoredWindows() {
        val ws = try {
            windows
        } catch (_: RuntimeException) {
            return
        }
        val m = resources.displayMetrics
        val screenArea = m.widthPixels.toLong() * m.heightPixels
        val r = Rect()
        val ignored = HashSet<Int>()
        for (w in ws) {
            val noise = when (w.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY,
                AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
                -> true
                AccessibilityWindowInfo.TYPE_SYSTEM -> {
                    w.getBoundsInScreen(r)
                    r.width().toLong() * r.height() * SMALL_SYSTEM_WINDOW < screenArea
                }
                else -> false
            }
            if (noise) ignored.add(w.id)
        }
        idleWaiter.ignoredWindows = ignored
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun clear() {
        overlay?.destroy()
        overlay = null
        controller.overlay = null
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugReceiver = null
        routineWatcher?.stop()
        routineWatcher = null
        // A service switched off and on quickly: the old instance may only now unbind, after the new one connected.
        if (instance === this) {
            instance = null
            _connected.value = false
        }
    }

    private fun registerDebugReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                if (action == ACTION_ANSWER) {
                    // Read back by `am broadcast` as the result data.
                    resultData = answerConfirm(decline = intent.getStringExtra("mode") == "no")
                    Log.d(TAG, "DEBUG_ANSWER: $resultData")
                    return
                }
                val index = intent.getIntExtra("index", -1)
                val mode = intent.getStringExtra("mode")
                // text64: UTF-8 in base64, so a script's quotes and Korean survive the device shell.
                val text = intent.getStringExtra("text64")?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
                    ?: intent.getStringExtra("text")
                val pending = goAsync()
                val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                val finish = { if (finished.compareAndSet(false, true)) pending.finish() }
                scope.launch {
                    try {
                        handleDebug(action, index, mode, text, finish)
                    } catch (e: Exception) {
                        Log.e(TAG, "debug action $action failed", e)
                    } finally {
                        finish()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_DUMP)
            addAction(ACTION_TAP)
            addAction(ACTION_OVERLAY)
            addAction(ACTION_SHOT)
            addAction(ACTION_PROBE)
            addAction(ACTION_RUN)
            addAction(ACTION_STOP)
            addAction(ACTION_ANSWER)
            addAction(ACTION_MODEL)
        }
        // Only senders holding DUMP (adb shell) may drive the debug actions.
        registerReceiver(receiver, filter, Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        debugReceiver = receiver
    }

    // adb-only debug actions (DUMP permission). Lint's newer interprocedural thread check (AGP 9.5) flags the probe
    // branch; every branch runs on the service's main scope, unchanged from before the check existed.
    @android.annotation.SuppressLint("ThreadConstraint")
    private suspend fun handleDebug(action: String, index: Int, mode: String?, text: String?, finish: () -> Unit) {
        when (action) {
            ACTION_OVERLAY -> {
                debugOverlay(mode, text, finish)
                return
            }
            ACTION_STOP -> {
                com.phoneuse.app.AppGraph.runner.stop()
                Log.d(TAG, "DEBUG_STOP")
                return
            }
            ACTION_MODEL -> {
                // Picks the model for the next runs, so a benchmark can compare models.
                if (!text.isNullOrBlank()) com.phoneuse.app.AppGraph.settings.setModel(text)
                Log.d(TAG, "DEBUG_MODEL: $text")
                return
            }
            ACTION_RUN -> {
                // Starts a task as if typed in a new conversation, for scripted benchmarks.
                val ok = text != null && com.phoneuse.app.AppGraph.runner.send(null, text, com.phoneuse.app.Source.APP)
                Log.d(TAG, "DEBUG_RUN: started=$ok")
                return
            }
            ACTION_PROBE -> {
                finish() // probes wait for windows to settle
                probes.run(mode ?: "windows", text ?: "com.android.settings")
                return
            }
            ACTION_SHOT -> {
                val bytes = controller.screenshot()
                if (bytes == null) {
                    Log.w(TAG, "DEBUG_SHOT: screenshot failed")
                } else {
                    val file = File(getExternalFilesDir(null), "pu_shot.jpg")
                    file.writeBytes(bytes)
                    Log.d(TAG, "DEBUG_SHOT: wrote ${file.absolutePath} (${bytes.size} bytes)")
                }
                return
            }
        }
        val screen = TreeCompressor.compress(controller.snapshot())
        when (action) {
            ACTION_DUMP -> screen.text.lines().chunkedByLength(LOG_CHUNK).forEach { Log.d(TAG, it) }
            ACTION_TAP -> {
                // --es text "x,y" taps a point instead of an element.
                val xy = text?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                if (xy != null && xy.size == 2) {
                    val (x, y) = xy
                    Log.d(TAG, "DEBUG_TAP: ($x,$y) tapped=${controller.tap(x, y)}")
                    return
                }
                val node = screen.elements[index]
                if (node == null) {
                    Log.w(TAG, "DEBUG_TAP: no element $index")
                } else {
                    val ok = controller.tap(node.bounds.centerX, node.bounds.centerY)
                    Log.d(TAG, "DEBUG_TAP: [$index] tapped=$ok")
                }
            }
        }
    }

    private suspend fun debugOverlay(mode: String?, text: String?, finish: () -> Unit) {
        val o = overlay ?: return
        when (mode) {
            "show" -> {
                o.state.update { it.copy(phase = AgentPhase.ACTING, lines = text?.split('|') ?: listOf("디버그 오버레이")) }
                o.show()
            }
            "think" -> {
                o.show()
                o.state.update { it.copy(phase = AgentPhase.THINKING, lines = listOf("생각 중…")) }
            }
            "hide" -> o.hide()
            // The overlay as a run shows it: touches blocked, so DEBUG_TAP tests the pass-through.
            "block" -> {
                o.setBlocking(true)
                o.show()
            }
            "confirm" -> {
                // A run's card is answered through the callbacks its gate set; a debug gate must not take them.
                if (o.state.value.card != null || com.phoneuse.app.AppGraph.runner.running.value) {
                    Log.w(TAG, "DEBUG_OVERLAY: confirm refused while a run uses the overlay")
                    return
                }
                o.show()
                finish() // do not hold the broadcast open while waiting for the user
                val prevResult = o.onCardResult
                val prevClosed = o.onClosed
                try {
                    val ok = OverlayUserGate(o).confirm(RiskCategory.MONEY, text ?: "디버그 확인")
                    Log.d(TAG, "DEBUG_OVERLAY: confirm result=$ok")
                } finally {
                    o.onCardResult = prevResult
                    o.onClosed = prevClosed
                }
            }
            else -> Log.w(TAG, "DEBUG_OVERLAY: unknown mode $mode")
        }
    }

    /**
     * Approves the confirm card a run is waiting on, in the overlay or the chat screen, for scripted benchmarks:
     * the overlay card lives in an accessibility window that uiautomator cannot see. Questions and hand-offs are
     * left alone. [decline] answers no instead (safety tasks check what the agent asked about). Returns where it
     * answered, then the card's category and summary on one line: "overlay|MONEY|…", "chat|…" or "none".
     */
    private fun answerConfirm(decline: Boolean): String {
        val o = overlay
        val card = o?.state?.value?.card
        if (o != null && card is com.phoneuse.overlay.DockCard.Confirm) {
            o.onCardResult(card.id, if (decline) OverlayUserGate.CONFIRM_NO else OverlayUserGate.CONFIRM_YES)
            return "overlay|${card.category}|${card.summary.replace('\n', ' ').take(200)}"
        }
        val runner = com.phoneuse.app.AppGraph.runner
        val chat = runner.chatGate.card.value
        if (chat is com.phoneuse.app.ChatCard.Confirm) {
            runner.chatGate.answer(chat.id, if (decline) com.phoneuse.app.ChatUserGate.CONFIRM_NO else com.phoneuse.app.ChatUserGate.CONFIRM_YES)
            return "chat|${chat.category}|${chat.summary.replace('\n', ' ').take(200)}"
        }
        return "none"
    }

    /** Joins lines into chunks of at most [max] chars, splitting only between lines (a long line is hard-split). */
    private fun List<String>.chunkedByLength(max: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (line in this) {
            for (piece in line.chunked(max)) {
                if (cur.isNotEmpty() && cur.length + 1 + piece.length > max) {
                    out.add(cur.toString())
                    cur.clear()
                }
                if (cur.isNotEmpty()) cur.append('\n')
                cur.append(piece)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    companion object {
        const val TAG = "PhoneUse"
        const val ACTION_DUMP = "com.phoneuse.DEBUG_DUMP"
        const val ACTION_TAP = "com.phoneuse.DEBUG_TAP"
        const val ACTION_OVERLAY = "com.phoneuse.DEBUG_OVERLAY"
        const val ACTION_SHOT = "com.phoneuse.DEBUG_SHOT"
        const val ACTION_PROBE = "com.phoneuse.DEBUG_PROBE"
        const val ACTION_RUN = "com.phoneuse.DEBUG_RUN"
        const val ACTION_STOP = "com.phoneuse.DEBUG_STOP"
        const val ACTION_ANSWER = "com.phoneuse.DEBUG_ANSWER"
        const val ACTION_MODEL = "com.phoneuse.DEBUG_MODEL"
        private const val LOG_CHUNK = 3500

        /** A system window under 1/this of the screen is a bar, not a panel the user looks at. */
        private const val SMALL_SYSTEM_WINDOW = 4

        /** Times a connection came up (onServiceConnected on any instance); a change means a fresh token. */
        val connections = java.util.concurrent.atomic.AtomicInteger(0)

        @Volatile
        var instance: PhoneUseAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
