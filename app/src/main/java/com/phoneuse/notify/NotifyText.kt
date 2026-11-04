package com.phoneuse.notify

/**
 * An answer as a notification shows it. Replies are Markdown (ui/chat/Markdown), which the shade shows as raw
 * text: `**`, `#` and table pipes would read as noise. Lists and table rows keep their shape as plain lines.
 */
object NotifyText {
    /** About what an expanded notification shows before the system cuts it anyway. */
    const val BIG_CHARS = 900

    /** The collapsed line; the shade cuts it shorter, but a whole first sentence is kept for the lock screen. */
    const val LINE_CHARS = 140

    const val TITLE_CHARS = 40

    private val FENCE = Regex("""^\s*(```|~~~).*$""")
    private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
    private val HEADING = Regex("""^#{1,6}\s+""")
    private val QUOTE = Regex("""^>\s?""")
    private val BULLET = Regex("""^[-*+]\s+""")
    private val IMAGE = Regex("""!\[([^\]]*)]\((?:[^()]|\([^()]*\))*\)""")
    private val LINK = Regex("""\[([^\]]+)]\((?:[^()]|\([^()]*\))*\)""")
    private val BOLD = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
    private val STRIKE = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")
    private val ITALIC_STAR = Regex("""(?<![\w*])\*(?=\S)(.+?)(?<=\S)\*(?![\w*])""")

    // Underscores inside words (snake_case, file names) are not emphasis.
    private val ITALIC_UNDERSCORE = Regex("""(?<![\p{L}\p{N}_])_(?=\S)(.+?)(?<=\S)_(?![\p{L}\p{N}_])""")
    private val CODE = Regex("""`([^`]+)`""")
    private val ESCAPE = Regex("""\\([\\`*_{}\[\]()#+\-.!|>~])""")

    /** [markdown] as plain lines: no emphasis marks, headings or rules; "• " bullets; table cells joined by " · ". */
    fun plain(markdown: String): String {
        val out = ArrayList<String>()
        var fenced = false
        for (raw in markdown.lines()) {
            if (FENCE.matches(raw)) {
                fenced = !fenced
                continue
            }
            if (fenced) {
                out += raw.trimEnd()
                continue
            }
            val line = raw.trim()
            when {
                line.isEmpty() -> out += ""
                RULE.matches(line) -> out += ""
                line.startsWith("|") && TABLE_SEPARATOR.matches(line) -> continue
                line.startsWith("|") -> out += line.trim('|').split('|').map { inline(it.trim()) }.filter { it.isNotEmpty() }.joinToString(" · ")
                else -> out += block(line)
            }
        }
        return collapse(out)
    }

    /** The first line with text of [plainText], cut to [LINE_CHARS]. */
    fun firstLine(plainText: String): String =
        clip(plainText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty(), LINE_CHARS)

    /**
     * [text] cut to at most [max] characters, at a line end or a space in the last third when there is one, with
     * "…" when anything was cut.
     */
    fun clip(text: String, max: Int): String {
        val t = text.trim()
        if (t.length <= max) return t
        val head = t.substring(0, max - 1)
        val cut = maxOf(head.lastIndexOf('\n'), head.lastIndexOf(' '))
        val kept = if (cut >= max * 2 / 3) head.substring(0, cut) else head
        return kept.trimEnd() + "…"
    }

    /** A notification title: [title] when it has text (cut to [TITLE_CHARS]), else [fallback]. */
    fun title(title: String?, fallback: String): String =
        title?.let { plain(it).lineSequence().firstOrNull { l -> l.isNotBlank() } }?.let { clip(it, TITLE_CHARS) } ?: fallback

    private fun block(line: String): String {
        var t = line.replace(HEADING, "").replace(QUOTE, "")
        if (BULLET.containsMatchIn(t)) t = "• " + t.replace(BULLET, "")
        return inline(t)
    }

    private fun inline(text: String): String {
        // Code spans first, so emphasis marks inside them stay as written.
        val spans = ArrayList<String>()
        var t = CODE.replace(text.replace("\u0000", "")) { spans += it.groupValues[1]; "\u0000${spans.size - 1}\u0000" }
        t = t.replace(IMAGE, "$1").replace(LINK, "$1")
        t = t.replace(BOLD, "$2").replace(STRIKE, "$1").replace(ITALIC_STAR, "$1").replace(ITALIC_UNDERSCORE, "$1")
        t = t.replace(ESCAPE, "$1")
        return Regex("\u0000(\\d+)\u0000").replace(t) { spans.getOrNull(it.groupValues[1].toIntOrNull() ?: -1) ?: "" }.trim()
    }

    /** Trims each line's end and leaves at most one blank line in a row, none at either end. */
    private fun collapse(lines: List<String>): String {
        val out = ArrayList<String>()
        for (l in lines) {
            val line = l.trimEnd()
            if (line.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
            out += line
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return out.joinToString("\n")
    }
}
