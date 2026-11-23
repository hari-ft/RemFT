package com.ftapps.remft.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
    val priority: PriorityColors,
    val code: CodeColors,
)

val LightExtras = RemftExtraColors(
    success = LightSemantic.success.color,
    warning = LightSemantic.warning.color,
    link = Brand.Lapis,
    hairline = Color(0x1F161A2E),
    glassTint = Color(0xF2FFFFFF),
    glassStroke = Color(0xFFE3E6F2),
    glassHighlight = Color(0x00FFFFFF),
    glassShadow = Color(0x332B3FD9),
    priority = PriorityColors(
        low = LightSemantic.file.color,
        medium = LightSemantic.warning.color,
        high = LightSemantic.overdue.color,
        urgent = LightSemantic.urgent.color,
    ),
    code = CodeColors(
        keyword = Color(0xFF6B3FC4), string = Color(0xFF24803A), comment = Color(0xFF5E647D),
        number = Color(0xFFA84E00), name = Brand.Lapis, meta = Color(0xFF0B7285),
    ),
)

val DarkExtras = RemftExtraColors(
    success = DarkSemantic.success.color,
    warning = DarkSemantic.warning.color,
    link = Brand.Periwinkle,
    hairline = Color(0x29E8EAF7),
    glassTint = Color(0xF2151830),
    glassStroke = Color(0xFF262B52),
    glassHighlight = Color(0x00FFFFFF),
    glassShadow = Color(0x8A02040C),
    priority = PriorityColors(
        low = DarkSemantic.file.color,
        medium = DarkSemantic.warning.color,
        high = DarkSemantic.overdue.color,
        urgent = DarkSemantic.urgent.color,
    ),
    code = CodeColors(
        keyword = Color(0xFFC9B3FF), string = Color(0xFF8ADB9A), comment = Color(0xFFA6ACC8),
        number = Color(0xFFFFB077), name = Brand.Periwinkle, meta = Color(0xFF7FD3E6),
    ),
)

val LocalRemftColors = staticCompositionLocalOf { LightExtras }

object Space {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 10.dp
    val x4 = 14.dp
    val x5 = 18.dp
    val x6 = 18.dp
    val x8 = 24.dp
    val x12 = 36.dp
}

object Radius {
    val chip = 12.dp
    val input = 16.dp
    val card = 16.dp
    val sheet = 24.dp
    val capsule = 32.dp
    val tightSmall = 4.dp
    val tightMedium = 6.dp
    val tightLarge = 8.dp
    val small = 12.dp
    val fab = 16.dp
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

object Sizes {
    val touchTarget = 48.dp
    val navBarHeight = 58.dp
    val fab = 48.dp
    val checkbox = 20.dp
    val listRow = 52.dp
    val listRowSingle = 48.dp
    val settingsRow = 50.dp
    val chip = 22.dp
    val button = 36.dp
    val iconButton = 36.dp
    val topBar = 52.dp
    val nameMarkBar = 52.dp
    val quickAdd = 46.dp
    val weekTile = 46.dp
    val icon = 24.dp
    val iconSmall = 18.dp
    val iconNav = 20.dp
    val iconChip = 14.dp
    val avatar = 32.dp
    val hairline = 0.5.dp
    val stroke = 1.5.dp
    val emptyIllustration = 96.dp
    val emptyBadge = 48.dp
}
