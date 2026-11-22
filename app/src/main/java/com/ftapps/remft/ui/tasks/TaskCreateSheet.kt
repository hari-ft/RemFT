package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.NotificationImportant
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.tasks.Change
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.tasks.TaskDefaults
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.domain.tasks.TaskPriority
import com.ftapps.remft.ui.repeat.CreateSheetRepeatRow
import com.ftapps.remft.ui.repeat.RepeatDraft
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.Radius
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class TaskSheetState(
    title: String,
    description: String,
    priority: String,
    dueAt: LocalDateTime?,
    remindAt: LocalDateTime?,
    isUrgent: Boolean,
    remindAuto: Boolean,
) {
    var title by mutableStateOf(title)
    var description by mutableStateOf(description)
    var priority by mutableStateOf(priority)
    var dueAt by mutableStateOf(dueAt)
    var remindAt by mutableStateOf(remindAt)
    var isUrgent by mutableStateOf(isUrgent)
    var remindAuto by mutableStateOf(remindAuto)

    fun syncAutoReminder(now: LocalDateTime) {
        val due = dueAt
        if (due != null && (remindAt == null || remindAuto)) {
            remindAt = TaskDefaults.autoReminderFor(due, now)
            remindAuto = true
        } else if (due == null && remindAuto) {
            remindAt = null
            remindAuto = false
        }
    }

    fun changeDue(value: LocalDateTime?, now: LocalDateTime) {
        dueAt = value
        syncAutoReminder(now)
    }

    fun changeRemind(value: LocalDateTime?) {
        remindAt = value
        remindAuto = false
    }

    val descriptionOrNull: String? get() = description.trim().ifEmpty { null }

    companion object {
        fun forCreate(initialDue: LocalDateTime?, now: LocalDateTime): TaskSheetState =
            TaskSheetState("", "", TaskPriority.Medium.db, initialDue, null, isUrgent = true, remindAuto = false)
                .also { it.syncAutoReminder(now) }

        fun forEdit(task: Task, toLocal: (java.time.Instant) -> LocalDateTime): TaskSheetState = TaskSheetState(
            task.title,
            task.description.orEmpty(),
            task.priority,
            task.dueAt?.let(toLocal),
            task.remindAt?.let(toLocal),
            task.isUrgent,
            remindAuto = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCreateSheet(
    onDismiss: () -> Unit,
    initialDue: LocalDateTime? = null,
    editing: Task? = null,
    initialTitle: String = "",
) {
    val env = LocalTasksEnvironment.current
    val repository = env.repository
    val zone = repository.zone
    val now = { LocalDateTime.ofInstant(repository.now(), repository.zone) }
    val state = remember(editing?.id) {
        if (editing != null) TaskSheetState.forEdit(editing) { it.toLocal(zone) } else TaskSheetState.forCreate(initialDue, now()).also { it.title = initialTitle }
    }
    val repeatDraft = remember { RepeatDraft() }
    var saving by remember { mutableStateOf(false) }
    var titleError by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val save: () -> Unit = save@{
        if (state.title.isBlank()) {
            titleError = true
            return@save
        }
        saving = true
        saveError = false
        scope.launch {
            val ok = runCatching {
                if (editing != null) {
                    repository.update(
                        editing.id,
                        TaskPatch(
                            title = state.title.trim(),
                            description = Change(state.descriptionOrNull),
                            priority = state.priority,
                            dueAt = Change(state.dueAt?.toInstant(zone)),
                            remindAt = Change(state.remindAt?.toInstant(zone)),
                            isUrgent = state.isUrgent,
                        ),
                    )
                } else {
                    val createdId = repository.create(
                        TaskDraft(
                            title = state.title.trim(),
                            description = state.descriptionOrNull,
                            priority = state.priority,
                            dueAt = state.dueAt?.toInstant(zone),
                            remindAt = state.remindAt?.toInstant(zone),
                            isUrgent = state.isUrgent,
                        ),
                    )
                    val rule = repeatDraft.rule
                    val series = env.series
                    if (rule != null && series != null) {
                        runCatching { series.start(createdId, rule, repeatDraft.anchor(state, now()).toInstant(zone)) }
                    }
                }
            }.isSuccess
            saving = false
            if (ok) onDismiss() else saveError = true
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetMaxWidth = Breakpoints.sheetMaxWidth,
        modifier = Modifier.testTag("task-sheet"),
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.x6, vertical = Space.x1),
            ) {
                Text(
                    stringResource(if (editing != null) R.string.task_planTask else R.string.task_newTask),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(Space.x4))
                OutlinedTextField(
                    value = state.title,
                    onValueChange = {
                        state.title = it
                        if (it.isNotBlank()) titleError = false
                    },
                    label = { Text(stringResource(R.string.task_title)) },
                    isError = titleError,
                    supportingText = if (titleError) {
                        { Text(stringResource(R.string.task_titleRequired)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("task-sheet-title"),
                )
                Spacer(Modifier.height(Space.x3))
                OutlinedTextField(
                    value = state.description,
                    onValueChange = { state.description = it },
                    label = { Text(stringResource(R.string.task_descriptionLabel)) },
                    placeholder = { Text(stringResource(R.string.task_descriptionHint)) },
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("task-sheet-description"),
                )
                Spacer(Modifier.height(Space.x3))
                ChoiceField(
                    label = stringResource(R.string.task_priorityLabel),
                    selected = state.priority,
                    options = TaskPriority.entries.map { it.db },
                    onSelect = { state.priority = it },
                    tag = "task-sheet-priority",
                ) { PriorityLabel(it) }
                Spacer(Modifier.height(Space.x3))
                SheetSurface {
                    DateTimeField(
                        tag = "task-sheet-due",
                        label = stringResource(R.string.task_due),
                        icon = Icons.Outlined.Flag,
                        value = state.dueAt,
                        now = now,
                        clearDescription = stringResource(R.string.task_clearDue),
                        onChanged = {
                            state.changeDue(it, now())
                            repeatDraft.onDueTouched()
                        },
                        modifier = Modifier.padding(start = Space.x4, end = Space.x3, top = Space.x1, bottom = Space.x3),
                    )
                }
                Spacer(Modifier.height(Space.x2))
                SheetSurface {
                    DateTimeField(
                        tag = "task-sheet-remind",
                        label = stringResource(R.string.task_remind),
                        icon = Icons.Outlined.Alarm,
                        value = state.remindAt,
                        now = now,
                        deadline = state.dueAt,
                        clearDescription = stringResource(R.string.task_clearReminder),
                        onChanged = { state.changeRemind(it) },
                        modifier = Modifier.padding(start = Space.x4, end = Space.x3, top = Space.x1, bottom = Space.x3),
                    )
                }
                Spacer(Modifier.height(Space.x2))
                SheetSurface {
                    UrgentSwitch(state.isUrgent, { state.isUrgent = it }, "task-sheet-urgent")
                }
                if (editing == null && env.series != null) {
                    Spacer(Modifier.height(Space.x2))
                    SheetSurface { CreateSheetRepeatRow(repeatDraft, state, now) }
                }
                if (state.isUrgent && state.dueAt == null && state.remindAt == null) {
                    Text(
                        stringResource(R.string.task_urgentNeedsTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Space.x4, end = Space.x4, top = Space.x1).testTag("task-sheet-urgent-needs-time"),
                    )
                }
                if (saveError) {
                    Spacer(Modifier.height(Space.x3))
                    Text(
                        stringResource(R.string.task_couldNotSave),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("task-sheet-error"),
                    )
                }
            }
            Button(
                onClick = save,
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = Space.x6, end = Space.x6, top = Space.x2, bottom = Space.x4)
                    .heightIn(min = 48.dp)
                    .testTag("task-sheet-create"),
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(if (editing != null) R.string.common_save else R.string.task_createTask))
                }
            }
        }
    }
}

@Composable
fun SheetSurface(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.input),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().widthIn(max = Breakpoints.sheetMaxWidth),
    ) { content() }
}

@Composable
fun UrgentSwitch(checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.task_urgentAlarm)) },
        supportingContent = { Text(stringResource(R.string.task_urgentAlarmSub)) },
        leadingContent = { Icon(Icons.Outlined.NotificationImportant, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, colors = successSwitchColors(), modifier = Modifier.testTag(tag)) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

@Composable
fun successSwitchColors() = androidx.compose.material3.SwitchDefaults.colors(
    checkedTrackColor = com.ftapps.remft.ui.theme.RemftThemeExtras.colors.success,
    checkedThumbColor = MaterialTheme.colorScheme.surface,
    checkedBorderColor = com.ftapps.remft.ui.theme.RemftThemeExtras.colors.success,
)
