package com.ftapps.remft.ui.repeat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.repeat.EditScope
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.ui.components.RemftDialog
import com.ftapps.remft.ui.components.RemftFilledButton
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.components.RemftTonalButton
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.TasksEnvironment
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SeriesScopeDialog(isDateEdit: Boolean, onDismiss: () -> Unit, onApply: (EditScope) -> Unit) {
    var scope by remember { mutableStateOf(EditScope.preselected(isDateEdit)) }
    SeriesScopeDialogContent(scope, { scope = it }, onDismiss, onApply)
}

@Composable
private fun SeriesScopeDialogContent(
    scope: EditScope,
    onPick: (EditScope) -> Unit,
    onDismiss: () -> Unit,
    onApply: (EditScope) -> Unit,
) {
    val labels = mapOf(
        EditScope.This to R.string.repeat_scope_this,
        EditScope.Future to R.string.repeat_scope_future,
        EditScope.All to R.string.repeat_scope_all,
    )
    RemftDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("series-scope-dialog"),
        title = stringResource(R.string.repeat_scope_title),
        text = {
            Column {
                Text(
                    stringResource(R.string.repeat_scope_question),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x3))
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
                    EditScope.entries.forEach { option ->
                        ScopeOption(
                            label = stringResource(labels.getValue(option)),
                            selected = scope == option,
                            onClick = { onPick(option) },
                            modifier = Modifier.testTag("scope-${option.wire}"),
                        )
                    }
                }
            }
        },
        dismissButton = {
            RemftTextButton(stringResource(R.string.common_cancel), onClick = onDismiss, modifier = Modifier.testTag("scope-cancel"))
        },
        confirmButton = {
            RemftFilledButton(stringResource(R.string.repeat_scope_apply), onClick = { onApply(scope) }, modifier = Modifier.testTag("scope-apply"))
        },
    )
}

@Composable
private fun ScopeOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val container = if (selected) scheme.primaryContainer else Color.Transparent
    val content = if (selected) scheme.onPrimaryContainer else scheme.onSurface
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MarkedShapes.small)
            .background(container, MarkedShapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Space.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = scheme.primary, unselectedColor = scheme.outline),
        )
        Text(label, style = MaterialTheme.typography.bodyLarge, color = content, modifier = Modifier.padding(start = Space.x3))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OccurrenceDeleteDialog(onDismiss: () -> Unit, onThis: () -> Unit, onFuture: () -> Unit) {
    RemftDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("occurrence-delete-scope"),
        title = stringResource(R.string.repeat_scope_title),
        text = {
            Text(
                stringResource(R.string.repeat_scope_question),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        dismissButton = {
            RemftTextButton(stringResource(R.string.common_cancel), onClick = onDismiss, modifier = Modifier.testTag("delete-scope-cancel"))
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.x1, Alignment.End), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
                RemftTonalButton(stringResource(R.string.repeat_scope_this), onClick = onThis, modifier = Modifier.testTag("delete-scope-this"))
                DangerButton(stringResource(R.string.repeat_scope_future), onClick = onFuture, modifier = Modifier.testTag("delete-scope-future"))
            }
        },
    )
}

@Composable
private fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = MarkedShapes.pill,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        modifier = modifier.heightIn(min = Sizes.touchTarget),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun StopRepeatDialog(afterCompletion: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    RemftDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("repeat-stop-dialog"),
        title = stringResource(R.string.repeat_stop),
        text = {
            Text(
                stringResource(if (afterCompletion) R.string.repeat_stopConfirmAfter else R.string.repeat_stopConfirm),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        dismissButton = {
            RemftTextButton(stringResource(R.string.common_cancel), onClick = onDismiss)
        },
        confirmButton = {
            RemftFilledButton(stringResource(R.string.repeat_stop), onClick = onConfirm, modifier = Modifier.testTag("repeat-stop-confirm"))
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

@Preview(name = "Series scope light")
@Composable
private fun SeriesScopeLightPreview() {
    RemftTheme(darkTheme = false) { SeriesScopeDialogContent(EditScope.Future, {}, {}, {}) }
}

@Preview(name = "Series scope dark")
@Composable
private fun SeriesScopeDarkPreview() {
    RemftTheme(darkTheme = true) { SeriesScopeDialogContent(EditScope.Future, {}, {}, {}) }
}

@Preview(name = "Occurrence delete light")
@Composable
private fun OccurrenceDeleteLightPreview() {
    RemftTheme(darkTheme = false) { OccurrenceDeleteDialog({}, {}, {}) }
}

@Preview(name = "Occurrence delete dark")
@Composable
private fun OccurrenceDeleteDarkPreview() {
    RemftTheme(darkTheme = true) { OccurrenceDeleteDialog({}, {}, {}) }
}

@Preview(name = "Stop repeat light")
@Composable
private fun StopRepeatLightPreview() {
    RemftTheme(darkTheme = false) { StopRepeatDialog(afterCompletion = false, onDismiss = {}, onConfirm = {}) }
}

@Preview(name = "Stop repeat dark")
@Composable
private fun StopRepeatDarkPreview() {
    RemftTheme(darkTheme = true) { StopRepeatDialog(afterCompletion = true, onDismiss = {}, onConfirm = {}) }
}
