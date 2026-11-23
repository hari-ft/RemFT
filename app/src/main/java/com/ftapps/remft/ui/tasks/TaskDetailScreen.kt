package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.ftapps.remft.ui.components.MarkedCard
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ftapps.remft.R
import com.ftapps.remft.data.tasks.TaskDetail
import com.ftapps.remft.domain.tasks.ChecklistItem
import com.ftapps.remft.domain.tasks.Change
import com.ftapps.remft.domain.tasks.Linkify
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.domain.tasks.TaskPriority
import com.ftapps.remft.domain.tasks.TaskStatus
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.EmptyState
import com.ftapps.remft.ui.repeat.OccurrenceDeleteHost
import com.ftapps.remft.ui.repeat.SeriesScopeAsker
import com.ftapps.remft.ui.repeat.SeriesScopeHost
import com.ftapps.remft.ui.repeat.TaskRepeatRow
import com.ftapps.remft.ui.repeat.rememberOccurrenceDeleter
import com.ftapps.remft.ui.repeat.rememberSeriesScopeAsker
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.files.AttachmentsSection
import com.ftapps.remft.ui.files.LocalFilesEnvironment
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space
import java.time.LocalDateTime

@Composable
fun TaskDetailScreen(taskId: String, onBack: () -> Unit, bottomPadding: Dp = 0.dp) {
    val env = LocalTasksEnvironment.current
    val vm: TaskDetailViewModel = viewModel(key = "task-$taskId") { TaskDetailViewModel(env, taskId) }
    val load by vm.state.collectAsStateWithLifecycle()
    val deletedText = stringResource(R.string.task_deleted)
    val asker = rememberSeriesScopeAsker()
    val deleter = rememberOccurrenceDeleter()
    SeriesScopeHost(asker)
    OccurrenceDeleteHost(deleter)
    DisposableEffect(vm, asker) {
        vm.scopeAsker = asker::ask
        onDispose {
            vm.scopeAsker = null
            vm.flushTitle()
            vm.flushDescription()
        }
    }
    val ready = load as? DetailLoad.Ready
    TasksScaffold(
        title = stringResource(R.string.task_detailTitle),
        bottomPadding = bottomPadding,
        onBack = onBack,
        actions = {
            if (ready != null) {
                val completed = ready.detail.task.isCompleted
                RemftIconButton(label = stringResource(if (completed) R.string.task_reopen else R.string.task_complete), onClick = vm::toggleComplete, modifier = Modifier.testTag("task-complete")) {
                    Icon(
                        if (completed) Icons.Outlined.Replay else Icons.Outlined.CheckCircleOutline,
                        contentDescription = stringResource(if (completed) R.string.task_reopen else R.string.task_complete),
                    )
                }
                RemftIconButton(
                    label = stringResource(R.string.task_deleteTooltip),
                    onClick = {
                        val task = ready.detail.task
                        val message = deletedText.fill("title" to task.title)
                        deleter.request(task, message, deleteThis = { vm.delete(message) }, afterDone = onBack)
                    },
                    modifier = Modifier.testTag("task-delete"),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.task_deleteTooltip))
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).testTag("screen-task"), contentAlignment = Alignment.TopCenter) {
            when (val current = load) {
                DetailLoad.Loading -> CircularProgressIndicator(Modifier.padding(Space.x8))
                DetailLoad.NotFound -> EmptyState(
                    Icons.Outlined.SearchOff,
                    stringResource(R.string.state_taskNotFound),
                    stringResource(R.string.state_notFoundBody),
                    Modifier.testTag("task-not-found"),
                )
                is DetailLoad.Ready -> TaskDetailBody(current.detail, vm, asker)
            }
        }
    }
}

fun oneLineTitle(text: String): String = text.replace(LineBreaks, " ")

private val LineBreaks = Regex("[\r\n]+")

