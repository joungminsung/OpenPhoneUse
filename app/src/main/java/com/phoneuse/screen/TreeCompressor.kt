package com.phoneuse.screen

import java.util.Collections
import java.util.IdentityHashMap

class CompressedScreen(
    val text: String,
    val elements: Map<Int, UiNode>,
    /** index -> label shown in that element's line (after truncation). */
    val labels: Map<Int, String>,
    val packageName: String?,
    /** index -> normalized label without truncation; the risk gate classifies these. */
    val fullLabels: Map<Int, String>,
    val displayWidth: Int,
    val displayHeight: Int,
    /** px per dp of the display (ScreenSnapshot.density). */
    val density: Float = ScreenSnapshot.DEFAULT_DENSITY,
    /** A shown vertical list can still scroll down: there is content the model has not seen. */
    val listContinues: Boolean = false,
) {
    /** Where to touch element [index]: the centre of its bounds clipped to the display. */
    fun tapPoint(index: Int): Pair<Int, Int> {
        val n = elements.getValue(index)
        val b = (n.uncovered ?: n.bounds).clippedTo(displayWidth, displayHeight)
        return b.centerX to b.centerY
    }
}

object TreeCompressor {
    private const val MAX_LABEL = 80
    /** In the header when a list goes on below what is shown. */
    const val LIST_CONTINUES = "list continues below (more-below): scroll to see the rest"

    private class Entry(val node: UiNode, val fullLabel: String) {
        val label = truncate(fullLabel)
    }

    fun compress(s: ScreenSnapshot, maxElements: Int = 300): CompressedScreen {
        val entries = ArrayList<Entry>()
        val absorbed: MutableSet<UiNode> = Collections.newSetFromMap(IdentityHashMap())
        for (root in s.roots) collect(root, s, entries, absorbed)

        val shown = entries.take(maxElements)
        val elements = LinkedHashMap<Int, UiNode>()
        val labels = LinkedHashMap<Int, String>()
        val fullLabels = LinkedHashMap<Int, String>()
        val sb = StringBuilder()
        sb.append("app: ").append(s.packageName).append(" | screen ")
            .append(s.displayWidth).append('x').append(s.displayHeight)
        if (s.keyboardVisible) sb.append(" | keyboard open")
        val continues = shown.any { it.node.scrollable && it.node.moreAfter && !it.node.horizontal }
        if (continues) sb.append(" | $LIST_CONTINUES")
        shown.forEachIndexed { idx, e ->
            val i = idx + 1
            elements[i] = e.node
            labels[i] = e.label
            fullLabels[i] = e.fullLabel
            sb.append('\n').append(line(i, e, s))
        }
        val omitted = entries.size - shown.size
        if (omitted > 0) sb.append("\n... ($omitted more elements omitted)")
        return CompressedScreen(
            sb.toString(), elements, labels, s.packageName, fullLabels, s.displayWidth, s.displayHeight, s.density,
            continues,
        )
    }

    private fun line(i: Int, e: Entry, s: ScreenSnapshot): String {
        val n = e.node
        val sb = StringBuilder()
        sb.append('[').append(i).append("] ")
            .append(n.className?.substringAfterLast('.')?.ifEmpty { null } ?: "View")
            .append(" \"").append(e.label).append('"')
        if (n.clickable) sb.append(" clickable")
        if (n.editable) sb.append(" editable")
        if (n.scrollable) {
            sb.append(" scrollable")
            if (n.moreBefore) sb.append(if (n.horizontal) " more-left" else " more-above")
            if (n.moreAfter) sb.append(if (n.horizontal) " more-right" else " more-below")
        }
        if (n.checkable) sb.append(if (n.checked) " checked" else " unchecked")
        if (!n.enabled) sb.append(" disabled")
        if (n.focused) sb.append(" focused")
        if (n.selected) sb.append(" selected")
        if (n.password) sb.append(" password")
        val c = (n.uncovered ?: n.bounds).clippedTo(s.displayWidth, s.displayHeight)
        sb.append(" (").append(c.centerX).append(',').append(c.centerY).append(')')
        return sb.toString()
    }



    private fun ownText(n: UiNode): String? =
        n.text?.takeIf { it.isNotBlank() } ?: n.contentDescription?.takeIf { it.isNotBlank() }

