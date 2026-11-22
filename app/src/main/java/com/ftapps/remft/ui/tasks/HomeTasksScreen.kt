package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ftapps.remft.R
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.tasks.HomeBucket
import com.ftapps.remft.domain.tasks.HomeGroup
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.tasks.TaskSort
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.alarm.AlarmRefreshBox
import com.ftapps.remft.ui.alarm.HomeAlarmCards
import com.ftapps.remft.ui.backup.HomeBackupReminder
import com.ftapps.remft.ui.components.EmptyState
import com.ftapps.remft.ui.onedrive.HomeOneDriveCards
import com.ftapps.remft.ui.components.ErrorState
import com.ftapps.remft.ui.repeat.OccurrenceDeleteHost
import com.ftapps.remft.ui.repeat.rememberOccurrenceDeleter
import com.ftapps.remft.ui.quickadd.HomeQuickAdd
import com.ftapps.remft.ui.search.GlobalSearchButton
import com.ftapps.remft.ui.search.SearchField
import com.ftapps.remft.ui.search.TaskSearchState
import com.ftapps.remft.ui.search.homeSearchResults
import com.ftapps.remft.ui.search.rememberHomeSearch
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.Radius
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.theme.Space
import java.time.Instant
import java.time.ZoneId

@Composable
fun HomeTasksScreen(bottomPadding: Dp, onOpenSettings: () -> Unit, onOpenTask: (String) -> Unit, onOpenAutoBackup: () -> Unit = {}) {
    val env = LocalTasksEnvironment.current
    val vm: HomeViewModel = viewModel { HomeViewModel(env) }
    val state by vm.state.collectAsStateWithLifecycle()
    val format = rememberDateFormat()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var sheetDue by remember { mutableStateOf<java.time.LocalDateTime?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searching = searchOpen && searchQuery.isNotBlank()
    val request by env.homeRequests.requests.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val openSheet = {
        sheetDue = vm.defaultDueForSelection()
        sheetOpen = true
    }
    LaunchedEffect(request) {
        when (val current = request) {
            HomeRequest.Create -> if (env.homeRequests.take(current)) openSheet()
            is HomeRequest.SelectDay -> if (env.homeRequests.take(current)) vm.select(current.day)
            null -> Unit
        }
    }
    val couldNotUpdate = stringResource(R.string.task_couldNotUpdate)
    val deletedText = stringResource(R.string.task_deleted)
    val deleter = rememberOccurrenceDeleter()
    OccurrenceDeleteHost(deleter)
    val rowActions = RowActions(
        onToggle = { task -> vm.toggle(task) { android.widget.Toast.makeText(context, couldNotUpdate.fill("title" to task.title), android.widget.Toast.LENGTH_SHORT).show() } },
        onOpen = { task -> onOpenTask(task.id) },
        onDelete = { task ->
            val message = deletedText.fill("title" to task.title)
            deleter.request(task, message, deleteThis = { vm.delete(task, message) })
        },
    )
    TasksScaffold(
        title = stringResource(R.string.nav_home),
        bottomPadding = bottomPadding,
        onOpenSettings = onOpenSettings,
        actions = {
            GlobalSearchButton()
            IconButton(
                onClick = {
                    if (searchOpen) searchQuery = ""
                    searchOpen = !searchOpen
                },
                modifier = Modifier.testTag("home-search-toggle"),
            ) {
                Icon(
                    if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = stringResource(if (searchOpen) R.string.common_close else R.string.common_search),
                )
            }
            if (!searchOpen) SortMenu(state, vm)
            IconButton(onClick = vm::toggleCalendar, modifier = Modifier.testTag("toggle-calendar")) {
                Icon(
                    if (state.calendarVisible) Icons.Filled.CalendarMonth else Icons.Outlined.CalendarMonth,
                    contentDescription = stringResource(if (state.calendarVisible) R.string.home_hideCalendar else R.string.home_showCalendar),
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = openSheet,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("home-fab"),
            ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.shell_fabNewTask)) }
        },
    ) { padding ->
        AlarmRefreshBox(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("screen-home")) {
            val wide = maxWidth >= Breakpoints.homeSideCalendar
            val quickAddHint = state.selectedDay?.let {
                stringResource(R.string.home_quickAddForDay).fill("date" to DateFormats.date(it.atStartOfDay(), format))
            } ?: stringResource(R.string.home_quickAddHint)
            val quickAdd: @Composable () -> Unit = {
                HomeQuickAdd(quickAddHint, prefillDue = vm::defaultDueForSelection)
            }
            val searchField: LazyListScope.() -> Unit = {
                if (searchOpen) {
                    item(key = "home-search-field") {
                        SearchField(searchQuery, { searchQuery = it }, stringResource(R.string.home_searchHint), "home-search")
                    }
                }
            }
            val searchResults: LazyListScope.(TaskSearchState) -> Unit = { results ->
                homeSearchResults(results, searchQuery, format, env.repository.zone, state.now, rowActions)
            }
            val results = if (searching) rememberHomeSearch(searchQuery) else null
            val calendar: @Composable () -> Unit = {
                MonthCalendar(
                    today = state.today,
                    markedDays = state.markedDays,
                    selectedDay = state.selectedDay,
                    onDaySelected = vm::select,
                    modifier = Modifier.padding(horizontal = Space.x2, vertical = Space.x2),
                )
            }
            val listPadding = PaddingValues(start = Space.x4, end = Space.x4, top = Space.x2, bottom = bottomPadding + 88.dp)
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("home-list"), contentPadding = listPadding) {
                        item { HomeAlarmCards() }
                        item { HomeBackupReminder() }
                        item { HomeOneDriveCards(onOpenAutoBackup) }
                        searchField()
                        item { quickAdd() }
                        if (results != null) searchResults(results) else homeBody(state, format, env.repository.zone, rowActions, vm::retry)
                    }
                    Box(
                        Modifier
                            .width(356.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(start = Space.x1, top = Space.x2, end = Space.x4, bottom = Space.x4),
                    ) {
                        if (state.calendarVisible) CalendarCard(calendar)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag("home-list"), contentPadding = listPadding) {
                    item { HomeAlarmCards() }
                    item { HomeBackupReminder() }
                    item { HomeOneDriveCards(onOpenAutoBackup) }
                    searchField()
                    item { quickAdd() }
                    if (results != null) {
                        searchResults(results)
                    } else {
                        if (state.calendarVisible) item { CalendarCard(calendar) }
                        homeBody(state, format, env.repository.zone, rowActions, vm::retry)
                    }
                }
            }
        }
        }
    }
    if (sheetOpen) {
        TaskCreateSheet(onDismiss = { sheetOpen = false }, initialDue = sheetDue)
    }
}

