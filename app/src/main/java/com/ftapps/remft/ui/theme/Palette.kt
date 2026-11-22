package com.ftapps.remft.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

object Brand {
    val Lapis = Color(0xFF2B3FD9)
    val Violet = Color(0xFF5B4BE6)
    val Marigold = Color(0xFFF2A000)
    val MarigoldLight = Color(0xFFFFC24D)
    val MarigoldPale = Color(0xFFFFE2AE)
    val MarigoldInk = Color(0xFF8A5300)
    val TodayInk = Color(0xFF3D2500)
    val Hibiscus = Color(0xFFC81E55)
    val HibiscusDeep = Color(0xFFA3164A)
    val Periwinkle = Color(0xFFB3BCFF)
    val Ink = Color(0xFF161A2E)
    val InkSoft = Color(0xFF4A5068)
    val NightInk = Color(0xFF0E1020)
    val AlarmIndigo = Color(0xFF2A1F8F)
    val AlarmHibiscus = Color(0xFFB0245F)
}

val LightColors: ColorScheme = lightColorScheme(
    primary = Brand.Lapis, onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE2FF), onPrimaryContainer = Color(0xFF0F1C7A),
    secondary = Brand.MarigoldInk, onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Brand.MarigoldPale, onSecondaryContainer = Color(0xFF3D2500),
    tertiary = Color(0xFF0B7285), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCEBF2), onTertiaryContainer = Color(0xFF063A45),
    error = Color(0xFFB3361A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD1), onErrorContainer = Color(0xFF4F1205),
    background = Color(0xFFF5F6FB), onBackground = Brand.Ink,
    surface = Color(0xFFFFFFFF), onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFECEEF7), onSurfaceVariant = Brand.InkSoft,
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF9FAFD),
    surfaceContainer = Color(0xFFECEEF7), surfaceContainerHigh = Color(0xFFE3E6F2),
    surfaceContainerHighest = Color(0xFFDADEEC),
    surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFDADEEC),
    outline = Color(0xFF747A93), outlineVariant = Color(0xFFD3D7E6),
    inverseSurface = Color(0xFF2A2F47), inverseOnSurface = Color(0xFFEEF0FA), inversePrimary = Brand.Periwinkle,
    surfaceTint = Brand.Lapis, scrim = Color(0xFF000000),
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = Brand.Periwinkle, onPrimary = Color(0xFF0D1A78),
    primaryContainer = Color(0xFF2A369C), onPrimaryContainer = Color(0xFFE0E4FF),
    secondary = Brand.MarigoldLight, onSecondary = Color(0xFF3A2600),
    secondaryContainer = Color(0xFF5E3A00), onSecondaryContainer = Brand.MarigoldPale,
    tertiary = Color(0xFF7FD3E6), onTertiary = Color(0xFF063A45),
    tertiaryContainer = Color(0xFF0A4C5A), onTertiaryContainer = Color(0xFFCCEBF2),
    error = Color(0xFFFFA28C), onError = Color(0xFF4F1205),
    errorContainer = Color(0xFF7A2410), onErrorContainer = Color(0xFFFFDAD1),
    background = Brand.NightInk, onBackground = Color(0xFFE8EAF7),
    surface = Color(0xFF151830), onSurface = Color(0xFFE8EAF7),
    surfaceVariant = Color(0xFF1C2040), onSurfaceVariant = Color(0xFFB1B6D1),
    surfaceContainerLowest = Color(0xFF0B0D1A), surfaceContainerLow = Color(0xFF151830),
    surfaceContainer = Color(0xFF1C2040), surfaceContainerHigh = Color(0xFF262B52),
    surfaceContainerHighest = Color(0xFF30365F),
    surfaceBright = Color(0xFF30365F), surfaceDim = Brand.NightInk,
    outline = Color(0xFF8087A8), outlineVariant = Color(0xFF353B63),
    inverseSurface = Color(0xFFE8EAF7), inverseOnSurface = Color(0xFF1C2040), inversePrimary = Brand.Lapis,
    surfaceTint = Brand.Periwinkle, scrim = Color(0xFF000000),
)

