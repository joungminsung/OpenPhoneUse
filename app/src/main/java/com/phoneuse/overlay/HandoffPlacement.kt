package com.phoneuse.overlay

import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode

internal data class HandoffControls(val targets: List<Bounds> = emptyList(), val containers: List<Bounds> = emptyList())

/** Prefer the space above a docked keyboard unless the bar needs more room to clear the user's controls. */
internal fun handoffArea(area: Bounds, keyboard: Bounds?, minBarHeight: Int, moveFrom: Bounds? = null): Bounds {
    if (keyboard == null || !area.intersects(keyboard)) return area
    val docked = keyboard.left <= area.left && keyboard.right >= area.right && keyboard.bottom >= area.bottom
    val height = minBarHeight.coerceAtLeast(1)
    if (!docked || keyboard.top - area.top < height) return area
    val strip = area.copy(bottom = keyboard.top)
    if (moveFrom != null && strip.intersects(moveFrom) &&
        moveFrom.top - strip.top < height && strip.bottom - moveFrom.bottom < height
    ) return area
    return strip
}

/** Use only the cached geometry of app windows that are still on this display. */
internal fun ScreenSnapshot.handoffControls(windowIds: Set<Int>): HandoffControls {
    val targets = mutableListOf<Bounds>()
    val containers = mutableListOf<Bounds>()
    fun visit(node: UiNode) {
        val firstChildTarget = targets.size
        node.children.forEach(::visit)
        if (!node.visibleToUser || !node.enabled || node.covered) return
        val input = node.editable || node.checkable || node.password
        if (!input && !node.clickable && !node.longClickable) return
        val bounds = node.uncovered ?: node.bounds
        if (bounds.isEmpty) return
        // A tappable background may span the entire app, including the buttons inside it.
        if (!input && (firstChildTarget until targets.size).any { bounds.contains(targets[it]) }) {
            containers.add(bounds)
        } else {
            targets.add(bounds)
        }
    }

    for (root in appRoots) if (root.windowId in windowIds) visit(root)
    return HandoffControls(targets, containers)
}

/** The closest clear position to the requested edge for a horizontally centered handoff bar. */
internal fun handoffBarTop(
    area: Bounds,
    barWidth: Int,
    barHeight: Int,
    controls: List<Bounds>,
    preferBottom: Boolean,
    containers: List<Bounds> = emptyList(),
): Int {
    if (area.isEmpty || barHeight >= area.bottom - area.top) return area.top
    val lastTop = area.bottom - barHeight.coerceAtLeast(0)
    val edge = if (preferBottom) lastTop else area.top
    if (barWidth <= 0 || barHeight <= 0) return edge

    val width = minOf(barWidth, area.right - area.left)
    val left = area.left + (area.right - area.left - width) / 2
    fun clearTop(obstacles: List<Bounds>): Int? {
        val ordered = if (preferBottom) obstacles.sortedByDescending { it.bottom } else obstacles.sortedBy { it.top }
        var top = edge
        for (obstacle in ordered) {
            if (obstacle.intersects(Bounds(left, top, left + width, top + barHeight))) {
                // Every position up to the far side of this obstacle overlaps it.
                top = if (preferBottom) obstacle.top - barHeight else obstacle.bottom
                if (top !in area.top..lastTop) return null
            }
        }
        return top
    }

    if (containers.isNotEmpty()) clearTop(controls + containers)?.let { return it }
    return clearTop(controls) ?: edge
}
