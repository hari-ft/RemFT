package com.ftapps.remft.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.ftapps.remft.ui.files.FilesSection
import com.ftapps.remft.ui.tasks.HomeTasksScreen
import com.ftapps.remft.ui.tasks.InboxTasksScreen

@Composable
fun HomeScreen(bottomPadding: Dp, onOpenSettings: () -> Unit, onOpenTask: (String) -> Unit = {}, onOpenAutoBackup: () -> Unit = {}) {
    HomeTasksScreen(bottomPadding, onOpenSettings, onOpenTask, onOpenAutoBackup)
}

@Composable
fun InboxScreen(bottomPadding: Dp, onOpenSettings: () -> Unit) {
    InboxTasksScreen(bottomPadding, onOpenSettings)
}

@Composable
fun FilesScreen(
    bottomPadding: Dp,
    onOpenSettings: () -> Unit,
    onOpenTask: (String) -> Unit = {},
    onOpenNote: (String) -> Unit = {},
    onOpenOneDrive: () -> Unit = {},
) {
    FilesSection(bottomPadding, onOpenSettings, onOpenTask, onOpenNote, onOpenOneDrive)
}
