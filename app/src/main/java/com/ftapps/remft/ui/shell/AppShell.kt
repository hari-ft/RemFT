package com.ftapps.remft.ui.shell

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ftapps.remft.ui.components.AppBackground
import com.ftapps.remft.ui.components.RemftNavItem
import com.ftapps.remft.ui.components.RemftNavRowItem
import com.ftapps.remft.ui.components.RemftNavigationBar
import com.ftapps.remft.ui.components.RemftNavigationRail
import com.ftapps.remft.ui.components.LocalHazeState
import com.ftapps.remft.ui.backup.BackupSettingsScreen
import com.ftapps.remft.ui.onedrive.oneDriveDestinations
import com.ftapps.remft.ui.onedrive.openAutoBackup
import com.ftapps.remft.ui.files.SettingsBinRoute
import com.ftapps.remft.ui.files.SettingsCompletedRoute
import com.ftapps.remft.ui.files.filesDestinations
import com.ftapps.remft.ui.screens.FilesScreen
import com.ftapps.remft.ui.screens.HomeScreen
import com.ftapps.remft.ui.screens.InboxScreen
import com.ftapps.remft.ui.onboarding.LocalTourController
import com.ftapps.remft.ui.onboarding.TourHost
import com.ftapps.remft.ui.onboarding.tourAnchor
import com.ftapps.remft.ui.search.GlobalSearchHost
import com.ftapps.remft.ui.search.GlobalSearchRailEntry
import com.ftapps.remft.ui.settings.AppLogRoute
import com.ftapps.remft.ui.settings.NotificationsSettingsRoute
import com.ftapps.remft.ui.settings.PermissionsRoute
import com.ftapps.remft.ui.settings.SettingsGeneralScreen
import com.ftapps.remft.ui.settings.alarmSettingsDestinations
import com.ftapps.remft.ui.settings.openAlarmSettings
import com.ftapps.remft.ui.settings.SettingsScreen
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.Motion
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.tasks.UndoSnackbarHost
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

val BottomBarClearance = 96.dp

@Composable
fun AppShell(
    navController: NavHostController = rememberNavController(),
    deepLink: String? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    TaskDeepLinkEffect(deepLink, navController, onDeepLinkHandled)
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val current = Section.entries.firstOrNull { section ->
        destination?.hierarchy?.any { it.hasRoute(section.route::class) } == true
    }
    val navigate: (Section) -> Unit = { section -> navController.switchSection(section.route) }
    val hazeState = rememberHazeState()
    val tour = LocalTourController.current
    if (tour != null) LaunchedEffect(tour) { tour.maybeAutoStart() }
    val replayTour = tour?.let {
        {
            it.start()
            if (!navController.popBackStack(HomeRoute, inclusive = false)) navigate(Section.Home)
        }
    }
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        AppBackground {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= Breakpoints.rail
                val extended = maxWidth >= Breakpoints.extendedRail
                Row(Modifier.fillMaxSize()) {
                    if (wide && current != null) {
                        NavigationRailPanel(current, navigate, extended = extended)
                    }
                    val sides = if (wide && current != null) WindowInsetsSides.End else WindowInsetsSides.Horizontal
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .hazeSource(hazeState)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(sides))
                            .testTag("shell-content"),
                    ) {
                        RemftNavHost(navController, showBottomBar = !wide, onReplayTour = replayTour)
                    }
                }
                AppUndoHost(bottomBar = !wide && current != null, modifier = Modifier.align(Alignment.BottomCenter))
                GlobalSearchHost(navController, wide = maxWidth >= Breakpoints.searchDialog)
                if (!wide && current != null) {
                    BottomBar(
                        current = current,
                        onSelect = navigate,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                if (tour != null) TourHost(tour)
            }
        }
    }
}

@Composable
private fun RemftNavHost(navController: NavHostController, showBottomBar: Boolean, onReplayTour: (() -> Unit)?) {
    val bottomPadding = if (showBottomBar) BottomBarClearance else 0.dp
    val openSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } }
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        enterTransition = { fadeIn(tween(Motion.MEDIUM, easing = Motion.Easing)) },
        exitTransition = { fadeOut(tween(Motion.SHORT, easing = Motion.Easing)) },
    ) {
        composable<HomeRoute> {
            HomeScreen(bottomPadding, openSettings, onOpenTask = { navController.openTask(it) }, onOpenAutoBackup = { navController.openAutoBackup() })
        }
        composable<InboxRoute> { InboxScreen(bottomPadding, openSettings) }
        notesSection(navController, bottomPadding, openSettings)
        composable<FilesRoute> {
            FilesScreen(bottomPadding, openSettings, { navController.openTask(it) }, { navController.navigate(NoteRoute(it)) { launchSingleTop = true } })
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenGeneral = { navController.navigate(SettingsGeneralRoute) },
                onOpenBackup = { navController.navigate(SettingsBackupRoute) },
                onOpenNotifications = { navController.openAlarmSettings(NotificationsSettingsRoute) },
                onOpenPermissions = { navController.openAlarmSettings(PermissionsRoute) },
                onOpenAppLog = { navController.openAlarmSettings(AppLogRoute) },
                onOpenCompleted = { navController.navigate(SettingsCompletedRoute) },
                onOpenBin = { navController.navigate(SettingsBinRoute) },
            )
        }
        composable<SettingsGeneralRoute> { SettingsGeneralScreen(onBack = { navController.popBackStack() }, onReplayTour = onReplayTour) }
        taskDestinations(navController)
        composable<SettingsBackupRoute> {
            BackupSettingsScreen(onBack = { navController.popBackStack() }, onOpenAutoBackup = { navController.openAutoBackup() })
        }
        oneDriveDestinations(navController)
        alarmSettingsDestinations(navController)
        filesDestinations(onBack = { navController.popBackStack() }, onOpenTask = { navController.openTask(it) })
    }
}

@Composable
private fun AppUndoHost(bottomBar: Boolean, modifier: Modifier = Modifier) {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val barSpace = if (bottomBar) Sizes.navBarHeight + navInset else navInset + Space.x2
    UndoSnackbarHost(modifier.padding(bottom = maxOf(barSpace, imeInset)))
}

@Composable
private fun BottomBar(current: Section, onSelect: (Section) -> Unit, modifier: Modifier = Modifier) {
    RemftNavigationBar(modifier.testTag("bottom-bar")) {
        Section.entries.forEach { section ->
            NavItem(section, section == current, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavItem(section: Section, selected: Boolean, onSelect: (Section) -> Unit, modifier: Modifier = Modifier) {
    RemftNavItem(
        icon = section.icon,
        label = stringResource(section.label),
        selected = selected,
        onClick = { onSelect(section) },
        modifier = modifier.testTag(section.testTag).tourAnchor(LocalTourController.current, section),
    )
}

@Composable
private fun NavigationRailPanel(current: Section, onSelect: (Section) -> Unit, extended: Boolean) {
    RemftNavigationRail(Modifier.testTag("nav-rail"), width = if (extended) 220.dp else 88.dp) {
        GlobalSearchRailEntry(extended)
        Section.entries.forEach { section ->
            if (extended) {
                RemftNavRowItem(
                    icon = section.icon,
                    label = stringResource(section.label),
                    selected = section == current,
                    onClick = { onSelect(section) },
                    modifier = Modifier.testTag(section.testTag).tourAnchor(LocalTourController.current, section),
                )
            } else {
                NavItem(section, section == current, onSelect, Modifier.fillMaxWidth())
            }
        }
    }
}
