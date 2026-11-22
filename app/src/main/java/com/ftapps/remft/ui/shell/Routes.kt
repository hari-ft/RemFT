package com.ftapps.remft.ui.shell

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.ui.graphics.vector.ImageVector
import com.ftapps.remft.R
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object InboxRoute
@Serializable data object NotesRoute
@Serializable data object FilesRoute
@Serializable data object SettingsRoute
@Serializable data object SettingsGeneralRoute
@Serializable data object SettingsBackupRoute

enum class Section(val route: Any, @param:StringRes val label: Int, val icon: ImageVector, val testTag: String) {
    Home(HomeRoute, R.string.nav_home, Icons.Outlined.SpaceDashboard, "nav-home"),
    Inbox(InboxRoute, R.string.nav_inbox, Icons.Outlined.Inbox, "nav-inbox"),
    Notes(NotesRoute, R.string.nav_notes, Icons.Outlined.Description, "nav-notes"),
    Files(FilesRoute, R.string.nav_files, Icons.Outlined.FolderCopy, "nav-files"),
}
