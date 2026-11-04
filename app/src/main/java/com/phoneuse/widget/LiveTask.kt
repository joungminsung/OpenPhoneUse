package com.phoneuse.widget

import kotlin.math.pow

/** What the live-task widget shows; mapped from the runner's state by [LiveTask.map]. */
sealed interface LiveView {
    /** A run is going: its task, the newest step line, how many steps so far and a progress estimate (0..1). */
    data class Running(val title: String, val step: String, val steps: Int, val progress: Float, val needsYou: Boolean) : LiveView

    /** No run: the last reply's first line, and its conversation for "Open" and "Follow up". */
    data class Idle(val title: String, val result: String, val conversationId: Long) : LiveView

    /** Nothing has run yet (or the last conversation is gone). */
    data object Empty : LiveView
}

/** Pure state mapping and text cleanup for the live-task widget. */
object LiveTask {
    const val TITLE_CHARS = 60
    const val STEP_CHARS = 90
    const val RESULT_CHARS = 140

    /** Progress never reaches the end while running; each step closes [STEP_SHARE] of the remaining gap. */
    private const val STEP_SHARE = 0.18
    private const val START = 0.06f
    private const val CAP = 0.94f

    private val MARKUP = Regex("""^\s*(#{1,6}\s+|>\s*|[-*+]\s+|\d+[.)]\s+|\|)""")
    private val INLINE = Regex("""\*\*|__|`|~~""")

    fun map(
        running: Boolean,
        task: String,
        steps: List<String>,
        streaming: String,
        awaitingConfirm: Boolean,
        lastReply: String?,
        lastConversation: Long?,
    ): LiveView = when {
        running -> LiveView.Running(
            title = clip(task.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(), TITLE_CHARS),
            step = clip(currentStep(steps, streaming), STEP_CHARS),
            steps = steps.size,
            progress = progress(steps.size),
            needsYou = awaitingConfirm,
        )
        lastConversation != null && !lastReply.isNullOrBlank() ->
            LiveView.Idle(clip(task.trim(), TITLE_CHARS), clip(firstLine(lastReply), RESULT_CHARS), lastConversation)
        else -> LiveView.Empty
    }

    /** The newest step line, or the reply as it streams, or "" (the widget then says it is thinking). */
    fun currentStep(steps: List<String>, streaming: String): String =
        steps.lastOrNull { it.isNotBlank() }?.trim() ?: firstLine(streaming)

    /** 1 - (1 - share)^steps, between [START] and [CAP]: moves on every step and never claims to be done. */
    fun progress(steps: Int): Float {
        if (steps <= 0) return START
        val p = 1.0 - (1.0 - STEP_SHARE).pow(steps)
        return p.toFloat().coerceIn(START, CAP)
    }

    /** The first line of Markdown text with list, heading, quote and emphasis marks taken off. */
    fun firstLine(text: String): String = text.lineSequence()
        .map { it.replace(MARKUP, "").replace(INLINE, "").replace("|", " ").trim() }
        .firstOrNull { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
        .orEmpty()

    fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
}
