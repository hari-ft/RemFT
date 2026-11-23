package com.ftapps.remft.ui.scheduled

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ftapps.remft.R
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.tasks.ScheduledBucket
import com.ftapps.remft.domain.tasks.ScheduledEntry
import com.ftapps.remft.domain.tasks.ScheduledSection
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.tasks.TaskPriority
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.components.ChipRow
import com.ftapps.remft.ui.components.ChipTone
import com.ftapps.remft.ui.components.CircleCheckbox
import com.ftapps.remft.ui.components.DueChip
import com.ftapps.remft.ui.components.GroupedCardItem
import com.ftapps.remft.ui.components.PriorityChip
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.components.RemftListRow
import com.ftapps.remft.ui.components.RemftSnackbar
import com.ftapps.remft.ui.components.RemftTopBar
import com.ftapps.remft.ui.components.RepeatChip
import com.ftapps.remft.ui.components.StatusChip
import com.ftapps.remft.ui.empty.EmptyIllustration
import com.ftapps.remft.ui.empty.IllustratedEmptyState
import com.ftapps.remft.ui.empty.IllustratedErrorState
import com.ftapps.remft.ui.tasks.ListLoad
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.priorityLabel
import com.ftapps.remft.ui.tasks.rememberDateFormat
import com.ftapps.remft.ui.tasks.showsChip
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.theme.groupPosition
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

object ScheduledLabels {
    private val weekday: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

    fun headerKey(bucket: ScheduledBucket): String = when (bucket) {
        ScheduledBucket.Overdue -> "overdue"
        is ScheduledBucket.Day -> bucket.date.toString()
    }

    fun weekdayOf(day: LocalDate): String = weekday.format(day)

    fun dateOf(day: LocalDate, today: LocalDate, format: DateFormatId): String =
        if (day.year == today.year) {
            DateFormats.short(day.atStartOfDay(), format, withTime = false)
        } else {
            DateFormats.date(day.atStartOfDay(), format)
        }

    fun timeOf(at: Instant, rowDay: LocalDate?, zone: ZoneId, format: DateFormatId): String {
        val local = LocalDateTime.ofInstant(at, zone)
        return if (local.toLocalDate() == rowDay) DateFormats.time(local, format) else DateFormats.short(local, format)
    }
}

@Composable
private fun headerLabel(bucket: ScheduledBucket, today: LocalDate, format: DateFormatId): String = when (bucket) {
    ScheduledBucket.Overdue -> stringResource(R.string.home_bucket_overdue)
    is ScheduledBucket.Day -> when (bucket.date) {
        today -> stringResource(R.string.home_bucket_today)
        today.plusDays(1) -> stringResource(R.string.home_bucket_tomorrow)
        else -> stringResource(R.string.sched_dayHeader).fill(
            "weekday" to ScheduledLabels.weekdayOf(bucket.date),
            "date" to ScheduledLabels.dateOf(bucket.date, today, format),
        )
    }
}

