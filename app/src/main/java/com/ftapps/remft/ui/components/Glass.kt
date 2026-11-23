package com.ftapps.remft.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.ftapps.remft.ui.theme.RemftThemeExtras
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    floating: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = RemftThemeExtras.colors
    val haze = LocalHazeState.current
    val fill = Brush.verticalGradient(
        listOf(colors.glassHighlight, colors.glassTint, colors.glassTint.copy(alpha = colors.glassTint.alpha * 0.4f)),
    )
    val stroke = Brush.verticalGradient(listOf(colors.glassStroke, colors.glassStroke.copy(alpha = colors.glassStroke.alpha * 0.3f)))
    var surface = modifier
    if (floating) {
        surface = surface
            .shadow(14.dp, shape, ambientColor = colors.glassShadow, spotColor = colors.glassShadow)
            .shadow(4.dp, shape, ambientColor = colors.glassShadow, spotColor = colors.glassShadow)
    }
    surface = surface.clip(shape)
    if (haze != null) {
        surface = surface.hazeEffect(
            state = haze,
            style = HazeStyle(
                backgroundColor = colors.glassTint,
                tints = listOf(HazeTint(colors.glassTint)),
                blurRadius = 22.dp,
                noiseFactor = 0f,
                fallbackTint = HazeTint(colors.glassTint.copy(alpha = 0.94f)),
            ),
        )
    }
    surface = surface
        .drawBehind { drawRect(fill) }
        .border(1.dp, stroke, shape)
    Box(surface, content = content)
}
