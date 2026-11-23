package com.ftapps.remft.ui.settings

import com.ftapps.remft.ui.theme.Sizes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ftapps.remft.ui.components.GroupedCardItem
import com.ftapps.remft.ui.components.MarkedCard
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space

enum class SettingsTone { Brand, Success, Warning, Danger, Neutral }

private val IconCircle = 30.dp
private val RowStart = Space.x4
private val DividerInset = RowStart + IconCircle + Space.x4

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    MarkedCard(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        DividedColumn(content)
    }
}

@Composable
private fun DividedColumn(content: @Composable () -> Unit) {
    val divider = MaterialTheme.colorScheme.outlineVariant
    val bounds = remember { DividerBounds() }
    Layout(
        content = content,
        modifier = Modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val inset = DividerInset.toPx()
                val stroke = Sizes.hairline.toPx()
                val (start, end) = if (layoutDirection == LayoutDirection.Ltr) inset to size.width else 0f to size.width - inset
                bounds.tops.forEach { y ->
                    drawLine(divider, Offset(start, y), Offset(end, y), stroke)
                }
            },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val width = placeables.maxOfOrNull { it.width }?.coerceAtLeast(constraints.minWidth) ?: constraints.minWidth
        val height = placeables.sumOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)
        val tops = ArrayList<Float>(placeables.size)
        layout(width, height) {
            var y = 0
            placeables.forEachIndexed { index, placeable ->
                if (index > 0 && placeable.height > 0) tops += y.toFloat()
                placeable.placeRelative(0, y)
                y += placeable.height
            }
            bounds.tops = tops
        }
    }
}

private class DividerBounds {
    var tops: List<Float> = emptyList()
}

@Composable
fun SettingsSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Space.x2, end = Space.x2, top = Space.x4, bottom = Space.x2)
            .semantics { heading() },
    )
}

@Composable
fun SettingsNote(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.x2, vertical = Space.x2),
    )
}

data class ToneColors(val container: Color, val content: Color)

@Composable
fun settingsToneColors(tone: SettingsTone): ToneColors {
    val scheme = MaterialTheme.colorScheme
    val semantic = RemftTheme.colors
    return when (tone) {
        SettingsTone.Brand -> ToneColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        SettingsTone.Success -> ToneColors(semantic.success.container, semantic.success.onContainer)
        SettingsTone.Warning -> ToneColors(semantic.warning.container, semantic.warning.onContainer)
        SettingsTone.Danger -> ToneColors(scheme.errorContainer, scheme.onErrorContainer)
        SettingsTone.Neutral -> ToneColors(RemftTheme.tonal.level2, scheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsIcon(icon: ImageVector, tone: SettingsTone = SettingsTone.Brand, modifier: Modifier = Modifier, circle: Dp = IconCircle) {
    val colors = settingsToneColors(tone)
    Box(modifier.size(circle).background(colors.container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(circle * 0.55f))
    }
}

@Composable
fun SettingsInfo(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Info) {
    MarkedCard(modifier.fillMaxWidth(), color = RemftTheme.tonal.level2, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(Modifier.padding(Space.x4), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.x3))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun LogEntryRow(
    position: GroupPosition,
    title: String,
    stamp: String,
    modifier: Modifier = Modifier,
    details: String? = null,
    icon: ImageVector? = null,
    tone: SettingsTone = SettingsTone.Neutral,
) {
    val scheme = MaterialTheme.colorScheme
    GroupedCardItem(position) {
        Row(
            modifier.fillMaxWidth().heightIn(min = Sizes.settingsRow).padding(horizontal = Space.x4, vertical = Space.x2 + 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (icon != null) {
                SettingsIcon(icon, tone, circle = IconCircle)
                Spacer(Modifier.width(Space.x3))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                if (details != null) Text(details, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(Space.x2))
            Text(stamp, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: SettingsTone = SettingsTone.Brand,
    subtitle: String? = null,
    subtitleModifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    below: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.settingsRow)
            .padding(start = RowStart, end = Space.x3, top = Space.x2 - 1.dp, bottom = Space.x2 - 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SettingsIcon(icon, tone)
            Spacer(Modifier.width(Space.x3))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = subtitleColor, modifier = subtitleModifier)
            }
            below?.invoke()
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.x2))
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { trailing() }
        }
    }
}

@Composable
fun SettingsChevron() {
    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tag: String,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = { SettingsChevron() },
    onClick: (() -> Unit)? = null,
    tone: SettingsTone = SettingsTone.Brand,
) {
    val clickable = if (enabled && onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    SettingsItem(
        title = title,
        modifier = Modifier.testTag(tag).then(clickable).alpha(if (enabled) 1f else 0.5f),
        icon = icon,
        tone = tone,
        subtitle = subtitle,
        trailing = if (enabled) trailing else null,
    )
}

@Composable
fun SettingsFilterChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        shape = MarkedShapes.pill,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = scheme.surface,
            labelColor = scheme.onSurfaceVariant,
            selectedContainerColor = scheme.primaryContainer,
            selectedLabelColor = scheme.onPrimaryContainer,
            selectedLeadingIconColor = scheme.onPrimaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = scheme.outlineVariant,
            selectedBorderColor = scheme.primaryContainer,
        ),
        leadingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        modifier = modifier,
    )
}

@Composable
fun SettingsSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        thumbContent = if (checked) {
            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else {
            null
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = scheme.onPrimary,
            checkedTrackColor = scheme.primary,
            checkedIconColor = scheme.primary,
            checkedBorderColor = scheme.primary,
            uncheckedThumbColor = scheme.outline,
            uncheckedTrackColor = RemftTheme.tonal.level2,
            uncheckedBorderColor = scheme.outline,
        ),
    )
}