@Composable
fun ScheduledScreen(onBack: () -> Unit, onOpenTask: (String) -> Unit) {
    val env = LocalTasksEnvironment.current
    val vm: ScheduledViewModel = viewModel { ScheduledViewModel(env) }
    val state by vm.state.collectAsStateWithLifecycle()
    val format = rememberDateFormat()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val completedText = stringResource(R.string.sched_completed)
    val couldNotUpdate = stringResource(R.string.task_couldNotUpdate)
    val undoLabel = stringResource(R.string.common_undo)
    val complete: (Task) -> Unit = { task ->
        val failed = { scope.launch { snackbar.showSnackbar(couldNotUpdate.fill("title" to task.title)) }; Unit }
        vm.complete(
            task,
            onDone = {
                scope.launch {
                    val result = snackbar.showSnackbar(completedText.fill("title" to task.title), actionLabel = undoLabel, duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) vm.reopen(task, onFailed = failed)
                }
            },
            onFailed = failed,
        )
    }
    Scaffold(
        containerColor = RemftTheme.tonal.level0,
        snackbarHost = { SnackbarHost(snackbar) { RemftSnackbar(it) } },
        topBar = {
            RemftTopBar(
                title = stringResource(R.string.sched_title),
                navigationIcon = {
                    RemftIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, label = stringResource(R.string.common_close), onClick = onBack, modifier = Modifier.testTag("back"))
                },
            )
        },
        modifier = Modifier.testTag("screen-scheduled"),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (val load = state.load) {
                ListLoad.Loading -> CircularProgressIndicator(Modifier.padding(Space.x8))
                ListLoad.Failed -> IllustratedErrorState(onRetry = vm::retry)
                is ListLoad.Ready -> if (load.items.isEmpty()) {
                    IllustratedEmptyState(
                        EmptyIllustration.Home,
                        stringResource(R.string.sched_emptyTitle),
                        stringResource(R.string.sched_emptyBody),
                        Modifier.testTag("sched-empty"),
                    )
                } else {
                    ScheduledList(load.items, state.today, state.now, state.zone, format, onOpenTask, complete)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScheduledList(
    sections: List<ScheduledSection>,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
    format: DateFormatId,
    onOpenTask: (String) -> Unit,
    onComplete: (Task) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.widthIn(max = Breakpoints.detailMaxWidth).fillMaxWidth().testTag("sched-list"),
        contentPadding = PaddingValues(start = Space.x4, end = Space.x4, bottom = Space.x6),
    ) {
        for (section in sections) {
            val bucket = section.bucket
            val key = ScheduledLabels.headerKey(bucket)
            stickyHeader(key = "h-$key", contentType = "header") {
                ScheduledHeader(headerLabel(bucket, today, format), bucket == ScheduledBucket.Overdue, Modifier.testTag("sched-header-$key"))
            }
            val rowDay = (bucket as? ScheduledBucket.Day)?.date ?: today
            itemsIndexed(section.entries, key = { _, entry -> entry.task.id }, contentType = { _, _ -> "task" }) { index, entry ->
                ScheduledRow(
                    entry = entry,
                    position = groupPosition(index, section.entries.size),
                    overdue = bucket == ScheduledBucket.Overdue,
                    rowDay = rowDay,
                    today = today,
                    now = now,
                    zone = zone,
                    format = format,
                    onOpen = { onOpenTask(entry.task.id) },
                    onComplete = { onComplete(entry.task) },
                    modifier = itemMotion(),
                )
            }
        }
    }
}

@Composable
private fun LazyItemScope.itemMotion(): Modifier {
    val motion = RemftTheme.motion
    return Modifier.animateItem(
        fadeInSpec = motion.quick(),
        placementSpec = if (motion.reduced) null else motion.standard(),
        fadeOutSpec = motion.quick(),
    )
}

@Composable
private fun ScheduledHeader(label: String, overdue: Boolean, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (overdue) RemftTheme.colors.overdue.color else MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .background(RemftTheme.tonal.level0)
            .padding(start = Space.x1, end = Space.x1, top = Space.x3, bottom = Space.x1)
            .semantics { heading() },
    )
}

@Composable
private fun ScheduledRow(
    entry: ScheduledEntry,
    position: GroupPosition,
    overdue: Boolean,
    rowDay: LocalDate,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
    format: DateFormatId,
    onOpen: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val task = entry.task
    val priority = TaskPriority.parse(task.priority)
    val remind = task.remindAt
    GroupedCardItem(position, modifier.testTag("sched-row-${task.id}")) {
        RemftListRow(
            headline = task.title,
            onClick = onOpen,
            leading = {
                CircleCheckbox(
                    checked = false,
                    description = stringResource(R.string.task_completeNamed).fill("title" to task.title),
                    onToggle = onComplete,
                    modifier = Modifier.testTag("sched-check-${task.id}"),
                )
            },
            below = {
                ChipRow {
                    DueChip(
                        ScheduledLabels.timeOf(entry.at, if (overdue) today else rowDay, zone, format),
                        overdue = overdue || !entry.at.isAfter(now),
                        today = rowDay == today,
                        modifier = Modifier.testTag("sched-time-${task.id}"),
                    )
                    if (entry.snoozed) StatusChip(stringResource(R.string.sched_snoozed), ChipTone.Warning, icon = Icons.Outlined.Snooze)
                    if (task.isUrgent) StatusChip(stringResource(R.string.task_urgent), ChipTone.Urgent, icon = Icons.Filled.NotificationImportant)
                    if (task.isRecurring) RepeatChip(stringResource(R.string.repeat_badgeTooltip))
                    if (priority.showsChip()) {
                        val text = stringResource(R.string.task_prioritySemantic).fill("priority" to stringResource(priorityLabel(task.priority)))
                        if (priority == TaskPriority.Low) StatusChip(text, ChipTone.File) else PriorityChip(text, strong = true)
                    }
                    if (remind != null && remind != entry.at) {
                        StatusChip(
                            stringResource(R.string.sched_reminder).fill("time" to ScheduledLabels.timeOf(remind, entry.at.atZone(zone).toLocalDate(), zone, format)),
                            ChipTone.Neutral,
                            icon = Icons.Outlined.NotificationsNone,
                        )
                    }
                    if (task.alarmsMutedAt != null) StatusChip(stringResource(R.string.task_alarmsMuted), ChipTone.Neutral, icon = Icons.Outlined.NotificationsOff)
                }
            },
        )
    }
}

private val previewZone: ZoneId = ZoneId.of("Asia/Kolkata")
private val previewNow: Instant = LocalDateTime.of(2026, 11, 20, 10, 0).atZone(previewZone).toInstant()

private fun previewEntry(id: String, title: String, local: String, urgent: Boolean = false, series: Boolean = false, priority: String = "none", remind: String? = null): ScheduledEntry {
    val at = LocalDateTime.parse(local).atZone(previewZone).toInstant()
    val task = Task(
        id = id,
        workspaceId = "W1",
        title = title,
        priority = priority,
        dueAt = at,
        remindAt = remind?.let { LocalDateTime.parse(it).atZone(previewZone).toInstant() },
        isUrgent = urgent,
        seriesId = if (series) "S1" else null,
    )
    return ScheduledEntry(task, at, snoozed = false)
}

@Preview(name = "Scheduled light", widthDp = 411)
@Composable
private fun ScheduledLightPreview() {
    RemftTheme(darkTheme = false) { ScheduledPreviewContent() }
}

@Preview(name = "Scheduled dark", widthDp = 411)
@Composable
private fun ScheduledDarkPreview() {
    RemftTheme(darkTheme = true) { ScheduledPreviewContent() }
}

@Composable
private fun ScheduledPreviewContent() {
    val today = previewNow.atZone(previewZone).toLocalDate()
    val overdue = previewEntry("P1", "Pay the electricity bill", "2026-11-19T18:00", urgent = true)
    val first = previewEntry("P2", "Water the balcony plants", "2026-11-20T18:30", series = true, remind = "2026-11-20T18:00")
    val second = previewEntry("P3", "Call the plumber", "2026-11-20T20:00", priority = "high")
    val later = previewEntry("P4", "Book train tickets", "2026-11-24T09:00")
    Column(Modifier.background(RemftTheme.tonal.level0).padding(Space.x4)) {
        ScheduledHeader("Overdue", overdue = true)
        PreviewRow(overdue, GroupPosition.Single, true, today)
        ScheduledHeader("Today", overdue = false)
        PreviewRow(first, GroupPosition.First, false, today)
        PreviewRow(second, GroupPosition.Last, false, today)
        ScheduledHeader("Tue, 24 Nov", overdue = false)
        PreviewRow(later, GroupPosition.Single, false, today.plusDays(4))
    }
}

@Composable
private fun PreviewRow(entry: ScheduledEntry, position: GroupPosition, overdue: Boolean, day: LocalDate) {
    ScheduledRow(entry, position, overdue, day, previewNow.atZone(previewZone).toLocalDate(), previewNow, previewZone, DateFormatId.System, {}, {})
}
