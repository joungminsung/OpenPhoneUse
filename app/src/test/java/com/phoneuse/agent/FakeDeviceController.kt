package com.phoneuse.agent

import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode

/** Records every call in [calls]; snapshots come from [snapshots] in order, the last one repeating. */
class FakeDeviceController(snapshots: List<ScreenSnapshot> = emptyList()) : DeviceController {
    val snapshots: MutableList<ScreenSnapshot> = snapshots.toMutableList()
    val calls = mutableListOf<String>()
    var screenshotResult: ByteArray? = byteArrayOf(1, 2, 3)
    var apps: List<AppInfo> = emptyList()
    var unavailable = false
    var failAll = false
    var throwOnTap: Exception? = null
    private var snapshotCount = 0

    init {
        require(snapshots.isNotEmpty()) { "FakeDeviceController needs at least one snapshot" }
    }

    private fun record(call: String) {
        calls += call
        if (unavailable) throw ServiceUnavailableException()
    }

    override suspend fun snapshot(): ScreenSnapshot {
        record("snapshot()")
        val i = minOf(snapshotCount, snapshots.size - 1)
        snapshotCount++
        return snapshotHook?.invoke(snapshots[i]) ?: snapshots[i]
    }

    /** The marks of each marked screenshot taken, in order. */
    val marks = ArrayList<List<ScreenMark>>()

    override suspend fun markedScreenshot(marks: List<ScreenMark>): ByteArray? {
        this.marks += marks
        return screenshot()
    }

    override suspend fun screenshot(): ByteArray? {
        record("screenshot()")
        return screenshotResult
    }

    override suspend fun tap(x: Int, y: Int): Boolean {
        record("tap($x,$y)")
        throwOnTap?.let { throw it }
        return !failAll
    }

    override suspend fun longPress(x: Int, y: Int): Boolean {
        record("longPress($x,$y)")
        return !failAll
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        record("swipe($x1,$y1,$x2,$y2,$durationMs)")
        return !failAll
    }

    override suspend fun pinch(x: Int, y: Int, zoomIn: Boolean, durationMs: Long): Boolean {
        record("pinch($x,$y,${if (zoomIn) "in" else "out"})")
        return true
    }

    /** The nodes setText was called on, in order. */
    val setTextNodes = mutableListOf<UiNode>()

    override suspend fun setText(node: UiNode, text: String): Boolean {
        setTextNodes += node
        record("setText($text)")
        return !failAll
    }

    override suspend fun imeEnter(node: UiNode): Boolean {
        record("imeEnter()")
        return !failAll
    }

    override suspend fun scroll(node: UiNode?, direction: String): Boolean {
        record("scroll(${if (node == null) "screen" else "node"},$direction)")
        return !failAll
    }

    override suspend fun globalAction(action: GlobalAction): Boolean {
        record("globalAction($action)")
        return !failAll
    }

    override suspend fun launchApp(packageName: String, fresh: Boolean): Boolean {
        record(if (fresh) "launchApp($packageName, fresh)" else "launchApp($packageName)")
        return !failAll
    }

    override suspend fun openUri(uri: String): Boolean {
        record("openUri($uri)")
        return !failAll
    }

    override suspend fun installedApps(): List<AppInfo> {
        record("installedApps()")
        return apps
    }

    /** A quick settle's remaining window (see [Speculation]); 0 keeps the default full settle. */
    var quickWindowMs = 0L

    /** Replaces what [snapshot] returns (given the scripted one); null returns it as scripted. */
    var snapshotHook: ((ScreenSnapshot) -> ScreenSnapshot)? = null

    /** What [screenHash] returns. */
    var liveHash: () -> Int? = { null }

    override suspend fun settleQuick(baseline: String?, kind: SettleKind): QuickSettle {
        if (quickWindowMs == 0L) return super.settleQuick(baseline, kind)
        record("settleQuick()")
        return QuickSettle("quiet", quickWindowMs)
    }

    override suspend fun screenHash(): Int? = liveHash()

    override suspend fun waitForIdle(timeoutMs: Long) {
        record("waitForIdle()")
    }
}
