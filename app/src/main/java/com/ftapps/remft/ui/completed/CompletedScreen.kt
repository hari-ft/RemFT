package com.ftapps.remft.ui.completed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.db.entity.TaskEntity
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.ui.components.CircleCheckbox
import com.ftapps.remft.ui.components.GroupedCardItem
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.components.RemftListRow
import com.ftapps.remft.ui.components.RemftSnackbar
import com.ftapps.remft.ui.components.RemftTopBar
import com.ftapps.remft.ui.empty.EmptyIllustration
import com.ftapps.remft.ui.empty.IllustratedEmptyState
import com.ftapps.remft.ui.files.LocalFilesEnvironment
import com.ftapps.remft.ui.files.rememberDateFormat
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.theme.groupPosition
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

sealed interface CompletedRow {
    data class Header(val day: LocalDate?) : CompletedRow
    data class Item(val task: TaskEntity) : CompletedRow
}

object CompletedArchive {
    const val PAGE_SIZE = 50

    fun dayOf(task: TaskEntity, zone: ZoneId): LocalDate? =
        UtcText.parse(task.dueAt ?: task.completedAt)?.atZone(zone)?.toLocalDate()

    fun rows(tasks: List<TaskEntity>, zone: ZoneId): List<CompletedRow> {
        val out = mutableListOf<CompletedRow>()
        var started = false
        var current: LocalDate? = null
        for (task in tasks) {
            val day = dayOf(task, zone)
            if (!started || day != current) {
                started = true
                current = day
                out += CompletedRow.Header(day)
            }
            out += CompletedRow.Item(task)
        }
        return out
    }

    fun shouldGrow(loaded: Int, pages: Int, lastVisible: Int, total: Int): Boolean =
        loaded >= pages * PAGE_SIZE && lastVisible >= total - 10
}

private fun itemPositions(rows: List<CompletedRow>): List<GroupPosition> {
    val out = MutableList(rows.size) { GroupPosition.Single }
    var start = 0
    while (start < rows.size) {
        if (rows[start] is CompletedRow.Header) {
            start++
            continue
        }
        var end = start
        while (end < rows.size && rows[end] is CompletedRow.Item) end++
        for (i in start until end) out[i] = groupPosition(i - start, end - start)
        start = end
    }
    return out
}

@Composable
private fun headerLabel(day: LocalDate?, today: LocalDate, format: DateFormatId): String = when {
    day == null -> stringResource(R.string.task_notSet)
    day == today -> stringResource(R.string.completed_today)
    day == today.minusDays(1) -> stringResource(R.string.completed_yesterday)
    else -> DateFormats.date(day.atStartOfDay(), format)
}

