package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ftapps.remft.R
import com.ftapps.remft.domain.alarm.AlarmProblem
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.tasks.HomeBucket
import com.ftapps.remft.domain.tasks.HomeGroup
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.tasks.TaskSort
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.alarm.AlarmProblemBannerContent
import com.ftapps.remft.ui.alarm.AlarmRefreshBox
import com.ftapps.remft.ui.alarm.HomeAlarmWarnings
import com.ftapps.remft.ui.alarm.HomeUrgentAlarm
import com.ftapps.remft.ui.alarm.UrgentAlarmCardContent
import com.ftapps.remft.ui.backup.BackupReminderCardContent
import com.ftapps.remft.ui.backup.HomeBackupReminder
import com.ftapps.remft.ui.components.ChipRow
import com.ftapps.remft.ui.components.CircleCheckbox
import com.ftapps.remft.ui.components.DueChip
import com.ftapps.remft.ui.components.GroupedCardItem
import com.ftapps.remft.ui.components.MarkedCard
import com.ftapps.remft.ui.components.NameMarkTopBar
import com.ftapps.remft.ui.components.PriorityChip
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.components.RemftListRow
import com.ftapps.remft.ui.components.RepeatChip
import com.ftapps.remft.ui.components.RoundIconButton
import com.ftapps.remft.ui.components.SquareFab
import com.ftapps.remft.ui.empty.EmptyIllustration
import com.ftapps.remft.ui.empty.IllustratedEmptyState
import com.ftapps.remft.ui.empty.IllustratedErrorState
import com.ftapps.remft.ui.onedrive.HomeOneDriveCards
import com.ftapps.remft.ui.quickadd.HomeQuickAdd
import com.ftapps.remft.ui.repeat.OccurrenceDeleteHost
import com.ftapps.remft.ui.repeat.rememberOccurrenceDeleter
import com.ftapps.remft.ui.search.GlobalSearchButton
import com.ftapps.remft.ui.search.SearchField
import com.ftapps.remft.ui.search.TaskSearchState
import com.ftapps.remft.ui.search.homeSearchResults
import com.ftapps.remft.ui.search.rememberHomeSearch
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.theme.groupPosition
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer

private val SideCalendarWidth = 356.dp

