package com.phoneuse.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.phoneuse.R
import com.phoneuse.app.ThemeMode

/** Design tokens for the light and dark themes. */
@Immutable
data class PuColors(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val line: Color,
    val lineSoft: Color,
    val inputBorder: Color,
    val text: Color,
    val textSub: Color,
    /** Secondary body text (step lines, card bodies). */
    val text2: Color,
    val bubble: Color,
    /** Fill of primary buttons, send/stop and switches: the text colour. */
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val accentText: Color,
    val accentSoft: Color,
    val accentBorder: Color,
    val danger: Color,
    val dangerSoft: Color,
    val dangerBorder: Color,
    val money: Color,
    val moneySoft: Color,
    val moneyBorder: Color,
    val secure: Color,
    val secureSoft: Color,
    val secureBorder: Color,
    val success: Color,
    /** Sunken boxes inside cards (file lists, quoted content). */
    val sunken: Color,
    val chip: Color,
    val segmentBg: Color,
    val segmentOn: Color,
    val switchOff: Color,
    val radioOff: Color,
    val scrim: Color,
    val glow: Color,
    val agentDot: Color,
    val liveBg: Color,
    val liveBorder: Color,
    val liveText: Color,
    /** Memory purple: used-memory chips, the "기억했어요" line. */
    val memory: Color,
    val memorySoft: Color,
    val memoryText: Color,
    /** Background of a memory the app is not sure about ("맞나요?"). */
    val guessBg: Color,
)

val LightPu = PuColors(
    dark = false,
    bg = Color(0xFFF2F2EF),
    surface = Color(0xFFFFFFFF),
    line = Color(0xFFE3E3DE),
    lineSoft = Color(0xFFECECE8),
    inputBorder = Color(0xFFDADAD4),
    text = Color(0xFF171816),
    textSub = Color(0xFF5F605A),
    text2 = Color(0xFF3B3C37),
    bubble = Color(0xFFE6E6E0),
    primary = Color(0xFF171816),
    onPrimary = Color(0xFFFFFFFF),
    accent = Color(0xFFE4572E),
    accentText = Color(0xFFB23C14),
    accentSoft = Color(0xFFFCEDE7),
    accentBorder = Color(0xFFF3CDBE),
    danger = Color(0xFFB42318),
    dangerSoft = Color(0xFFFEF0EE),
    dangerBorder = Color(0xFFF3C9C3),
    money = Color(0xFFB54708),
    moneySoft = Color(0xFFFFF4E5),
    moneyBorder = Color(0xFFF5D4A6),
    secure = Color(0xFF5B3FB8),
    secureSoft = Color(0xFFF1EEFB),
    secureBorder = Color(0xFFD9D1F3),
    success = Color(0xFF1D7A4C),
    sunken = Color(0xFFF2F2EF),
    chip = Color(0xFFF7F7F4),
    segmentBg = Color(0xFFECECE8),
    segmentOn = Color(0xFFFFFFFF),
    switchOff = Color(0xFFC9C9C2),
    radioOff = Color(0xFFA5A69F),
    scrim = Color(0x5214161A),
    glow = Color(0xFF8EC5FF),
    agentDot = Color(0xFF2F6BFF),
    liveBg = Color(0xFFEEF4FF),
    liveBorder = Color(0xFFCFE0FF),
    liveText = Color(0xFF1D3F8F),
    memory = Color(0xFF4A3796),
    memorySoft = Color(0xFFECE9F7),
    memoryText = Color(0xFF2E2266),
    guessBg = Color(0xFFFFF8F6),
)

