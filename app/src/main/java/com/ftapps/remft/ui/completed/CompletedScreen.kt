package com.ftapps.remft.ui.completed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.data.db.entity.TaskEntity
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.ui.components.EmptyState
import com.ftapps.remft.ui.files.LocalFilesEnvironment
import com.ftapps.remft.ui.files.rememberDateFormat
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.Radius
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

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

@Composable
private fun headerLabel(day: LocalDate?, today: LocalDate, format: DateFormatId): String = when {
    day == null -> stringResource(R.string.task_notSet)
    day == today -> stringResource(R.string.completed_today)
    day == today.minusDays(1) -> stringResource(R.string.completed_yesterday)
    else -> DateFormats.date(day.atStartOfDay(), format)
}

@OptIn(ExperimentalMaterial3Api::class)
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
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.completed_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        modifier = Modifier.testTag("screen-completed"),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val loaded = tasks
            when {
                loaded == null -> CircularProgressIndicator(Modifier.padding(Space.x8))
                loaded.isEmpty() -> EmptyState(
                    Icons.Outlined.CheckCircleOutline,
                    stringResource(R.string.completed_emptyTitle),
                    stringResource(R.string.completed_emptyBody),
                    Modifier.testTag("completed-empty"),
                )
                else -> {
                    val format = env?.let { rememberDateFormat(it) } ?: DateFormatId.System
                    val today = tasksEnv.repository.now().atZone(zone).toLocalDate()
                    val rows = remember(loaded, zone) { CompletedArchive.rows(loaded, zone) }
                    val listState = rememberLazyListState()
                    val lastVisible by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
                    LaunchedEffect(lastVisible, loaded.size, pages) {
                        if (CompletedArchive.shouldGrow(loaded.size, pages, lastVisible, rows.size + 1)) pages += 1
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.widthIn(max = Breakpoints.detailMaxWidth).fillMaxWidth().testTag("completed-list"),
                        contentPadding = PaddingValues(horizontal = Space.x4, vertical = Space.x2),
                    ) {
                        item {
                            Text(
                                stringResource(R.string.completed_scope),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = Space.x2, end = Space.x2, top = Space.x1, bottom = Space.x3),
                            )
                        }
                        items(rows, key = { row -> if (row is CompletedRow.Item) row.task.id else "h-${(row as CompletedRow.Header).day}" }) { row ->
                            when (row) {
                                is CompletedRow.Header -> Text(
                                    headerLabel(row.day, today, format),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = Space.x2, end = Space.x2, top = Space.x4, bottom = Space.x1).testTag("completed-header-${row.day}"),
                                )
                                is CompletedRow.Item -> CompletedTaskRow(
                                    task = row.task,
                                    onOpen = { onOpenTask(row.task.id) },
                                    onReopen = {
                                        scope.launch {
                                            tasksEnv.repository.setCompleted(row.task.id, false)
                                            snackbar.showSnackbar(reopened.fill("title" to row.task.title))
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
private fun CompletedTaskRow(task: TaskEntity, onOpen: () -> Unit, onReopen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        border = BorderStroke(1.dp, RemftThemeExtras.colors.hairline),
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.x1).testTag("completed-${task.id}"),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onOpen).padding(start = Space.x4, end = Space.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = Space.x2)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge.copy(textDecoration = TextDecoration.LineThrough),
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onReopen, modifier = Modifier.testTag("reopen-${task.id}")) {
                Icon(Icons.Outlined.Replay, contentDescription = stringResource(R.string.completed_reopen))
            }
        }
    }
}
