package com.ftapps.remft.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF0A5CFF), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E5FF), onPrimaryContainer = Color(0xFF0A3FBF),
    secondary = Color(0xFF5A50E0), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E1FF), onSecondaryContainer = Color(0xFF3A32A8),
    tertiary = Color(0xFF0C7D6C), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBFF2E6), onTertiaryContainer = Color(0xFF084F44),
    error = Color(0xFFD70015), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE3E0), onErrorContainer = Color(0xFF99000F),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0F1B2E),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF0F1B2E),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF6F9FF),
    surfaceContainer = Color(0xFFEBF1FC), surfaceContainerHigh = Color(0xFFE7EEFA),
    surfaceContainerHighest = Color(0xFFDEE8F8), surfaceVariant = Color(0xFFE7EEFA),
    onSurfaceVariant = Color(0xFF44536F),
    outline = Color(0xFF63789E), outlineVariant = Color(0xFFC7D3E8),
    inverseSurface = Color(0xFF1D2739), inverseOnSurface = Color(0xFFEFF3FA), inversePrimary = Color(0xFF3E9BFF),
    surfaceTint = Color(0xFF0A5CFF), scrim = Color(0xFF000000),
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF3E9BFF), onPrimary = Color(0xFF04234E),
    primaryContainer = Color(0xFF1D4FA6), onPrimaryContainer = Color(0xFFD9E8FF),
    secondary = Color(0xFFB9AFFF), onSecondary = Color(0xFF241B66),
    secondaryContainer = Color(0xFF3B3583), onSecondaryContainer = Color(0xFFE6E1FF),
    tertiary = Color(0xFF35D6C2), onTertiary = Color(0xFF04352D),
    tertiaryContainer = Color(0xFF0E5B4F), onTertiaryContainer = Color(0xFFBDF6EC),
    error = Color(0xFFFF5147), onError = Color(0xFF450603),
    errorContainer = Color(0xFF7A1F18), onErrorContainer = Color(0xFFFFD9D5),
    surface = Color(0xFF151F3C), onSurface = Color(0xFFEAF0FD),
    background = Color(0xFF151F3C), onBackground = Color(0xFFEAF0FD),
    surfaceContainerLowest = Color(0xFF0E1630), surfaceContainerLow = Color(0xFF121B36),
    surfaceContainer = Color(0xFF182342), surfaceContainerHigh = Color(0xFF1F2C51),
    surfaceContainerHighest = Color(0xFF26345E), surfaceVariant = Color(0xFF1F2C51),
    onSurfaceVariant = Color(0xFFAAB6D6),
    outline = Color(0xFF7186B5), outlineVariant = Color(0xFF37456B),
    inverseSurface = Color(0xFFEAF0FD), inverseOnSurface = Color(0xFF151F3C), inversePrimary = Color(0xFF0A5CFF),
    surfaceTint = Color(0xFF3E9BFF), scrim = Color(0xFF000000),
)

@Immutable
data class PriorityColors(val low: Color, val medium: Color, val high: Color, val urgent: Color)

@Immutable
data class CodeColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val name: Color,
    val meta: Color,
)

@Immutable
data class RemftExtraColors(
    val success: Color,
    val warning: Color,
    val link: Color,
    val hairline: Color,
    val glassTint: Color,
    val glassStroke: Color,
    val glassHighlight: Color,
    val glassShadow: Color,
    val auroraTop: Color,
    val auroraBottom: Color,
    val blobA: Color,
    val blobB: Color,
    val blobC: Color,
    val veil: Color,
    val priority: PriorityColors,
    val code: CodeColors,
)

val LightExtras = RemftExtraColors(
    success = Color(0xFF0D7A33),
    warning = Color(0xFFC77700),
    link = Color(0xFF0B54D0),
    hairline = Color(0x140F1B2E),
    glassTint = Color(0xBDF6F9FF),
    glassStroke = Color(0xA6FFFFFF),
    glassHighlight = Color(0x73FFFFFF),
    glassShadow = Color(0x33203A66),
    auroraTop = Color(0xFFE9F2FF),
    auroraBottom = Color(0xFFF1EBFF),
    blobA = Color(0x4D3D7DFF),
    blobB = Color(0x408E5CFF),
    blobC = Color(0x3800C7B0),
    veil = Color(0x94F5F9FF),
    priority = PriorityColors(Color(0xFF0F9D46), Color(0xFFC77700), Color(0xFFE8500A), Color(0xFFE3261A)),
    code = CodeColors(
        keyword = Color(0xFF8B2FA8), string = Color(0xFF0A6E3D), comment = Color(0xFF5A6782),
        number = Color(0xFF9A4A05), name = Color(0xFF0B54D0), meta = Color(0xFF00636E),
    ),
)

val DarkExtras = RemftExtraColors(
    success = Color(0xFF30D158),
    warning = Color(0xFFFFC400),
    link = Color(0xFF3E9BFF),
    hairline = Color(0x1FEAF0FD),
    glassTint = Color(0xB8111A38),
    glassStroke = Color(0x40FFFFFF),
    glassHighlight = Color(0x24FFFFFF),
    glassShadow = Color(0x8A02040C),
    auroraTop = Color(0xFF0B1233),
    auroraBottom = Color(0xFF070B1F),
    blobA = Color(0x6B3D7DFF),
    blobB = Color(0x598B5CF6),
    blobC = Color(0x4A22C9B4),
    veil = Color(0x7A0A102A),
    priority = PriorityColors(Color(0xFF30D158), Color(0xFFFFC400), Color(0xFFFF8A1E), Color(0xFFFF453A)),
    code = CodeColors(
        keyword = Color(0xFFE5A8FF), string = Color(0xFF7EE8A8), comment = Color(0xFFA8B6D4),
        number = Color(0xFFFFC07A), name = Color(0xFF8FC4FF), meta = Color(0xFF5FE0F0),
    ),
)

val LocalRemftColors = staticCompositionLocalOf { LightExtras }

object Space {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x5 = 20.dp
    val x6 = 24.dp
    val x8 = 32.dp
    val x12 = 48.dp
}

object Radius {
    val chip = 12.dp
    val input = 16.dp
    val card = 20.dp
    val sheet = 28.dp
    val capsule = 32.dp
}

object Motion {
    const val SHORT = 150
    const val MEDIUM = 220
    const val LONG = 320
}

object Breakpoints {
    val rail = 800.dp
    val extendedRail = 1160.dp
    val homeSideCalendar = 720.dp
    val splitView = 900.dp
    val searchDialog = 600.dp
    val settingsMaxWidth = 720.dp
    val detailMaxWidth = 760.dp
    val sheetMaxWidth = 560.dp
}
