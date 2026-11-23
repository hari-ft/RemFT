package com.ftapps.remft.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space

private val ButtonPadding = PaddingValues(horizontal = Space.x4, vertical = Space.x2)

@Composable
fun RemftFilledButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MarkedShapes.pill,
        contentPadding = ButtonPadding,
        modifier = modifier.heightIn(min = Sizes.button),
    ) { ButtonLabel(text, icon) }
}

@Composable
fun RemftTonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = MarkedShapes.pill,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier.heightIn(min = Sizes.button),
    ) { ButtonLabel(text, icon) }
}

@Composable
fun RemftTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = MarkedShapes.pill,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        modifier = modifier.heightIn(min = Sizes.button),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun ButtonLabel(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(Sizes.iconSmall))
        Spacer(Modifier.size(Space.x2))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = RemftTheme.tonal.level2,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Tooltipped(contentDescription) {
        Box(
            modifier
                .size(Sizes.touchTarget)
                .clickable(
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = Sizes.iconButton / 2),
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(Sizes.iconButton).background(container, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = contentDescription, tint = content, modifier = Modifier.size(Sizes.iconSmall))
            }
        }
    }
}

@Composable
fun SquareFab(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tooltipped(contentDescription) {
        SquareFabBody(icon, contentDescription, onClick, modifier)
    }
}

@Composable
private fun SquareFabBody(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    FloatingActionButton(
        onClick = onClick,
        shape = MarkedShapes.fab,
        containerColor = scheme.primary,
        contentColor = scheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
        modifier = modifier
            .shadow(6.dp, MarkedShapes.fab, ambientColor = scheme.primary.copy(alpha = 0.35f), spotColor = scheme.primary.copy(alpha = 0.35f))
            .size(Sizes.fab),
    ) { Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(Sizes.icon)) }
}

@Preview(name = "Buttons light")
@Composable
private fun ButtonsLightPreview() {
    RemftTheme(darkTheme = false) { ButtonsPreviewContent() }
}

@Preview(name = "Buttons dark")
@Composable
private fun ButtonsDarkPreview() {
    RemftTheme(darkTheme = true) { ButtonsPreviewContent() }
}

@Composable
private fun ButtonsPreviewContent() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Space.x2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                RemftFilledButton("Save task", onClick = {})
                RemftTonalButton("Later", onClick = {})
                RemftTextButton("Back up now", onClick = {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                RoundIconButton(Icons.Outlined.Search, "Search", onClick = {})
                SquareFab(Icons.Filled.Add, "Add", onClick = {})
            }
        }
    }
}
