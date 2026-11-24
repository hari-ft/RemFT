package com.ftapps.remft.ui.tasks

import com.ftapps.remft.ui.theme.Sizes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.tasks.TaskPriority
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.components.ChipRow
import com.ftapps.remft.ui.components.ChipTone
import com.ftapps.remft.ui.components.DueChip
import com.ftapps.remft.ui.components.GroupedCardItem
import com.ftapps.remft.ui.components.PriorityChip
import com.ftapps.remft.ui.components.RepeatChip
import com.ftapps.remft.ui.components.StatusChip
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.Space
import java.time.Instant
import java.time.ZoneId

@Composable
fun TaskRow(
    task: Task,
    format: DateFormatId,
    zone: ZoneId,
    now: Instant,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    highlighted: Boolean = false,
    position: GroupPosition = GroupPosition.Single,
    hasAttachments: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        highlighted -> scheme.primaryContainer.copy(alpha = 0.35f).compositeOver(scheme.surface)
        dimmed -> scheme.surfaceContainerLow
        else -> scheme.surface
    }
    val grouped = position != GroupPosition.Single
    SwipeToReveal(
        id = task.id,
        deleteDescription = stringResource(R.string.task_deleteSemantic).fill("title" to task.title),
        onDelete = onDelete,
        modifier = if (grouped) modifier else modifier.padding(vertical = Space.x1),
    ) {
        if (grouped) {
            GroupedCardItem(position, Modifier.testTag("task-row-${task.id}"), color = container) {
                TaskRowContent(task, format, zone, now, onToggle, onOpen, hasAttachments)
            }
        } else {
            Surface(
                shape = MarkedShapes.medium,
                color = container,
                border = if (highlighted) BorderStroke(1.dp, scheme.primary.copy(alpha = 0.45f)) else null,
                modifier = Modifier.fillMaxWidth().testTag("task-row-${task.id}"),
            ) {
                TaskRowContent(task, format, zone, now, onToggle, onOpen, hasAttachments)
            }
        }
    }
}

@Composable
private fun TaskRowContent(
    task: Task,
    format: DateFormatId,
    zone: ZoneId,
    now: Instant,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    hasAttachments: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val completed = task.isCompleted
    val due = task.dueAt
    val overdue = due != null && !completed && due.isBefore(now)
    val dueToday = due != null && due.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
    val muted = !completed && task.alarmsMutedAt != null
    val recurring = !completed && task.isRecurring
    val priority = TaskPriority.parse(task.priority)
    val urgentAlarm = task.isUrgent && !completed && (task.dueAt != null || task.remindAt != null)
    val showPriority = !completed && priority.showsChip()
    val hasChips = due != null || muted || recurring || showPriority || urgentAlarm
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.listRow)
            .clickable(onClick = onOpen)
            .padding(start = Space.x1, end = Space.x3, top = Space.x2, bottom = Space.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val description = if (completed) {
            stringResource(R.string.task_reopenNamed).fill("title" to task.title)
        } else {
            stringResource(R.string.task_completeNamed).fill("title" to task.title)
        }
        CircularCheck(completed, description, onToggle, Modifier.testTag("task-check-${task.id}"))
        Spacer(Modifier.width(Space.x2))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
            Text(
                task.title,
                style = MaterialTheme.typography.titleMedium.copy(textDecoration = if (completed) TextDecoration.LineThrough else null),
                color = if (completed) scheme.onSurfaceVariant else scheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasChips) {
                ChipRow {
                    if (due != null) {
                        val text = DateFormats.short(due.toLocal(zone), format)
                        DueChip(
                            if (overdue) {
                                stringResource(R.string.task_overdueDue).fill("date" to text)
                            } else {
                                stringResource(R.string.task_dueOn).fill("date" to text)
                            },
                            overdue = overdue,
                            today = dueToday && !completed,
                            modifier = Modifier.testTag("task-due-${task.id}"),
                        )
                    }
                    if (showPriority) {
                        val text = stringResource(R.string.task_prioritySemantic).fill("priority" to stringResource(priorityLabel(task.priority)))
                        if (priority == TaskPriority.Low) {
                            StatusChip(text, ChipTone.File, modifier = Modifier.testTag("task-priority-${task.id}"))
                        } else {
                            PriorityChip(text, strong = true, modifier = Modifier.testTag("task-priority-${task.id}"))
                        }
                    }
                    if (urgentAlarm) StatusChip(stringResource(R.string.task_urgent), ChipTone.Urgent, icon = Icons.Filled.NotificationImportant)
                    if (recurring) RepeatChip(stringResource(R.string.repeat_badgeTooltip))
                    if (muted) StatusChip(stringResource(R.string.task_alarmsMuted), ChipTone.Neutral, icon = Icons.Outlined.NotificationsOff)
                }
            }
        }
        Spacer(Modifier.width(Space.x2))
        if (hasAttachments) {
            Icon(
                Icons.Outlined.AttachFile,
                contentDescription = stringResource(R.string.taskattach_hasFiles),
                tint = scheme.outline,
                modifier = Modifier.size(18.dp).testTag("task-attached-${task.id}"),
            )
            Spacer(Modifier.width(Space.x1))
        }
        Icon(
            statusIcon(task.status),
            contentDescription = stringResource(R.string.task_statusSemantic).fill("status" to stringResource(statusLabel(task.status))),
            tint = scheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}

fun TaskPriority.showsChip(): Boolean = this != TaskPriority.None && this != TaskPriority.Medium
