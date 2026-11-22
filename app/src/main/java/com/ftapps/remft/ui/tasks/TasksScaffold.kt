package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.components.RemftTopBar
import com.ftapps.remft.ui.theme.Space

@Composable
fun TasksScaffold(
    title: String,
    bottomPadding: Dp,
    onOpenSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
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
                        RemftIconButton(label = stringResource(R.string.common_close), onClick = onBack, modifier = Modifier.testTag("back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_close))
                        }
                    }
                },
                actions = {
                    actions()
                    if (onOpenSettings != null) {
                        RemftIconButton(label = stringResource(R.string.settings_title), onClick = onOpenSettings, modifier = Modifier.testTag("open-settings")) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            content(padding)
            Box(Modifier.align(Alignment.BottomEnd).padding(end = Space.x4, bottom = bottomPadding + Space.x4)) {
                floatingActionButton()
            }
            UndoSnackbarHost(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomPadding + if (bottomPadding > 0.dp) 0.dp else Space.x2),
            )
        }
    }
}
