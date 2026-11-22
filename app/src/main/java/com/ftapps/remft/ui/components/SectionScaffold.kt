package com.ftapps.remft.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.ftapps.remft.R
import com.ftapps.remft.ui.theme.Space

@Composable
fun SectionScaffold(
    title: String,
    onOpenSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            RemftTopBar(
                title = title,
                navigationIcon = {
                    if (onBack != null) {
                        RemftIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, label = stringResource(R.string.common_close), onClick = onBack, modifier = Modifier.testTag("back"))
                    }
                },
                actions = {
                    if (onOpenSettings != null) {
                        RemftIconButton(icon = Icons.Outlined.Settings, label = stringResource(R.string.settings_title), onClick = onOpenSettings, modifier = Modifier.testTag("open-settings"))
                    }
                },
            )
        },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

@Composable
fun CenteredList(
    padding: PaddingValues,
    maxWidth: Dp,
    bottomPadding: Dp = Space.x4,
    content: LazyListScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth(),
            contentPadding = PaddingValues(start = Space.x4, end = Space.x4, top = Space.x2, bottom = bottomPadding + padding.calculateBottomPadding()),
            content = content,
        )
    }
}

@Composable
fun CenteredBox(padding: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { content() }
}
