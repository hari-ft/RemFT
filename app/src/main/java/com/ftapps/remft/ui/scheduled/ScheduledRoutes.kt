package com.ftapps.remft.ui.scheduled

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable data object SettingsScheduledRoute

fun NavGraphBuilder.scheduledDestinations(onBack: () -> Unit, onOpenTask: (String) -> Unit) {
    composable<SettingsScheduledRoute> { ScheduledScreen(onBack = onBack, onOpenTask = onOpenTask) }
}
