package com.ftapps.remft.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.data.tasks.PendingDeletes
import com.ftapps.remft.domain.search.GlobalSearch
import com.ftapps.remft.domain.search.SearchMatch
import com.ftapps.remft.domain.search.SearchTier
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.empty.EmptyIllustration
import com.ftapps.remft.ui.empty.IllustratedEmptyState
import com.ftapps.remft.ui.empty.IllustratedErrorState
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.RowActions
import com.ftapps.remft.ui.tasks.TaskRow
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class TaskSearchRow(val task: Task, val tier: SearchTier, val context: String?)

sealed interface TaskSearchState {
    data object Loading : TaskSearchState
    data object Failed : TaskSearchState
    data class Ready(val rows: List<TaskSearchRow>) : TaskSearchState
}

object TaskSearchRanking {
    private val dueOrder = compareBy<TaskSearchRow> { it.tier.rank }
        .thenBy { it.task.dueAt == null }
        .thenBy { it.task.dueAt }
        .thenByDescending { it.task.id }

    fun rank(tasks: List<Task>, query: String, statuses: Collection<String> = GlobalSearch.homeTaskStatuses): List<TaskSearchRow> {
        val words = SearchMatch.queryWords(query)
        if (words.isEmpty()) return emptyList()
        return tasks
            .asSequence()
            .filter { it.status in statuses }
            .distinctBy { it.id }
            .mapNotNull { task ->
                SearchMatch.tier(words, task.title, task.description)?.let { tier ->
                    TaskSearchRow(task, tier, GlobalSearch.context(tier, task.description, words))
                }
            }
            .sortedWith(dueOrder)
            .toList()
    }
}

private sealed interface TaskPool {
    data object Loading : TaskPool
    data object Failed : TaskPool
    data class Ready(val tasks: List<Task>) : TaskPool
}

@Composable
fun rememberHomeSearch(query: String): TaskSearchState {
    val env = LocalTasksEnvironment.current
    val pool: Flow<TaskPool> = remember(env) {
        combine(env.repository.watchHome(), env.repository.watchInbox(), env.pendingDeletes.ids) { home, inbox, pending ->
            PendingDeletes.without(home + inbox, pending) { it.id }
        }
            .map<List<Task>, TaskPool> { TaskPool.Ready(it) }
            .catch { emit(TaskPool.Failed) }
    }
    val current by pool.collectAsStateWithLifecycle(TaskPool.Loading)
    return remember(current, query) {
        when (val value = current) {
            TaskPool.Loading -> TaskSearchState.Loading
            TaskPool.Failed -> TaskSearchState.Failed
            is TaskPool.Ready -> TaskSearchState.Ready(TaskSearchRanking.rank(value.tasks, query))
        }
    }
}

fun LazyListScope.homeSearchResults(
    state: TaskSearchState,
    query: String,
    format: DateFormatId,
    zone: ZoneId,
    now: Instant,
    actions: RowActions,
    onRetry: () -> Unit = {},
) {
    when (state) {
        TaskSearchState.Loading -> item(key = "home-search-loading") {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(vertical = Space.x2),
                color = MaterialTheme.colorScheme.primary,
                trackColor = RemftTheme.tonal.level2,
            )
        }
        TaskSearchState.Failed -> item(key = "home-search-failed") { IllustratedErrorState(onRetry = onRetry) }
        is TaskSearchState.Ready -> {
            if (state.rows.isEmpty()) {
                item(key = "home-search-empty") {
                    IllustratedEmptyState(EmptyIllustration.Search,
                        
                        stringResource(R.string.home_searchEmptyTitle),
                        stringResource(R.string.home_searchEmptyBody).fill("query" to query.trim()),
                        Modifier.testTag("home-search-empty"),
                    )
                }
            }
            items(state.rows, key = { "search-${it.task.id}" }) { row ->
                Column(Modifier.testTag("home-search-result-${row.task.id}")) {
                    TaskRow(
                        task = row.task,
                        format = format,
                        zone = zone,
                        now = now,
                        onToggle = { actions.onToggle(row.task) },
                        onOpen = { actions.onOpen(row.task) },
                        onDelete = { actions.onDelete(row.task) },
                    )
                    row.context?.let { snippet ->
                        SearchSnippet(snippet, query)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSnippet(snippet: String, query: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.x12, end = Space.x2, bottom = Space.x2)
            .background(RemftTheme.tonal.level2, MarkedShapes.small)
            .padding(horizontal = Space.x3, vertical = Space.x2),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.FormatQuote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Space.x2))
        HighlightedText(
            text = snippet,
            query = query,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

@Composable
private fun SearchSnippetPreviewContent() {
    PreviewSurface {
        Column {
            SearchSnippet("Ask the landlord for the rent receipt for October", "rent")
        }
    }
}

@Preview(name = "Search snippet light")
@Composable
private fun SearchSnippetLightPreview() {
    RemftTheme(darkTheme = false) { SearchSnippetPreviewContent() }
}

@Preview(name = "Search snippet dark")
@Composable
private fun SearchSnippetDarkPreview() {
    RemftTheme(darkTheme = true) { SearchSnippetPreviewContent() }
}
