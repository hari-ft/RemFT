package com.ftapps.remft.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space

@Composable
fun MarkedCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    shape: Shape = MarkedShapes.medium,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, contentColor = contentColor, border = border) {
            Column(content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, contentColor = contentColor, border = border) {
            Column(content = content)
        }
    }
}

@Composable
fun GradientCard(
    brush: Brush,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
    shape: Shape = MarkedShapes.large,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Box(modifier.clip(shape).background(brush).then(click)) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

@Composable
fun GroupedCardItem(
    position: GroupPosition,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val shape = MarkedShapes.group(position)
    Column(modifier.fillMaxWidth().clip(shape).background(color, shape)) {
        if (position == GroupPosition.Middle || position == GroupPosition.Last) {
            HorizontalDivider(
                thickness = Sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(end = Space.x3),
            )
        }
        content()
    }
}

@Composable
fun InfoCard(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = RemftTheme.tonal.level2,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: Shape = MarkedShapes.medium,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(start = Space.x4, top = Space.x2, end = Space.x2, bottom = Space.x2),
    actions: @Composable (() -> Unit)? = null,
) {
    MarkedCard(modifier.fillMaxWidth(), color = color, contentColor = contentColor, shape = shape, onClick = onClick) {
        Row(Modifier.heightIn(min = Sizes.quickAdd).padding(contentPadding), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Sizes.iconSmall + 2.dp))
            Spacer(Modifier.width(Space.x3))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (actions != null) {
                Spacer(Modifier.width(Space.x2))
                actions()
            }
        }
    }
}

@Preview(name = "Cards light")
@Composable
private fun CardsLightPreview() {
    RemftTheme(darkTheme = false) { CardsPreviewContent() }
}

@Preview(name = "Cards dark")
@Composable
private fun CardsDarkPreview() {
    RemftTheme(darkTheme = true) { CardsPreviewContent() }
}

@Composable
private fun CardsPreviewContent() {
    PreviewSurface {
        Column {
            MarkedCard(Modifier.fillMaxWidth()) {
                Text("Sample card", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(Space.x4))
            }
            Spacer(Modifier.size(Space.x3))
            InfoCard(Icons.Outlined.CloudQueue, "Sample information card", actions = { RemftTextButton("Action", onClick = {}) })
        }
    }
}
