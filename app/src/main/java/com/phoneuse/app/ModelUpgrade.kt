package com.phoneuse.app

/**
 * Finds a newer model of the same family in a provider's own list, so a default follows new releases without an
 * app update. A family is the id with its version numbers taken out ("gpt-6.1-terra" → "gpt-#-terra",
 * "claude-opus-5-5" → "claude-opus-#"), so a tier (opus, haiku, mini) or a preview never replaces another. Dated
 * snapshots ("-20261001", "-0709", "-2026-10-01") are the same version, not a newer one.
 */
object ModelUpgrade {
    /** The highest-versioned id in [listed] of [base]'s family when it is newer than [base]; null otherwise. */
    fun newest(base: String, listed: List<String>): String? {
        val (family, version) = parse(base) ?: return null
        return listed
            .mapNotNull { id -> parse(id)?.takeIf { it.first == family }?.let { id to it.second } }
            .filter { compare(it.second, version) > 0 }
            .maxWithOrNull { a, b -> compare(a.second, b.second) }
            ?.first
    }

    /** Family and version numbers of [id]; null when it has no version number. */
    private fun parse(id: String): Pair<String, List<Int>>? {
        val core = id.lowercase().removePrefix("models/")
            .replace(DATE_SUFFIX, "")
        val version = mutableListOf<Int>()
        val family = core.split('-').map { token ->
            NUMBER.findAll(token).forEach { m -> m.value.split('.').mapTo(version) { it.toInt() } }
            token.replace(NUMBER, "#")
        }.fold(mutableListOf<String>()) { acc, t -> if (t == "#" && acc.lastOrNull() == "#") acc else acc.apply { add(t) } }
            .joinToString("-")
        return if (version.isEmpty()) null else family to version
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    private val NUMBER = Regex("""\d+(?:\.\d+)*""")
    /** -YYYY-MM-DD, -YYYYMMDD or -MMDD at the end. */
    private val DATE_SUFFIX = Regex("""-(?:\d{4}-\d{2}-\d{2}|\d{8}|\d{4})$""")
}
