package com.ftapps.remft.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.repeat.SeriesTooDenseException
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.ui.files.PickedFile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

enum class SheetSave { Idle, Saving, Failed, TooDense, Saved }

class TaskSheetViewModel(
    private val env: TasksEnvironment,
    private val attach: (suspend (String, List<PickedFile>) -> Unit)? = null,
    private val startSeries: suspend (String, RepeatRule, Instant) -> Unit = { id, rule, at -> env.series?.start(id, rule, at) },
) : ViewModel() {
    private val status = MutableStateFlow(SheetSave.Idle)
    val state: StateFlow<SheetSave> = status.asStateFlow()
    private val pending = MutableStateFlow<List<PickedFile>>(emptyList())
    val picks: StateFlow<List<PickedFile>> = pending.asStateFlow()

    fun addPicks(added: List<PickedFile>) {
        if (status.value == SheetSave.Saved) {
            added.forEach(::release)
            return
        }
        pending.update { it + added }
    }

    fun removePick(index: Int) {
        var removed: PickedFile? = null
        pending.update { current ->
            removed = current.getOrNull(index)
            if (removed == null) current else current.filterIndexed { at, _ -> at != index }
        }
        removed?.let(::release)
    }

    fun discard() {
        val dropped = pending.value
        pending.value = emptyList()
        dropped.forEach(::release)
    }

    fun create(draft: TaskDraft, rule: RepeatRule?, anchor: Instant?) = run {
        val repeat: (suspend (String) -> Unit)? = if (rule != null && anchor != null && env.series != null) {
            { id -> startSeries(id, rule, anchor) }
        } else {
            null
        }
        handOff(env.repository.create(draft, repeat))
    }

    fun update(id: String, patch: TaskPatch) = run {
        env.repository.update(id, patch)
        handOff(id)
    }

    private suspend fun handOff(taskId: String) {
        val handed = pending.value
        val upload = attach
        if (handed.isEmpty() || upload == null) return
        pending.value = emptyList()
        try {
            upload(taskId, handed)
        } catch (error: Exception) {
            AppLogs.warn("tasks.attach_failed", error)
            handed.forEach(::release)
        }
    }

    private fun release(pick: PickedFile) {
        runCatching(pick.cleanup).onFailure { AppLogs.warn("files.pick_cleanup_failed", it) }
    }

    override fun onCleared() {
        discard()
    }

    private fun run(write: suspend () -> Unit) {
        if (status.value == SheetSave.Saving || status.value == SheetSave.Saved) return
        status.value = SheetSave.Saving
        viewModelScope.launch {
            status.value = withContext(NonCancellable) {
                try {
                    write()
                    SheetSave.Saved
                } catch (dense: SeriesTooDenseException) {
                    SheetSave.TooDense
                } catch (error: Exception) {
                    AppLogs.error("tasks.save_failed", error)
                    SheetSave.Failed
                }
            }
        }
    }
}
