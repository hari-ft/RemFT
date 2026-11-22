package com.ftapps.remft.ui.quickadd

import android.widget.Toast
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ftapps.remft.R
import com.ftapps.remft.domain.quickadd.QuickParse
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.tasks.DateTimeField
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.TasksEnvironment
import com.ftapps.remft.ui.theme.Radius
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime

data class PendingDeadline(val raw: String, val parsed: QuickParse, val prefill: LocalDateTime?)

class QuickAddViewModel(env: TasksEnvironment) : ViewModel() {
    private val repository = env.repository
    private val creator = QuickAddCreator(repository, env.series)
    private val pendingState = MutableStateFlow<PendingDeadline?>(null)
    val pending: StateFlow<PendingDeadline?> = pendingState.asStateFlow()

    fun now(): LocalDateTime = LocalDateTime.ofInstant(repository.now(), repository.zone)

    fun submitFromHome(raw: String, parsed: QuickParse, prefill: LocalDateTime?, onFailed: (String) -> Unit) {
        if (parsed.hasSchedule) {
            create(raw, parsed, null, capture = false, onFailed)
        } else {
            pendingState.value = PendingDeadline(raw, parsed, prefill)
        }
    }

    fun answerDeadline(pending: PendingDeadline, due: LocalDateTime?, onFailed: (String) -> Unit) {
        if (!pendingState.compareAndSet(pending, null)) return
        create(pending.raw, pending.parsed, due, capture = false, onFailed)
    }

    fun capture(raw: String, parsed: QuickParse, onFailed: (String) -> Unit) {
        create(raw, parsed, null, capture = true, onFailed)
    }

    private fun create(raw: String, parsed: QuickParse, due: LocalDateTime?, capture: Boolean, onFailed: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { creator.createFromQuickAdd(parsed, due, capture) }.onFailure { onFailed(raw) }
        }
    }
}

@Composable
private fun failureToast(): (String) -> Unit {
    val context = LocalContext.current
    val template = stringResource(R.string.task_couldNotAdd)
    return { raw -> Toast.makeText(context, template.fill("title" to raw), Toast.LENGTH_SHORT).show() }
}

@Composable
fun HomeQuickAdd(hint: String, prefillDue: () -> LocalDateTime?, modifier: Modifier = Modifier) {
    val env = LocalTasksEnvironment.current
    val vm: QuickAddViewModel = viewModel { QuickAddViewModel(env) }
    val pending by vm.pending.collectAsStateWithLifecycle()
    val onFailed = failureToast()
    QuickAddBar(
        hint = hint,
        tag = "home-quick-add",
        onSubmit = { raw, parsed -> vm.submitFromHome(raw, parsed, prefillDue(), onFailed) },
        modifier = modifier,
    )
    pending?.let { current ->
        DeadlineDialog(
            initial = current.prefill,
            now = vm::now,
            onAnswer = { due -> vm.answerDeadline(current, due, onFailed) },
        )
    }
}

@Composable
fun InboxQuickCapture(hint: String, modifier: Modifier = Modifier) {
    val env = LocalTasksEnvironment.current
    val vm: QuickAddViewModel = viewModel { QuickAddViewModel(env) }
    val onFailed = failureToast()
    QuickAddBar(
        hint = hint,
        tag = "inbox-capture",
        onSubmit = { raw, parsed -> vm.capture(raw, parsed, onFailed) },
        modifier = modifier,
    )
}

@Composable
fun DeadlineDialog(initial: LocalDateTime?, now: () -> LocalDateTime, onAnswer: (LocalDateTime?) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text(stringResource(R.string.home_deadlineTitle)) },
        text = {
            DateTimeField(
                tag = "quick-add-deadline",
                label = stringResource(R.string.task_due),
                icon = Icons.Outlined.Flag,
                value = value,
                now = now,
                onChanged = { value = it },
            )
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(null) }, modifier = Modifier.testTag("quick-add-deadline-skip")) {
                Text(stringResource(R.string.home_deadlineSkip))
            }
        },
        confirmButton = {
            Button(onClick = { onAnswer(value) }, modifier = Modifier.testTag("quick-add-deadline-set")) {
                Text(stringResource(R.string.home_deadlineSet))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(Radius.sheet),
        modifier = Modifier.testTag("quick-add-deadline-dialog"),
    )
}
