package com.ftapps.remft.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space

@Composable
fun RemftNavigationBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        HorizontalDivider(thickness = Sizes.hairline, color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(min = Sizes.navBarHeight)
                .padding(horizontal = Space.x2),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun RemftNavigationRail(modifier: Modifier = Modifier, width: androidx.compose.ui.unit.Dp = 80.dp, content: @Composable ColumnScope.() -> Unit) {
    Row(modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                .width(width)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = Space.x6, horizontal = Space.x2),
            verticalArrangement = Arrangement.spacedBy(Space.x2),
            content = content,
        )
        VerticalDivider(thickness = Sizes.hairline, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun RemftNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = RemftTheme.motion
    val pill by animateColorAsState(if (selected) scheme.primaryContainer else Color.Transparent, motion.standard(), label = "nav-pill")
    val iconColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    Column(
        modifier
            .heightIn(min = Sizes.listRow)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = Space.x1),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(width = 56.dp, height = 28.dp).background(pill, MarkedShapes.pill), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(Sizes.iconSmall + 4.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.W600 else FontWeight.W500),
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RemftNavRowItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = RemftTheme.motion
    val pill by animateColorAsState(if (selected) scheme.primaryContainer else Color.Transparent, motion.standard(), label = "nav-row-pill")
    val color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.touchTarget)
            .background(pill, MarkedShapes.pill)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(Space.x3))
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Preview(name = "Navigation bar light")
@Composable
private fun NavigationLightPreview() {
    RemftTheme(darkTheme = false) { NavigationPreviewContent() }
}

@Preview(name = "Navigation bar dark")
@Composable
private fun NavigationDarkPreview() {
    RemftTheme(darkTheme = true) { NavigationPreviewContent() }
}

@Composable
private fun NavigationPreviewContent() {
    RemftNavigationBar {
        RemftNavItem(Icons.Outlined.Home, "Home", selected = true, onClick = {}, modifier = Modifier.weight(1f))
        RemftNavItem(Icons.Outlined.Inbox, "Inbox", selected = false, onClick = {}, modifier = Modifier.weight(1f))
        RemftNavItem(Icons.Outlined.Folder, "Files", selected = false, onClick = {}, modifier = Modifier.weight(1f))
    }
}
