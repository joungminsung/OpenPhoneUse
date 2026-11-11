package com.phoneuse.widget

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.phoneuse.R
import com.phoneuse.i18n.Lang
import com.phoneuse.i18n.tr
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle as FormatStyle
import java.util.Locale

/**
 * The widgets' look: one fixed palette on every Android version (a white card, ink text and buttons, light grey
 * fills and a single orange accent) rather than the wallpaper's dynamic colours, which could bring in blue. Light
 * and dark follow the system.
 */
object WidgetTheme {
    private val ACCENT = Color(0xFFE4572E)

    private val light = lightColorScheme(
        primary = ACCENT, onPrimary = Color.White,
        primaryContainer = Color(0xFFFCEDE7), onPrimaryContainer = Color(0xFFB23C14),
        secondary = Color(0xFF5F605A), onSecondary = Color.White,
        secondaryContainer = Color(0xFFECECE8), onSecondaryContainer = Color(0xFF171816),
        // One accent only: tertiary repeats it so nothing falls back to Material's default purple.
        tertiary = ACCENT, onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFCEDE7), onTertiaryContainer = Color(0xFFB23C14),
        background = Color(0xFFFFFFFF), onBackground = Color(0xFF171816),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF171816),
        surfaceVariant = Color(0xFFF2F2EF), onSurfaceVariant = Color(0xFF5F605A),
        inverseSurface = Color(0xFF171816), inverseOnSurface = Color(0xFFFFFFFF), inversePrimary = ACCENT, surfaceTint = ACCENT,
        outline = Color(0xFFE3E3DE), error = Color(0xFFB42318),
    )
    private val dark = darkColorScheme(
        primary = ACCENT, onPrimary = Color.White,
        primaryContainer = Color(0xFF3A2219), onPrimaryContainer = Color(0xFFFFB59A),
        secondary = Color(0xFF9BA3AF), onSecondary = Color(0xFF0E1013),
        secondaryContainer = Color(0xFF22262D), onSecondaryContainer = Color(0xFFECEEF2),
        tertiary = ACCENT, onTertiary = Color.White,
        tertiaryContainer = Color(0xFF3A2219), onTertiaryContainer = Color(0xFFFFB59A),
        background = Color(0xFF171A1F), onBackground = Color(0xFFECEEF2),
        surface = Color(0xFF171A1F), onSurface = Color(0xFFECEEF2),
        surfaceVariant = Color(0xFF1E2228), onSurfaceVariant = Color(0xFF9BA3AF),
        inverseSurface = Color(0xFFECEEF2), inverseOnSurface = Color(0xFF171A1F), inversePrimary = ACCENT, surfaceTint = ACCENT,
        outline = Color(0xFF2A2F37), error = Color(0xFFFF8A7A),
    )

    val fallback: ColorProviders = androidx.glance.material3.ColorProviders(light = light, dark = dark)

    @Composable
    fun colors(): ColorProviders = fallback
}

/**
 * Every widget's root: the theme and a card with large rounded corners (no border, no shadow) that fills the cell.
 * Colour roles across the widgets: background is the card, surfaceVariant a tile or secondary button,
 * secondaryContainer a track, onSurface ink (text and primary buttons), onSurfaceVariant secondary text, primary the
 * accent, primaryContainer with onPrimaryContainer an "on" state, error the Stop label.
 */
@Composable
fun WidgetRoot(padding: Dp = 16.dp, radius: Dp = 28.dp, onClick: Action? = null, content: @Composable ColumnScope.() -> Unit) {
    GlanceTheme(colors = WidgetTheme.colors()) {
        var m = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(radius).background(GlanceTheme.colors.background)
        if (onClick != null) m = m.clickable(onClick)
        Column(m.padding(padding), content = content)
    }
}

/** A small icon tinted with [tint]. */
@Composable
fun WidgetIcon(res: Int, tint: ColorProvider, size: Dp = 18.dp, description: String? = null) {
    Image(ImageProvider(res), contentDescription = description, colorFilter = ColorFilter.tint(tint), modifier = GlanceModifier.size(size))
}

/** The small grey section label at the top of a widget ("Routines"), with an optional note on the right ("2 on"). */
@Composable
fun WidgetLabel(title: String, note: String? = null) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        if (note != null) {
            Text(note, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
    }
}

/**
 * A 44dp pill button. A plain one sits on the grey fill with ink text and an accent glyph; a [strong] one is ink
 * with card-coloured text; a [danger] one (Stop) has the danger colour on the darker fill.
 */
