package com.ftapps.remft.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()

val RemftTypography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.W700, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.W700, letterSpacing = (-0.4).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.W700),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.W600),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.W600),
    bodyLarge = base.bodyLarge.copy(lineHeight = (base.bodyLarge.fontSize.value * 1.5f).sp),
)

val RemftShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.chip),
    small = RoundedCornerShape(Radius.chip),
    medium = RoundedCornerShape(Radius.input),
    large = RoundedCornerShape(Radius.card),
    extraLarge = RoundedCornerShape(Radius.sheet),
)

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)

@Composable
fun RemftTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val extras = if (darkTheme) DarkExtras else LightExtras
    CompositionLocalProvider(LocalRemftColors provides extras) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = RemftTypography,
            shapes = RemftShapes,
            content = content,
        )
    }
}

object RemftThemeExtras {
    val colors: RemftExtraColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRemftColors.current
}
