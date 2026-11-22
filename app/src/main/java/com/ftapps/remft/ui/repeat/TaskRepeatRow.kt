package com.ftapps.remft.ui.repeat

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.data.repeat.SeriesRepository
import com.ftapps.remft.data.tasks.TasksRepository
import com.ftapps.remft.domain.repeat.RepeatEditor
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.repeat.SeriesRecord
import com.ftapps.remft.domain.repeat.SeriesTooDenseException
import com.ftapps.remft.domain.repeat.WallClock
import com.ftapps.remft.domain.tasks.Change
import com.ftapps.remft.domain.tasks.DefaultTime
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.tasks.TaskDefaults
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.rememberDateFormat
import com.ftapps.remft.ui.tasks.successSwitchColors
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object TaskRepeatActions {
    fun anchorFor(task: Task, now: LocalDateTime, zone: ZoneId): LocalDateTime {
        val at = task.dueAt ?: task.remindAt
        return at?.let { LocalDateTime.ofInstant(it, zone) } ?: DefaultTime.repeatStart(now)
    }

    suspend fun start(repository: TasksRepository, series: SeriesRepository, task: Task, rule: RepeatRule): String? {
        val zone = repository.zone
        val now = LocalDateTime.ofInstant(repository.now(), zone)
        var anchor = anchorFor(task, now, zone)
        if (task.dueAt == null && task.remindAt == null) {
            val first = RepeatEditor.firstRepeatDue(rule, anchor)
            if (first != null) {
                anchor = first
                repository.update(
                    task.id,
                    TaskPatch(
                        dueAt = Change(first.atZone(zone).toInstant()),
                        remindAt = Change(TaskDefaults.autoReminderFor(first, now)?.atZone(zone)?.toInstant()),
                    ),
                )
            }
        }
        return series.start(task.id, rule, anchor.atZone(zone).toInstant())
    }
}

@Composable
fun TaskRepeatRow(task: Task, modifier: Modifier = Modifier) {
    val env = LocalTasksEnvironment.current
    val series = env.series ?: return
    val repository = env.repository
    val zone = repository.zone
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val format = rememberDateFormat()
    val sentence = rememberRepeatSentence()
    val seriesId = task.seriesId
    val flow = remember(seriesId) { seriesId?.let { series.watch(it) } ?: flowOf(null) }
    val record: SeriesRecord? by flow.collectAsStateWithLifecycle(null)
    val rule = record?.let { runCatching { it.rule }.getOrNull() }
    var editing by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    val tooDense = stringResource(R.string.repeat_tooDense)
    val failed = stringResource(R.string.repeat_couldNotSave)
    val report: (Throwable) -> Unit = { error ->
        Toast.makeText(context, if (error is SeriesTooDenseException) tooDense else failed, Toast.LENGTH_SHORT).show()
    }
    val on = seriesId != null
    Column(modifier.fillMaxWidth().testTag("repeat-row")) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.repeat_switchTitle)) },
            supportingContent = { Text(stringResource(R.string.repeat_switchSubtitle)) },
            leadingContent = { Icon(Icons.Outlined.Repeat, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingContent = {
                Switch(
                    checked = on,
                    onCheckedChange = { wanted -> if (wanted) editing = true else stopping = true },
                    colors = successSwitchColors(),
                    modifier = Modifier.testTag("repeat-switch"),
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        val current = record
        if (current != null && rule != null) {
            Row(Modifier.fillMaxWidth().padding(start = Space.x4), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sentence.sentence(rule, format, current.anchorDay),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).testTag("repeat-summary"),
                )
                TextButton(onClick = { editing = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("repeat-change")) {
                    Text(stringResource(R.string.repeat_change))
                }
            }
        }
    }
    if (editing) {
        val now = LocalDateTime.ofInstant(repository.now(), zone)
        val today = now.toLocalDate()
        val current = record
        val anchor: LocalDate = current?.let { WallClock.wallClock(it.anchorAt, it.timezone).toLocalDate() }
            ?: TaskRepeatActions.anchorFor(task, now, zone).toLocalDate()
        RepeatEditorSheet(
            anchor = anchor,
            initial = rule,
            format = format,
            today = today,
            previewFrom = if (current != null) TaskRepeatActions.anchorFor(task, now, zone).toLocalDate() else null,
            onDismiss = { editing = false },
            onTurnOff = if (current != null) {
                {
                    editing = false
                    stopping = true
                }
            } else {
                null
            },
            onSave = { picked ->
                editing = false
                scope.launch {
                    runCatching {
                        if (current == null) {
                            TaskRepeatActions.start(repository, series, task, picked)
                        } else if (picked != rule) {
                            series.updateRule(current.id, picked)
                        }
                    }.onFailure(report)
                }
            },
        )
    }
    if (stopping) {
        val current = record
        StopRepeatDialog(
            afterCompletion = current?.let { runCatching { it.isCompletionSeries }.getOrDefault(false) } ?: false,
            onDismiss = { stopping = false },
            onConfirm = {
                stopping = false
                val id = task.seriesId
                if (id != null) scope.launch { runCatching { series.stop(id, keepTaskId = task.id) }.onFailure(report) }
            },
        )
    }
}
