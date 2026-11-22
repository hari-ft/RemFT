package com.ftapps.remft.ui.shell

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Home
import androidx.compose.ui.graphics.vector.ImageVector
import com.ftapps.remft.R
import kotlinx.serialization.Serializable
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

@Serializable data object HomeRoute
@Serializable data object InboxRoute
@Serializable data object NotesRoute
@Serializable data object FilesRoute
@Serializable data object SettingsRoute
@Serializable data object SettingsGeneralRoute
@Serializable data object SettingsBackupRoute

enum class Section(val route: Any, @param:StringRes val label: Int, val icon: ImageVector, val testTag: String) {
    Home(HomeRoute, R.string.nav_home, Icons.Outlined.Home, "nav-home"),
    Inbox(InboxRoute, R.string.nav_inbox, Icons.Outlined.Inbox, "nav-inbox"),
    Notes(NotesRoute, R.string.nav_notes, Icons.Outlined.Description, "nav-notes"),
    Files(FilesRoute, R.string.nav_files, Icons.Outlined.Folder, "nav-files"),
}

fun NavHostController.switchSection(route: Any) {
    popBackStack<SettingsRoute>(inclusive = true)
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