class RowActions(
    val onToggle: (Task) -> Unit,
    val onOpen: (Task) -> Unit,
    val onDelete: (Task) -> Unit,
)

@Composable
private fun CalendarCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, RemftThemeExtras.colors.hairline),
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.x2),
    ) { content() }
}

private fun LazyListScope.homeBody(
    state: HomeUiState,
    format: DateFormatId,
    zone: ZoneId,
    actions: RowActions,
    onRetry: () -> Unit,
) {
    when (val load = state.load) {
        ListLoad.Loading -> item {
            Box(Modifier.fillMaxWidth().padding(Space.x8), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        ListLoad.Failed -> item { ErrorState(onRetry = onRetry) }
        is ListLoad.Ready -> {
            if (load.items.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.BeachAccess,
                        stringResource(R.string.home_allCaughtUp),
                        stringResource(R.string.home_allCaughtUpBody),
                        Modifier.testTag("home-empty"),
                    )
                }
            }
            load.items.forEach { group -> homeGroup(group, format, zone, state.now, actions) }
        }
    }
}

private fun LazyListScope.homeGroup(group: HomeGroup, format: DateFormatId, zone: ZoneId, now: Instant, actions: RowActions) {
    val key = "${group.bucket}-${group.day}"
    item(key = "header-$key") {
        val label = group.day?.let { DateFormats.dayHeading(it.atStartOfDay(), format) } ?: stringResource(bucketLabel(group.bucket))
        Text(
            "$label · ${group.tasks.size}",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = when (group.bucket) {
                HomeBucket.Overdue -> MaterialTheme.colorScheme.error
                HomeBucket.SelectedDay -> RemftThemeExtras.colors.link
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = Space.x1, end = Space.x1, top = Space.x4, bottom = Space.x2).testTag("group-header-$key"),
        )
    }
    items(group.tasks, key = { "task-${it.id}" }) { task ->
        TaskRow(
            task = task,
            format = format,
            zone = zone,
            now = now,
            onToggle = { actions.onToggle(task) },
            onOpen = { actions.onOpen(task) },
            onDelete = { actions.onDelete(task) },
            dimmed = group.dimmed,
            highlighted = group.bucket == HomeBucket.SelectedDay,
        )
    }
}

fun bucketLabel(bucket: HomeBucket): Int = when (bucket) {
    HomeBucket.SelectedDay -> R.string.home_bucket_selectedDay
    HomeBucket.Overdue -> R.string.home_bucket_overdue
    HomeBucket.NoDate -> R.string.home_bucket_noDate
    HomeBucket.Today -> R.string.home_bucket_today
    HomeBucket.Tomorrow -> R.string.home_bucket_tomorrow
    HomeBucket.ThisWeek -> R.string.home_bucket_thisWeek
    HomeBucket.Next30Days -> R.string.home_bucket_next30Days
}

@Composable
private fun SortMenu(state: HomeUiState, vm: HomeViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("home-sort")) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.sort_tooltip))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.sort_sectionTitle),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.x4, vertical = Space.x2),
            )
            TaskSort.choices.forEach { choice ->
                val label = when (choice.id) {
                    "priority" -> R.string.sort_taskPriority
                    "title" -> R.string.sort_title
                    else -> R.string.sort_taskDate
                }
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = {
                        if (state.sort.id == choice.id) Icon(Icons.Outlined.Check, contentDescription = null) else Box(Modifier.width(24.dp))
                    },
                    onClick = {
                        open = false
                        vm.chooseSort(choice)
                    },
                    modifier = Modifier.testTag("sort-${choice.id}"),
                )
            }
            HorizontalDivider()
            val natural = TaskSort.choices.firstOrNull { it.id == state.sort.id }?.descendingByDefault ?: false
            val reversed = state.sort.descending != natural
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_reverse)) },
                leadingIcon = { Checkbox(checked = reversed, onCheckedChange = null) },
                onClick = {
                    open = false
                    vm.reverseSort()
                },
                modifier = Modifier.testTag("sort-reverse"),
            )
        }
    }
}
