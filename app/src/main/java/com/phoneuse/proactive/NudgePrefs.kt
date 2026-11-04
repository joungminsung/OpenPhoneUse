package com.phoneuse.proactive

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime

/**
 * The board NudgeSettings' own choices: one switch per [NudgeKind] (all on by default), the briefing time and the
 * daily cap, in their own preferences file. The quiet hours are not here: they are the app-wide
 * SettingsStore.quietHours ("방해하지 않는 시간"), which ProactiveSystem applies.
 */
class NudgePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())

    val settings: StateFlow<NudgeSettings> = _settings.asStateFlow()

    fun setEnabled(kind: NudgeKind, on: Boolean) = edit { putBoolean(onKey(kind), on) }

    fun setBriefingAt(t: LocalTime) = edit { putString(BRIEFING_AT, NudgeTimes.format(t)) }

    fun setMaxPerDay(n: Int) = edit { putInt(MAX_PER_DAY, n.coerceIn(1, 20)) }

    /** The one-time card about "항상 허용" location (leave times and the briefing's weather need it) was closed. */
    var locationHintSeen: Boolean
        get() = prefs.getBoolean(LOCATION_HINT_SEEN, false)
        set(v) = prefs.edit().putBoolean(LOCATION_HINT_SEEN, v).apply()

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _settings.value = read()
    }

    private fun read(): NudgeSettings = NudgeSettings(
        enabled = NudgeKind.entries.filter { prefs.getBoolean(onKey(it), true) }.toSet(),
        briefingAt = NudgeTimes.parse(prefs.getString(BRIEFING_AT, null)) ?: NudgeSettings.DEFAULT_BRIEFING,
        maxPerDay = prefs.getInt(MAX_PER_DAY, NudgeSettings.DEFAULT_MAX_PER_DAY),
    )

    companion object {
        const val FILE = "nudges"
        const val BRIEFING_AT = "briefing_at"
        const val MAX_PER_DAY = "max_per_day"
        const val LOCATION_HINT_SEEN = "location_hint_seen"

        private fun onKey(kind: NudgeKind) = "on_" + kind.name.lowercase()
    }
}

/** "07:30" ↔ LocalTime. */
object NudgeTimes {
    fun format(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

    fun parse(s: String?): LocalTime? = s?.split(":")?.takeIf { it.size == 2 }?.let { (h, m) ->
        runCatching { LocalTime.of(h.trim().toInt(), m.trim().toInt()) }.getOrNull()
    }
}
