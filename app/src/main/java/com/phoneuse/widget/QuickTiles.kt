package com.phoneuse.widget

import android.content.Context
import com.phoneuse.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The glyph of a quick-action tile; [wire] is its name in the saved JSON. */
enum class TileIcon(val wire: String) {
    BOLT("bolt"),
    MOON("moon"),
    HOME("home"),
    BELL("bell"),
    CLOCK("clock"),
    SPARK("spark"),
    ;

    companion object {
        fun fromWire(s: String?): TileIcon = entries.firstOrNull { it.wire == s?.trim()?.lowercase() } ?: BOLT
    }
}

/** One tile of the quick-actions widget: [label] is shown, [prompt] is sent as a new task on a tap. */
data class QuickTile(val label: String, val prompt: String, val icon: TileIcon = TileIcon.BOLT)

/**
 * The quick-actions tiles, saved as JSON. Until the user saves their own the defaults are used, in the app's
 * current language. Reading is forgiving: blank or broken entries are dropped, long text is cut, unknown icons
 * become [TileIcon.BOLT]; nothing at all gives the defaults.
 */
object QuickTiles {
    const val MAX_TILES = 6
    const val LABEL_CHARS = 24
    const val PROMPT_CHARS = 300

    @Serializable
    private data class Wire(val label: String = "", val prompt: String = "", val icon: String? = null)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val serializer = ListSerializer(Wire.serializer())

    fun defaults(): List<QuickTile> = listOf(
        QuickTile(tr("Silent 1 hour", "1시간 무음"), tr("Turn on Do Not Disturb for 1 hour", "1시간 동안 방해 금지 모드 켜줘"), TileIcon.MOON),
        QuickTile(tr("Navigate home", "집으로 길안내"), tr("Start navigation home in my map app", "지도 앱으로 집까지 길안내 시작해줘"), TileIcon.HOME),
        QuickTile(tr("Notification digest", "알림 요약"), tr("Summarize my notifications from today", "오늘 온 알림 요약해줘"), TileIcon.BELL),
        QuickTile(tr("Tomorrow", "내일 일정"), tr("What's on my calendar tomorrow?", "내일 일정 알려줘"), TileIcon.CLOCK),
        QuickTile(tr("Alarm 7:00", "7시 알람"), tr("Set an alarm for 7 AM tomorrow", "내일 아침 7시에 알람 맞춰줘"), TileIcon.CLOCK),
        QuickTile(tr("Battery saver", "절전 모드"), tr("Turn on battery saver", "절전 모드 켜줘"), TileIcon.BOLT),
    )

    /** Tiles kept: trimmed, cut to size, without blank prompts, at most [MAX_TILES]. A blank label takes the prompt's start. */
    fun clean(tiles: List<QuickTile>): List<QuickTile> = tiles.mapNotNull { t ->
        val prompt = t.prompt.replace(Regex("\\s+"), " ").trim().take(PROMPT_CHARS)
        if (prompt.isEmpty()) return@mapNotNull null
        val label = t.label.replace(Regex("\\s+"), " ").trim().ifEmpty { prompt }.take(LABEL_CHARS)
        QuickTile(label, prompt, t.icon)
    }.take(MAX_TILES)

    fun encode(tiles: List<QuickTile>): String = json.encodeToString(serializer, clean(tiles).map { Wire(it.label, it.prompt, it.icon.wire) })

    /** The saved tiles, or null when [raw] is missing, unreadable or keeps nothing (the caller uses [defaults]). */
    fun decode(raw: String?): List<QuickTile>? {
        if (raw.isNullOrBlank()) return null
        val wires = try {
            json.decodeFromString(serializer, raw)
        } catch (e: Exception) {
            return null
        }
        return clean(wires.map { QuickTile(it.label, it.prompt, TileIcon.fromWire(it.icon)) }).takeIf { it.isNotEmpty() }
    }
}

/** Where the tiles live: private preferences, shared by every quick-actions widget, observed by them. */
class QuickTileStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _custom = MutableStateFlow(QuickTiles.decode(prefs.getString(KEY, null)))

    /** The user's own tiles, or null while the defaults are in use. */
    val custom: StateFlow<List<QuickTile>?> = _custom.asStateFlow()

    fun tiles(): List<QuickTile> = _custom.value ?: QuickTiles.defaults()

    fun save(tiles: List<QuickTile>) {
        val clean = QuickTiles.clean(tiles)
        if (clean.isEmpty()) return reset()
        prefs.edit().putString(KEY, QuickTiles.encode(clean)).apply()
        _custom.value = clean
    }

    fun reset() {
        prefs.edit().remove(KEY).apply()
        _custom.value = null
    }

    companion object {
        private const val PREFS = "widget_tiles"
        private const val KEY = "tiles"

        @Volatile private var instance: QuickTileStore? = null

        fun get(context: Context): QuickTileStore = instance ?: synchronized(this) {
            instance ?: QuickTileStore(context).also { instance = it }
        }
    }
}
