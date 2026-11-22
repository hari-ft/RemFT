package com.ftapps.remft.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.ftapps.remft.ui.theme.RemftThemeExtras

@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = RemftThemeExtras.colors
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(colors.auroraTop, colors.auroraBottom), start = Offset.Zero, end = Offset.Infinite)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val big = maxOf(w, h)
            drawCircle(
                Brush.radialGradient(listOf(colors.blobA, Color.Transparent), center = Offset(w * 0.1f, h * 0.08f), radius = big * 0.55f),
                radius = big * 0.55f,
                center = Offset(w * 0.1f, h * 0.08f),
            )
            drawCircle(
                Brush.radialGradient(listOf(colors.blobB, Color.Transparent), center = Offset(w * 0.95f, h * 0.35f), radius = big * 0.5f),
                radius = big * 0.5f,
                center = Offset(w * 0.95f, h * 0.35f),
            )
            drawCircle(
                Brush.radialGradient(listOf(colors.blobC, Color.Transparent), center = Offset(w * 0.3f, h * 0.95f), radius = big * 0.5f),
                radius = big * 0.5f,
                center = Offset(w * 0.3f, h * 0.95f),
            )
            drawRect(colors.veil)
        }
        content()
    }
}
