package com.ftapps.remft.ui.share

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.db.entity.NoteEntity
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.files.FilesEnvironment
import com.ftapps.remft.ui.files.FolderTree
import com.ftapps.remft.ui.files.KindTile
import com.ftapps.remft.ui.files.PickedFile
import com.ftapps.remft.ui.files.TileTone
import com.ftapps.remft.ui.files.UploadTarget
import com.ftapps.remft.ui.files.formatBytes
import com.ftapps.remft.ui.files.kindIcon
import com.ftapps.remft.ui.tasks.TaskCreateSheet
import com.ftapps.remft.ui.tasks.TasksEnvironment
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private enum class ShareStep { Overview, Folder, Task, Note }

private sealed interface NewTaskRequest {
    data class WithFiles(val picks: List<PickedFile>) : NewTaskRequest
    data class WithTitle(val title: String) : NewTaskRequest
}

object ShareDestinations {
    fun openTasks(tasks: TasksEnvironment): Flow<List<Task>> =
        combine(tasks.repository.watchHome(), tasks.repository.watchInbox(), tasks.repository.watchScheduled()) { home, inbox, scheduled ->
            (home + inbox + scheduled).filter { it.completedAt == null }.distinctBy { it.id }.sortedBy { it.title.lowercase() }
        }

    fun matches(title: String, query: String): Boolean = query.isBlank() || title.contains(query.trim(), ignoreCase = true)

    fun taskTitleFrom(text: String): String = text.lines().joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim()
}

