package com.ftapps.remft.ui.scheduled

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.data.tasks.PendingDeletes
import com.ftapps.remft.domain.tasks.ScheduledList
import com.ftapps.remft.domain.tasks.ScheduledSection
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.ui.tasks.ListLoad
import com.ftapps.remft.ui.tasks.TasksEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ScheduledUiState(
    val load: ListLoad<List<ScheduledSection>> = ListLoad.Loading,
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val today: LocalDate get() = now.atZone(zone).toLocalDate()
}

class ScheduledViewModel(private val env: TasksEnvironment) : ViewModel() {
    private val repository = env.repository
    private val retry = MutableStateFlow(0)

    val state: StateFlow<ScheduledUiState> = states(env, retry).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ScheduledUiState(now = repository.now(), zone = repository.zone),
    )

    fun retry() {
        retry.value += 1
    }

    fun complete(task: Task, onDone: () -> Unit, onFailed: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.setCompleted(task.id, true) }
                .onSuccess { onDone() }
                .onFailure { onFailed() }
        }
    }

    fun reopen(task: Task, onFailed: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.setCompleted(task.id, false) }.onFailure { onFailed() }
        }
    }

    companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun states(env: TasksEnvironment, retry: Flow<Int> = MutableStateFlow(0)): Flow<ScheduledUiState> {
            val repository = env.repository
            val tasks = retry.flatMapLatest {
                repository.watchScheduled()
                    .map<List<Task>, ListLoad<List<Task>>> { ListLoad.Ready(it) }
                    .onStart { emit(ListLoad.Loading) }
                    .catch { emit(ListLoad.Failed) }
            }
            return combine(tasks, repository.minuteTicks(), env.pendingDeletes.ids) { load, now, pending ->
                val zone = repository.zone
                val sections = when (load) {
                    ListLoad.Loading -> ListLoad.Loading
                    ListLoad.Failed -> ListLoad.Failed
                    is ListLoad.Ready -> ListLoad.Ready(ScheduledList.sections(PendingDeletes.without(load.items, pending) { it.id }, now, zone))
                }
                ScheduledUiState(sections, now, zone)
            }
        }
    }
}