@Composable
fun SettingsBanner(
    icon: ImageVector,
    title: String,
    tone: SettingsTone,
    modifier: Modifier = Modifier,
    body: String? = null,
    actions: @Composable (() -> Unit)? = null,
) {
    val colors = settingsToneColors(tone)
    MarkedCard(modifier.fillMaxWidth(), color = colors.container, contentColor = colors.content, shape = MarkedShapes.medium) {
        Column(Modifier.padding(start = Space.x4, top = Space.x4, end = Space.x3, bottom = if (actions != null) Space.x1 else Space.x4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(Space.x3))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            if (body != null) {
                Spacer(Modifier.size(Space.x2))
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (actions != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            }
        }
    }
}

@Preview(name = "Settings parts light")
@Composable
private fun SettingsPartsLightPreview() {
    RemftTheme(darkTheme = false) { SettingsPartsPreview() }
}

@Preview(name = "Settings parts dark")
@Composable
private fun SettingsPartsDarkPreview() {
    RemftTheme(darkTheme = true) { SettingsPartsPreview() }
}

@Composable
private fun SettingsPartsPreview() {
    PreviewSurface {
        Column {
            SettingsSectionHeader("Preferences")
            SettingsCard {
                SettingsRow(Icons.Outlined.Tune, "General", "Date format, editor formatting", "p-general")
                SettingsRow(Icons.Outlined.Palette, "Colours from your wallpaper", "Sample switch row", "p-switch", trailing = {
                    SettingsSwitch(checked = true, onCheckedChange = {})
                })
                SettingsRow(Icons.Outlined.NotificationsOff, "Private notifications", "Sample switch row off", "p-off", trailing = {
                    SettingsSwitch(checked = false, onCheckedChange = {})
                })
                SettingsRow(Icons.Outlined.Info, "Disabled row", null, "p-disabled", enabled = false)
            }
            Spacer(Modifier.size(Space.x4))
            SettingsBanner(Icons.Outlined.WarningAmber, "Sample warning", SettingsTone.Warning, body = "Something needs a look.")
            Spacer(Modifier.size(Space.x4))
            SettingsInfo("Sample note about what this page shows.")
            Spacer(Modifier.size(Space.x4))
            LogEntryRow(GroupPosition.First, "Scheduled · urgent", "10:42", details = "task=sample at=10:45", icon = Icons.Outlined.Info)
            LogEntryRow(GroupPosition.Last, "Degraded · urgent", "10:44", icon = Icons.Outlined.WarningAmber, tone = SettingsTone.Warning)
        }
    }
}
