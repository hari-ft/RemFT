package com.ftapps.remft.ui.tasks

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.theme.Radius
import com.ftapps.remft.ui.theme.RemftThemeExtras
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
) {
    val scheme = MaterialTheme.colorScheme
    val completed = task.isCompleted
    val due = task.dueAt
    val overdue = due != null && !completed && due.isBefore(now)
    val muted = !completed && task.alarmsMutedAt != null
    val recurring = !completed && task.isRecurring
    val flag = priorityColor(task.priority)
    val container = when {
        highlighted -> scheme.primaryContainer.copy(alpha = 0.45f).compositeOver(scheme.surface)
        completed -> scheme.surfaceContainerLow
        dimmed -> scheme.surfaceContainer
        else -> scheme.surface
    }
    SwipeToReveal(
        id = task.id,
        deleteDescription = stringResource(R.string.task_deleteSemantic).fill("title" to task.title),
        onDelete = onDelete,
        modifier = modifier.padding(vertical = Space.x1),
    ) {
        Card(
            shape = RoundedCornerShape(Radius.card),
            colors = CardDefaults.cardColors(containerColor = container),
            border = BorderStroke(1.dp, if (highlighted) scheme.primary.copy(alpha = 0.45f) else RemftThemeExtras.colors.hairline),
            modifier = Modifier.fillMaxWidth().testTag("task-row-${task.id}"),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(onClick = onOpen)
                    .padding(start = Space.x1, end = Space.x3, top = Space.x1, bottom = Space.x1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val description = if (completed) {
                    stringResource(R.string.task_reopenNamed).fill("title" to task.title)
                } else {
                    stringResource(R.string.task_completeNamed).fill("title" to task.title)
                }
                CircularCheck(completed, description, onToggle, Modifier.testTag("task-check-${task.id}"))
                Spacer(Modifier.width(Space.x1))
                Column(Modifier.weight(1f).padding(vertical = Space.x2)) {
                    Text(
                        task.title,
                        style = if (completed) {
                            MaterialTheme.typography.bodyLarge.copy(textDecoration = TextDecoration.LineThrough, fontWeight = FontWeight.Medium)
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        color = if (completed) scheme.onSurfaceVariant else scheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (due != null || muted || recurring) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                            if (recurring) {
                                Icon(Icons.Outlined.Repeat, contentDescription = stringResource(R.string.repeat_badgeTooltip), tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                            if (muted) {
                                Icon(Icons.Outlined.NotificationsOff, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                Text(
                                    stringResource(R.string.task_alarmsMuted),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (due != null) {
                                val text = DateFormats.short(due.toLocal(zone), format)
                                Text(
                                    if (overdue) {
                                        stringResource(R.string.task_overdueDue).fill("date" to text)
                                    } else {
                                        stringResource(R.string.task_dueOn).fill("date" to text)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = when {
                                        overdue -> scheme.error
                                        else -> scheme.onSurfaceVariant
                                    },
                                    fontWeight = if (overdue) FontWeight.SemiBold else null,
                                    modifier = Modifier.testTag("task-due-${task.id}"),
                                )
                            }
                        }
                    }
                }
                if (flag != null) {
                    Icon(
                        Icons.Filled.Flag,
                        contentDescription = stringResource(R.string.task_prioritySemantic).fill("priority" to stringResource(priorityLabel(task.priority))),
                        tint = flag,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Space.x2))
                }
                if (task.isUrgent && !completed && (task.dueAt != null || task.remindAt != null)) {
                    Icon(
                        Icons.Filled.NotificationImportant,
                        contentDescription = stringResource(R.string.task_urgent),
                        tint = scheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Space.x2))
                }
                Icon(
                    statusIcon(task.status),
                    contentDescription = stringResource(R.string.task_statusSemantic).fill("status" to stringResource(statusLabel(task.status))),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
