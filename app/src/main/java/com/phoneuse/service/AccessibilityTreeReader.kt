package com.phoneuse.service

import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.Occlusion
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode

/** Reads the accessibility windows into the pure [UiNode] model and re-resolves nodes for actions. */
class AccessibilityTreeReader(private val service: PhoneUseAccessibilityService) {

    /**
     * Reads every window but the keyboard and our own. Stops early at [deadline] (uptime ms) or after
     * [maxNodes] nodes; the snapshot then says [ScreenSnapshot.truncated]. Each node notes whether a window
     * above covers it (see [Occlusion]).
     */
    fun snapshot(
        displayWidth: Int,
        displayHeight: Int,
        density: Float = ScreenSnapshot.DEFAULT_DENSITY,
        deadline: Long = Long.MAX_VALUE,
        maxNodes: Int = MAX_NODES,
    ): ScreenSnapshot = snapshot(displayWidth, displayHeight, density, ReadBudget(deadline, maxNodes))

    /** As above, with a [budget] that another thread may extend or stop while the read runs. */
    fun snapshot(displayWidth: Int, displayHeight: Int, density: Float, budget: ReadBudget): ScreenSnapshot {
        val windows = service.windows
        // What each window covers, for the windows below it.
        val covers = windows.map { w -> w.layer to (if (coversBelow(w)) regionRects(w) else emptyList()) }
        val appRoots = ArrayList<UiNode>()
        val otherRoots = ArrayList<UiNode>()
        val systemIds = HashSet<Int>()
        var activePackage: String? = null
        var firstAppPackage: String? = null
        var ownApp = false
        var activeIsSystem = false
        var keyboard = false
        for (w in windows) {
            // The keyboard only matters as a flag; its keys would shift every element index.
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                keyboard = true
                continue
            }
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val root = w.root ?: continue
            val pkg = root.packageName?.toString()
            val isApp = w.type == AccessibilityWindowInfo.TYPE_APPLICATION
            if (pkg == OWN_PACKAGE) {
                if (isApp) ownApp = true
                continue
            }
            if (w.isActive && activePackage == null) {
                activePackage = pkg
                activeIsSystem = w.type != AccessibilityWindowInfo.TYPE_APPLICATION
            }
            if (isApp && firstAppPackage == null) firstAppPackage = pkg
            val above = covers.filter { it.first > w.layer }.flatMap { it.second }
            val node = convert(root, w.id, emptyList(), above, displayWidth, displayHeight, budget)
            // App windows first (keeping their order), then system windows such as the status bar.
            if (isApp) {
                appRoots.add(node)
            } else {
                otherRoots.add(node)
                systemIds.add(w.id)
            }
        }
        val packageName = activePackage ?: firstAppPackage
        return ScreenSnapshot(
            packageName = packageName,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            roots = appRoots + otherRoots,
            keyboardVisible = keyboard,
            density = density,
            systemWindowIds = systemIds,
            ownAppInFront = packageName == null && ownApp,
            activeIsSystem = activeIsSystem,
            truncated = budget.truncated,
        )
    }

    /** Windows that take the touches over the windows below them (not our overlay, which passes them through). */
    private fun coversBelow(w: AccessibilityWindowInfo): Boolean = when (w.type) {
        AccessibilityWindowInfo.TYPE_APPLICATION,
        AccessibilityWindowInfo.TYPE_INPUT_METHOD,
        AccessibilityWindowInfo.TYPE_SYSTEM,
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
        -> true
        else -> false
    }

    private fun regionRects(w: AccessibilityWindowInfo): List<Bounds> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val region = Region()
            w.getRegionInScreen(region)
            val out = ArrayList<Bounds>()
            val it = RegionIterator(region)
            val r = Rect()
            while (it.next(r)) out.add(Bounds(r.left, r.top, r.right, r.bottom))
            return out
        }
        val r = Rect()
        w.getBoundsInScreen(r)
        return listOf(Bounds(r.left, r.top, r.right, r.bottom))
    }

    @Suppress("DEPRECATION") // isChecked is deprecated only on API 36; minSdk is 30
    private fun convert(
        n: AccessibilityNodeInfo,
        windowId: Int,
        path: List<Int>,
        above: List<Bounds>,
        width: Int,
        height: Int,
        budget: ReadBudget,
    ): UiNode {
        val r = Rect()
        n.getBoundsInScreen(r)
        val bounds = Bounds(r.left, r.top, r.right, r.bottom)
        val visibleToUser = n.isVisibleToUser
        val offScreen = !bounds.isEmpty &&
            (bounds.right <= 0 || bounds.bottom <= 0 || bounds.left >= width || bounds.top >= height)
        val children = ArrayList<UiNode>()
        // A hidden view off the screen (a pre-laid-out page, an off-screen pager tab) shows nothing below it.
        if (!(offScreen && !visibleToUser) && !budget.spent()) {
            for (i in 0 until n.childCount) {
                if (budget.truncated) break
                val c = n.getChild(i) ?: continue
                children.add(convert(c, windowId, path + i, above, width, height, budget))
            }
        }
        var uncovered: Bounds? = null
        var covered = false
        if (above.isNotEmpty() && !bounds.isEmpty) {
            val shown = bounds.clippedTo(width, height)
            val part = Occlusion.visiblePart(shown, above)
            if (part == null) covered = true else if (part != shown) uncovered = part
        }
        // Lists offer forward / down only while there is more to see, so these say whether the list goes on.
        val acts = if (n.isScrollable) n.actionList else emptyList()
        val down = AccessibilityAction.ACTION_SCROLL_DOWN in acts
        val up = AccessibilityAction.ACTION_SCROLL_UP in acts
        val right = AccessibilityAction.ACTION_SCROLL_RIGHT in acts
        val left = AccessibilityAction.ACTION_SCROLL_LEFT in acts
        return UiNode(
            windowId = windowId,
            path = path,
            className = n.className?.toString(),
            text = n.text?.toString(),
            contentDescription = n.contentDescription?.toString(),
            hint = n.hintText?.toString(),
            viewId = n.viewIdResourceName,
            bounds = bounds,
            clickable = n.isClickable,
            longClickable = n.isLongClickable,
            editable = n.isEditable,
            scrollable = n.isScrollable,
            checkable = n.isCheckable,
            checked = n.isChecked,
            enabled = n.isEnabled,
            focused = n.isFocused,
            selected = n.isSelected,
            password = n.isPassword,
            moreAfter = down || right || AccessibilityAction.ACTION_SCROLL_FORWARD in acts,
            moreBefore = up || left || AccessibilityAction.ACTION_SCROLL_BACKWARD in acts,
            horizontal = (left || right) && !(up || down),
            visibleToUser = visibleToUser,
            hasRange = n.rangeInfo != null,
            uncovered = uncovered,
            covered = covered,
            children = children,
        )
    }

    /**
     * Finds the live node for [node]: by window id + child path (the same element, even if it moved), else the
     * deepest node of the same class and about the same size at its position (see [NodeMatch]); null if none.
     */
    fun resolve(node: UiNode): AccessibilityNodeInfo? {
        val window = service.windows.firstOrNull { it.id == node.windowId } ?: return null
        val root = window.root ?: return null
        var cur: AccessibilityNodeInfo? = root
        for (i in node.path) {
            cur = cur?.getChild(i)
            if (cur == null) break
        }
        if (cur != null && matchesAtPath(cur, node)) return cur
        return findByPosition(root, node, 0, MAX_SEARCH_DEPTH)?.first
    }

    private fun boundsOf(n: AccessibilityNodeInfo): Bounds {
        val r = Rect()
        n.getBoundsInScreen(r)
        return Bounds(r.left, r.top, r.right, r.bottom)
    }

    private fun matchesAtPath(live: AccessibilityNodeInfo, node: UiNode): Boolean =
        NodeMatch.atPath(
            node,
            live.className?.toString(),
            live.viewIdResourceName,
            live.text?.toString(),
            live.contentDescription?.toString(),
            boundsOf(live),
        )

    /** The deepest match under [n] and its depth; only subtrees containing the element's centre are searched. */
    private fun findByPosition(n: AccessibilityNodeInfo, node: UiNode, depth: Int, maxDepth: Int): Pair<AccessibilityNodeInfo, Int>? {
        val b = boundsOf(n)
        if (!b.isEmpty && !b.containsPoint(node.bounds.centerX, node.bounds.centerY)) return null
        var best: Pair<AccessibilityNodeInfo, Int>? =
            if (NodeMatch.byPosition(node, n.className?.toString(), b)) n to depth else null
        if (depth >= maxDepth) return best
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val found = findByPosition(c, node, depth + 1, maxDepth) ?: continue
            if (best == null || found.second > best.second) best = found
        }
        return best
    }

    companion object {
        const val OWN_PACKAGE = "com.phoneuse"

        /** A read stops after this many nodes (the model is shown at most a few hundred elements). */
        const val MAX_NODES = 3000
        private const val MAX_SEARCH_DEPTH = 60
    }
}
