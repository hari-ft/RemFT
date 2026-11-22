package com.ftapps.remft.ui.tasks

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.tasks.TaskPriority
import com.ftapps.remft.domain.tasks.TaskStatus
import com.ftapps.remft.ui.components.CircleCheckbox
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftSnackbar
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@StringRes
fun statusLabel(status: String): Int = when (TaskStatus.parse(status)) {
    TaskStatus.Inbox -> R.string.task_status_inbox
    TaskStatus.Open, null -> R.string.task_status_open
    TaskStatus.Scheduled -> R.string.task_status_scheduled
    TaskStatus.InProgress -> R.string.task_status_in_progress
    TaskStatus.Waiting -> R.string.task_status_waiting
    TaskStatus.Completed -> R.string.task_status_completed
    TaskStatus.Cancelled -> R.string.task_status_cancelled
    TaskStatus.Archived -> R.string.task_status_archived
}

fun statusIcon(status: String): ImageVector = when (TaskStatus.parse(status)) {
    TaskStatus.Inbox -> Icons.Outlined.Inbox
    TaskStatus.Open -> Icons.Outlined.HourglassEmpty
    TaskStatus.Scheduled -> Icons.Outlined.Event
    TaskStatus.InProgress -> Icons.Outlined.Timelapse
    TaskStatus.Waiting -> Icons.Outlined.PauseCircleOutline
    TaskStatus.Completed -> Icons.Filled.CheckCircle
    TaskStatus.Cancelled -> Icons.Outlined.Cancel
    TaskStatus.Archived -> Icons.Outlined.Archive
    null -> Icons.Outlined.Circle
}

@StringRes
fun priorityLabel(priority: String): Int = when (TaskPriority.parse(priority)) {
    TaskPriority.None -> R.string.task_priority_none
    TaskPriority.Low -> R.string.task_priority_low
    TaskPriority.Medium -> R.string.task_priority_medium
    TaskPriority.High -> R.string.task_priority_high
    TaskPriority.Urgent -> R.string.task_priority_urgent
}

@Composable
fun priorityColor(priority: String): Color? {
    val flags = RemftThemeExtras.colors.priority
    return when (TaskPriority.parse(priority)) {
        TaskPriority.None -> null
        TaskPriority.Low -> flags.low
        TaskPriority.Medium -> flags.medium
        TaskPriority.High -> flags.high
        TaskPriority.Urgent -> flags.urgent
    }
}

@Composable
fun rememberDateFormat(): DateFormatId {
    val env = LocalTasksEnvironment.current
    val flow = remember(env) { env.settings.flow(SettingKeys.DateFormat).map { DateFormatId.parse(it) } }
    val value by flow.collectAsStateWithLifecycle(DateFormatId.System)
    return value
}

@Composable
fun PriorityLabel(priority: String, modifier: Modifier = Modifier) {
    val color = priorityColor(priority)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (color == null) Icons.Outlined.Flag else Icons.Filled.Flag,
            contentDescription = null,
            tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Space.x2))
        Text(stringResource(priorityLabel(priority)))
    }
}

@Composable
fun StatusLabel(status: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(statusIcon(status), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.x2))
        Text(stringResource(statusLabel(status)))
    }
}

@Composable
fun CircularCheck(checked: Boolean, description: String, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    CircleCheckbox(checked, description, onToggle, modifier)
}

@Composable
fun <T> ChoiceField(
    label: String,
    selected: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tag: String,
    item: @Composable (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = MarkedShapes.small,
            color = RemftTheme.tonal.level2,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = Space.x4, end = Space.x3, top = Space.x2, bottom = Space.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    ProvideTextStyle(MaterialTheme.typography.titleMedium) { item(selected) }
                }
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MarkedShapes.medium,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { item(option) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    modifier = Modifier.testTag("$tag-$option"),
                )
            }
        }
    }
}

@Composable
fun SwipeToReveal(
    id: String,
    deleteDescription: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { 96.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sign = if (rtl) 1f else -1f
    val offset = remember(id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val draggable = rememberDraggableState { delta ->
        val next = offset.value + delta
        scope.launch { offset.snapTo(if (rtl) next.coerceIn(0f, revealPx) else next.coerceIn(-revealPx, 0f)) }
    }
    Box(modifier.fillMaxWidth()) {
        if (offset.value != 0f) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier
                        .width(88.dp)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp)
                        .background(MaterialTheme.colorScheme.error, MarkedShapes.medium)
                        .toggleable(value = false, role = Role.Button, onValueChange = {
                            scope.launch { offset.snapTo(0f) }
                            onDelete()
                        })
                        .semantics { contentDescription = deleteDescription }
                        .testTag("swipe-delete-$id"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    state = draggable,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        val target = if (kotlin.math.abs(offset.value) > revealPx / 2) sign * revealPx else 0f
                        offset.animateTo(target)
                    },
                )
                .testTag("swipe-$id"),
        ) { content() }
    }
}

@Composable
fun UndoSnackbarHost(modifier: Modifier = Modifier) {
    val env = LocalTasksEnvironment.current
    val host = remember { SnackbarHostState() }
    val request by env.undo.current.collectAsStateWithLifecycle()
    val undoLabel = stringResource(R.string.common_undo)
    val tooLate = stringResource(R.string.common_undoTooLate)
    LaunchedEffect(request) {
        val current = request ?: return@LaunchedEffect
        var resolved = false
        try {
            val result = coroutineScope {
                val timer = launch {
                    delay(env.pendingDeletes.window)
                    host.currentSnackbarData?.dismiss()
                }
                host.showSnackbar(current.message, undoLabel, duration = SnackbarDuration.Indefinite).also { timer.cancel() }
            }
            resolved = true
            if (result == SnackbarResult.ActionPerformed) {
                if (!env.undo.resolve(current, undo = true)) host.showSnackbar(tooLate)
            } else {
                env.undo.resolve(current, undo = false)
            }
        } finally {
            if (!resolved) env.undo.resolve(current, undo = false)
        }
    }
    SnackbarHost(host, modifier.testTag("undo-snackbar")) { data ->
        RemftSnackbar(data)
    }
}

@Preview(name = "Choice fields light")
@Composable
private fun ChoiceFieldsLightPreview() {
    RemftTheme(darkTheme = false) { ChoiceFieldsPreviewContent() }
}

@Preview(name = "Choice fields dark")
@Composable
private fun ChoiceFieldsDarkPreview() {
    RemftTheme(darkTheme = true) { ChoiceFieldsPreviewContent() }
}

@Composable
private fun ChoiceFieldsPreviewContent() {
    PreviewSurface {
        Row {
            ChoiceField(
                label = "Status",
                selected = TaskStatus.InProgress.db,
                options = TaskStatus.entries.map { it.db },
                onSelect = {},
                tag = "preview-status",
                modifier = Modifier.weight(1f),
            ) { StatusLabel(it) }
            Spacer(Modifier.width(Space.x3))
            ChoiceField(
                label = "Priority",
                selected = TaskPriority.High.db,
                options = TaskPriority.entries.map { it.db },
                onSelect = {},
                tag = "preview-priority",
                modifier = Modifier.weight(1f),
            ) { PriorityLabel(it) }
        }
    }
}
