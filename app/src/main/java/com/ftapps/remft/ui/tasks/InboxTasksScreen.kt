package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ftapps.remft.R
import com.ftapps.remft.data.tasks.PendingDeletes
import com.ftapps.remft.domain.search.SearchMatch
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.MarkedCard
import com.ftapps.remft.ui.components.RemftDialog
import com.ftapps.remft.ui.components.RemftFilledButton
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.empty.EmptyIllustration
import com.ftapps.remft.ui.empty.IllustratedEmptyState
import com.ftapps.remft.ui.empty.IllustratedErrorState
import com.ftapps.remft.ui.quickadd.InboxQuickCapture
import com.ftapps.remft.ui.search.SearchField
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InboxUiState(val load: ListLoad<List<Task>> = ListLoad.Loading, val total: Int = 0, val query: String = "")

class InboxViewModel(private val env: TasksEnvironment) : ViewModel() {
    private val repository = env.repository
    private val query = MutableStateFlow("")
    private val retry = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tasks = retry.flatMapLatest {
        repository.watchInbox()
            .map<List<Task>, ListLoad<List<Task>>> { ListLoad.Ready(it) }
            .onStart { emit(ListLoad.Loading) }
            .catch { emit(ListLoad.Failed) }
    }

    val state: StateFlow<InboxUiState> = combine(tasks, env.pendingDeletes.ids, query) { load, pending, q ->
        when (load) {
            is ListLoad.Ready -> {
                val visible = PendingDeletes.without(load.items, pending) { it.id }
                InboxUiState(ListLoad.Ready(visible.filter { SearchMatch.matchesSearch(q, listOf(it.title, it.description)) }), visible.size, q)
            }
            else -> InboxUiState(load, 0, q)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun retry() {
        retry.value += 1
    }

    fun delete(task: Task, message: String) {
        env.undo.post(task.id, message) { repository.delete(task.id) }
    }

    fun convertToNote(task: Task, onDone: () -> Unit) {
        viewModelScope.launch {
            if (runCatching { repository.convertToNote(task.id) }.getOrNull() != null) onDone()
        }
    }
}

@Composable
fun InboxTasksScreen(bottomPadding: Dp, onOpenSettings: () -> Unit) {
    val env = LocalTasksEnvironment.current
    val vm: InboxViewModel = viewModel { InboxViewModel(env) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var searching by rememberSaveable { mutableStateOf(false) }
    var planning by remember { mutableStateOf<Task?>(null) }
    var converting by remember { mutableStateOf<Task?>(null) }
    val deletedText = stringResource(R.string.task_deleted)
    val movedText = stringResource(R.string.inbox_movedToNotes)
    TasksScaffold(
        title = stringResource(R.string.nav_inbox),
        bottomPadding = bottomPadding,
        onOpenSettings = onOpenSettings,
        actions = {
            IconButton(
                onClick = {
                    if (searching) vm.setQuery("")
                    searching = !searching
                },
                modifier = Modifier.testTag("inbox-search-toggle"),
            ) {
                Icon(
                    if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = stringResource(if (searching) R.string.common_close else R.string.common_search),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).testTag("screen-inbox"), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxWidth().testTag("inbox-list"),
                contentPadding = PaddingValues(start = Space.x4, end = Space.x4, top = Space.x2, bottom = bottomPadding + Space.x6),
            ) {
                if (searching) {
                    item {
                        SearchField(state.query, vm::setQuery, stringResource(R.string.inbox_searchHint), "inbox-search")
                    }
                }
                item {
                    InboxQuickCapture(stringResource(R.string.inbox_captureHint))
                }
                when (val load = state.load) {
                    ListLoad.Loading -> item {
                        Box(Modifier.fillMaxWidth().padding(Space.x8), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    ListLoad.Failed -> item { IllustratedErrorState(onRetry = vm::retry) }
                    is ListLoad.Ready -> {
                        if (state.total == 0) {
                            item {
                                IllustratedEmptyState(EmptyIllustration.Inbox, stringResource(R.string.inbox_captureTitle), stringResource(R.string.inbox_captureBody))
                            }
                        } else if (load.items.isEmpty()) {
                            item {
                                IllustratedEmptyState(EmptyIllustration.Inbox,
                                    
                                    stringResource(R.string.home_searchEmptyTitle),
                                    stringResource(R.string.home_searchEmptyBody).fill("query" to state.query.trim()),
                                )
                            }
                        }
                        items(load.items, key = { it.id }) { task ->
                            CaptureRow(
                                task = task,
                                onPlan = { planning = task },
                                onConvert = { converting = task },
                                onDelete = { vm.delete(task, deletedText.fill("title" to task.title)) },
                            )
                        }
                    }
                }
            }
        }
    }
    planning?.let { task ->
        TaskCreateSheet(onDismiss = { planning = null }, editing = task)
    }
    converting?.let { task ->
        RemftDialog(
            onDismissRequest = { converting = null },
            title = stringResource(R.string.inbox_convertTitle),
            text = {
                Text(
                    stringResource(R.string.inbox_convertBody),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            dismissButton = {
                RemftTextButton(stringResource(R.string.common_cancel), onClick = { converting = null })
            },
            confirmButton = {
                RemftFilledButton(
                    stringResource(R.string.inbox_convert),
                    onClick = {
                        converting = null
                        vm.convertToNote(task) {
                            android.widget.Toast.makeText(context, movedText, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    icon = Icons.Outlined.Description,
                    modifier = Modifier.testTag("convert-confirm"),
                )
            },
        )
    }
}

@Composable
private fun CaptureRow(task: Task, onPlan: () -> Unit, onConvert: () -> Unit, onDelete: () -> Unit) {
    SwipeToReveal(
        id = task.id,
        deleteDescription = stringResource(R.string.task_deleteSemantic).fill("title" to task.title),
        onDelete = onDelete,
        modifier = Modifier.padding(vertical = Space.x1),
    ) {
        CaptureRowContent(task.id, task.title, onPlan, onConvert, onDelete)
    }
}

@Composable
private fun CaptureRowContent(id: String, title: String, onPlan: () -> Unit, onConvert: () -> Unit, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    MarkedCard(Modifier.fillMaxWidth().testTag("capture-row-$id")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onPlan).padding(start = Space.x3, end = Space.x1, top = Space.x2, bottom = Space.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).background(scheme.primaryContainer, MarkedShapes.pill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Inbox, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(Space.x3))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CaptureAction(Icons.Outlined.Event, stringResource(R.string.inbox_plan), onPlan, "capture-plan-$id", scheme.primary)
            CaptureAction(Icons.Outlined.Description, stringResource(R.string.inbox_convertToNote), onConvert, "capture-to-note-$id", RemftTheme.colors.note.color)
            CaptureAction(Icons.Outlined.DeleteOutline, stringResource(R.string.common_delete), onDelete, "capture-delete-$id", scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CaptureAction(icon: ImageVector, description: String, onClick: () -> Unit, tag: String, tint: Color) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
        modifier = Modifier.testTag(tag),
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Preview(name = "Inbox light", widthDp = 411)
@Composable
private fun InboxLightPreview() {
    RemftTheme(darkTheme = false) { InboxPreviewContent() }
}

@Preview(name = "Inbox dark", widthDp = 411)
@Composable
private fun InboxDarkPreview() {
    RemftTheme(darkTheme = true) { InboxPreviewContent() }
}

@Composable
private fun InboxPreviewContent() {
    Column(Modifier.background(RemftTheme.tonal.level0).padding(Space.x4), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
        CaptureRowContent("a", "Ask about the car service slot", {}, {}, {})
        CaptureRowContent("b", "Ideas for the weekend trip", {}, {}, {})
        CaptureRowContent("c", "Renew the library card before the end of the month", {}, {}, {})
    }
}