@Composable
private fun TaskDetailBody(detail: TaskDetail, vm: TaskDetailViewModel, asker: SeriesScopeAsker) {
    val task = detail.task
    val scoped: (TaskPatch, Boolean) -> Unit = { patch, isDateEdit -> asker.ask(task, isDateEdit) { scope -> vm.apply(patch, scope) } }
    val env = LocalTasksEnvironment.current
    val zone = env.repository.zone
    val now = { LocalDateTime.ofInstant(vm.now(), zone) }
    val context = LocalContext.current
    val backOn = stringResource(R.string.task_alarmsBackOn)
    val passed = stringResource(R.string.task_alarmTimePassed)
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }
    var lastSeenTitle by remember(task.id) { mutableStateOf(task.title) }
    var titleFocused by remember(task.id) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    if (task.title != lastSeenTitle) {
        if (title == lastSeenTitle) title = task.title
        lastSeenTitle = task.title
    }
    Column(
        Modifier
            .widthIn(max = Breakpoints.detailMaxWidth)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.x4, vertical = Space.x2),
    ) {
        TextField(
            value = title,
            onValueChange = {
                val oneLine = oneLineTitle(it)
                title = oneLine
                vm.onTitleChanged(oneLine)
            },
            placeholder = { Text(stringResource(R.string.task_titleHint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onBackground),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focus ->
                    if (titleFocused && !focus.isFocused) vm.flushTitle(interactive = true)
                    titleFocused = focus.isFocused
                }
                .testTag("task-title"),
        )
        Spacer(Modifier.height(Space.x2))
        DescriptionField(task.id, task.description, vm)
        Spacer(Modifier.height(Space.x3))
        SectionCard(stringResource(R.string.task_details)) {
            SideBySideOrStacked(gap = Space.x3) {
                ChoiceField(
                    label = stringResource(R.string.task_statusLabel),
                    selected = task.status,
                    options = TaskStatus.entries.map { it.db },
                    onSelect = { vm.apply(TaskPatch(status = it)) },
                    tag = "status-dropdown",
                ) { StatusLabel(it) }
                ChoiceField(
                    label = stringResource(R.string.task_priorityLabel),
                    selected = task.priority,
                    options = TaskPriority.entries.map { it.db },
                    onSelect = { scoped(TaskPatch(priority = it), false) },
                    tag = "priority-dropdown",
                ) { PriorityLabel(it) }
            }
            Spacer(Modifier.height(Space.x2))
            UrgentSwitch(task.isUrgent, { scoped(TaskPatch(isUrgent = it), false) }, "urgent-switch")
            if (task.isUrgent && task.dueAt == null && task.remindAt == null) {
                Text(
                    stringResource(R.string.task_urgentNeedsTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.x4).testTag("urgent-needs-time"),
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.task_muteAlarms), style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(stringResource(R.string.task_alarmsMutedSub)) },
                leadingContent = if (isLargeFont()) {
                    null
                } else {
                    {
                        Box(Modifier.size(Sizes.avatar + 4.dp).background(RemftTheme.tonal.level2, MarkedShapes.pill).testTag("mute-alarms-icon"), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.NotificationsOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                trailingContent = {
                    Switch(
                        checked = task.alarmsMutedAt != null,
                        onCheckedChange = { mute ->
                            vm.setMuted(mute)
                            if (!mute) {
                                val next = task.remindAt ?: task.dueAt
                                val text = if (next != null && !next.isAfter(vm.now())) passed else backOn
                                android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = successSwitchColors(),
                        modifier = Modifier.testTag("mute-alarms-switch"),
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent, supportingColor = MaterialTheme.colorScheme.onSurfaceVariant),
            )
            SectionDivider()
            DateTimeField(
                tag = "due-row",
                label = stringResource(R.string.task_due),
                icon = Icons.Outlined.Flag,
                value = task.dueAt?.toLocal(zone),
                now = now,
                clearDescription = stringResource(R.string.task_clearDue),
                onChanged = { scoped(TaskPatch(dueAt = Change(it?.toInstant(zone))), true) },
                modifier = Modifier.padding(vertical = Space.x2),
                leadingInset = 16.dp,
            )
            DateTimeField(
                tag = "remind-row",
                label = stringResource(R.string.task_remind),
                icon = Icons.Outlined.Alarm,
                value = task.remindAt?.toLocal(zone),
                now = now,
                deadline = task.dueAt?.toLocal(zone),
                clearDescription = stringResource(R.string.task_clearReminder),
                onChanged = { scoped(TaskPatch(remindAt = Change(it?.toInstant(zone))), true) },
                modifier = Modifier.padding(vertical = Space.x2),
                leadingInset = 16.dp,
            )
            SectionDivider()
            TaskRepeatRow(task)
        }
        Spacer(Modifier.height(Space.x3))
        SectionCard(stringResource(R.string.task_checklist)) {
            Checklist(detail.checklist, vm)
        }
        if (LocalFilesEnvironment.current != null) {
            Spacer(Modifier.height(Space.x3))
            SectionCard(stringResource(R.string.task_attachments)) {
                AttachmentsSection("task", task.id, onMessage = { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() })
            }
        }
        Spacer(Modifier.height(Space.x8))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    MarkedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.x4)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Space.x2))
            content()
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = Space.x1))
}

@Composable
private fun DescriptionField(taskId: String, description: String?, vm: TaskDetailViewModel) {
    var editing by rememberSaveable(taskId) { mutableStateOf(false) }
    var text by rememberSaveable(taskId) { mutableStateOf(description.orEmpty()) }
    if (!editing && text != description.orEmpty()) text = description.orEmpty()
    if (editing) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                vm.onDescriptionChanged(it)
            },
            placeholder = { Text(stringResource(R.string.task_descriptionHint)) },
            label = { Text(stringResource(R.string.task_descriptionLabel)) },
            shape = MarkedShapes.small,
            colors = sheetFieldColors(),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("task-description"),
        )
        return
    }
    if (description.isNullOrBlank()) {
        TextButton(
            onClick = { editing = true },
            shape = MarkedShapes.pill,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.heightIn(min = 48.dp).testTag("task-add-description"),
        ) {
            Icon(Icons.Outlined.Notes, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.x2))
            Text(stringResource(R.string.task_addDescription), style = MaterialTheme.typography.labelLarge)
        }
        return
    }
    val linkColor = RemftThemeExtras.colors.link
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.task_editDescription)) { editing = true }
            .padding(vertical = Space.x3)
            .testTag("task-description-display"),
    ) {
        Text(linkified(description, linkColor), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

fun linkified(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    Linkify.segments(text).forEach { segment ->
        val url = segment.url
        if (url == null) {
            append(segment.text)
        } else {
            withLink(LinkAnnotation.Url(url, styles)) { append(segment.text) }
        }
    }
}

@Composable
private fun Checklist(items: List<ChecklistItem>, vm: TaskDetailViewModel) {
    var newItem by rememberSaveable { mutableStateOf("") }
    val add = {
        if (newItem.isNotBlank()) {
            vm.addItem(newItem)
            newItem = ""
        }
    }
    Column {
        items.forEachIndexed { index, item ->
            ChecklistRow(item, index, items.size, vm)
        }
        OutlinedTextField(
            value = newItem,
            onValueChange = { newItem = it },
            placeholder = { Text(stringResource(R.string.task_addChecklistItem)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            shape = MarkedShapes.pill,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = RemftTheme.tonal.level2,
                unfocusedContainerColor = RemftTheme.tonal.level2,
                unfocusedBorderColor = Color.Transparent,
            ),
            trailingIcon = {
                RemftIconButton(
                    label = stringResource(R.string.task_addItem),
                    onClick = add,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("checklist-add-button"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.task_addItem))
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = Space.x2).testTag("checklist-add"),
        )
    }
}

@Composable
private fun ChecklistRow(item: ChecklistItem, index: Int, count: Int, vm: TaskDetailViewModel) {
    var editing by remember(item.id) { mutableStateOf(false) }
    var draft by remember(item.id) { mutableStateOf(item.title) }
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("checklist-item-${item.id}"), verticalAlignment = Alignment.CenterVertically) {
        CircularCheck(
            checked = item.isDone,
            description = item.title,
            onToggle = { vm.setItemDone(item.id, !item.isDone) },
            modifier = Modifier.testTag("checklist-check-${item.id}"),
        )
        if (editing) {
            val save = {
                editing = false
                vm.renameItem(item.id, draft)
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                shape = MarkedShapes.small,
                colors = sheetFieldColors(),
                trailingIcon = {
                    RemftIconButton(label = stringResource(R.string.common_save), onClick = save, modifier = Modifier.testTag("checklist-save-${item.id}")) {
                        Icon(Icons.Outlined.CheckCircleOutline, contentDescription = stringResource(R.string.common_save))
                    }
                },
                modifier = Modifier.weight(1f).testTag("checklist-edit-${item.id}"),
            )
        } else {
            ChecklistTitle(
                item.title,
                item.isDone,
                Modifier
                    .weight(1f)
                    .clickable(onClickLabel = stringResource(R.string.tasks_checklistEdit)) {
                        draft = item.title
                        editing = true
                    }
                    .padding(vertical = Space.x3),
            )
        }
        Box {
            RemftIconButton(
                label = stringResource(R.string.tasks_checklistItemOptions),
                onClick = { menu = true },
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.testTag("checklist-menu-${item.id}"),
            ) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.tasks_checklistItemOptions))
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                shape = MarkedShapes.medium,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tasks_checklistEdit)) },
                    onClick = {
                        menu = false
                        draft = item.title
                        editing = true
                    },
                    modifier = Modifier.testTag("checklist-edit-action-${item.id}"),
                )
                if (index > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tasks_checklistMoveUp)) },
                        onClick = {
                            menu = false
                            vm.moveItem(item.id, -1)
                        },
                        modifier = Modifier.testTag("checklist-up-${item.id}"),
                    )
                }
                if (index < count - 1) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tasks_checklistMoveDown)) },
                        onClick = {
                            menu = false
                            vm.moveItem(item.id, 1)
                        },
                        modifier = Modifier.testTag("checklist-down-${item.id}"),
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.task_removeItem)) },
                    onClick = {
                        menu = false
                        vm.deleteItem(item.id)
                    },
                    modifier = Modifier.testTag("checklist-remove-${item.id}"),
                )
            }
        }
    }
}

