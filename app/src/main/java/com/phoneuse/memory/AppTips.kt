package com.phoneuse.memory

/**
 * How a device task was done in one app the last time it succeeded: [task] is a short summary of it (see
 * [AppTips.task]; never the user's message verbatim), [steps] the actions that did it (never typed text). A new task in the same app gets the best few as hints, so it takes fewer turns.
 */
data class AppTip(val id: Long, val packageName: String, val task: String, val steps: String, val createdAt: Long)

interface AppTipStore {
    /** Stores a tip; the same task in the same app replaces the older one. Each app keeps its newest [PER_APP]. */
    suspend fun add(packageName: String, task: String, steps: String): Long

    /** The [n] tips of [packageName] that best match [query], newer first among equals. */
    suspend fun top(packageName: String, query: String, n: Int = 3): List<AppTip>
    suspend fun count(): Int
    suspend fun deleteAllTips()

    companion object {
        const val PER_APP = 30
        const val MAX_TASK = 80
        const val MAX_STEPS = 400
    }
}

object AppTips {
    private val QUOTED = Regex("\"[^\"]*\"|'[^']*'|“[^”]*”|‘[^’]*’|「[^」]*」")

    /**
     * The task as kept in a tip: the first sentence of finish's [summary], quoted parts (often the text that was
     * sent or typed) replaced by "…", at most [AppTipStore.MAX_TASK] characters. Null when nothing is left.
     */
    fun task(summary: String): String? {
        val first = summary.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val cleaned = QUOTED.replace(first, "…").replace(Regex("\\s+"), " ").trim()
        val sentence = Regex("^.*?[.!?。](\\s|$)").find(cleaned)?.value?.trim() ?: cleaned
        return sentence.take(AppTipStore.MAX_TASK).trim().takeIf { it.isNotEmpty() }
    }

    /** Ranks [tips] for [query] by the terms their task and steps share with it, newer first among equals. */
    fun rank(query: String, tips: List<AppTip>, n: Int): List<AppTip> {
        val terms = MemoryText.terms(query)
        return tips.sortedWith(
            compareByDescending<AppTip> { t -> terms.count { it in (t.task + " " + t.steps).lowercase() } }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.id },
        ).take(n)
    }

    /** Actions of a run joined into one line, cut to [AppTipStore.MAX_STEPS]. */
    fun steps(actions: List<String>): String {
        val line = actions.joinToString(" → ") { it.replace(Regex("\\s+"), " ").trim() }
        return if (line.length <= AppTipStore.MAX_STEPS) line else line.take(AppTipStore.MAX_STEPS - 1) + "…"
    }

    /** The note added to the model input when a task reaches [packageName]; null when there is nothing to say. */
    fun note(packageName: String, tips: List<AppTip>): String? {
        if (tips.isEmpty()) return null
        return buildString {
            append("[앱 조작 요령 · ").append(packageName).append("] 이 앱에서 전에 성공한 방법이에요. 화면이 다르면 무시하세요.")
            for (t in tips) append("\n- ").append(t.task).append(": ").append(t.steps)
        }
    }
}