@Composable
fun HomeTasksScreen(bottomPadding: Dp, onOpenSettings: () -> Unit, onOpenTask: (String) -> Unit, onOpenAutoBackup: () -> Unit = {}) {
    val env = LocalTasksEnvironment.current
    val vm: HomeViewModel = viewModel { HomeViewModel(env) }
    val state by vm.state.collectAsStateWithLifecycle()
    val pendingDeletes by env.pendingDeletes.ids.collectAsStateWithLifecycle()
    val format = rememberDateFormat()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var sheetDue by rememberSaveable { mutableStateOf<java.time.LocalDateTime?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var monthOpen by rememberSaveable { mutableStateOf(false) }
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
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val barSpace = if (bottomPadding > 0.dp) Sizes.navBarHeight + navInset else navInset
    val bottomInset = maxOf(barSpace, imeInset)
    Column(Modifier.fillMaxSize()) {
        NameMarkTopBar {
            GlobalSearchButton()
            RemftIconButton(
                label = stringResource(R.string.settings_title),
                onClick = onOpenSettings,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.testTag("open-settings"),
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
            }
        }
        AlarmRefreshBox(Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxSize().testTag("screen-home")) {
                val wide = maxWidth >= Breakpoints.homeSideCalendar
                val quickAddHint = state.selectedDay?.let {
                    stringResource(R.string.home_quickAddForDay).fill("date" to DateFormats.date(it.atStartOfDay(), format))
                } ?: stringResource(R.string.home_quickAddHint)
                val results = if (searching) rememberHomeSearch(searchQuery) else null
                val listPadding = PaddingValues(start = Space.x4, end = Space.x4, top = 0.dp, bottom = bottomInset + Space.x4)
                val header: LazyListScope.() -> Unit = {
                    item(key = "home-header") {
                        HomeHeader(
                            dateLines = homeDateLines(state.today, format),
                            headline = homeHeadline(state),
                        ) {
                            RemftIconButton(
                                label = stringResource(if (searchOpen) R.string.common_close else R.string.lowui_filter),
                                onClick = {
                                    if (searchOpen) searchQuery = ""
                                    searchOpen = !searchOpen
                                },
                                colors = homeFilterToggleColors(searchOpen),
                                modifier = Modifier.testTag("home-search-toggle"),
                            ) {
                                Icon(
                                    if (searchOpen) Icons.Outlined.Close else Icons.Outlined.FilterList,
                                    contentDescription = stringResource(if (searchOpen) R.string.common_close else R.string.lowui_filter),
                                )
                            }
                            if (!searchOpen) {
                                ListSortMenu(state.sort, TaskSort.choices, SortLabels::home, "home-sort", vm::chooseSort, vm::reverseSort)
                            }
                            if (!wide && state.calendarVisible && !searching) {
                                RemftIconButton(label = stringResource(if (monthOpen) R.string.home_showWeek else R.string.home_showMonth), onClick = { monthOpen = !monthOpen }, modifier = Modifier.testTag("calendar-expand")) {
                                    Icon(
                                        if (monthOpen) Icons.Outlined.CalendarViewWeek else Icons.Outlined.CalendarViewMonth,
                                        contentDescription = stringResource(if (monthOpen) R.string.home_showWeek else R.string.home_showMonth),
                                    )
                                }
                            }
                            RemftIconButton(label = stringResource(if (state.calendarVisible) R.string.home_hideCalendar else R.string.home_showCalendar), onClick = vm::toggleCalendar, modifier = Modifier.testTag("toggle-calendar")) {
                                Icon(
                                    if (state.calendarVisible) Icons.Filled.CalendarMonth else Icons.Outlined.CalendarMonth,
                                    contentDescription = stringResource(if (state.calendarVisible) R.string.home_hideCalendar else R.string.home_showCalendar),
                                )
                            }
                        }
                    }
                    if (searchOpen) {
                        item(key = "home-search-field") {
                            SearchField(searchQuery, { searchQuery = it }, stringResource(R.string.home_searchHint), "home-search")
                        }
                    }
                    item(key = "home-quick-add") {
                        HomeQuickAddRow {
                            HomeQuickAdd(quickAddHint, prefillDue = vm::defaultDueForSelection, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(Space.x3))
                            SquareFab(
                                Icons.Filled.Add,
                                stringResource(R.string.shell_fabNewTask),
                                onClick = openSheet,
                                modifier = Modifier.padding(top = Space.x2).testTag("home-fab"),
                            )
                        }
                    }
                    if (!searching) {
                        item(key = "home-alarm-warnings") { HomeAlarmWarnings(hiddenTasks = pendingDeletes) }
                        item(key = "home-onedrive") { HomeOneDriveCards(onOpenAutoBackup) }
                        item(key = "home-backup") { HomeBackupReminder() }
                        item(key = "home-urgent-alarm") { HomeUrgentAlarm(onOpenTask = onOpenTask, hiddenTasks = pendingDeletes) }
                    }
                }
                val searchResults: LazyListScope.(TaskSearchState) -> Unit = { found ->
                    homeSearchResults(found, searchQuery, format, env.repository.zone, state.now, rowActions)
                }
                val monthCalendar: @Composable () -> Unit = {
                    MonthCalendar(
                        today = state.today,
                        markedDays = state.markedDays,
                        selectedDay = state.selectedDay,
                        onDaySelected = vm::select,
                        modifier = Modifier.padding(horizontal = Space.x2, vertical = Space.x2),
                    )
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("home-list"), contentPadding = listPadding) {
                            header()
                            if (results != null) searchResults(results) else homeBody(state, format, env.repository.zone, rowActions, vm::retry)
                        }
                        Box(
                            Modifier
                                .width(SideCalendarWidth)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(start = Space.x1, top = Space.x4, end = Space.x4, bottom = Space.x4),
                        ) {
                            if (state.calendarVisible) CalendarCard(monthCalendar)
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().testTag("home-list"), contentPadding = listPadding) {
                        header()
                        if (results != null) {
                            searchResults(results)
                        } else {
                            if (state.calendarVisible) {
                                item(key = "home-calendar") {
                                    if (monthOpen) {
                                        CalendarCard(monthCalendar)
                                    } else {
                                        WeekStrip(
                                            today = state.today,
                                            markedDays = state.markedDays,
                                            selectedDay = state.selectedDay,
                                            onDaySelected = vm::select,
                                            modifier = Modifier.padding(vertical = Space.x2),
                                            format = format,
                                        )
                                    }
                                }
                            }
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

private fun homeDateLines(today: LocalDate, format: DateFormatId): List<String> {
    if (today == LocalDate.MIN) return listOf("")
    val date = DateFormats.short(today.atStartOfDay(), format, withTime = false)
    val full = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val short = today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    return listOf("$full, $date", "$short, $date", date)
}

@Composable
private fun homeHeadline(state: HomeUiState): String? {
    if (state.load !is ListLoad.Ready) return null
    val count = state.dueCount
    return if (count == 0) stringResource(R.string.home_headlineNone) else pluralStringResource(R.plurals.home_headlineCount, count, count)
}

@Composable
fun HomeHeader(dateLines: List<String>, headline: String?, modifier: Modifier = Modifier, tools: @Composable () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(top = Space.x2, bottom = Space.x3).testTag("home-header")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FittingDateLine(dateLines, scheme.onSurfaceVariant, Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) { tools() }
            }
        }
        if (headline != null) {
            Text(
                headline,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.W700),
                color = scheme.onBackground,
                modifier = Modifier.semantics { heading() }.testTag("home-headline"),
            )
        }
    }
}

@Composable
private fun FittingDateLine(lines: List<String>, color: Color, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyLarge
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val shown = remember(lines, width, style, measurer) {
            lines.firstOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= width } ?: lines.last()
        }
        val full = lines.first()
        Text(
            shown,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .semantics { if (shown != full) contentDescription = full }
                .testTag("home-date"),
        )
    }
}

