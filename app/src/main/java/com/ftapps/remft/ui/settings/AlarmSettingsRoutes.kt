package com.ftapps.remft.ui.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable data object NotificationsSettingsRoute
@Serializable data object ReminderSystemRoute
@Serializable data object PermissionsRoute
@Serializable data object AlarmLogRoute
@Serializable data object AppLogRoute

fun NavHostController.openAlarmSettings(route: Any) {
    navigate(route) { launchSingleTop = true }
}

fun NavGraphBuilder.alarmSettingsDestinations(navController: NavHostController) {
    val back: () -> Unit = { navController.popBackStack() }
    composable<NotificationsSettingsRoute> {
        NotificationsSettingsScreen(
            onBack = back,
            onOpenReminderSystem = { navController.openAlarmSettings(ReminderSystemRoute) },
            onOpenAlarmLog = { navController.openAlarmSettings(AlarmLogRoute) },
        )
    }
    composable<ReminderSystemRoute> { ReminderSystemScreen(onBack = back) }
    composable<PermissionsRoute> { PermissionsScreen(onBack = back) }
    composable<AlarmLogRoute> { AlarmLogScreen(onBack = back) }
    composable<AppLogRoute> {
        AppLogScreen(onBack = back, onOpenAlarmLog = { navController.openAlarmSettings(AlarmLogRoute) })
    }
}
