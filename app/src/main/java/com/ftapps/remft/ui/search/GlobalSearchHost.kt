package com.ftapps.remft.ui.search

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.ftapps.remft.R
import com.ftapps.remft.domain.search.GlobalSearchItem
import com.ftapps.remft.domain.search.GlobalSearchKind
import com.ftapps.remft.domain.search.PaletteEntry
import com.ftapps.remft.domain.search.SearchRecent
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.RoundIconButton
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.shell.FilesRoute
import com.ftapps.remft.ui.shell.NoteRoute
import com.ftapps.remft.ui.shell.TaskRoute
import com.ftapps.remft.ui.shell.openTask
import com.ftapps.remft.ui.tasks.TaskCreateSheet
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.launch

fun NavHostController.openSectionFromSearch(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

fun NavHostController.openNoteFromSearch(id: String) {
    navigate(NoteRoute(id = id)) { launchSingleTop = true }
}

fun NavHostController.openSearchItem(item: GlobalSearchItem, openNote: (String) -> Unit) {
    when (item.kind) {
        GlobalSearchKind.Task -> openTask(item.id)
        GlobalSearchKind.Note -> openNote(item.id)
        GlobalSearchKind.File -> openSectionFromSearch(FilesRoute)
    }
}

@Composable
fun GlobalSearchHost(
    navController: NavHostController,
    wide: Boolean,
    openNote: (String) -> Unit = { navController.openNoteFromSearch(it) },
) {
    val search = LocalSearchEnvironment.current ?: return
    val open by search.palette.isOpen.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var createTitle by rememberSaveable { mutableStateOf<String?>(null) }
    val entry by navController.currentBackStackEntryAsState()
    LaunchedEffect(entry) {
        val current = entry ?: return@LaunchedEffect
        if (current.destination.hasRoute(TaskRoute::class)) {
            runCatching { search.recents.record(SearchRecent(GlobalSearchKind.Task, current.toRoute<TaskRoute>().id)) }
        } else if (current.destination.hasRoute(NoteRoute::class)) {
            current.toRoute<NoteRoute>().id?.let { id -> runCatching { search.recents.record(SearchRecent(GlobalSearchKind.Note, id)) } }
        }
    }
    if (open) {
        GlobalSearchPalette(
            search = search,
            wide = wide,
            onDismiss = search.palette::close,
            onChoose = { choice ->
                search.palette.close()
                when (choice) {
                    is PaletteEntry.Create -> createTitle = choice.title
                    is PaletteEntry.Item -> {
                        val item = choice.item
                        scope.launch { runCatching { search.recents.record(SearchRecent(item.kind, item.id)) } }
                        navController.openSearchItem(item, openNote)
                    }
                }
            },
        )
    }
    createTitle?.let { title ->
        TaskCreateSheet(onDismiss = { createTitle = null }, initialTitle = title)
    }
}

@Composable
fun GlobalSearchButton(modifier: Modifier = Modifier) {
    val search = LocalSearchEnvironment.current ?: return
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    if (windowWidth >= Breakpoints.rail) return
    RoundIconButton(
        icon = Icons.Outlined.Search,
        contentDescription = stringResource(R.string.search_global_open),
        onClick = search.palette::open,
        modifier = modifier.testTag("global-search-button"),
    )
}

@Composable
fun GlobalSearchRailEntry(extended: Boolean, modifier: Modifier = Modifier) {
    val search = LocalSearchEnvironment.current ?: return
    val shortcut = stringResource(R.string.search_global_shortcut)
    val label = stringResource(R.string.search_global_openWithShortcut).fill("shortcut" to shortcut)
    if (!extended) {
        IconButton(onClick = search.palette::open, modifier = modifier.fillMaxWidth().testTag("global-search-rail")) {
            Icon(Icons.Outlined.Search, contentDescription = label)
        }
        return
    }
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = search.palette::open,
        shape = MarkedShapes.pill,
        color = scheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().height(48.dp).testTag("global-search-rail"),
    ) {
        Row(Modifier.padding(horizontal = Space.x3), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.x2))
            Text(
                stringResource(R.string.search_global_open),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(shortcut, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
    }
}