@Composable
fun HomeQuickAddRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(bottom = Space.x1).testTag("home-quick-add-row"),
        verticalAlignment = Alignment.Top,
        content = content,
    )
}

@Composable
private fun homeFilterToggleColors(active: Boolean): IconButtonColors {
    val scheme = MaterialTheme.colorScheme
    return if (active) {
        IconButtonDefaults.iconButtonColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer)
    } else {
        IconButtonDefaults.iconButtonColors(contentColor = scheme.onSurfaceVariant)
    }
}

@Composable
private fun CalendarCard(content: @Composable () -> Unit) {
    MarkedCard(modifier = Modifier.fillMaxWidth().padding(vertical = Space.x2)) { content() }
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
        ListLoad.Failed -> item { IllustratedErrorState(onRetry = onRetry) }
        is ListLoad.Ready -> {
            if (load.items.isEmpty()) {
                item {
                    IllustratedEmptyState(EmptyIllustration.Home,
                        
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
            style = MaterialTheme.typography.labelLarge,
            color = when (group.bucket) {
                HomeBucket.Overdue -> RemftTheme.colors.overdue.color
                HomeBucket.SelectedDay -> MaterialTheme.colorScheme.primary
                HomeBucket.Today -> RemftTheme.colors.today.color
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(start = Space.x1, end = Space.x1, top = Space.x4, bottom = Space.x2)
                .semantics { heading() }
                .testTag("group-header-$key"),
        )
    }
    itemsIndexed(group.tasks, key = { _, task -> "task-${task.id}" }) { index, task ->
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
            position = groupPosition(index, group.tasks.size),
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

@Preview(name = "Home light", widthDp = 411, heightDp = 1000)
@Composable
private fun HomeLightPreview() {
    RemftTheme(darkTheme = false) { HomePreviewContent() }
}

@Preview(name = "Home dark", widthDp = 411, heightDp = 1000)
@Composable
private fun HomeDarkPreview() {
    RemftTheme(darkTheme = true) { HomePreviewContent() }
}

@Composable
private fun HomePreviewContent() {
    val today = LocalDate.of(2026, 11, 22)
    Box(Modifier.fillMaxSize().background(RemftTheme.tonal.level0)) {
        Column {
            NameMarkTopBar {
                RoundIconButton(Icons.Outlined.Search, "Search", onClick = {})
            }
            Column(Modifier.padding(horizontal = Space.x4)) {
                HomeHeader(listOf("Sunday, 22 Nov", "Sun, 22 Nov", "22 Nov"), "4 things today")
                HomeQuickAddRow {
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(vertical = Space.x2)
                            .height(56.dp)
                            .background(MaterialTheme.colorScheme.surface, MarkedShapes.pill),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("Quick add a task…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Space.x5))
                    }
                    Spacer(Modifier.width(Space.x3))
                    SquareFab(Icons.Filled.Add, "New task", onClick = {}, modifier = Modifier.padding(top = Space.x2))
                }
                AlarmProblemBannerContent(AlarmProblem.NOTIFICATIONS_OFF, onClick = {})
                BackupReminderCardContent(onLater = {}, onBackUpNow = {}, modifier = Modifier.padding(vertical = Space.x2))
                UrgentAlarmCardContent("Call the electrician", "6:30 pm", "Rings in 2 h 10 min", onClick = {})
                WeekStrip(today, setOf(today, today.plusDays(1), today.plusDays(3), today.plusDays(5)), null, onDaySelected = {}, modifier = Modifier.padding(vertical = Space.x2))
                Spacer(Modifier.height(Space.x2))
                PreviewRow("Pay the electricity bill", GroupPosition.First) { DueChip("Overdue — 21 Nov", overdue = true, today = false) }
                PreviewRow("Book train tickets", GroupPosition.Middle) {
                    DueChip("Due 6:00 pm", overdue = false, today = true)
                    PriorityChip("High priority", strong = true)
                }
                PreviewRow("Water the balcony plants", GroupPosition.Middle) {
                    DueChip("Due 7:00 pm", overdue = false, today = true)
                    RepeatChip("Repeats")
                }
                GroupedCardItem(GroupPosition.Last) {
                    RemftListRow("Send the rent receipt", struck = true, leading = { CircleCheckbox(true, "Reopen", onToggle = {}) })
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(title: String, position: GroupPosition, chips: @Composable () -> Unit) {
    GroupedCardItem(position) {
        RemftListRow(title, leading = { CircleCheckbox(false, "Complete", onToggle = {}) }, below = { ChipRow { chips() } })
    }
}
