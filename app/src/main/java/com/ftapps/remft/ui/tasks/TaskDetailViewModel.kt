package com.ftapps.remft.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.data.tasks.TaskDetail
import com.ftapps.remft.domain.repeat.EditScope
import com.ftapps.remft.domain.tasks.Change
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.tasks.TaskPatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

sealed interface DetailLoad {
    data object Loading : DetailLoad
    data object NotFound : DetailLoad
    data class Ready(val detail: TaskDetail) : DetailLoad
}

class TaskDetailViewModel(private val env: TasksEnvironment, val taskId: String) : ViewModel() {
    private val repository = env.repository
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var titleJob: Job? = null
    private var pendingTitle: String? = null
    private var descriptionJob: Job? = null
    private var pendingDescription: String? = null

    val state: StateFlow<DetailLoad> = repository.watchDetail(taskId)
        .map { detail -> if (detail == null) DetailLoad.NotFound else DetailLoad.Ready(detail) }
        .onStart { emit(DetailLoad.Loading) }
        .catch { emit(DetailLoad.NotFound) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailLoad.Loading)

    fun now(): Instant = repository.now()

    private fun current() = (state.value as? DetailLoad.Ready)?.detail?.task

    var scopeAsker: ((Task, Boolean, (EditScope?) -> Unit) -> Unit)? = null

    fun apply(patch: TaskPatch, scope: EditScope? = null) {
        writes.launch { runCatching { repository.update(taskId, patch, scope) } }
    }

    private fun applyTitle(task: Task?, patch: TaskPatch, interactive: Boolean) {
        val asker = scopeAsker
        when {
            task == null || !task.isRecurring -> apply(patch)
            interactive && asker != null -> asker(task, false) { scope -> apply(patch, scope) }
            else -> writes.launch {
                runCatching {
                    val afterCompletion = env.series?.isAfterCompletion(task.seriesId) ?: true
                    repository.update(taskId, patch, if (afterCompletion) null else EditScope.Future)
                }
            }
        }
    }

    fun onTitleChanged(text: String) {
        pendingTitle = text
        titleJob?.cancel()
        titleJob = viewModelScope.launch {
            delay(AUTOSAVE_MS)
            flushTitle(interactive = true)
        }
    }

    fun flushTitle(interactive: Boolean = false) {
        titleJob?.cancel()
        val text = pendingTitle ?: return
        pendingTitle = null
        val title = text.trim()
        val task = current()
        if (title.isEmpty() || title == task?.title) return
        applyTitle(task, TaskPatch(title = title), interactive)
    }

    fun onDescriptionChanged(text: String) {
        pendingDescription = text
        descriptionJob?.cancel()
        descriptionJob = viewModelScope.launch {
            delay(AUTOSAVE_MS)
            flushDescription()
        }
    }

    fun flushDescription() {
        descriptionJob?.cancel()
        val text = pendingDescription ?: return
        pendingDescription = null
        val value = text.trim()
        if (value == current()?.description.orEmpty().trim()) return
        apply(TaskPatch(description = Change(value.ifEmpty { null })))
    }

    fun toggleComplete() {
        val task = current() ?: return
        writes.launch { runCatching { repository.setCompleted(taskId, !task.isCompleted) } }
    }

    fun setMuted(muted: Boolean) {
        writes.launch { runCatching { repository.muteAlarms(taskId, muted) } }
    }

    fun delete(message: String) {
        flushTitle()
        flushDescription()
        env.undo.post(taskId, message) { repository.delete(taskId) }
    }

    fun addItem(title: String) {
        writes.launch { runCatching { repository.addChecklistItem(taskId, title) } }
    }

    fun renameItem(id: String, title: String) {
        writes.launch { runCatching { repository.renameChecklistItem(id, title) } }
    }

    fun setItemDone(id: String, done: Boolean) {
        writes.launch { runCatching { repository.setChecklistItemDone(id, done) } }
    }

    fun deleteItem(id: String) {
        writes.launch { runCatching { repository.deleteChecklistItem(id) } }
    }

    fun moveItem(id: String, offset: Int) {
        writes.launch { runCatching { repository.moveChecklistItem(taskId, id, offset) } }
    }

    override fun onCleared() {
        flushTitle()
        flushDescription()
    }

    companion object {
        const val AUTOSAVE_MS = 1_500L
    }
}
