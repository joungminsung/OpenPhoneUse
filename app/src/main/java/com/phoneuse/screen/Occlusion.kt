package com.phoneuse.screen

/**
 * Which part of an element is not under a window drawn above it (the keyboard, a dialog, a system bar). A tap at
 * an element's centre goes to whatever window is on top there, so an element whose centre is covered must be
 * tapped in its uncovered part, or not at all.
 */
object Occlusion {
    /**
     * The part of [b] outside every rectangle of [above], or null when nothing usable remains. A rectangle that
     * spans the element's whole width (or height) cuts it from that side; any other overlap leaves the element as
     * it is unless it covers the element's centre, which then counts as covered.
     */
    fun visiblePart(b: Bounds, above: List<Bounds>): Bounds? {
        var cur = b
        for (r in above) {
            if (!r.intersects(cur)) continue
            if (r.contains(cur)) return null
            cur = when {
                r.left <= cur.left && r.right >= cur.right -> when {
                    r.top <= cur.top -> Bounds(cur.left, r.bottom, cur.right, cur.bottom)
                    r.bottom >= cur.bottom -> Bounds(cur.left, cur.top, cur.right, r.top)
                    // A band across the middle: keep the larger side.
                    r.top - cur.top >= cur.bottom - r.bottom -> Bounds(cur.left, cur.top, cur.right, r.top)
                    else -> Bounds(cur.left, r.bottom, cur.right, cur.bottom)
                }
                r.top <= cur.top && r.bottom >= cur.bottom -> when {
                    r.left <= cur.left -> Bounds(r.right, cur.top, cur.right, cur.bottom)
                    r.right >= cur.right -> Bounds(cur.left, cur.top, r.left, cur.bottom)
                    r.left - cur.left >= cur.right - r.right -> Bounds(cur.left, cur.top, r.left, cur.bottom)
                    else -> Bounds(r.right, cur.top, cur.right, cur.bottom)
                }
                r.containsPoint(cur.centerX, cur.centerY) -> return null
                else -> cur
            }
            if (cur.isEmpty) return null
        }
        return cur
    }
}
