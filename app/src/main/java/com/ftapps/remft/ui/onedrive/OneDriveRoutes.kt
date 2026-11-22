package com.ftapps.remft.ui.onedrive

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable data object SettingsAutoBackupRoute

fun NavHostController.openAutoBackup() {
    navigate(SettingsAutoBackupRoute) { launchSingleTop = true }
}

fun NavGraphBuilder.oneDriveDestinations(navController: NavHostController) {
    composable<SettingsAutoBackupRoute> { AutoBackupScreen(onBack = { navController.popBackStack() }) }
}
