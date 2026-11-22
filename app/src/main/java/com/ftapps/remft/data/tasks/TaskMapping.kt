package com.ftapps.remft.data.tasks

import com.ftapps.remft.data.db.entity.ChecklistItemEntity
import com.ftapps.remft.data.db.entity.TaskEntity
import com.ftapps.remft.domain.Fold
import com.ftapps.remft.domain.tasks.ChecklistItem
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.time.UtcText
import java.time.Instant

object TaskMapping {
    fun toDomain(entity: TaskEntity): Task = Task(
        id = entity.id,
        workspaceId = entity.workspaceId,
        title = entity.title,
        description = entity.description,
        status = entity.status,
        priority = entity.priority,
        dueAt = UtcText.parse(entity.dueAt),
        remindAt = UtcText.parse(entity.remindAt),
        snoozedUntil = UtcText.parse(entity.snoozedUntil),
        alarmsMutedAt = UtcText.parse(entity.alarmsMutedAt),
        completedAt = UtcText.parse(entity.completedAt),
        timezone = entity.timezone,
        isUrgent = entity.isUrgent,
        requiresAcknowledgement = entity.requiresAcknowledgement,
        seriesId = entity.seriesId,
        occurrenceDate = entity.occurrenceDate,
        spawnedFromTaskId = entity.spawnedFromTaskId,
        sortOrder = entity.sortOrder,
        createdAt = UtcText.parse(entity.createdAt),
        updatedAt = UtcText.parse(entity.updatedAt),
    )

    fun toEntity(task: Task, previous: TaskEntity? = null): TaskEntity = TaskEntity(
        id = task.id,
        workspaceId = task.workspaceId,
        title = task.title,
        description = task.description,
        status = task.status,
        priority = task.priority,
        dueAt = keep(previous?.dueAt, task.dueAt),
        remindAt = keep(previous?.remindAt, task.remindAt),
        snoozedUntil = keep(previous?.snoozedUntil, task.snoozedUntil),
        alarmsMutedAt = keep(previous?.alarmsMutedAt, task.alarmsMutedAt),
        timezone = task.timezone,
        isUrgent = task.isUrgent,
        requiresAcknowledgement = task.requiresAcknowledgement,
        seriesId = task.seriesId,
        occurrenceDate = task.occurrenceDate,
        spawnedFromTaskId = task.spawnedFromTaskId,
        sortOrder = task.sortOrder,
        completedAt = keep(previous?.completedAt, task.completedAt),
        titleFold = if (previous != null && previous.title == task.title) previous.titleFold ?: Fold.searchText(task.title) else Fold.searchText(task.title),
        descriptionFold = task.description?.let { description ->
            if (previous != null && previous.description == description && previous.descriptionFold != null) previous.descriptionFold else Fold.searchText(description)
        },
        createdAt = keep(previous?.createdAt, task.createdAt),
        updatedAt = keep(previous?.updatedAt, task.updatedAt),
    )

    fun toDomain(entity: ChecklistItemEntity): ChecklistItem =
        ChecklistItem(entity.id, entity.taskId, entity.title, entity.isDone, entity.sortOrder)

    private fun keep(original: String?, value: Instant?): String? = when {
        value == null -> null
        original != null && UtcText.parse(original) == value -> original
        else -> UtcText.format(value)
    }
}
