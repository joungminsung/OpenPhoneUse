package com.phoneuse.memory

/** Splitting Korean/English text into match terms, shared by memory ranking and the FTS queries. */
object MemoryText {
    /** Particles and endings dropped from the end of a word, longest first, so "엄마가" matches "엄마". */
    private val SUFFIXES = listOf(
        "에서는", "으로는", "에게서", "이랑", "한테", "에게", "께서", "에서", "으로", "까지", "부터", "처럼", "보다",
        "이나", "이는", "은", "는", "이", "가", "을", "를", "에", "의", "와", "과", "도", "로", "랑", "만", "께", "요",
    )

    /** Words that say nothing about what to look up. */
    private val STOP = setOf(
        "뭐", "뭐가", "뭘", "좀", "그", "저", "나", "내", "제", "어떻게", "무엇", "어디", "언제", "누가", "왜", "있어", "없어",
        "해줘", "알려줘", "어때", "그리고", "근데", "the", "a", "an", "and", "or", "to", "of", "is", "in",
    )

    /** Distinct lowercase stems of [text], in order; one-letter words (except digits) are dropped. */
    fun terms(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in text.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))) {
            if (raw.isEmpty() || raw in STOP) continue
            val stem = stem(raw)
            if (stem in STOP) continue
            if (stem.length < 2 && !stem.all { it.isDigit() }) continue
            out += stem
        }
        return out.toList()
    }

    private fun stem(word: String): String {
        for (s in SUFFIXES) {
            if (word.length >= s.length + 2 && word.endsWith(s)) return word.dropLast(s.length)
        }
        return word
    }

    /**
     * An FTS4 MATCH expression: each term as a prefix query, OR-ed. Terms hold only letters and digits and are
     * lowercase, so no quote, bracket or operator (OR, NEAR, NOT) of the user's reaches the parser. Null when
     * there is nothing to look for.
     */
    const val MAX_TERMS = 12

    fun ftsQuery(text: String, maxTerms: Int = MAX_TERMS): String? =
        terms(text).take(maxTerms).takeIf { it.isNotEmpty() }?.joinToString(" OR ") { "$it*" }
}

/**
 * Picks the memories to put into the prompt for one message: only confirmed (SURE) ones whose text contains
 * some of the message's terms, most terms first and newer first among equals, within a character budget so
 * the prompt stays small however many memories there are. Nothing unrelated is sent, and a GUESS (which may
 * come from text the agent read rather than from the user) stays out until the user confirms it.
 */
object MemoryRanker {
    data class Scored(val memory: Memory, val score: Int)

    fun score(memory: Memory, terms: List<String>): Int {
        if (terms.isEmpty()) return 0
        val hay = memory.text.lowercase()
        return terms.count { it in hay }
    }

    fun rank(query: String, memories: List<Memory>): List<Scored> {
        val terms = MemoryText.terms(query)
        return memories.map { Scored(it, score(it, terms)) }
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenByDescending { it.memory.updatedAt }
                    .thenByDescending { it.memory.id },
            )
    }

    /**
     * At most [n] matching SURE memories whose [block] is at most [budgetChars] long. A line that does not fit
     * is skipped.
     */
    fun top(userText: String, memories: List<Memory>, n: Int = 12, budgetChars: Int = 2000): List<Scored> {
        val out = ArrayList<Scored>()
        var used = 0
        for (s in rank(userText, memories.filter { it.confidence == Confidence.SURE })) {
            if (out.size >= n || s.score <= 0) break
            val cost = line(s.memory).length + if (out.isEmpty()) 0 else 1
            if (used + cost > budgetChars) continue
            out += s
            used += cost
        }
        return out
    }

    /** One prompt line: id (for forget), kind, text, optional date/due and a guess marker. */
    fun line(m: Memory): String = buildString {
        append("- [").append(m.id).append("] (").append(m.kind.label).append(") ").append(m.text)
        m.date?.takeIf { it.isNotBlank() }?.let { append(" · 날짜 ").append(it) }
        m.due?.takeIf { it.isNotBlank() }?.let { append(" · 기한 ").append(it) }
        if (m.confidence == Confidence.GUESS) append(" (확실하지 않음)")
    }

    fun block(selected: List<Scored>): String = selected.joinToString("\n") { line(it.memory) }
}
