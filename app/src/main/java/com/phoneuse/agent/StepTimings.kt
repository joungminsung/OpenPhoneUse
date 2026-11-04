package com.phoneuse.agent

/**
 * Where one agent step's time went, logged to logcat ("PhoneUse") to find what makes screen control slow.
 * Model: [firstByteMs] (response headers), [firstOutputMs] (first output item or text) and [modelMs] (whole
 * call, retries included). Tools: [toolMs] for the step's tool calls, of which [settleMs] was spent waiting for
 * the UI to settle ([settle] says how the last wait ended) and [readMs] reading and compressing the screen.
 * [screenChars] is the size of the last screen text sent; [inputChars] the text size of the model input.
 */
data class StepTimings(
    val step: Int,
    val modelMs: Long,
    val firstByteMs: Long? = null,
    val firstOutputMs: Long? = null,
    val inputTokens: Int? = null,
    val inputChars: Int = 0,
    val tools: List<String> = emptyList(),
    val toolMs: Long = 0,
    val settleMs: Long = 0,
    val settle: String? = null,
    val readMs: Long = 0,
    /** Capturing and marking the step's screenshots (see [ToolExecutor]'s vision-first reads). */
    val shotMs: Long = 0,
    val screenChars: Int = 0,
    /** Model calls cancelled and sent again because the screen changed meanwhile (see [Speculation]). */
    val restarts: Int = 0,
) {
    /** One line, e.g. `step=3 model=2140ms ttfb=310ms out=1650ms in=8123tok/23000ch | tap 820ms settle=410ms(quiet) read=95ms screen=3412ch`. */
    fun format(): String = buildString {
        append("step=").append(step).append(" model=").append(modelMs).append("ms")
        firstByteMs?.let { append(" ttfb=").append(it).append("ms") }
        firstOutputMs?.let { append(" out=").append(it).append("ms") }
        if (restarts > 0) append(" restarts=").append(restarts)
        append(" in=")
        inputTokens?.let { append(it).append("tok/") }
        append(inputChars).append("ch")
        if (tools.isNotEmpty()) {
            append(" | ").append(tools.joinToString("+")).append(' ').append(toolMs).append("ms")
            append(" settle=").append(settleMs).append("ms")
            settle?.let { append('(').append(it).append(')') }
            append(" read=").append(readMs).append("ms")
            if (shotMs > 0) append(" shot=").append(shotMs).append("ms")
            append(" screen=").append(screenChars).append("ch")
        }
    }
}

/** Accumulates the settle and screen-read time of tool calls; [ToolExecutor] adds to it, the loop resets it. */
class ToolClock {
    var settleMs = 0L
        private set
    var readMs = 0L
        private set
    var settle: String? = null
        private set
    var screenChars = 0
        private set
    var shotMs = 0L
        private set

    fun reset() {
        settleMs = 0
        readMs = 0
        shotMs = 0
        settle = null
        screenChars = 0
    }

    internal fun settled(ms: Long, how: String?) {
        settleMs += ms
        if (how != null) settle = how
    }

    internal fun shot(ms: Long) {
        shotMs += ms
    }

    internal fun read(ms: Long, chars: Int) {
        readMs += ms
        screenChars = chars
    }
}
