package com.ftapps.remft.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ftapps.remft.R

val BricolageGrotesque = FontFamily(
    Font(R.font.bricolage_grotesque_semibold, FontWeight.W600),
    Font(R.font.bricolage_grotesque_bold, FontWeight.W700),
)

val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.W400),
    Font(R.font.instrument_sans_medium, FontWeight.W500),
    Font(R.font.instrument_sans_semibold, FontWeight.W600),
)

val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.W400))

private fun display(size: Int, line: Int, weight: FontWeight = FontWeight.W700, tracking: Double = -0.02) = TextStyle(
    fontFamily = BricolageGrotesque,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

private fun sans(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = InstrumentSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

val RemftTypography = Typography(
    displayLarge = display(96, 96, tracking = -0.04),
    displayMedium = display(57, 64, tracking = -0.03),
    displaySmall = display(45, 52, tracking = -0.03),
    headlineLarge = display(24, 30, FontWeight.W600),
    headlineMedium = display(22, 28, FontWeight.W600),
    headlineSmall = display(20, 26, FontWeight.W600),
    titleLarge = display(18, 24, FontWeight.W600, tracking = -0.01),
    titleMedium = sans(15, 20, FontWeight.W600),
    titleSmall = sans(13, 18, FontWeight.W600),
    bodyLarge = sans(15, 22, FontWeight.W400),
    bodyMedium = sans(13, 18, FontWeight.W400),
    bodySmall = sans(12, 16, FontWeight.W400),
    labelLarge = sans(13, 18, FontWeight.W600),
    labelMedium = sans(12, 16, FontWeight.W500),
    labelSmall = sans(11, 14, FontWeight.W500),
)

val CodeStyle = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 20.sp)

val MonoStyle = TextStyle(fontFamily = JetBrainsMono)

val NameMarkStyle = TextStyle(fontFamily = BricolageGrotesque, fontWeight = FontWeight.W700, fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.02).em)
