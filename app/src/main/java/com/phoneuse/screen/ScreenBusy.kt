package com.phoneuse.screen

/**
 * Whether a screen is still loading: a spinner the user can see, or a short label that says so ("로딩 중…",
 * "Loading..."), in an app window. Longer text that merely mentions loading (a chat message, an article) does not
 * count, nor does a determinate bar (playback, download, storage: it carries a range and can sit there for
 * minutes), a hidden or covered spinner, or anything in the status bar. A screen with no app window (between two
 * activities, only the status bar left) is busy too: it is a transition, not a page. Our own app in front is not.
 */
object ScreenBusy {
    private val LOADING_WORDS = listOf("로딩", "불러오는 중", "loading")

    /** A loading label is short; longer text is content. */
    private const val MAX_LABEL = 20

    fun isBusy(snapshot: ScreenSnapshot): Boolean {
        if (snapshot.packageName == null) return !snapshot.ownAppInFront
        return snapshot.appRoots.any { busy(it, snapshot.displayWidth, snapshot.displayHeight) }
    }

    /**
     * The app windows show nothing yet: no element with text or an action (a new page before its first layout,
     * a window still being created). A game or camera view looks like this too, so callers wait only briefly.
     */
    fun isBlank(snapshot: ScreenSnapshot): Boolean =
        !snapshot.activeIsSystem && snapshot.appRoots.none { hasContent(it, snapshot.displayWidth, snapshot.displayHeight) }

    private fun hasContent(node: UiNode, width: Int, height: Int): Boolean {
        if (node.covered) return false
        val shown = visible(node.bounds, width, height) &&
            (node.isActionable || !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())
        return shown || node.children.any { hasContent(it, width, height) }
    }

    private fun busy(node: UiNode, width: Int, height: Int): Boolean {
        if (!node.visibleToUser || node.covered) return false
        if (visible(node.bounds, width, height) && looksLoading(node)) return true
        return node.children.any { busy(it, width, height) }
    }

    private fun looksLoading(node: UiNode): Boolean {
        val cls = node.className.orEmpty()
        // An indeterminate ProgressBar (Compose progress indicators report it too); a SeekBar is a control.
        if (cls.endsWith("ProgressBar") || cls.endsWith("ProgressIndicator")) return !node.hasRange
        return listOfNotNull(node.text, node.contentDescription).any { raw ->
            val text = raw.trim()
            text.length <= MAX_LABEL && LOADING_WORDS.any { text.contains(it, ignoreCase = true) }
        }
    }

    private fun visible(b: Bounds, width: Int, height: Int): Boolean =
        b.right > b.left && b.bottom > b.top && b.right > 0 && b.bottom > 0 && b.left < width && b.top < height
}
