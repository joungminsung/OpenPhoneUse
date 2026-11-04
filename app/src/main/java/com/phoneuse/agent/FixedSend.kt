package com.phoneuse.agent

import com.phoneuse.screen.CompressedScreen

/**
 * A routine's saved message: with "확인 없이 보내기" on, a SEND may skip the confirm card only
 * when the live screen is a chat with exactly this recipient [to] and its message field holds exactly this
 * [body]. Anything else or anything unclear (another or a longer name, a group, a different draft, two filled
 * fields, no header) confirms as usual, and so does every connector send. Pure.
 */
data class FixedSend(val to: String, val body: String) {
    /**
     * Whether [screen], as it is now, sends [body] to [to]. [typedInto]/[typed]: a type_text with submit is about to
     * put [typed] into element [typedInto] (the screen does not show it yet).
     *
     * - The body: exactly one editable field holds text, and that text equals [body] (spacing and case aside).
     * - The recipient: some non-editable node in the header (the top of the screen, or a title/toolbar view) of the
     *   same window as that field, whose whole label equals [to] after trimming; a header node merely containing it
     *   ("시엄마", "엄마, 아빠") does not, nor does a heads-up or chat bubble (another window) showing the name.
     */
    fun matches(screen: CompressedScreen, typedInto: Int? = null, typed: String? = null): Boolean {
        val who = norm(to)
        val what = norm(body)
        if (who.isEmpty() || what.isEmpty()) return false
        val filled = screen.elements.mapNotNull { (i, n) ->
            if (!n.editable) return@mapNotNull null
            val content = if (i == typedInto) typed else n.text?.takeIf { it != n.hint }
            content?.takeIf { it.isNotBlank() }?.let { it to n.windowId }
        }
        if (filled.size != 1 || norm(filled.single().first) != what) return false
        val window = filled.single().second
        return screen.elements.any { (i, n) ->
            !n.editable && n.windowId == window && isHeader(n.bounds.bottom, n.viewId, screen.displayHeight) &&
                listOfNotNull(screen.fullLabels[i], n.text, n.contentDescription).any { norm(it) == who }
        }
    }

    companion object {
        /** The header: the top [HEADER_FRACTION] of the screen, or a view whose id names a title or toolbar. */
        const val HEADER_FRACTION = 0.15

        private val HEADER_IDS = listOf("title", "toolbar", "header", "action_bar", "actionbar")

        internal fun isHeader(bottom: Int, viewId: String?, displayHeight: Int): Boolean {
            val id = viewId?.lowercase().orEmpty()
            return bottom <= displayHeight * HEADER_FRACTION || HEADER_IDS.any { it in id }
        }

        private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim().lowercase()
    }
}
