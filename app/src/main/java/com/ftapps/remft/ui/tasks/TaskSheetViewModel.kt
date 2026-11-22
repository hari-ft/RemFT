package com.ftapps.remft.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.repeat.SeriesTooDenseException
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.tasks.TaskPatch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

enum class SheetSave { Idle, Saving, Failed, TooDense, Saved }

class TaskSheetViewModel(
    private val env: TasksEnvironment,
    private val startSeries: suspend (String, RepeatRule, Instant) -> Unit = { id, rule, at -> env.series?.start(id, rule, at) },
) : ViewModel() {
    private val status = MutableStateFlow(SheetSave.Idle)
    val state: StateFlow<SheetSave> = status.asStateFlow()

    fun create(draft: TaskDraft, rule: RepeatRule?, anchor: Instant?) = run {
        val repeat: (suspend (String) -> Unit)? = if (rule != null && anchor != null && env.series != null) {
            { id -> startSeries(id, rule, anchor) }
        } else {
            null
        }
        env.repository.create(draft, repeat)
    }

    fun update(id: String, patch: TaskPatch) = run { env.repository.update(id, patch) }

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
                    SheetSave.Failed
                }
            }
        }
    }
}