@Composable
fun ShareInHost(
    inbox: ShareInbox,
    files: FilesEnvironment,
    tasks: TasksEnvironment,
    notes: NotesRepository?,
    ready: Boolean,
    onOpenTask: (String) -> Unit,
    onMessage: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val state by inbox.state.collectAsState()
    var newTask by remember { mutableStateOf<NewTaskRequest?>(null) }
    val message: (String) -> Unit = onMessage ?: { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    when (val request = newTask) {
        is NewTaskRequest.WithFiles -> TaskCreateSheet(onDismiss = { newTask = null }, initialPicks = request.picks)
        is NewTaskRequest.WithTitle -> TaskCreateSheet(onDismiss = { newTask = null }, initialTitle = request.title)
        null -> Unit
    }
    if (!ready || newTask != null) return
    when (val current = state) {
        null -> Unit
        is ShareState.Copying -> CopyingSheet(current.count, onCancel = inbox::dismiss)
        is ShareState.Ready -> ShareInSheet(
            intake = current.intake,
            inbox = inbox,
            files = files,
            tasks = tasks,
            notes = notes,
            onNewTask = { newTask = it },
            onOpenTask = onOpenTask,
            onMessage = message,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CopyingSheet(count: Int, onCancel: () -> Unit) {
    RemftBottomSheet(onDismissRequest = onCancel, modifier = Modifier.testTag("sharein-copying")) {
        Row(Modifier.padding(Space.x5).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Spacer(Modifier.size(Space.x4))
            Text(pluralStringResource(R.plurals.sharein_preparing, count, count), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareInSheet(
    intake: Intake,
    inbox: ShareInbox,
    files: FilesEnvironment,
    tasks: TasksEnvironment,
    notes: NotesRepository?,
    onNewTask: (NewTaskRequest) -> Unit,
    onOpenTask: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var step by remember(intake) { mutableStateOf(ShareStep.Overview) }
    val count = intake.items.size
    val failed = stringResource(R.string.sharein_failed)
    fun deliver(target: suspend () -> UploadTarget, done: String) {
        scope.launch {
            val resolved = try {
                target()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogs.warn("files.share_in_failed", e, mapOf("stage" to "target"))
                onMessage(failed)
                return@launch
            }
            val picks = inbox.handOff()
            if (picks.isEmpty()) return@launch
            files.uploads.enqueueAll(resolved, picks)
            AppLogs.info("files.share_in_saved", mapOf("destination" to resolved.targetType, "items" to picks.size))
            onMessage(done)
        }
    }
    RemftBottomSheet(
        onDismissRequest = inbox::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("sharein-sheet"),
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Space.x3)) {
            when (step) {
                ShareStep.Overview -> Overview(
                    intake = intake,
                    notesAvailable = notes != null,
                    onFolder = { step = ShareStep.Folder },
                    onNewTask = { onNewTask(NewTaskRequest.WithFiles(inbox.handOff())) },
                    onTask = { step = ShareStep.Task },
                    onNote = { step = ShareStep.Note },
                    onTextTask = {
                        inbox.dismiss()
                        onNewTask(NewTaskRequest.WithTitle(ShareDestinations.taskTitleFrom(intake.text.orEmpty())))
                    },
                    onTextNote = {
                        val repository = notes ?: return@Overview
                        val text = intake.text.orEmpty()
                        scope.launch {
                            try {
                                repository.create("", text)
                                AppLogs.info("files.share_in_saved", mapOf("destination" to "note_text"))
                                inbox.dismiss()
                                onMessage(resources.getString(R.string.sharein_noteCreated))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogs.warn("files.share_in_failed", e, mapOf("stage" to "note_text"))
                                onMessage(failed)
                            }
                        }
                    },
                    onClose = inbox::dismiss,
                )
                ShareStep.Folder -> FolderStep(
                    files = files,
                    onNewFolder = { name ->
                        deliver({
                            val folder = files.files.createFolder(name, null)
                            files.files.workspace().let { UploadTarget(it, "workspace", it, folder) }
                        }, resources.getQuantityString(R.plurals.sharein_savingToFiles, count, count))
                    },
                    onPick = { folderId ->
                        deliver({ files.files.workspace().let { UploadTarget(it, "workspace", it, folderId) } }, resources.getQuantityString(R.plurals.sharein_savingToFiles, count, count))
                    },
                )
                ShareStep.Task -> TaskStep(tasks) { taskId ->
                    deliver({ UploadTarget(files.files.workspace(), "task", taskId) }, resources.getQuantityString(R.plurals.sharein_attachingToTask, count, count))
                    onOpenTask(taskId)
                }
                ShareStep.Note -> NoteStep(
                    notes = notes,
                    onNew = {
                        val repository = notes ?: return@NoteStep
                        deliver({ UploadTarget(files.files.workspace(), "note", repository.create("", null)) }, resources.getQuantityString(R.plurals.sharein_attachingToNote, count, count))
                    },
                    onPick = { noteId ->
                        deliver({ UploadTarget(files.files.workspace(), "note", noteId) }, resources.getQuantityString(R.plurals.sharein_attachingToNote, count, count))
                    },
                )
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = Space.x5, end = Space.x5, bottom = Space.x2),
    )
}

@Composable
private fun Overview(
    intake: Intake,
    notesAvailable: Boolean,
    onFolder: () -> Unit,
    onNewTask: () -> Unit,
    onTask: () -> Unit,
    onNote: () -> Unit,
    onTextTask: () -> Unit,
    onTextNote: () -> Unit,
    onClose: () -> Unit,
) {
    Heading(stringResource(R.string.sharein_title))
    LazyColumn(Modifier.heightIn(max = 260.dp).testTag("sharein-items")) {
        items(intake.items, key = { it.file.path }) { item ->
            ListItem(
                modifier = Modifier.testTag("sharein-item"),
                leadingContent = { ItemThumb(item) },
                headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(formatBytes(item.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
    intake.text?.let { text ->
        Text(
            text,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = Space.x5, vertical = Space.x2).testTag("sharein-text"),
        )
    }
    Problems(intake.problems)
    when {
        intake.items.isNotEmpty() -> {
            Destination(Icons.Outlined.Folder, stringResource(R.string.sharein_destFiles), "sharein-dest-files", onFolder)
            Destination(Icons.Outlined.AddTask, stringResource(R.string.sharein_destNewTask), "sharein-dest-new-task", onNewTask)
            Destination(Icons.Outlined.TaskAlt, stringResource(R.string.sharein_destTask), "sharein-dest-task", onTask)
            if (notesAvailable) Destination(Icons.AutoMirrored.Outlined.StickyNote2, stringResource(R.string.sharein_destNote), "sharein-dest-note", onNote)
        }
        intake.text != null -> {
            Destination(Icons.Outlined.AddTask, stringResource(R.string.sharein_textNewTask), "sharein-text-task", onTextTask)
            if (notesAvailable) Destination(Icons.AutoMirrored.Outlined.NoteAdd, stringResource(R.string.sharein_textNewNote), "sharein-text-note", onTextNote)
        }
        else -> Text(
            stringResource(R.string.sharein_nothing),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = Space.x5, vertical = Space.x2).testTag("sharein-nothing"),
        )
    }
    Box(Modifier.fillMaxWidth().padding(end = Space.x3), contentAlignment = Alignment.CenterEnd) {
        RemftTextButton(stringResource(R.string.common_cancel), onClick = onClose, modifier = Modifier.testTag("sharein-cancel"))
    }
}

@Composable
private fun Problems(problems: List<IntakeProblem>) {
    val counts = problems.groupingBy { it }.eachCount()
    IntakeProblem.entries.forEach { problem ->
        val n = counts[problem] ?: return@forEach
        val text = when (problem) {
            IntakeProblem.Unreadable -> pluralStringResource(R.plurals.sharein_unreadable, n, n)
            IntakeProblem.TooLarge -> pluralStringResource(R.plurals.sharein_tooLarge, n, n)
            IntakeProblem.Unsupported -> pluralStringResource(R.plurals.sharein_unsupported, n, n)
        }
        Text(
            text,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = Space.x5, vertical = Space.x1).testTag("sharein-problem-${problem.code}"),
        )
    }
}

@Composable
private fun ItemThumb(item: SharedItem) {
    if (item.isImage) {
        AsyncImage(
            model = item.file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(40.dp).clip(MarkedShapes.small),
        )
    } else {
        KindTile(kindIcon(item.mime))
    }
}

@Composable
private fun Destination(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.testTag(tag).clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun FolderStep(files: FilesEnvironment, onNewFolder: (String) -> Unit, onPick: (String?) -> Unit) {
    val folders by remember(files) { files.files.watchFolders() }.collectAsState(emptyList())
    val entries = remember(folders) { FolderTree.flatten(folders, null) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Heading(stringResource(R.string.sharein_pickFolder))
    if (naming) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.x4), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 255) name = it },
                label = { Text(stringResource(R.string.files_folderName)) },
                singleLine = true,
                shape = MarkedShapes.small,
                modifier = Modifier.weight(1f).testTag("sharein-folder-name"),
            )
            RemftTextButton(
                stringResource(R.string.common_save),
                onClick = { if (name.isNotBlank()) onNewFolder(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("sharein-folder-create"),
            )
        }
    }
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        item {
            PickRow(stringResource(R.string.files_rootFolder), "sharein-folder-root", leading = { KindTile(Icons.Outlined.Home, TileTone.Folder) }) { onPick(null) }
        }
        if (!naming) {
            item {
                PickRow(stringResource(R.string.files_newFolder), "sharein-folder-new", leading = { KindTile(Icons.Outlined.CreateNewFolder, TileTone.Folder) }) { naming = true }
            }
        }
        items(entries, key = { it.folder.id }) { entry ->
            PickRow(
                entry.folder.name,
                "sharein-folder-${entry.folder.id}",
                modifier = Modifier.padding(start = Space.x4 * entry.depth),
                leading = { KindTile(Icons.Outlined.Folder, TileTone.Folder) },
            ) { onPick(entry.folder.id) }
        }
    }
}

@Composable
private fun TaskStep(tasks: TasksEnvironment, onPick: (String) -> Unit) {
    val all by remember(tasks) { ShareDestinations.openTasks(tasks) }.collectAsState(emptyList())
    var query by remember { mutableStateOf("") }
    val shown = all.filter { ShareDestinations.matches(it.title, query) }
    Heading(stringResource(R.string.sharein_pickTask))
    SearchField(query, "sharein-task-search") { query = it }
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        if (shown.isEmpty()) item { EmptyLine(stringResource(R.string.sharein_noTasks)) }
        items(shown, key = { it.id }) { task ->
            PickRow(task.title, "sharein-task-${task.id}", leading = { KindTile(Icons.Outlined.TaskAlt) }) { onPick(task.id) }
        }
    }
}

@Composable
private fun NoteStep(notes: NotesRepository?, onNew: () -> Unit, onPick: (String) -> Unit) {
    val all by remember(notes) { notes?.observeList() ?: flowOf(emptyList<NoteEntity>()) }.collectAsState(emptyList())
    var query by remember { mutableStateOf("") }
    val shown = all.filter { !it.isArchived && ShareDestinations.matches(it.title, query) }
    Heading(stringResource(R.string.sharein_pickNote))
    SearchField(query, "sharein-note-search") { query = it }
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        item { PickRow(stringResource(R.string.sharein_newNote), "sharein-note-new", leading = { KindTile(Icons.AutoMirrored.Outlined.NoteAdd) }, onClick = onNew) }
        if (shown.isEmpty()) item { EmptyLine(stringResource(R.string.sharein_noNotes)) }
        items(shown, key = { it.id }) { note ->
            PickRow(note.title, "sharein-note-${note.id}", leading = { KindTile(Icons.AutoMirrored.Outlined.StickyNote2) }) { onPick(note.id) }
        }
    }
}

@Composable
private fun SearchField(value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.sharein_search)) },
        singleLine = true,
        shape = MarkedShapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.x4).testTag(tag),
    )
    Spacer(Modifier.height(Space.x2))
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = Space.x5, vertical = Space.x3),
    )
}

@Composable
private fun PickRow(label: String, tag: String, modifier: Modifier = Modifier, leading: @Composable () -> Unit, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.testTag(tag).clickable(onClick = onClick).then(modifier),
        leadingContent = leading,
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