    /** On screen = not under a window above, bounds non-empty and intersecting (0,0,displayWidth,displayHeight). */
    private fun visible(n: UiNode, s: ScreenSnapshot): Boolean {
        if (n.covered) return false
        val b = n.bounds
        if (b.isEmpty) return false
        return b.left < s.displayWidth && b.right > 0 && b.top < s.displayHeight && b.bottom > 0
    }

    private fun collect(
        n: UiNode,
        s: ScreenSnapshot,
        out: MutableList<Entry>,
        absorbed: MutableSet<UiNode>,
    ) {
        if (visible(n, s) && n !in absorbed) {
            val own = ownText(n)
            if (n.isActionable) {
                var label = own ?: n.hint?.takeIf { it.isNotBlank() }
                if (label == null) {
                    val parts = ArrayList<String>()
                    for (c in n.children) gather(c, s, parts, absorbed)
                    label = parts.joinToString(" ")
                }
                out.add(Entry(n, normalize(label)))
            } else if (own != null) {
                out.add(Entry(n, normalize(own)))
            }
        }
        for (c in n.children) collect(c, s, out, absorbed)
    }

    /** Collects text of non-actionable descendants (not crossing actionable ones) and marks them absorbed. */
    private fun gather(
        n: UiNode,
        s: ScreenSnapshot,
        parts: MutableList<String>,
        absorbed: MutableSet<UiNode>,
    ) {
        if (n.isActionable) return
        if (visible(n, s)) {
            ownText(n)?.let {
                parts.add(it)
                absorbed.add(n)
            }
        }
        for (c in n.children) gather(c, s, parts, absorbed)
    }

    /**
     * A hash of what the screen shows, for deciding whether it changed: app windows only (not the status bar's
     * clock or speed meter), no coordinates (a marker or sheet that moves a few px is not a new screen), digits in
     * short labels as 0 ("0:12", "1.2 KB/s", "350 m" tick on their own), and nothing inside a map, video or other
     * surface view. Equal hashes = the same screen to act on, not byte-equal model text.
     */
    fun stabilityHash(s: ScreenSnapshot): Int {
        var h = 17
        h = 31 * h + (s.packageName?.hashCode() ?: 0)
        h = 31 * h + if (s.keyboardVisible) 1 else 0
        for (root in s.appRoots) h = hashNode(root, s, h)
        return h
    }

    private fun hashNode(n: UiNode, s: ScreenSnapshot, seed: Int): Int {
        if (!visible(n, s) && n.children.isEmpty()) return seed
        var h = seed
        if (visible(n, s)) {
            val own = ownText(n) ?: n.hint
            if (own != null || n.isActionable) {
                h = 31 * h + (n.className?.substringAfterLast('.')?.hashCode() ?: 0)
                h = 31 * h + (own?.let { stableLabel(it) }?.hashCode() ?: 0)
                var flags = 0
                if (n.clickable) flags = flags or 1
                if (n.editable) flags = flags or 2
                if (n.scrollable) flags = flags or 4
                if (n.checked) flags = flags or 8
                if (!n.enabled) flags = flags or 16
                if (n.focused) flags = flags or 32
                if (n.selected) flags = flags or 64
                if (n.moreAfter) flags = flags or 128
                if (n.moreBefore) flags = flags or 256
                h = 31 * h + flags
            }
        }
        if (isSurface(n.className)) return h
        for (c in n.children) h = hashNode(c, s, h)
        return h
    }

    private val DIGIT = Regex("[0-9]")

    /** Short labels change on their own when they hold a clock, a counter or a speed; their digits do not count. */
    private fun stableLabel(raw: String): String {
        val t = normalize(raw)
        return if (t.length <= VOLATILE_LABEL) DIGIT.replace(t, "0") else t
    }

    private const val VOLATILE_LABEL = 16

    /** A view that draws continuously (a map, video or camera surface): what is inside it moves on its own. */
    fun isSurface(className: String?): Boolean {
        val c = className ?: return false
        val simple = c.substringAfterLast('.')
        return SURFACE_SUFFIXES.any { simple.endsWith(it) } || simple.contains("MapView") || c.contains(".maps.")
    }

    private val SURFACE_SUFFIXES = listOf("SurfaceView", "TextureView", "VideoView", "PlayerView")

    private fun normalize(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().replace('"', '\'')

    /** Cuts to [MAX_LABEL] chars ending in "...", never splitting a surrogate pair. */
    private fun truncate(t: String): String {
        if (t.length <= MAX_LABEL) return t
        var cut = MAX_LABEL - 3
        if (t[cut - 1].isHighSurrogate()) cut--
        return t.take(cut) + "..."
    }
}
