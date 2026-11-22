package com.ftapps.remft.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalSemanticColors = staticCompositionLocalOf { LightSemantic }
val LocalTonalLevels = staticCompositionLocalOf { LightTonal }
val LocalGradients = staticCompositionLocalOf { LightGradients }

fun dynamicColorAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun RemftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val brand = if (darkTheme) DarkColors else LightColors
    val scheme: ColorScheme = if (dynamicColor && dynamicColorAvailable()) {
        remember(context, darkTheme) {
            val system = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            system.copy(background = system.surfaceContainerLowest, surface = system.surfaceContainerLow)
        }
    } else {
        brand
    }
    val reduced = rememberReducedMotion()
    val motion = remember(reduced) { RemftMotion(reduced) }
    val tonal = if (dynamicColor && dynamicColorAvailable()) {
        TonalLevels(scheme.background, scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerHigh)
    } else if (darkTheme) {
        DarkTonal
    } else {
        LightTonal
    }
    CompositionLocalProvider(
        LocalRemftColors provides if (darkTheme) DarkExtras else LightExtras,
        LocalSemanticColors provides if (darkTheme) DarkSemantic else LightSemantic,
        LocalTonalLevels provides tonal,
        LocalGradients provides if (darkTheme) DarkGradients else LightGradients,
        LocalRemftMotion provides motion,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = RemftTypography,
            shapes = RemftShapes,
            content = content,
        )
    }
}

object RemftTheme {
    val colors: RemftSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSemanticColors.current

    val tonal: TonalLevels
        @Composable
        @ReadOnlyComposable
        get() = LocalTonalLevels.current

    val gradients: RemftGradients
        @Composable
        @ReadOnlyComposable
        get() = LocalGradients.current

    val motion: RemftMotion
        @Composable
        @ReadOnlyComposable
        get() = LocalRemftMotion.current

    val extras: RemftExtraColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRemftColors.current

    val shapes: MarkedShapes get() = MarkedShapes
}

object RemftThemeExtras {
    val colors: RemftExtraColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRemftColors.current
}
