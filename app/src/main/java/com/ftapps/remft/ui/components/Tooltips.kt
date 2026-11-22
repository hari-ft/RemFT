package com.ftapps.remft.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ftapps.remft.ui.theme.MarkedShapes

val LocalTooltipsSuppressed = compositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tooltipped(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state = rememberTooltipState()
    val suppressed = LocalTooltipsSuppressed.current
    LaunchedEffect(suppressed, state.isVisible) { if (suppressed && state.isVisible) state.dismiss() }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip(
                shape = MarkedShapes.small,
                containerColor = scheme.inverseSurface,
                contentColor = scheme.inverseOnSurface,
            ) { Text(label, style = MaterialTheme.typography.labelMedium) }
        },
        state = state,
        modifier = modifier,
        focusable = false,
        enableUserInput = !suppressed,
        content = content,
    )
}

@Composable
fun RemftIconButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit,
) {
    Tooltipped(label) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors, content = content)
    }
}

@Composable
fun RemftIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    RemftIconButton(onClick = onClick, label = label, modifier = modifier, enabled = enabled) {
        Icon(icon, contentDescription = label, tint = if (tint == Color.Unspecified) LocalContentColor.current else tint)
    }
}
