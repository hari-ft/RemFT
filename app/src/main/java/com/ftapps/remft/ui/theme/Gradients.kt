package com.ftapps.remft.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Immutable
data class GradientStops(val start: Color, val end: Color) {
    val diagonal: Brush get() = Brush.linearGradient(listOf(start, end), start = Offset.Zero, end = Offset.Infinite)
    val horizontal: Brush get() = Brush.horizontalGradient(listOf(start, end))
    val vertical: Brush get() = Brush.verticalGradient(listOf(start, end))
}

@Immutable
data class RemftGradients(
    val brand: GradientStops,
    val today: GradientStops,
    val urgent: GradientStops,
    val alarmRing: GradientStops,
)

val LightGradients = RemftGradients(
    brand = GradientStops(Brand.Lapis, Brand.Violet),
    today = GradientStops(Brand.MarigoldPale, Brand.MarigoldLight),
    urgent = GradientStops(Brand.Hibiscus, Brand.HibiscusDeep),
    alarmRing = GradientStops(Brand.AlarmIndigo, Brand.AlarmHibiscus),
)

val DarkGradients = LightGradients.copy(
    urgent = GradientStops(Color(0xFF8A1A47), Color(0xFFA81D57)),
)
