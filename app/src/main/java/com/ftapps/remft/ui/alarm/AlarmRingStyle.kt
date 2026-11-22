package com.ftapps.remft.ui.alarm

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.ftapps.remft.ui.theme.Brand
import com.ftapps.remft.ui.theme.LightColors
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.Motion
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import kotlin.math.cos
import kotlin.math.sin

@Immutable
data class AlarmRingColors(
    val content: Color,
    val contentSoft: Color,
    val accent: Color,
    val actionContainer: Color,
    val actionContent: Color,
    val pillContainer: Color,
    val pillBorder: Color,
)

@Composable
fun alarmRingColors(): AlarmRingColors {
    val onRing = LightColors.onPrimary
    return AlarmRingColors(
        content = onRing,
        contentSoft = onRing.copy(alpha = 0.88f),
        accent = Brand.MarigoldLight,
        actionContainer = onRing,
        actionContent = RemftTheme.gradients.alarmRing.start,
        pillContainer = onRing.copy(alpha = 0.14f),
        pillBorder = onRing.copy(alpha = 0.6f),
    )
}

@Composable
fun AlarmOrbitPulse(pulsing: Boolean, modifier: Modifier = Modifier, size: Dp = 128.dp) {
    val colors = alarmRingColors()
    val animate = pulsing && !RemftTheme.motion.reduced
    val halo: State<Float>
    val breath: State<Float>
    if (animate) {
        val transition = rememberInfiniteTransition(label = "alarm-orbit")
        halo = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(Motion.LONG * HALO_BEATS, easing = Motion.Easing), RepeatMode.Restart),
            label = "alarm-orbit-halo",
        )
        breath = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(Motion.LONG * BREATH_BEATS, easing = Motion.Easing), RepeatMode.Reverse),
            label = "alarm-orbit-breath",
        )
    } else {
        halo = remember { mutableFloatStateOf(0f) }
        breath = remember { mutableFloatStateOf(0f) }
    }
    Box(modifier.size(size).testTag("alarm-pulse"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val ring = this.size.minDimension * 0.34f
            val stroke = this.size.minDimension * 0.07f
            if (animate) {
                val h = halo.value
                drawCircle(
                    color = colors.content.copy(alpha = 0.32f * (1f - h)),
                    radius = ring * (1f + 0.42f * h),
                    center = center,
                    style = Stroke(width = stroke * (1f - 0.6f * h)),
                )
            }
            drawCircle(color = colors.pillContainer, radius = ring - stroke / 2f, center = center)
            drawArc(
                color = colors.content,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(center.x - ring, center.y - ring),
                size = Size(ring * 2f, ring * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val angle = Math.toRadians(-45.0)
            val dot = Offset(center.x + (ring * cos(angle)).toFloat(), center.y + (ring * sin(angle)).toFloat())
            val dotRadius = stroke * (0.72f + 0.28f * breath.value)
            drawCircle(color = colors.accent.copy(alpha = 0.35f * breath.value), radius = dotRadius * 1.9f, center = dot)
            drawCircle(color = colors.accent, radius = dotRadius, center = dot)
        }
        Icon(Icons.Outlined.Alarm, contentDescription = null, tint = colors.content, modifier = Modifier.size(size * 0.3f))
    }
}

private const val HALO_BEATS = 6
private const val BREATH_BEATS = 3

@Composable
fun AlarmPrimaryPill(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val colors = alarmRingColors()
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MarkedShapes.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.actionContainer,
            contentColor = colors.actionContent,
            disabledContainerColor = colors.actionContainer.copy(alpha = 0.7f),
            disabledContentColor = colors.actionContent,
        ),
        contentPadding = PaddingValues(horizontal = Space.x6, vertical = Space.x4),
        modifier = modifier.heightIn(min = 64.dp),
        content = content,
    )
}

@Composable
fun AlarmOutlinePill(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val colors = alarmRingColors()
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MarkedShapes.pill,
        border = BorderStroke(1.5.dp, colors.pillBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colors.pillContainer,
            contentColor = colors.content,
            disabledContainerColor = colors.pillContainer,
            disabledContentColor = colors.contentSoft,
        ),
        contentPadding = PaddingValues(horizontal = Space.x5, vertical = Space.x2),
        modifier = modifier.heightIn(min = 56.dp),
        content = content,
    )
}

@Composable
fun AlarmPillLabel(icon: ImageVector?, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.x2))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
fun LightSystemBarsOff() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val status = controller?.isAppearanceLightStatusBars
        val navigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (status != null) controller.isAppearanceLightStatusBars = status
            if (navigation != null) controller.isAppearanceLightNavigationBars = navigation
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
