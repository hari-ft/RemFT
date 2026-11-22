package com.ftapps.remft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemftTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
        modifier = modifier,
    )
}

@Composable
fun NameMarkTopBar(modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 72.dp)
            .padding(start = Space.x4, end = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NameMark(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Preview(name = "Top bar light")
@Composable
private fun TopBarLightPreview() {
    RemftTheme(darkTheme = false) { TopBarPreviewContent() }
}

@Preview(name = "Top bar dark")
@Composable
private fun TopBarDarkPreview() {
    RemftTheme(darkTheme = true) { TopBarPreviewContent() }
}

@Composable
private fun TopBarPreviewContent() {
    PreviewSurface {
        NameMarkTopBar {
            RoundIconButton(Icons.Outlined.Search, "Search", onClick = {})
            RoundIconButton(Icons.Outlined.Settings, "Settings", onClick = {})
        }
    }
}
