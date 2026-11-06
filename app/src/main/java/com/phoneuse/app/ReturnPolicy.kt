package com.phoneuse.app

import com.phoneuse.i18n.tr

/** How a run ended. */
enum class RunOutcome { FINISHED, FAILED, CANCELLED, SERVICE_LOST }

/** Whether the OpenPhoneUse chat comes back to the front when a run ends. */
object ReturnPolicy {
    /**
     * A run sent from the chat ([Source.APP]) or a card button ([Source.CARD]) that operated another app
     * ([usedDevice]: it drove the device and OpenPhoneUse is no longer on screen) returns to the chat however it
     * ended, unless it finished with [stayInApp] (the result is the other app: music playing, a map it only opened;
     * AgentLoop drops the model's flag when the run worked inside the app and nothing is playing).
     * Side-button runs ([Source.ASSIST]) were started from the user's app, so they stay there. Routine runs
     * ([Source.ROUTINE]) started by themselves: they never pull the user into the app; their result is a
     * notification and the routine's conversation.
     */
    fun shouldReturn(source: Source, usedDevice: Boolean, stayInApp: Boolean, outcome: RunOutcome): Boolean {
        if (source == Source.ASSIST || source == Source.ROUTINE || !usedDevice) return false
        if (stayInApp && outcome == RunOutcome.FINISHED) return false
        return true
    }

    /**
     * When (ms after starting the chat) to check that it resumed; if it has not by the last check, the launch
     * was blocked and a notification is posted instead.
     */
    val FALLBACK_CHECKS_MS = listOf(1500L, 4000L)
    private const val FALLBACK_MAX_CHARS = 80

    /**
     * True while the chat has not resumed at any point since the launch at [launchedAt] ([lastResumedAt]: the
     * elapsed-realtime of its last onResume). Leaving the chat again right away does not count as blocked.
     */
    fun needsFallback(launchedAt: Long, lastResumedAt: Long): Boolean = lastResumedAt < launchedAt

    /** The fallback notification's text: "작업 끝 · " and the summary's first non-blank line, shortened. */
    fun fallbackText(summary: String?): String {
        val line = summary?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: tr("Done", "완료")
        val short = if (line.length > FALLBACK_MAX_CHARS) line.take(FALLBACK_MAX_CHARS - 1).trimEnd() + "…" else line
        return tr("Task finished · $short", "작업 끝 · $short")
    }
}