@Composable
fun WidgetPill(
    text: String,
    onClick: Action,
    modifier: GlanceModifier = GlanceModifier,
    icon: Int? = null,
    strong: Boolean = false,
    danger: Boolean = false,
) {
    val bg = when {
        strong -> GlanceTheme.colors.onSurface
        danger -> GlanceTheme.colors.secondaryContainer
        else -> GlanceTheme.colors.surfaceVariant
    }
    val fg = when {
        strong -> GlanceTheme.colors.surface
        danger -> GlanceTheme.colors.error
        else -> GlanceTheme.colors.onSurface
    }
    Row(
        modifier.height(44.dp).cornerRadius(22.dp).background(bg).clickable(onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            WidgetIcon(icon, if (strong) fg else GlanceTheme.colors.primary, 16.dp)
            Spacer(GlanceModifier.width(6.dp))
        }
        Text(text, style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/** A round icon button, 44dp or more: grey fill with an ink glyph, or ink with a card-coloured glyph when [strong]. */
@Composable
fun WidgetRoundButton(icon: Int, onClick: Action, description: String, size: Dp = 44.dp, glyph: Dp = 18.dp, strong: Boolean = false) {
    Box(
        GlanceModifier.size(size).cornerRadius(size / 2)
            .background(if (strong) GlanceTheme.colors.onSurface else GlanceTheme.colors.surfaceVariant)
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) { WidgetIcon(icon, if (strong) GlanceTheme.colors.surface else GlanceTheme.colors.onSurface, glyph, description) }
}

/** The accent dot that says the AI is working. */
@Composable
fun WidgetDot(size: Dp = 9.dp) = WidgetIcon(R.drawable.ic_widget_dot, GlanceTheme.colors.primary, size)

/** Secondary text. */
@Composable
fun WidgetSub(text: String, maxLines: Int = 1, modifier: GlanceModifier = GlanceModifier, size: Float = 13f) {
    Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = size.sp), maxLines = maxLines, modifier = modifier)
}

/** Times and dates. Glance cannot load the app's fonts, so the system monospace stands in for the design's mono. */
@Composable
fun WidgetMono(text: String, color: ColorProvider, size: Float, modifier: GlanceModifier = GlanceModifier) {
    Text(text, style = TextStyle(color = color, fontSize = size.sp, fontFamily = FontFamily.Monospace), maxLines = 1, modifier = modifier)
}

@Composable
fun VSpace(h: Dp) = Spacer(GlanceModifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(GlanceModifier.width(w))

/** Widget wording that does not depend on Glance; pure so it is testable. */
object WidgetText {
    private const val MINUTE = 60_000L

    /** "Just now", "5 min ago", "2 h ago", or the clock time for anything older than a day; "" for never. */
    fun ago(now: Long, at: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (at <= 0L) return ""
        val d = (now - at).coerceAtLeast(0L)
        return when {
            d < MINUTE -> tr("Just now", "방금")
            d < 60 * MINUTE -> tr("${d / MINUTE} min ago", "${d / MINUTE}분 전")
            d < 24 * 60 * MINUTE -> tr("${d / (60 * MINUTE)} h ago", "${d / (60 * MINUTE)}시간 전")
            else -> Instant.ofEpochMilli(at).atZone(zone).let { "%d/%d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }
        }
    }

    /** The greeting over the brief: by the hour. */
    fun greeting(hour: Int): String = when (hour) {
        in 5..10 -> tr("Good morning", "좋은 아침이에요")
        in 11..16 -> tr("Good afternoon", "좋은 오후예요")
        in 17..21 -> tr("Good evening", "좋은 저녁이에요")
        else -> tr("Tonight", "오늘 밤")
    }

    /** The brief's date line: "11월 11일 화" or "Tue, Nov 11". */
    fun date(now: ZonedDateTime): String = if (Lang.korean) {
        "${now.monthValue}월 ${now.dayOfMonth}일 ${now.dayOfWeek.getDisplayName(FormatStyle.SHORT, Locale.KOREAN)}"
    } else {
        "${now.dayOfWeek.getDisplayName(FormatStyle.SHORT, Locale.ENGLISH)}, ${now.month.getDisplayName(FormatStyle.SHORT, Locale.ENGLISH)} ${now.dayOfMonth}"
    }

    val appName: String get() = "OpenPhoneUse"
}

/** The glyph drawable of a tile icon. */
fun TileIcon.drawable(): Int = when (this) {
    TileIcon.BOLT -> R.drawable.ic_widget_bolt
    TileIcon.MOON -> R.drawable.ic_widget_moon
    TileIcon.HOME -> R.drawable.ic_widget_home
    TileIcon.BELL -> R.drawable.ic_widget_bell
    TileIcon.CLOCK -> R.drawable.ic_widget_clock
    TileIcon.SPARK -> R.drawable.ic_widget_spark
}