@Composable
private fun ChecklistTitle(title: String, done: Boolean, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (done) TextDecoration.LineThrough else null),
        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Preview(name = "Task detail light", widthDp = 411, heightDp = 1100)
@Composable
private fun TaskDetailLightPreview() {
    RemftTheme(darkTheme = false) { TaskDetailPreviewContent() }
}

@Preview(name = "Task detail dark", widthDp = 411, heightDp = 1100)
@Composable
private fun TaskDetailDarkPreview() {
    RemftTheme(darkTheme = true) { TaskDetailPreviewContent() }
}

@Composable
private fun TaskDetailPreviewContent() {
    Column(
        Modifier
            .fillMaxSize()
            .background(RemftTheme.tonal.level0)
            .padding(horizontal = Space.x4, vertical = Space.x2),
    ) {
        Text(
            "Book train tickets",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = Space.x4, vertical = Space.x3),
        )
        Text(
            linkified("Window seats if possible, see example.com/trains", RemftThemeExtras.colors.link),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = Space.x3),
        )
        Spacer(Modifier.height(Space.x3))
        SectionCard("Details") {
            Row {
                ChoiceField(
                    label = "Status",
                    selected = TaskStatus.Open.db,
                    options = TaskStatus.entries.map { it.db },
                    onSelect = {},
                    tag = "preview-status",
                    modifier = Modifier.weight(1f),
                ) { StatusLabel(it) }
                Spacer(Modifier.width(Space.x3))
                ChoiceField(
                    label = "Priority",
                    selected = TaskPriority.High.db,
                    options = TaskPriority.entries.map { it.db },
                    onSelect = {},
                    tag = "preview-priority",
                    modifier = Modifier.weight(1f),
                ) { PriorityLabel(it) }
            }
            Spacer(Modifier.height(Space.x2))
            UrgentSwitch(true, {}, "preview-urgent")
            SectionDivider()
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
                modifier = Modifier.padding(vertical = Space.x2),
            )
        }
        Spacer(Modifier.height(Space.x3))
        SectionCard("Checklist") {
            listOf("Check the timetable" to true, "Pay for the tickets" to false).forEach { (title, done) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularCheck(done, title, onToggle = {})
                    ChecklistTitle(title, done, Modifier.weight(1f).padding(vertical = Space.x3))
                }
            }
        }
    }
}
