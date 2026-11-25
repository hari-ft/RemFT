package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID
import java.time.LocalDateTime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.ui.text.style.TextOverflow
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.ChipRow
import com.ftapps.remft.ui.files.AttachButton
import com.ftapps.remft.ui.files.FilesEnvironment
import com.ftapps.remft.ui.files.LocalFilesEnvironment
import com.ftapps.remft.ui.files.PickedFile
import com.ftapps.remft.ui.files.UploadTarget
import com.ftapps.remft.data.voice.VoiceText
import com.ftapps.remft.ui.voice.LocalVoiceEnvironment
import com.ftapps.remft.ui.voice.VoiceMicButton

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

        val Saver: Saver<TaskSheetState, Any> = listSaver(
            save = {
                listOf(
                    it.title,
                    it.description,
                    it.priority,
                    it.dueAt?.toString().orEmpty(),
                    it.remindAt?.toString().orEmpty(),
                    it.isUrgent,
                    it.remindAuto,
                )
            },
            restore = {
                TaskSheetState(
                    it[0] as String,
                    it[1] as String,
                    it[2] as String,
                    (it[3] as String).takeIf(String::isNotEmpty)?.let(LocalDateTime::parse),
                    (it[4] as String).takeIf(String::isNotEmpty)?.let(LocalDateTime::parse),
                    it[5] as Boolean,
                    it[6] as Boolean,
                )
            },
        )

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
    initialPicks: List<PickedFile> = emptyList(),
) {
    val env = LocalTasksEnvironment.current
    val repository = env.repository
    val zone = repository.zone
    val now = { LocalDateTime.ofInstant(repository.now(), repository.zone) }
    val state = rememberSaveable(editing?.id, saver = TaskSheetState.Saver) {
        if (editing != null) TaskSheetState.forEdit(editing) { it.toLocal(zone) } else TaskSheetState.forCreate(initialDue, now()).also { it.title = initialTitle }
    }
    val repeatDraft = rememberSaveable(saver = RepeatDraft.Saver) { RepeatDraft() }
    var titleError by rememberSaveable { mutableStateOf(false) }
    val session = rememberSaveable { UUID.randomUUID().toString() }
    val files = LocalFilesEnvironment.current
    val saver: TaskSheetViewModel = viewModel(key = "task-sheet-$session") { TaskSheetViewModel(env, attach = files?.let(::taskUploader)) }
    val saveState by saver.state.collectAsStateWithLifecycle()
    val picks by saver.picks.collectAsStateWithLifecycle()
    var seeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(saver) {
        if (!seeded) {
            seeded = true
            if (initialPicks.isNotEmpty()) saver.addPicks(initialPicks)
        }
    }
    val dismiss: () -> Unit = {
        saver.discard()
        onDismiss()
    }
    val saving = saveState == SheetSave.Saving
    LaunchedEffect(saveState) {
        if (saveState == SheetSave.Saved) onDismiss()
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val save: () -> Unit = save@{
        if (state.title.isBlank()) {
            titleError = true
            return@save
        }
        if (editing != null) {
            saver.update(
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
            val rule = repeatDraft.rule
            saver.create(
                TaskDraft(
                    title = state.title.trim(),
                    description = state.descriptionOrNull,
                    priority = state.priority,
                    dueAt = state.dueAt?.toInstant(zone),
                    remindAt = state.remindAt?.toInstant(zone),
                    isUrgent = state.isUrgent,
                ),
                rule,
                rule?.let { repeatDraft.anchor(state, now()).toInstant(zone) },
            )
        }
    }
    RemftBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("task-sheet"),
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.x6, vertical = Space.x1),
            ) {
                SheetHeading(stringResource(if (editing != null) R.string.task_planTask else R.string.task_newTask))
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
                    trailingIcon = voiceTrailing("voice-task-title", stringResource(R.string.voice_speakTitle)) { spoken ->
                        state.title = VoiceText.append(state.title, spoken)
                        if (state.title.isNotBlank()) titleError = false
                    },
                    shape = MarkedShapes.small,
                    colors = sheetFieldColors(),
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
                    trailingIcon = voiceTrailing("voice-task-notes", stringResource(R.string.voice_speakNotes)) { spoken ->
                        state.description = VoiceText.append(state.description, spoken)
                    },
                    shape = MarkedShapes.small,
                    colors = sheetFieldColors(),
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
                        leadingInset = 0.dp,
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
                        leadingInset = 0.dp,
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
                if (files != null) {
                    Spacer(Modifier.height(Space.x2))
                    SheetSurface { SheetAttachments(picks, saver::addPicks, saver::removePick) }
                }
                if (state.isUrgent && state.dueAt == null && state.remindAt == null) {
                    Text(
                        stringResource(R.string.task_urgentNeedsTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Space.x4, end = Space.x4, top = Space.x1).testTag("task-sheet-urgent-needs-time"),
                    )
                }
                if (saveState == SheetSave.Failed || saveState == SheetSave.TooDense) {
                    Spacer(Modifier.height(Space.x3))
                    Text(
                        stringResource(if (saveState == SheetSave.TooDense) R.string.repeat_tooDense else R.string.task_couldNotSave),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer, MarkedShapes.small)
                            .padding(horizontal = Space.x4, vertical = Space.x3)
                            .testTag("task-sheet-error"),
                    )
                }
            }
            SheetSaveButton(stringResource(if (editing != null) R.string.common_save else R.string.task_createTask), saving, save)
        }
    }
}

