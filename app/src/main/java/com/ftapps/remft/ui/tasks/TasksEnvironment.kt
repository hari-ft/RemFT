package com.ftapps.remft.ui.tasks

import androidx.compose.runtime.staticCompositionLocalOf
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.repeat.SeriesRepository
import com.ftapps.remft.data.tasks.PendingDeletes
import com.ftapps.remft.data.tasks.TasksRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

sealed interface HomeRequest {
    data object Create : HomeRequest
    data class SelectDay(val day: LocalDate) : HomeRequest
}

class HomeRequests {
    private val pending = MutableStateFlow<HomeRequest?>(null)
    val requests: StateFlow<HomeRequest?> = pending.asStateFlow()

    fun post(request: HomeRequest) {
        pending.value = request
    }

    fun take(request: HomeRequest): Boolean = pending.compareAndSet(request, null)
}

data class UndoRequest(val id: String, val message: String, val serial: Long)

class UndoCenter(private val pending: PendingDeletes) {
    private val state = MutableStateFlow<UndoRequest?>(null)
    private var serial = 0L
    val current: StateFlow<UndoRequest?> = state.asStateFlow()

    @Synchronized
    fun post(id: String, message: String, hide: Set<String> = emptySet(), commit: suspend () -> Unit) {
        state.value?.let { previous -> if (previous.id != id) pending.commitNow(previous.id) }
        serial += 1
        val request = UndoRequest(id, message, serial)
        pending.schedule(id, hide) {
            state.compareAndSet(request, null)
            commit()
        }
        state.value = request
    }

    fun shown(request: UndoRequest): Boolean = state.value == request && pending.restartWindow(request.id)

    fun resolve(request: UndoRequest, undo: Boolean): Boolean {
        state.compareAndSet(request, null)
        return if (undo) {
            pending.undo(request.id)
        } else {
            pending.commitNow(request.id)
            true
        }
    }
}

class TasksEnvironment(
    val repository: TasksRepository,
    val settings: SettingsStore,
    val pendingDeletes: PendingDeletes,
    val homeRequests: HomeRequests = HomeRequests(),
    val series: SeriesRepository? = null,
) {
    val undo = UndoCenter(pendingDeletes)
}

val LocalTasksEnvironment = staticCompositionLocalOf<TasksEnvironment> {
    error("TasksEnvironment not provided")
}
