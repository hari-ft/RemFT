package com.ftapps.remft.ui.repeat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.repeat.EditScope
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.TasksEnvironment
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SeriesScopeDialog(isDateEdit: Boolean, onDismiss: () -> Unit, onApply: (EditScope) -> Unit) {
    var scope by remember { mutableStateOf(EditScope.preselected(isDateEdit)) }
    val labels = mapOf(
        EditScope.This to R.string.repeat_scope_this,
        EditScope.Future to R.string.repeat_scope_future,
        EditScope.All to R.string.repeat_scope_all,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("series-scope-dialog"),
        title = { Text(stringResource(R.string.repeat_scope_title)) },
        text = {
            Column {
                Text(stringResource(R.string.repeat_scope_question))
                Spacer(Modifier.height(Space.x2))
                Column(Modifier.selectableGroup()) {
                    EditScope.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = scope == option, role = Role.RadioButton) { scope = option }
                                .testTag("scope-${option.wire}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = scope == option, onClick = null)
                            Text(stringResource(labels.getValue(option)), modifier = Modifier.padding(start = Space.x3))
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("scope-cancel")) { Text(stringResource(R.string.common_cancel)) }
        },
        confirmButton = {
            Button(onClick = { onApply(scope) }, modifier = Modifier.testTag("scope-apply")) { Text(stringResource(R.string.repeat_scope_apply)) }
        },
    )
}

@Composable
fun OccurrenceDeleteDialog(onDismiss: () -> Unit, onThis: () -> Unit, onFuture: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("occurrence-delete-scope"),
        title = { Text(stringResource(R.string.repeat_scope_title)) },
        text = { Text(stringResource(R.string.repeat_scope_question)) },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("delete-scope-cancel")) { Text(stringResource(R.string.common_cancel)) }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onThis, modifier = Modifier.testTag("delete-scope-this")) { Text(stringResource(R.string.repeat_scope_this)) }
                Button(onClick = onFuture, modifier = Modifier.testTag("delete-scope-future")) { Text(stringResource(R.string.repeat_scope_future)) }
            }
        },
    )
}

@Composable
fun StopRepeatDialog(afterCompletion: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("repeat-stop-dialog"),
        title = { Text(stringResource(R.string.repeat_stop)) },
        text = { Text(stringResource(if (afterCompletion) R.string.repeat_stopConfirmAfter else R.string.repeat_stopConfirm)) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.testTag("repeat-stop-confirm")) { Text(stringResource(R.string.repeat_stop)) }
        },
    )
}

class ScopeRequest(val isDateEdit: Boolean, val apply: (EditScope) -> Unit)

class SeriesScopeAsker(private val env: TasksEnvironment, private val scope: CoroutineScope) {
    var request by mutableStateOf<ScopeRequest?>(null)
        private set

    fun ask(task: Task, isDateEdit: Boolean, apply: (EditScope?) -> Unit) {
        val series = env.series
        if (!task.isRecurring || series == null) {
            apply(null)
            return
        }
        scope.launch {
            if (runCatching { series.isAfterCompletion(task.seriesId) }.getOrDefault(false)) {
                apply(null)
            } else {
                request = ScopeRequest(isDateEdit) { apply(it) }
            }
        }
    }

    fun dismiss() {
        request = null
    }
}

@Composable
fun rememberSeriesScopeAsker(): SeriesScopeAsker {
    val env = LocalTasksEnvironment.current
    val scope = rememberCoroutineScope()
    return remember(env, scope) { SeriesScopeAsker(env, scope) }
}

@Composable
fun SeriesScopeHost(asker: SeriesScopeAsker) {
    val request = asker.request ?: return
    SeriesScopeDialog(
        isDateEdit = request.isDateEdit,
        onDismiss = asker::dismiss,
        onApply = { picked ->
            asker.dismiss()
            request.apply(picked)
        },
    )
}

class OccurrenceDeleteRequest(val task: Task, val onThis: () -> Unit, val afterDone: () -> Unit)

class OccurrenceDeleter(private val env: TasksEnvironment, private val scope: CoroutineScope) {
    var request by mutableStateOf<OccurrenceDeleteRequest?>(null)
        private set

    fun request(task: Task, message: String, deleteThis: () -> Unit, afterDone: () -> Unit = {}) {
        val series = env.series
        val seriesId = task.seriesId
        if (seriesId == null || series == null) {
            deleteThis()
            afterDone()
            return
        }
        scope.launch {
            val afterCompletion = runCatching { series.isAfterCompletion(seriesId) }.getOrDefault(false)
            when {
                afterCompletion && !task.isCompleted -> {
                    env.undo.post(task.id, message) {
                        env.repository.delete(task.id)
                        series.stop(seriesId, fromDay = task.occurrenceDate)
                    }
                    afterDone()
                }
                afterCompletion -> {
                    deleteThis()
                    afterDone()
                }
                else -> request = OccurrenceDeleteRequest(task, deleteThis, afterDone)
            }
        }
    }

    fun answer(thisOnly: Boolean) {
        val current = request ?: return
        request = null
        val series = env.series
        val seriesId = current.task.seriesId
        if (thisOnly || series == null || seriesId == null) {
            current.onThis()
        } else {
            val day = current.task.occurrenceDate
            series.launch { stop(seriesId, fromDay = day) }
        }
        current.afterDone()
    }

    fun dismiss() {
        request = null
    }
}

@Composable
fun rememberOccurrenceDeleter(): OccurrenceDeleter {
    val env = LocalTasksEnvironment.current
    val scope = rememberCoroutineScope()
    return remember(env, scope) { OccurrenceDeleter(env, scope) }
}

@Composable
fun OccurrenceDeleteHost(deleter: OccurrenceDeleter) {
    deleter.request ?: return
    OccurrenceDeleteDialog(
        onDismiss = deleter::dismiss,
        onThis = { deleter.answer(thisOnly = true) },
        onFuture = { deleter.answer(thisOnly = false) },
    )
}
