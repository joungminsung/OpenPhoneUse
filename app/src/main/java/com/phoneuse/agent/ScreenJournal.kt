package com.phoneuse.agent

import com.phoneuse.screen.CompressedScreen

/**
 * Where the run has been. Only the newest screen is sent in full, so without this a step cannot tell that it has
 * seen a screen before. The journal adds facts, never rules: an older screen keeps a one-line [digest] instead of
 * vanishing, and a screen that looks like an earlier one says so ([revisit]). The model judges what that means.
 *
 * Screens are compared without digits, so a live price or clock does not make a revisited screen look new.
 */
class ScreenJournal {
    private class Visit(val screen: Int, val step: Int, val action: String)

    private val visits = ArrayList<Visit>()

    /**
     * Records that [action] at [step] led to [screen]. Returns a note when that screen was seen before, naming
     * the earlier steps and actions; null for a new screen.
     */
    fun revisit(step: Int, action: String, screen: CompressedScreen): String? {
        val print = fingerprint(screen)
        val before = visits.filter { it.screen == print }
        visits += Visit(print, step, action)
        if (before.isEmpty()) return null
        val shown = before.takeLast(MAX_NAMED).joinToString(", ") { "step ${it.step} (${it.action})" }
        val more = if (before.size > MAX_NAMED) " and ${before.size - MAX_NAMED} more" else ""
        return "Seen before: this screen looks the same as after $shown$more (${before.size + 1} visits)."
    }

    companion object {
        private const val MAX_NAMED = 4
        private const val DIGEST_LABELS = 8
        private const val DIGEST_LABEL_CHARS = 24

        fun fingerprint(screen: CompressedScreen): Int =
            (screen.packageName + "\n" + screen.text.replace(Regex("[0-9]"), "")).hashCode()

        /** [output] (a tool result) with [note] just before its screen tree, or at the end when it has none. */
        fun withNote(output: String, note: String): String {
            val at = output.lastIndexOf(SCREEN_HEADER)
            return if (at < 0) "$output\n$note" else output.substring(0, at) + note + "\n\n" + output.substring(at)
        }

        private const val SCREEN_HEADER = "# Screen"

        /** One line for an older screen: its app and first labels, so the model remembers what was there. */
        fun digest(screen: CompressedScreen): String {
            val labels = screen.labels.values.asSequence().filter { it.isNotBlank() }
                .take(DIGEST_LABELS).joinToString(" | ") { it.take(DIGEST_LABEL_CHARS) }
            return "[earlier screen: ${screen.packageName} · $labels]"
        }
    }
}
