package com.phoneuse.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.phoneuse.app.AppGraph
import com.phoneuse.app.ThemeMode
import com.phoneuse.assist.AssistVoiceActivity
import com.phoneuse.assist.VoiceBridge
import com.phoneuse.service.OverlayHooks
import com.phoneuse.service.PhoneUseAccessibilityService
import com.phoneuse.ui.theme.PhoneUseTheme
import com.phoneuse.ui.theme.isDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Three accessibility-overlay windows, bottom to top: touch blocker, border glow, steering dock.
 * All window mutations happen on the main thread.
 */
class OverlayManager(private val service: AccessibilityService) : OverlayHooks, CardSink {

    override val state = MutableStateFlow(AgentUiState())

    /** Bumped to ask the dock to drop text-field focus; the dock clears focus on every change. */
    private val focusClears = MutableStateFlow(0)

    var onStop: () -> Unit = {}
    var onSteer: (String) -> Unit = {}
    override var onCardResult: (id: Long, result: String) -> Unit = { _, _ -> }
    override var onClosed: () -> Unit = {}

    @Volatile
    override var isClosed = false
        private set

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val displayManager = service.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val main = Handler(Looper.getMainLooper())
    private val owner = OverlayLifecycleOwner()

    private var blocker: View? = null
    private var border: ComposeView? = null
    private var dock: ComposeView? = null
    private var blockerParams: WindowManager.LayoutParams? = null
    private var borderParams: WindowManager.LayoutParams? = null
    private var dockParams: WindowManager.LayoutParams? = null

    @Volatile
    private var shown = false
    private var blocking = true
    private var inputFocusable = false
    private var passDepth = 0
    private var dockHideDepth = 0
    private var captureDepth = 0

