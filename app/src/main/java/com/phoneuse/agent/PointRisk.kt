package com.phoneuse.agent

import com.phoneuse.i18n.tr
import com.phoneuse.risk.RiskCategory
import com.phoneuse.risk.RiskGuard
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.CompressedScreen
import com.phoneuse.screen.UiNode

/**
 * The risk of touching a point when no labeled element names it — a coordinate tap, an unlabeled index
 * tap, the start of a swipe ("밀어서 결제"). Judged on the live screen: the deepest element under the point, its
 * smallest clickable container and that container's text. Only for a tap whose touched element has no text at
 * all (an unlabeled button, a bare node in a web view, a drawn canvas) does the money/security/delete text within
 * [RADIUS_DP] of it count too, in the same window (and an unlabeled target in the row of a filled-in text field is a
 * send); a swipe never looks around, so scrolling a page that says "결제" elsewhere asks nothing. An unlabeled,
 * non-scrolling swipe start in a store/pay/bank app counts as a possible "slide to pay".
 */
internal object PointRisk {
    /**
     * [strong]: see [com.phoneuse.risk.RiskMatch.strong]. The guesses for unlabeled controls (a pay app, the send icon
     * beside a filled-in field) are strong: the AI risk judge never clears them.
     */
    class Verdict(val category: RiskCategory, val name: String, val context: String?, val strong: Boolean = true)

    /** A fixed physical distance, about a thumb's width around the point. */
    const val RADIUS_DP = 96.0

    /** [RADIUS_DP] in px for a display of [density] px per dp (never the screen width: a Fold is wide, not dense). */
    fun radiusPx(density: Float): Double = RADIUS_DP * density.coerceAtLeast(1f)

    fun judge(screen: CompressedScreen, x: Int, y: Int, swipe: Boolean = false): Verdict? {
        val containing = screen.elements.entries.filter { (_, n) -> n.bounds.contains(x, y) }
        val hit = containing.minByOrNull { (_, n) -> n.bounds.area }
        val container = containing.filter { (_, n) -> n.clickable || n.longClickable }.minByOrNull { (_, n) -> n.bounds.area }
        val own = buildList {
            hit?.let { add(it.key) }
            container?.let { c ->
                add(c.key)
                screen.elements.forEach { (i, n) -> if (n.within(c.value)) add(i) }
            }
        }.distinct().mapNotNull { screen.fullLabels[it]?.takeIf(String::isNotBlank) }.distinct().joinToString(" ")

        RiskGuard.match(own.ifBlank { null }, screen.packageName)?.let { return Verdict(it.category, own, null, it.strong) }
        if (own.isNotBlank()) return null
        if (swipe) {
            // A swipe never looks around, and a scrollable list is just scrolled; but one that starts on an
            // unlabeled, non-scrolling control in a store/pay/bank app may be a "slide to pay" slider.
            val scrolls = (container?.value ?: hit?.value)?.scrollable == true
            return if (!scrolls && RiskGuard.isPayPackage(screen.packageName)) Verdict(RiskCategory.MONEY, UNKNOWN, null) else null
        }

        val radius = radiusPx(screen.density)
        val window = hit?.value?.windowId
        val near = screen.elements.entries
            .filter { (_, n) -> (window == null || n.windowId == window) && n.bounds.distanceTo(x, y) <= radius }
            .mapNotNull { (i, _) -> screen.fullLabels[i]?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(" ")
        RiskGuard.match(near.ifBlank { null }, screen.packageName, allowFieldClear = false)
            ?.takeIf { it.category in ALWAYS }
            ?.let { return Verdict(it.category, UNKNOWN, near, it.strong) }
        if (RiskGuard.isPayPackage(screen.packageName)) return Verdict(RiskCategory.MONEY, UNKNOWN, null)
        // An unlabeled target in the same row as an editable field holding text: the paper-plane send icon.
        val target = (container?.value ?: hit?.value)?.bounds
        if (target != null && sharesRowWithFilledField(screen, hit?.key, target, radius)) {
            return Verdict(RiskCategory.SEND, UNKNOWN, null)
        }
        return null
    }

    private fun sharesRowWithFilledField(screen: CompressedScreen, self: Int?, target: Bounds, radius: Double): Boolean =
        screen.elements.entries.any { (i, n) ->
            i != self && n.editable && !n.password && !n.text.isNullOrBlank() &&
                !(n.bounds.left >= target.left && n.bounds.right <= target.right &&
                    n.bounds.top >= target.top && n.bounds.bottom <= target.bottom) &&
                minOf(n.bounds.bottom, target.bottom) > maxOf(n.bounds.top, target.top) &&
                maxOf(n.bounds.left - target.right, target.left - n.bounds.right, 0) <= radius
        }

    val UNKNOWN: String get() = tr("Unknown button", "알 수 없는 버튼")
    private val ALWAYS = setOf(RiskCategory.MONEY, RiskCategory.SECURITY, RiskCategory.DELETE)

    private fun Bounds.contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom

    private val Bounds.area: Long get() = (right - left).toLong() * (bottom - top)

    private fun Bounds.distanceTo(x: Int, y: Int): Double {
        val dx = maxOf(left - x, 0, x - right).toDouble()
        val dy = maxOf(top - y, 0, y - bottom).toDouble()
        return Math.hypot(dx, dy)
    }

    /** A descendant of [container] in the tree: the same window and a longer path that starts with its path. */
    private fun UiNode.within(container: UiNode): Boolean =
        windowId == container.windowId && path.size > container.path.size && path.subList(0, container.path.size) == container.path
}
