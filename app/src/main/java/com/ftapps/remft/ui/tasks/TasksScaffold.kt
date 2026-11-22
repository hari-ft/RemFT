package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.ui.theme.Space

@OptIn(ExperimentalMaterial3Api::class)
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
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_close))
                        }
                    }
                },
                actions = {
                    actions()
                    if (onOpenSettings != null) {
                        IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open-settings")) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
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