val DarkPu = PuColors(
    dark = true,
    bg = Color(0xFF0E1013),
    surface = Color(0xFF171A1F),
    line = Color(0xFF2A2F37),
    lineSoft = Color(0xFF242932),
    inputBorder = Color(0xFF2A2F37),
    text = Color(0xFFECEEF2),
    textSub = Color(0xFF9BA3AF),
    text2 = Color(0xFFC3C9D2),
    bubble = Color(0xFF252B36),
    primary = Color(0xFFECEEF2),
    onPrimary = Color(0xFF0E1013),
    accent = Color(0xFFE4572E),
    accentText = Color(0xFFFFB59A),
    accentSoft = Color(0xFF3A2219),
    accentBorder = Color(0xFF5A3324),
    danger = Color(0xFFFF8A7A),
    dangerSoft = Color(0xFF3A1B18),
    dangerBorder = Color(0xFF5C2A24),
    money = Color(0xFFFFB55C),
    moneySoft = Color(0xFF3A2A14),
    moneyBorder = Color(0xFF5A4019),
    secure = Color(0xFFB9A6FF),
    secureSoft = Color(0xFF262040),
    secureBorder = Color(0xFF3B3263),
    success = Color(0xFF4CC38A),
    sunken = Color(0xFF1F232A),
    chip = Color(0xFF1E2228),
    segmentBg = Color(0xFF22262D),
    segmentOn = Color(0xFF353B45),
    switchOff = Color(0xFF3A404A),
    radioOff = Color(0xFF5C636E),
    scrim = Color(0x8C000000),
    glow = Color(0xFF8EC5FF),
    agentDot = Color(0xFF5B8CFF),
    liveBg = Color(0xFF15213A),
    liveBorder = Color(0xFF24385F),
    liveText = Color(0xFFA9C4FF),
    memory = Color(0xFFC3B5FF),
    memorySoft = Color(0xFF272142),
    memoryText = Color(0xFFDCD3FF),
    guessBg = Color(0xFF2A201C),
)

val LocalPu = staticCompositionLocalOf { LightPu }

/** Shorthand for the current palette. */
val pu: PuColors
    @Composable get() = LocalPu.current

private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

/** IBM Plex Sans KR, downloaded on demand; text falls back to the system sans-serif until (or unless) it loads. */
val PlexSans = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
        Font(GoogleFont("IBM Plex Sans KR"), fontProvider, w)
    },
)

/** IBM Plex Mono for times, distances and counts. */
val PlexMono = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold).map { w ->
        Font(GoogleFont("IBM Plex Mono"), fontProvider, w)
    },
)

/** Tabular figures, as the boards' `.mono` class. */
val MonoStyle = TextStyle(fontFamily = PlexMono, fontFeatureSettings = "tnum")

private val BaseText = TextStyle(fontFamily = PlexSans, fontSize = 15.sp, lineHeight = 24.sp)

private val PuTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = PlexSans),
        displayMedium = t.displayMedium.copy(fontFamily = PlexSans),
        displaySmall = t.displaySmall.copy(fontFamily = PlexSans),
        headlineLarge = t.headlineLarge.copy(fontFamily = PlexSans),
        headlineMedium = t.headlineMedium.copy(fontFamily = PlexSans),
        headlineSmall = t.headlineSmall.copy(fontFamily = PlexSans),
        titleLarge = t.titleLarge.copy(fontFamily = PlexSans),
        titleMedium = t.titleMedium.copy(fontFamily = PlexSans),
        titleSmall = t.titleSmall.copy(fontFamily = PlexSans),
        bodyLarge = BaseText,
        bodyMedium = BaseText,
        bodySmall = t.bodySmall.copy(fontFamily = PlexSans),
        labelLarge = t.labelLarge.copy(fontFamily = PlexSans),
        labelMedium = t.labelMedium.copy(fontFamily = PlexSans),
        labelSmall = t.labelSmall.copy(fontFamily = PlexSans),
    )
}

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** [fontScale]: Settings "글자 크기", on top of the system font size. */
@Composable
fun PhoneUseTheme(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val c = if (dark) DarkPu else LightPu
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, onSurfaceVariant = c.textSub, outline = c.inputBorder,
            outlineVariant = c.line, error = c.danger, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface, scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, onSurfaceVariant = c.textSub, outline = c.inputBorder,
            outlineVariant = c.line, error = c.danger, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface, scrim = Color.Black,
        )
    }
    val selection = TextSelectionColors(handleColor = c.accent, backgroundColor = c.accent.copy(alpha = 0.3f))
    MaterialTheme(colorScheme = scheme, typography = PuTypography) {
        CompositionLocalProvider(
            LocalPu provides c,
            androidx.compose.foundation.text.selection.LocalTextSelectionColors provides selection,
        ) {
            if (fontScale == 1f) {
                content()
            } else {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * fontScale), content = content)
            }
        }
    }
}