@Composable
fun CompletedScreen(onBack: () -> Unit, onOpenTask: (String) -> Unit) {
    val env = LocalFilesEnvironment.current
    val tasksEnv = LocalTasksEnvironment.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pages by rememberSaveable { mutableIntStateOf(1) }
    val tasks by remember(env, pages) { env?.files?.watchCompleted(pages * CompletedArchive.PAGE_SIZE) ?: flowOf(emptyList()) }.collectAsState(null)
    val zone = tasksEnv.repository.zone
    val reopened = stringResource(R.string.completed_reopened)
    val couldNotUpdate = stringResource(R.string.task_couldNotUpdate)
    Scaffold(
        containerColor = RemftTheme.tonal.level0,
        snackbarHost = { SnackbarHost(snackbar) { RemftSnackbar(it) } },
        topBar = {
            RemftTopBar(
                title = stringResource(R.string.completed_title),
                navigationIcon = {
                    RemftIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, label = stringResource(R.string.common_close), onClick = onBack, modifier = Modifier.testTag("back"))
                },
            )
        },
        modifier = Modifier.testTag("screen-completed"),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val loaded = tasks
            when {
                loaded == null -> CircularProgressIndicator(Modifier.padding(Space.x8))
                loaded.isEmpty() -> IllustratedEmptyState(EmptyIllustration.Completed,
                    
                    stringResource(R.string.completed_emptyTitle),
                    stringResource(R.string.completed_emptyBody),
                    Modifier.testTag("completed-empty"),
                )
                else -> {
                    val format = env?.let { rememberDateFormat(it) } ?: DateFormatId.System
                    val today = tasksEnv.repository.now().atZone(zone).toLocalDate()
                    val rows = remember(loaded, zone) { CompletedArchive.rows(loaded, zone) }
                    val positions = remember(rows) { itemPositions(rows) }
                    val listState = rememberLazyListState()
                    val lastVisible by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
                    LaunchedEffect(lastVisible, loaded.size, pages) {
                        if (CompletedArchive.shouldGrow(loaded.size, pages, lastVisible, rows.size + 1)) pages += 1
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.widthIn(max = Breakpoints.detailMaxWidth).fillMaxWidth().testTag("completed-list"),
                        contentPadding = PaddingValues(start = Space.x4, end = Space.x4, top = Space.x1, bottom = Space.x6),
                    ) {
                        item { CompletedScopeNote(stringResource(R.string.completed_scope)) }
                        itemsIndexed(rows, key = { _, row -> if (row is CompletedRow.Item) row.task.id else "h-${(row as CompletedRow.Header).day}" }) { index, row ->
                            when (row) {
                                is CompletedRow.Header -> CompletedDayHeader(
                                    headerLabel(row.day, today, format),
                                    Modifier.testTag("completed-header-${row.day}"),
                                )
                                is CompletedRow.Item -> CompletedTaskRow(
                                    task = row.task,
                                    position = positions[index],
                                    onOpen = { onOpenTask(row.task.id) },
                                    onReopen = {
                                        scope.launch {
                                            val done = runCatching { tasksEnv.repository.setCompleted(row.task.id, false) }.onFailure { AppLogs.warn("tasks.complete_failed", it) }.isSuccess
                                            snackbar.showSnackbar((if (done) reopened else couldNotUpdate).fill("title" to row.task.title))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletedScopeNote(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Space.x2)
            .background(RemftTheme.tonal.level2, MarkedShapes.small)
            .padding(horizontal = Space.x3, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.TaskAlt, contentDescription = null, tint = RemftTheme.colors.done.color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.x3))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CompletedDayHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = RemftTheme.colors.done.color,
        modifier = modifier
            .padding(start = Space.x1, end = Space.x1, top = Space.x4, bottom = Space.x2)
            .semantics { heading() },
    )
}

@Composable
private fun CompletedTaskRow(task: TaskEntity, position: GroupPosition, onOpen: () -> Unit, onReopen: () -> Unit) {
    GroupedCardItem(position, Modifier.testTag("completed-${task.id}")) {
        RemftListRow(
            headline = task.title,
            struck = true,
            onClick = onOpen,
            leading = {
                CircleCheckbox(
                    checked = true,
                    description = stringResource(R.string.completed_reopen),
                    onToggle = onReopen,
                    modifier = Modifier.testTag("reopen-${task.id}"),
                )
            },
        )
    }
}

@Preview(name = "Completed light", widthDp = 411)
@Composable
private fun CompletedLightPreview() {
    RemftTheme(darkTheme = false) { CompletedPreviewContent() }
}

@Preview(name = "Completed dark", widthDp = 411)
@Composable
private fun CompletedDarkPreview() {
    RemftTheme(darkTheme = true) { CompletedPreviewContent() }
}

@Composable
private fun CompletedPreviewContent() {
    Column(Modifier.background(RemftTheme.tonal.level0).padding(Space.x4)) {
        CompletedScopeNote("Completed tasks, newest first.")
        CompletedDayHeader("Today")
        PreviewDoneRow("Send the rent receipt", GroupPosition.First)
        PreviewDoneRow("Water the balcony plants", GroupPosition.Last)
        CompletedDayHeader("Yesterday")
        PreviewDoneRow("Book train tickets for December", GroupPosition.Single)
    }
}

@Composable
private fun PreviewDoneRow(title: String, position: GroupPosition) {
    GroupedCardItem(position) {
        RemftListRow(title, struck = true, onClick = {}, leading = { CircleCheckbox(true, "Reopen", onToggle = {}) })
    }
}