    /** While the voice sheet is up for the dock's 🎤: the dock hides and touches pass to it. */
    private var listening = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (shown) relayout()
        }
    }

    /**
     * Adds the overlay's windows. Throws when the system refuses them (BadTokenException while the service is
     * between connections); then nothing stays added and a later show() tries again.
     */
    fun show() = onMain {
        if (shown || isClosed) return@onMain
        shown = true
        owner.resume()
        try {
            addWindows()
        } catch (e: RuntimeException) {
            hide()
            throw e
        }
    }

    private fun addWindows() {
        val bp = fullScreenParams(touchable = true)
        val blockerView = View(service).apply { setOnTouchListener { _, _ -> true } }
        wm.addView(blockerView, bp)
        blocker = blockerView
        blockerParams = bp

        val gp = fullScreenParams(touchable = false)
        val borderView = composeView {
            val s by state.collectAsState()
            val marks by touches.collectAsState()
            BorderGlow(s.phase)
            TouchMarks(marks)
        }
        wm.addView(borderView, gp)
        border = borderView
        borderParams = gp

        val dp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            DOCK_BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            @Suppress("DEPRECATION") // the replacement (setFitInsetsTypes) does not cover IME resizing on API 30
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            title = "PhoneUse dock"
        }
        val dockView = composeView {
            val s by state.collectAsState()
            val clears by focusClears.collectAsState()
            DockContent(
                state = s,
                focusClears = clears,
                onStop = { onStop() },
                onSteer = { text -> routeDockText(state.value.card, text, onCardResult, onSteer) },
                onCardResult = { id, result -> onCardResult(id, result) },
                onInputFocus = { setInputFocusable(it) },
                onVoice = { startVoice() },
            )
        }
        wm.addView(dockView, dp)
        dock = dockView
        dockParams = dp

        displayManager.registerDisplayListener(displayListener, main)
        applyState()
    }

    fun hide() = onMain {
        if (!shown) return@onMain
        shown = false
        displayManager.unregisterDisplayListener(displayListener)
        for (v in listOfNotNull(dock, border, blocker)) runCatching { wm.removeView(v) }
        dock = null
        border = null
        blocker = null
        blockerParams = null
        borderParams = null
        dockParams = null
        inputFocusable = false
        // The run is over: a voice request still open must not hold up the next run's device calls.
        if (listening) VoiceBridge.deliver(null)
        listening = false
        owner.pause()
    }

    /**
     * The dock's 🎤: runs the in-app voice sheet (through [AssistVoiceActivity], as the dock cannot receive
     * activity results); the recognised text answers the open question card, or steers when there is none.
     */
    private fun startVoice() = onMain {
        if (!shown || listening) return@onMain
        dropInputFocus()
        listening = true
        applyState()
        VoiceBridge.request { text ->
            onMain {
                if (listening) {
                    listening = false
                    applyState()
                }
                val said = text?.trim()
                if (shown && !said.isNullOrEmpty()) routeDockText(state.value.card, said, onCardResult, onSteer)
            }
        }
        try {
            service.startActivity(
                Intent(service, AssistVoiceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(AssistVoiceActivity.EXTRA_TO_BRIDGE, true),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "voice input could not start", e)
            VoiceBridge.deliver(null)
        }
    }

    /** Final teardown: fails any open card request, hides the windows and ends the lifecycle for good. */
    fun destroy() {
        if (!isClosed) {
            isClosed = true
            onClosed()
        }
        onMain {
            hide()
            owner.destroy()
        }
    }


    /** False while the user drives the phone themselves (hand-off). */
    override fun setBlocking(enabled: Boolean) = onMain {
        blocking = enabled
        applyState()
    }

    override suspend fun <T> passThrough(x: Int, y: Int, block: suspend () -> T): T {
        if (!shown) return block()
        var hidesDock = false
        var entered = false
        try {
            // Not cancellable: the increment and the finally below must stay balanced.
            withContext(NonCancellableMain) {
                dropInputFocus()
                hidesDock = isInDock(x, y)
                passDepth++
                if (hidesDock) dockHideDepth++
                entered = true
                applyState()
            }
            awaitBlockerApplied(alsoDock = hidesDock)
            return block()
        } finally {
            if (entered) {
                withContext(NonCancellableMain) {
                    passDepth = (passDepth - 1).coerceAtLeast(0)
                    if (hidesDock) dockHideDepth = (dockHideDepth - 1).coerceAtLeast(0)
                    applyState()
                }
            }
        }
    }

    override suspend fun <T> hiddenForCapture(block: suspend () -> T): T {
        if (!shown) return block()
        var entered = false
        try {
            withContext(NonCancellableMain) {
                captureDepth++
                entered = true
                applyState()
            }
            // The dock and glow must be gone from the screen, not just marked hidden: wait for their next frame.
            awaitFramesCommitted(listOfNotNull(dock, border))
            delay(APPLY_DELAY_MS)
            return block()
        } finally {
            if (entered) {
                withContext(NonCancellableMain) {
                    captureDepth = (captureDepth - 1).coerceAtLeast(0)
                    applyState()
                }
            }
        }
    }

    /**
     * Waits until the blocker's new flags reached the screen: its next frame is committed (the window was relaid
     * out with them), then one more frame for input to follow. A fixed delay was not enough on a busy phone:
     * the gesture landed on the blocker and was lost while it reported success.
     */
    private suspend fun awaitBlockerApplied(alsoDock: Boolean = false) {
        // A tap under the dock (a bottom bar's Delete, a camera shutter) also waits for the dock to stop taking
        // touches; waiting for the blocker alone let the gesture land on the dock and vanish while reporting OK.
        awaitFramesCommitted(listOfNotNull(blocker, dock.takeIf { alsoDock }))
        delay(APPLY_DELAY_MS)
    }

    /** Waits (at most [APPLIED_TIMEOUT_MS]) until each of [views] committed its next frame. */
    private suspend fun awaitFramesCommitted(views: List<View>) {
        if (views.isEmpty()) return
        withTimeoutOrNull(APPLIED_TIMEOUT_MS) {
            for (v in views) {
                suspendCancellableCoroutine<Unit> { cont ->
                    onMain {
                        v.viewTreeObserver.registerFrameCommitCallback { if (cont.isActive) cont.resume(Unit) }
                        v.invalidate()
                    }
                }
            }
        }
    }

    /** Touch marks the border layer draws (see [TouchMarks]); each removes itself after [TOUCH_MARK_MS]. */
    val touches = MutableStateFlow<List<TouchMark>>(emptyList())
    private var touchIds = 0L

    override fun showTouch(x: Int, y: Int, x2: Int?, y2: Int?, long: Boolean) {
        onMain {
            if (!shown) return@onMain
            val mark = TouchMark(++touchIds, x, y, x2, y2, long)
            touches.value = touches.value + mark
            main.postDelayed({ touches.value = touches.value - mark }, TOUCH_MARK_MS + if (long) 300 else 0)
        }
    }

    override suspend fun releaseInputFocus() {
        if (!shown) return
        val changed = withContext(NonCancellableMain) { dropInputFocus() }
        if (changed) delay(APPLY_DELAY_MS)
    }

    /** Main thread. Makes the dock non-focusable again and clears its text-field focus. True if it was focusable. */
    private fun dropInputFocus(): Boolean {
        focusClears.value++
        if (!inputFocusable) return false
        inputFocusable = false
        applyState()
        return true
    }

    private fun isInDock(x: Int, y: Int): Boolean {

        val d = dock ?: return false
        if (d.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        d.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + d.width, loc[1] + d.height).contains(x, y)
    }

    private fun setInputFocusable(focusable: Boolean) {
        if (inputFocusable == focusable) return
        inputFocusable = focusable
        applyState()
    }

    /** Recomputes flags and visibility of all windows from the current counters. Main thread only. */
    private fun applyState() {
        if (!shown) return
        blocker?.let { v ->
            val p = blockerParams ?: return@let
            val touchable = blocking && passDepth == 0 && !listening
            p.flags = withFlag(p.flags, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, !touchable)
            update(v, p)
        }
        // Hidden for a capture by alpha, not visibility: the view keeps drawing (an empty frame), so the frame
        // commit that awaitFramesCommitted waits for comes promptly.
        border?.alpha = if (captureDepth > 0) 0f else 1f
        dock?.let { v ->
            val p = dockParams ?: return@let
            p.flags = withFlag(p.flags, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, !inputFocusable)
            val dockHidden = dockHideDepth > 0 || listening
            p.flags = withFlag(p.flags, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, dockHidden)
            // Hidden by alpha, not visibility, like the border: it keeps drawing, so its frame commit comes promptly.
            v.alpha = if (captureDepth > 0 || dockHidden) 0f else 1f
            update(v, p)
        }
    }

    private fun update(v: View, p: WindowManager.LayoutParams) {
        // The "windows changed" event this causes is ours, not the app's (see IdleWaiter.ownWindowsChanged).
        (service as? PhoneUseAccessibilityService)?.idleWaiter?.ownWindowsChanged()
        try {
            wm.updateViewLayout(v, p)
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    /** Re-applies layout params after a display change (fold/unfold, rotation). */
    private fun relayout() {
        blocker?.let { v -> blockerParams?.let { update(v, it) } }
        border?.let { v -> borderParams?.let { update(v, it) } }
        dock?.let { v -> dockParams?.let { update(v, it) } }
    }

    private fun fullScreenParams(touchable: Boolean): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = if (touchable) "PhoneUse blocker" else "PhoneUse border"
        }

    private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit): ComposeView =
        ComposeView(service).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent {
                // Loaded at process start, long before an overlay can come up; SYSTEM only covers a cold read.
                val mode by AppGraph.themeMode.collectAsState()
                PhoneUseTheme(isDark(mode ?: ThemeMode.SYSTEM)) { content() }
            }
        }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun withFlag(flags: Int, flag: Int, on: Boolean): Int = if (on) flags or flag else flags and flag.inv()

    private companion object {
        /** A run never holds the screen on longer than this, even if the overlay is never hidden. */
        const val TAG = "PhoneUse"
        const val APPLY_DELAY_MS = 32L
        /** The longest a gesture waits for the blocker to stop taking touches (a frame that never comes). */
        const val APPLIED_TIMEOUT_MS = 400L
        /** How long a touch mark stays on screen. */
        const val TOUCH_MARK_MS = 700L
        const val DOCK_BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val NonCancellableMain = Dispatchers.Main + kotlinx.coroutines.NonCancellable
    }
}
