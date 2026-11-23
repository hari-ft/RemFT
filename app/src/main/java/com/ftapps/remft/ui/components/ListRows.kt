package com.ftapps.remft.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space

@Composable
fun CircleCheckbox(checked: Boolean, description: String, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val done = RemftTheme.colors.done
    val motion = RemftTheme.motion
    val fill by animateColorAsState(if (checked) done.color else Color.Transparent, motion.quick(), label = "check-fill")
    val ring by animateColorAsState(if (checked) done.color else MaterialTheme.colorScheme.outline, motion.quick(), label = "check-ring")
    Box(
        modifier
            .size(Sizes.touchTarget)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Sizes.checkbox)
                .background(fill, CircleShape)
                .border(Sizes.stroke, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = done.onColor, modifier = Modifier.size(Sizes.iconChip))
        }
    }
}

@Composable
fun RemftListRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    struck: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    below: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.listRow)
            .then(click)
            .padding(start = Space.x1, end = Space.x3, top = Space.x2, bottom = Space.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Space.x2))
        } else {
            Spacer(Modifier.width(Space.x3))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
            Text(
                headline,
                style = MaterialTheme.typography.titleMedium.copy(textDecoration = if (struck) TextDecoration.LineThrough else null),
                color = if (struck) scheme.onSurfaceVariant else scheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            below?.invoke()
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.x2))
            trailing()
        }
    }
}

@Preview(name = "Rows light")
@Composable
private fun RowsLightPreview() {
    RemftTheme(darkTheme = false) { RowsPreviewContent() }
}

@Preview(name = "Rows dark")
@Composable
private fun RowsDarkPreview() {
    RemftTheme(darkTheme = true) { RowsPreviewContent() }
}

@Composable
private fun RowsPreviewContent() {
    PreviewSurface {
        Column {
            GroupedCardItem(GroupPosition.First) {
                RemftListRow(
                    "Pay the electricity bill",
                    leading = { CircleCheckbox(false, "Complete", onToggle = {}) },
                    below = { ChipRow { DueChip("Overdue since yesterday", overdue = true, today = false) } },
                )
            }
            GroupedCardItem(GroupPosition.Middle) {
                RemftListRow(
                    "Water the balcony plants",
                    leading = { CircleCheckbox(false, "Complete", onToggle = {}) },
                    below = {
                        ChipRow {
                            DueChip("Due 7:00 pm", overdue = false, today = true)
                            RepeatChip("Every Sunday")
                        }
                    },
                )
            }
            GroupedCardItem(GroupPosition.Last) {
                RemftListRow("Send the rent receipt", struck = true, leading = { CircleCheckbox(true, "Reopen", onToggle = {}) })
            }
        }
    }
}