fun taskUploader(files: FilesEnvironment): suspend (String, List<PickedFile>) -> Unit = { taskId, picks ->
    files.uploads.enqueueAll(UploadTarget(files.files.workspace(), "task", taskId), picks)
}

@Composable
private fun SheetAttachments(picks: List<PickedFile>, onPicked: (List<PickedFile>) -> Unit, onRemove: (Int) -> Unit) {
    val context = LocalContext.current
    val pickFailed = stringResource(R.string.file_pickFailed)
    val removeLabel = stringResource(R.string.taskattach_removePick)
    Column(Modifier.padding(start = Space.x4, end = Space.x3, top = Space.x2, bottom = Space.x3).testTag("task-sheet-attachments")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.task_attachments),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            AttachButton(
                onPicked = onPicked,
                onFailed = { android.widget.Toast.makeText(context, pickFailed, android.widget.Toast.LENGTH_SHORT).show() },
                tag = "task-sheet-attach",
            )
        }
        if (picks.isNotEmpty()) {
            Spacer(Modifier.height(Space.x2))
            ChipRow(Modifier.testTag("task-sheet-picks")) {
                picks.forEachIndexed { index, pick ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(index) },
                        label = { Text(pick.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = removeLabel.fill("name" to pick.name),
                                modifier = Modifier.size(InputChipDefaults.IconSize),
                            )
                        },
                        shape = MarkedShapes.pill,
                        modifier = Modifier.testTag("task-sheet-pick-$index"),
                    )
                }
            }
        }
    }
}

