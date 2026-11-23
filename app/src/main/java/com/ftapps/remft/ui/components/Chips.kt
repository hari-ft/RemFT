package com.ftapps.remft.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.SemanticColor
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space

enum class ChipTone { Today, Overdue, Urgent, Repeat, Done, Note, File, Success, Warning, Neutral, Primary }

data class ChipColors(val container: Color, val content: Color)

@Composable
fun chipColors(tone: ChipTone): ChipColors {
    val semantic = RemftTheme.colors
    val scheme = MaterialTheme.colorScheme
    fun of(color: SemanticColor) = ChipColors(color.container, color.onContainer)
    return when (tone) {
        ChipTone.Today -> of(semantic.today)
        ChipTone.Overdue -> of(semantic.overdue)
        ChipTone.Urgent -> of(semantic.urgent)
        ChipTone.Repeat -> of(semantic.repeat)
        ChipTone.Done -> of(semantic.done)
        ChipTone.Note -> of(semantic.note)
        ChipTone.File -> of(semantic.file)
        ChipTone.Success -> of(semantic.success)
        ChipTone.Warning -> of(semantic.warning)
        ChipTone.Neutral -> ChipColors(RemftTheme.tonal.level2, scheme.onSurfaceVariant)
        ChipTone.Primary -> ChipColors(scheme.primaryContainer, scheme.onPrimaryContainer)
    }
}

@Composable
fun StatusChip(
    text: String,
    tone: ChipTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    description: String? = null,
) {
    val colors = chipColors(tone)
    val semantics = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    Row(
        modifier
            .heightIn(min = Sizes.chip)
            .background(colors.container, MarkedShapes.pill)
            .then(semantics)
            .padding(horizontal = Space.x2, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(Sizes.iconChip))
            Spacer(Modifier.width(Space.x1))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = colors.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun DueChip(text: String, overdue: Boolean, today: Boolean, modifier: Modifier = Modifier) {
    val tone = when {
        overdue -> ChipTone.Overdue
        today -> ChipTone.Today
        else -> ChipTone.Neutral
    }
    StatusChip(text, tone, modifier)
}

@Composable
fun PriorityChip(text: String, strong: Boolean, modifier: Modifier = Modifier, description: String? = null) {
    StatusChip(text, if (strong) ChipTone.Urgent else ChipTone.Warning, modifier, description = description)
}

@Composable
fun RepeatChip(text: String, modifier: Modifier = Modifier, description: String? = null) {
    StatusChip(text, ChipTone.Repeat, modifier, icon = Icons.Outlined.Repeat, description = description)
}

@Composable
fun TagChip(text: String, modifier: Modifier = Modifier) {
    StatusChip(text, ChipTone.Primary, modifier, icon = Icons.Outlined.Sell)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Space.x2),
        verticalArrangement = Arrangement.spacedBy(Space.x1),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Preview(name = "Chips light")
@Composable
private fun ChipsLightPreview() {
    RemftTheme(darkTheme = false) { ChipsPreviewContent() }
}

@Preview(name = "Chips dark")
@Composable
private fun ChipsDarkPreview() {
    RemftTheme(darkTheme = true) { ChipsPreviewContent() }
}

@Composable
private fun ChipsPreviewContent() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Space.x2)) {
            ChipRow {
                DueChip("Overdue since yesterday", overdue = true, today = false)
                DueChip("Due 6:00 pm", overdue = false, today = true)
            }
            ChipRow {
                PriorityChip("High priority", strong = true)
                RepeatChip("Every Sunday")
                TagChip("Home")
            }
        }
    }
}
