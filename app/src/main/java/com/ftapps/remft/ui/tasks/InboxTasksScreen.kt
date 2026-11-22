package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import com.ftapps.remft.ui.components.EmptyState
import com.ftapps.remft.ui.components.ErrorState
import com.ftapps.remft.ui.quickadd.InboxQuickCapture
import com.ftapps.remft.ui.search.SearchField
import com.ftapps.remft.ui.theme.Radius
import com.ftapps.remft.ui.theme.RemftThemeExtras
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
                    ListLoad.Failed -> item { ErrorState(onRetry = vm::retry) }
                    is ListLoad.Ready -> {
                        if (state.total == 0) {
                            item {
                                EmptyState(Icons.Outlined.Inbox, stringResource(R.string.inbox_captureTitle), stringResource(R.string.inbox_captureBody))
                            }
                        } else if (load.items.isEmpty()) {
                            item {
                                EmptyState(
                                    Icons.Outlined.SearchOff,
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
        AlertDialog(
            onDismissRequest = { converting = null },
            title = { Text(stringResource(R.string.inbox_convertTitle)) },
            text = { Text(stringResource(R.string.inbox_convertBody)) },
            dismissButton = {
                TextButton(onClick = { converting = null }) { Text(stringResource(R.string.common_cancel)) }
            },
            confirmButton = {
                Button(
                    onClick = {
                        converting = null
                        vm.convertToNote(task) {
                            android.widget.Toast.makeText(context, movedText, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("convert-confirm"),
                ) { Text(stringResource(R.string.inbox_convert)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(Radius.sheet),
        )
    }
}

@Composable
private fun CaptureRow(task: Task, onPlan: () -> Unit, onConvert: () -> Unit, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SwipeToReveal(
        id = task.id,
        deleteDescription = stringResource(R.string.task_deleteSemantic).fill("title" to task.title),
        onDelete = onDelete,
        modifier = Modifier.padding(vertical = Space.x1),
    ) {
        Card(
            shape = RoundedCornerShape(Radius.card),
            colors = CardDefaults.cardColors(containerColor = scheme.surface),
            border = BorderStroke(1.dp, RemftThemeExtras.colors.hairline),
            modifier = Modifier.fillMaxWidth().testTag("capture-row-${task.id}"),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onPlan).padding(start = Space.x3, end = Space.x1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Inbox, contentDescription = null, tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(Space.x3))
                Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onPlan, modifier = Modifier.testTag("capture-plan-${task.id}")) {
                    Icon(Icons.Outlined.Event, contentDescription = stringResource(R.string.inbox_plan))
                }
                IconButton(onClick = onConvert, modifier = Modifier.testTag("capture-to-note-${task.id}")) {
                    Icon(Icons.Outlined.Description, contentDescription = stringResource(R.string.inbox_convertToNote))
                }
                IconButton(onClick = onDelete, modifier = Modifier.testTag("capture-delete-${task.id}")) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.common_delete))
                }
            }
        }
    }
}