@Immutable
data class SemanticColor(val color: Color, val onColor: Color, val container: Color, val onContainer: Color)

@Immutable
data class RemftSemanticColors(
    val urgent: SemanticColor,
    val overdue: SemanticColor,
    val today: SemanticColor,
    val done: SemanticColor,
    val repeat: SemanticColor,
    val note: SemanticColor,
    val file: SemanticColor,
    val success: SemanticColor,
    val warning: SemanticColor,
)

private val LightOn = Color(0xFFFFFFFF)
private val DarkOn = Brand.NightInk

val LightSemantic = RemftSemanticColors(
    urgent = SemanticColor(Color(0xFFC81E55), LightOn, Color(0xFFFFD9E2), Color(0xFF5C0A26)),
    overdue = SemanticColor(Color(0xFFB3361A), LightOn, Color(0xFFFFDAD1), Color(0xFF4F1205)),
    today = SemanticColor(Color(0xFF8A5300), LightOn, Color(0xFFFFE2AE), Color(0xFF3D2500)),
    done = SemanticColor(Color(0xFF3F6E5E), LightOn, Color(0xFFD7EAE3), Color(0xFF153328)),
    repeat = SemanticColor(Color(0xFF0B7285), LightOn, Color(0xFFCCEBF2), Color(0xFF063A45)),
    note = SemanticColor(Color(0xFF6B3FC4), LightOn, Color(0xFFE7DDFB), Color(0xFF2E1466)),
    file = SemanticColor(Color(0xFF3F5A8A), LightOn, Color(0xFFDCE5F5), Color(0xFF1B2C4A)),
    success = SemanticColor(Color(0xFF24803A), LightOn, Color(0xFFD3F0D6), Color(0xFF0C3A16)),
    warning = SemanticColor(Color(0xFFA84E00), LightOn, Color(0xFFFFDDC2), Color(0xFF4A2000)),
)

val DarkSemantic = RemftSemanticColors(
    urgent = SemanticColor(Color(0xFFFF8FAB), DarkOn, Color(0xFF7A1238), Color(0xFFFFD9E2)),
    overdue = SemanticColor(Color(0xFFFFA28C), DarkOn, Color(0xFF7A2410), Color(0xFFFFDAD1)),
    today = SemanticColor(Color(0xFFFFC24D), DarkOn, Color(0xFF5E3A00), Color(0xFFFFE2AE)),
    done = SemanticColor(Color(0xFF9CCFBB), DarkOn, Color(0xFF234B3E), Color(0xFFD7EAE3)),
    repeat = SemanticColor(Color(0xFF7FD3E6), DarkOn, Color(0xFF0A4C5A), Color(0xFFCCEBF2)),
    note = SemanticColor(Color(0xFFC9B3FF), DarkOn, Color(0xFF47288F), Color(0xFFE7DDFB)),
    file = SemanticColor(Color(0xFFA9C0E8), DarkOn, Color(0xFF2C4168), Color(0xFFDCE5F5)),
    success = SemanticColor(Color(0xFF8ADB9A), DarkOn, Color(0xFF17552A), Color(0xFFD3F0D6)),
    warning = SemanticColor(Color(0xFFFFB077), DarkOn, Color(0xFF733600), Color(0xFFFFDDC2)),
)

@Immutable
data class TonalLevels(val level0: Color, val level1: Color, val level2: Color, val level3: Color)

val LightTonal = TonalLevels(Color(0xFFF5F6FB), Color(0xFFFFFFFF), Color(0xFFECEEF7), Color(0xFFE3E6F2))
val DarkTonal = TonalLevels(Brand.NightInk, Color(0xFF151830), Color(0xFF1C2040), Color(0xFF262B52))
