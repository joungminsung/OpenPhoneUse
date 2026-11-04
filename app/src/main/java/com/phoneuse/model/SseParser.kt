package com.phoneuse.model

data class SseEvent(val event: String?, val data: String)

/** Line-oriented Server-Sent Events parser. Feed lines without terminators; reuse across events. */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Returns a completed event when [line] finishes one, else null. */
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        return when (field) {
            "event" -> {
                // A new event line without a preceding blank line closes the pending event.
                val done = if (event != null || hasData) flush() else null
                event = value
                done
            }
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
                null
            }
            else -> null
        }
    }

    fun reset() {
        event = null
        data.setLength(0)
        hasData = false
    }

    private fun flush(): SseEvent? {
        val out = if (hasData) SseEvent(event, data.toString()) else null
        reset()
        return out
    }
}