@Composable
fun SheetSaveButton(text: String, saving: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !saving,
        shape = MarkedShapes.pill,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(start = Space.x6, end = Space.x6, top = Space.x2, bottom = Space.x4)
            .heightIn(min = 44.dp)
            .testTag("task-sheet-create"),
    ) {
        if (saving) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SheetSurface(content: @Composable () -> Unit) {
    Surface(
        shape = MarkedShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().widthIn(max = Breakpoints.sheetMaxWidth),
    ) { content() }
}

@Composable
fun SheetHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun voiceTrailing(tag: String, label: String, onText: (String) -> Unit): (@Composable () -> Unit)? {
    val env = LocalVoiceEnvironment.current ?: return null
    val show by env.showButtons.collectAsStateWithLifecycle(false)
    if (!show) return null
    return { VoiceMicButton(tag = tag, label = label, onText = onText) }
}

@Composable
fun sheetFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    errorContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

@Composable
fun UrgentSwitch(checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    val urgent = RemftTheme.colors.urgent
    ListItem(
        headlineContent = { Text(stringResource(R.string.task_urgentAlarm), style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(stringResource(R.string.task_urgentAlarmSub)) },
        leadingContent = if (isLargeFont()) {
            null
        } else {
            {
                Box(
                    Modifier
                        .size(Sizes.avatar + 4.dp)
                        .background(if (checked) urgent.container else RemftTheme.tonal.level2, MarkedShapes.pill)
                        .testTag("$tag-icon"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.NotificationImportant,
                        contentDescription = null,
                        tint = if (checked) urgent.onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, colors = successSwitchColors(), modifier = Modifier.testTag(tag)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, supportingColor = MaterialTheme.colorScheme.onSurfaceVariant),
    )
}

@Composable
fun successSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = RemftTheme.colors.success.color,
    checkedThumbColor = RemftTheme.colors.success.onColor,
    checkedIconColor = RemftTheme.colors.success.color,
    checkedBorderColor = RemftTheme.colors.success.color,
)

@Preview(name = "Task sheet light", widthDp = 411, heightDp = 900)
@Composable
private fun TaskSheetLightPreview() {
    RemftTheme(darkTheme = false) { TaskSheetPreviewContent() }
}

@Preview(name = "Task sheet dark", widthDp = 411, heightDp = 900)
@Composable
private fun TaskSheetDarkPreview() {
    RemftTheme(darkTheme = true) { TaskSheetPreviewContent() }
}

@Composable
private fun TaskSheetPreviewContent() {
    Box(Modifier.fillMaxSize().background(RemftTheme.tonal.level0), contentAlignment = Alignment.BottomCenter) {
        Surface(shape = MarkedShapes.sheet, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = Space.x6, vertical = Space.x6)) {
                SheetHeading("New task")
                Spacer(Modifier.height(Space.x4))
                OutlinedTextField(
                    value = "Book train tickets",
                    onValueChange = {},
                    label = { Text("Title") },
                    singleLine = true,
                    shape = MarkedShapes.small,
                    colors = sheetFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Space.x3))
                ChoiceField(
                    label = "Priority",
                    selected = TaskPriority.Medium.db,
                    options = TaskPriority.entries.map { it.db },
                    onSelect = {},
                    tag = "preview-priority",
                ) { PriorityLabel(it) }
                Spacer(Modifier.height(Space.x3))
                SheetSurface {
                    DateTimeFieldContent(
                        tag = "preview-due",
                        label = "Due",
                        icon = Icons.Outlined.Flag,
                        dateText = "Sun, 22 Nov 2026",
                        timeText = "6:00 pm",
                        isSet = true,
                        afterDeadline = false,
                        clearDescription = "Clear due",
                        onClear = {},
                        onPickDate = {},
                        onPickTime = {},
                        modifier = Modifier.padding(start = Space.x4, end = Space.x3, top = Space.x1, bottom = Space.x3),
                    )
                }
                Spacer(Modifier.height(Space.x2))
                SheetSurface {
                    DateTimeFieldContent(
                        tag = "preview-remind",
                        label = "Remind",
                        icon = Icons.Outlined.Alarm,
                        dateText = "Add date",
                        timeText = "Add time",
                        isSet = false,
                        afterDeadline = false,
                        clearDescription = "Clear reminder",
                        onClear = {},
                        onPickDate = {},
                        onPickTime = {},
                        modifier = Modifier.padding(start = Space.x4, end = Space.x3, top = Space.x1, bottom = Space.x3),
                    )
                }
                Spacer(Modifier.height(Space.x2))
                SheetSurface { UrgentSwitch(true, {}, "preview-urgent") }
                Spacer(Modifier.height(Space.x4))
                Button(onClick = {}, shape = MarkedShapes.pill, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Create task", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
