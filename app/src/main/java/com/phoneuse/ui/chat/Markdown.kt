package com.phoneuse.ui.chat

/** A run of text with one style. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    /** Set on a link's text: the http(s) address it opens. */
    val url: String? = null,
)

data class MdListItem(val spans: List<MdSpan>, val level: Int)

data class MdNumberedItem(val number: Int, val spans: List<MdSpan>)

sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Bullets(val items: List<MdListItem>) : MdBlock
    data class Numbered(val items: List<MdNumberedItem>) : MdBlock

    /** Every row has exactly as many cells as [header]. */
    data class Table(val header: List<List<MdSpan>>, val rows: List<List<List<MdSpan>>>) : MdBlock
    data class Code(val text: String) : MdBlock

    /** A `>` block: the answer's key line, shown as a highlighted box. */
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data object Rule : MdBlock
}

/**
 * The small Markdown subset model answers use: paragraphs, `#` headings, `-`/`*`/`•` and numbered lists,
 * pipe tables, fenced code, rules, `>` quotes, and inline `**bold**`, `*italic*`, `` `code` ``, `[text](url)`.
 * Links are http(s) only; any other scheme stays literal text, as do unclosed markers, so a reply that is still
 * streaming renders sensibly.
 */
object Markdown {
    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val bullet = Regex("""^(\s*)[-*+•]\s+(.*)$""")
    private val numbered = Regex("""^\s*(\d{1,4})[.)]\s+(.*)$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val fence = Regex("""^\s*```""")
    private val quote = Regex("""^\s{0,3}>\s?(.*)$""")
    private val tableSeparator = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
    private val httpUrl = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val openLink = Regex("""\[[^\]\n]*(?:](?:\((?:h|ht|htt|https?(?::/{0,2}\S*)?)?)?)?""", RegexOption.IGNORE_CASE)

    // Added by OpenAI's hosted web search to the addresses it cites. Group 2 is a following parameter's "&".
    private val openAiTracking = Regex("""([?&])utm_source=openai(?=[&#]|$)(&?)""")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MdBlock.Paragraph(inline(paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() -> {
                    flushParagraph()
                    i++
                }
                fence.containsMatchIn(line) -> {
                    flushParagraph()
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !fence.containsMatchIn(lines[i])) body += lines[i++]
                    i++ // the closing fence, if any
                    blocks += MdBlock.Code(body.joinToString("\n").trimEnd('\n'))
                }
                heading.matches(line) -> {
                    flushParagraph()
                    val m = heading.find(line)!!
                    blocks += MdBlock.Heading(m.groupValues[1].length, inline(m.groupValues[2].trim()))
                    i++
                }
                quote.matches(line) -> {
                    flushParagraph()
                    val body = mutableListOf<String>()
                    while (i < lines.size) {
                        val m = quote.find(lines[i]) ?: break
                        body += m.groupValues[1].trimEnd()
                        i++
                    }
                    val text = body.joinToString("\n").trim()
                    if (text.isNotEmpty()) blocks += MdBlock.Quote(inline(text))
                }
                rule.matches(line) -> {
                    flushParagraph()
                    blocks += MdBlock.Rule
                    i++
                }
                line.contains('|') && i + 1 < lines.size && lines[i + 1].contains('-') &&
                    tableSeparator.matches(lines[i + 1]) -> {
                    flushParagraph()
                    val header = cells(line)
                    i += 2
                    val rows = mutableListOf<List<List<MdSpan>>>()
                    // A table row needs a pipe; the first line without one ends the table.
                    while (i < lines.size && lines[i].contains('|') && !fence.containsMatchIn(lines[i])) {
                        val row = cells(lines[i])
                        rows += List(header.size) { c -> row.getOrElse(c) { emptyList() } }
                        i++
                    }
                    blocks += MdBlock.Table(header, rows)
                }
                bullet.matches(line) -> {
                    flushParagraph()
                    val items = mutableListOf<Pair<Int, StringBuilder>>()
                    while (i < lines.size) {
                        val m = bullet.find(lines[i])
                        if (m != null && !rule.matches(lines[i])) {
                            val indent = m.groupValues[1].replace("\t", "    ").length
                            items += (if (indent >= 2) 1 else 0) to StringBuilder(m.groupValues[2].trim())
                        } else if (isContinuation(lines[i]) && items.isNotEmpty()) {
                            items.last().second.append('\n').append(lines[i].trim())
                        } else {
                            break
                        }
                        i++
                    }
                    blocks += MdBlock.Bullets(items.map { (level, sb) -> MdListItem(inline(sb.toString()), level) })
                }
                numbered.matches(line) -> {
                    flushParagraph()
                    val items = mutableListOf<Pair<Int, StringBuilder>>()
                    while (i < lines.size) {
                        val m = numbered.find(lines[i])
                        if (m != null) {
                            items += m.groupValues[1].toInt() to StringBuilder(m.groupValues[2].trim())
                        } else if (isContinuation(lines[i]) && items.isNotEmpty()) {
                            items.last().second.append('\n').append(lines[i].trim())
                        } else {
                            break
                        }
                        i++
                    }
                    blocks += MdBlock.Numbered(items.map { (n, sb) -> MdNumberedItem(n, inline(sb.toString())) })
                }
                else -> {
                    paragraph += line.trimEnd()
                    i++
                }
            }
        }
        flushParagraph()
        return blocks
    }

    /** An indented, non-empty line that is not itself a list item continues the previous item. */
    private fun isContinuation(line: String): Boolean =
        line.isNotBlank() && (line.startsWith("  ") || line.startsWith("\t")) &&
            !bullet.matches(line) && !numbered.matches(line)

    private fun cells(line: String): List<List<MdSpan>> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length - 1)
        return t.split('|').map { inline(it.trim()) }
    }

    /** [text] without a link still being written at its end, for a reply that is streaming. */
    fun withoutOpenLink(text: String): String {
        val start = text.lastIndexOf('[')
        if (start < 0 || linkAt(text, start) != null) return text
        return if (openLink.matches(text.substring(start))) text.substring(0, start) else text
    }

    fun inline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        parseInline(text, bold = false, italic = false, url = null, out)
        return merge(out)
    }

    private class Link(val label: String, val url: String, val end: Int)

    /** The complete http(s) link whose `[` is at [start], with [end][Link.end] just past its `)`; else null. */
    private fun linkAt(text: String, start: Int): Link? {
        val close = matching(text, start, '[', ']', stopAtSpace = false) ?: return null
        if (close == start + 1 || text.getOrNull(close + 1) != '(') return null
        val end = matching(text, close + 1, '(', ')', stopAtSpace = true) ?: return null
        val url = text.substring(close + 2, end)
        if (!httpUrl.matches(url)) return null
        return Link(text.substring(start + 1, close), openAiTracking.replace(url) { if (it.groupValues[2].isEmpty()) "" else it.groupValues[1] }, end + 1)
    }

    /** The index of the [closer] balancing the [opener] at [start], on the same line; null when there is none. */
    private fun matching(text: String, start: Int, opener: Char, closer: Char, stopAtSpace: Boolean): Int? {
        var depth = 0
        for (i in start until text.length) {
            val c = text[i]
            when {
                c == '\n' || (stopAtSpace && c.isWhitespace()) -> return null
                c == opener -> depth++
                c == closer -> if (--depth == 0) return i
            }
        }
        return null
    }

    private fun parseInline(text: String, bold: Boolean, italic: Boolean, url: String?, out: MutableList<MdSpan>) {
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out += MdSpan(plain.toString(), bold = bold, italic = italic, url = url)
                plain.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    out += MdSpan(text.substring(i + 1, end), bold = bold, italic = italic, code = true, url = url)
                    i = end + 1
                    continue
                }
            }
            if (c == '[' && url == null) {
                val link = linkAt(text, i)
                if (link != null) {
                    flush()
                    parseInline(link.label, bold = bold, italic = italic, url = link.url, out)
                    i = link.end
                    continue
                }
            }
            if (!bold && (text.startsWith("**", i) || text.startsWith("__", i))) {
                val marker = text.substring(i, i + 2)
                val end = text.indexOf(marker, i + 2)
                if (end > i + 2 && !text[i + 2].isWhitespace() && !text[end - 1].isWhitespace()) {
                    flush()
                    parseInline(text.substring(i + 2, end), bold = true, italic = italic, url = url, out)
                    i = end + 2
                    continue
                }
            }
            if (!italic && c == '*' && !text.startsWith("**", i) && i + 1 < text.length && !text[i + 1].isWhitespace()) {
                val end = findItalicEnd(text, i + 1)
                if (end > 0) {
                    flush()
                    parseInline(text.substring(i + 1, end), bold = bold, italic = true, url = url, out)
                    i = end + 1
                    continue
                }
            }
            plain.append(c)
            i++
        }
        flush()
    }

    /** Index of a closing single `*` (not part of `**`, not after whitespace), or -1. */
    private fun findItalicEnd(text: String, from: Int): Int {
        var j = from
        while (j < text.length) {
            if (text[j] == '*') {
                if (text.startsWith("**", j)) {
                    j += 2
                    continue
                }
                if (!text[j - 1].isWhitespace()) return j
            }
            j++
        }
        return -1
    }

    private fun merge(spans: List<MdSpan>): List<MdSpan> {
        val merged = mutableListOf<MdSpan>()
        for (s in spans) {
            if (s.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.bold == s.bold && last.italic == s.italic && last.code == s.code && last.url == s.url) {
                merged[merged.lastIndex] = last.copy(text = last.text + s.text)
            } else {
                merged += s
            }
        }
        return merged
    }
}
