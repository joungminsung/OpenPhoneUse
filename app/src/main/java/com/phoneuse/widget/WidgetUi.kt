package com.phoneuse.widget

import android.os.Build
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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.phoneuse.R
import com.phoneuse.i18n.tr
import java.time.Instant
import java.time.ZoneId

/**
 * The widgets' look: Material 3 with the wallpaper's dynamic colours on Android 12+, and the app's own palette
 * (warm off-white, ink text, the orange accent) on Android 11. Light and dark follow the system.
 */
object WidgetTheme {
    private val light = lightColorScheme(
        primary = Color(0xFFB8471F), onPrimary = Color.White,
        primaryContainer = Color(0xFFFCEDE7), onPrimaryContainer = Color(0xFF5A1E08),
        secondary = Color(0xFF5F605A), onSecondary = Color.White,
        secondaryContainer = Color(0xFFECECE8), onSecondaryContainer = Color(0xFF171816),
        tertiary = Color(0xFF2F6BFF), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFEEF4FF), onTertiaryContainer = Color(0xFF1D3F8F),
        background = Color(0xFFF7F7F4), onBackground = Color(0xFF171816),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF171816),
        surfaceVariant = Color(0xFFF2F2EF), onSurfaceVariant = Color(0xFF5F605A),
        outline = Color(0xFFDADAD4), error = Color(0xFFB42318),
    )
    private val dark = darkColorScheme(
        primary = Color(0xFFFFB59A), onPrimary = Color(0xFF3A1206),
        primaryContainer = Color(0xFF3A2219), onPrimaryContainer = Color(0xFFFFDBCF),
        secondary = Color(0xFF9BA3AF), onSecondary = Color(0xFF0E1013),
        secondaryContainer = Color(0xFF252B36), onSecondaryContainer = Color(0xFFECEEF2),
        tertiary = Color(0xFF8EB0FF), onTertiary = Color(0xFF0E1A3A),
        tertiaryContainer = Color(0xFF15213A), onTertiaryContainer = Color(0xFFA9C4FF),
        background = Color(0xFF14171C), onBackground = Color(0xFFECEEF2),
        surface = Color(0xFF171A1F), onSurface = Color(0xFFECEEF2),
        surfaceVariant = Color(0xFF1E2228), onSurfaceVariant = Color(0xFF9BA3AF),
        outline = Color(0xFF2A2F37), error = Color(0xFFFF8A7A),
    )

    val fallback: ColorProviders = androidx.glance.material3.ColorProviders(light = light, dark = dark)

    @Composable
    fun colors(): ColorProviders = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else fallback
}

/** Every widget's root: the theme, a rounded background that fills the cell, and padding. */
@Composable
fun WidgetRoot(padding: Dp = 14.dp, onClick: Action? = null, content: @Composable ColumnScope.() -> Unit) {
    GlanceTheme(colors = WidgetTheme.colors()) {
        var m = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(24.dp).background(GlanceTheme.colors.background)
        if (onClick != null) m = m.clickable(onClick)
        Column(m.padding(padding), content = content)
    }
}

/** A small round icon tinted with [tint]. */
@Composable
fun WidgetIcon(res: Int, tint: ColorProvider, size: Dp = 18.dp, description: String? = null) {
    Image(ImageProvider(res), contentDescription = description, colorFilter = ColorFilter.tint(tint), modifier = GlanceModifier.size(size))
}

/** The header line: an accent glyph, a title, and on the right an optional stamp ("5 min ago"). */
@Composable
fun WidgetHeader(icon: Int, title: String, stamp: String? = null) {
    Row(GlanceModifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            GlanceModifier.size(26.dp).cornerRadius(13.dp).background(GlanceTheme.colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { WidgetIcon(icon, GlanceTheme.colors.onPrimaryContainer, 16.dp) }
        Spacer(GlanceModifier.width(8.dp))
        Text(title, style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        if (stamp != null) {
            Text(stamp, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp), maxLines = 1)
        }
    }
}

/** A pill: a tonal chip with an optional glyph, used for suggestions and buttons. */
@Composable
fun WidgetPill(
    text: String,
    onClick: Action,
    modifier: GlanceModifier = GlanceModifier,
    icon: Int? = null,
    strong: Boolean = false,
) {
    val bg = if (strong) GlanceTheme.colors.primary else GlanceTheme.colors.secondaryContainer
    val fg = if (strong) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSecondaryContainer
    Row(
        modifier.height(36.dp).cornerRadius(18.dp).background(bg).clickable(onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            WidgetIcon(icon, fg, 16.dp)
            Spacer(GlanceModifier.width(6.dp))
        }
        Text(text, style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/** Secondary text. */
@Composable
fun WidgetSub(text: String, maxLines: Int = 1, modifier: GlanceModifier = GlanceModifier) {
    Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = maxLines, modifier = modifier)
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
