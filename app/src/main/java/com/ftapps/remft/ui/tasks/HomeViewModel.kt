package com.ftapps.remft.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.tasks.DefaultTime
import com.ftapps.remft.domain.tasks.HomeGroup
import com.ftapps.remft.domain.tasks.HomeGrouping
import com.ftapps.remft.domain.tasks.SortChoice
import com.ftapps.remft.domain.tasks.SortState
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.tasks.TaskSort
import com.ftapps.remft.data.tasks.PendingDeletes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

sealed interface ListLoad<out T> {
    data object Loading : ListLoad<Nothing>
    data object Failed : ListLoad<Nothing>
    data class Ready<T>(val items: T) : ListLoad<T>
}

data class HomeUiState(
    val load: ListLoad<List<HomeGroup>> = ListLoad.Loading,
    val markedDays: Set<LocalDate> = emptySet(),
    val today: LocalDate = LocalDate.MIN,
    val now: Instant = Instant.EPOCH,
    val selectedDay: LocalDate? = null,
    val sort: SortState = SortState.parse(TaskSort.DEFAULT, TaskSort.choices),
    val calendarVisible: Boolean = true,
    val dueCount: Int = 0,
)

class HomeViewModel(private val env: TasksEnvironment) : ViewModel() {
    private val repository = env.repository
    private val selected = MutableStateFlow<LocalDate?>(null)
    private val retry = MutableStateFlow(0)

    private val sortFlow = env.settings.flow(SettingKeys.TasksSort).map { SortState.parse(it, TaskSort.choices) }
    private val calendarFlow = env.settings.flow(SettingKeys.HomeCalendarVisible).map { it != "false" }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tasksFlow = retry.flatMapLatest {
        repository.watchHome()
            .map<List<Task>, ListLoad<List<Task>>> { ListLoad.Ready(it) }
            .onStart { emit(ListLoad.Loading) }
            .catch { emit(ListLoad.Failed) }
    }

    private val listFlow = combine(tasksFlow, repository.minuteTicks()) { load, now -> load to now }

    val state: StateFlow<HomeUiState> = combine(listFlow, env.pendingDeletes.ids, selected, sortFlow, calendarFlow) { (load, now), pending, day, sort, calendar ->
        val zone = repository.zone
        val local = LocalDateTime.ofInstant(now, zone)
        when (load) {
            ListLoad.Loading -> HomeUiState(ListLoad.Loading, selectedDay = day, sort = sort, calendarVisible = calendar, today = local.toLocalDate(), now = now)
            ListLoad.Failed -> HomeUiState(ListLoad.Failed, selectedDay = day, sort = sort, calendarVisible = calendar, today = local.toLocalDate(), now = now)
            is ListLoad.Ready -> {
                val visible = PendingDeletes.without(load.items, pending) { it.id }
                HomeUiState(
                    load = ListLoad.Ready(HomeGrouping.group(visible, local, zone, day, sort)),
                    markedDays = HomeGrouping.daysWithTasks(visible, zone),
                    dueCount = HomeGrouping.dueByTodayCount(visible, local.toLocalDate(), zone),
                    today = local.toLocalDate(),
                    now = now,
                    selectedDay = day,
                    sort = sort,
                    calendarVisible = calendar,
                )
            }
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HomeUiState(today = LocalDateTime.ofInstant(repository.now(), repository.zone).toLocalDate(), now = repository.now()),
    )

    fun retry() {
        retry.value += 1
    }

    fun select(day: LocalDate?) {
        selected.value = day
    }

    fun toggleCalendar() {
        val visible = state.value.calendarVisible
        if (visible) selected.value = null
        viewModelScope.launch { env.settings.set(SettingKeys.HomeCalendarVisible.name, (!visible).toString()) }
    }

    fun chooseSort(choice: SortChoice) {
        saveSort(state.value.sort.select(choice))
    }

    fun reverseSort() {
        saveSort(state.value.sort.reversed())
    }

    private fun saveSort(sort: SortState) {
        viewModelScope.launch { env.settings.set(SettingKeys.TasksSort.name, sort.encode()) }
    }

    fun defaultDueForSelection(): LocalDateTime? {
        val day = selected.value ?: return null
        return DefaultTime.timeOn(day, LocalDateTime.ofInstant(repository.now(), repository.zone))
    }

    fun toggle(task: Task, onFailed: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.setCompleted(task.id, !task.isCompleted) }.onFailure { onFailed() }
        }
    }

    fun delete(task: Task, message: String) {
        env.undo.post(task.id, message) { repository.delete(task.id) }
    }
}
