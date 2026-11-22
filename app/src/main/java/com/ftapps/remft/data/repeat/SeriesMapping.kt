package com.ftapps.remft.data.repeat

import com.ftapps.remft.data.db.entity.SeriesSkipEntity
import com.ftapps.remft.data.db.entity.TaskEntity
import com.ftapps.remft.data.db.entity.TaskSeriesEntity
import com.ftapps.remft.domain.Fold
import com.ftapps.remft.domain.repeat.OccurrenceRow
import com.ftapps.remft.domain.repeat.SeriesRecord
import com.ftapps.remft.domain.repeat.SeriesSkip
import com.ftapps.remft.domain.repeat.TaskField
import com.ftapps.remft.domain.repeat.TaskUpdate
import com.ftapps.remft.domain.time.UtcText
import java.time.Instant

object SeriesMapping {
    fun toRecord(entity: TaskSeriesEntity): SeriesRecord? {
        val anchor = UtcText.parse(entity.anchorAt) ?: return null
        return SeriesRecord(
            id = entity.id,
            workspaceId = entity.workspaceId,
            ruleJson = entity.ruleJson,
            templateJson = entity.templateJson,
            timezone = entity.timezone,
            anchorAt = anchor,
            createdAt = UtcText.parse(entity.createdAt),
            updatedAt = UtcText.parse(entity.updatedAt),
        )
    }

    fun toEntity(record: SeriesRecord, previous: TaskSeriesEntity? = null): TaskSeriesEntity = TaskSeriesEntity(
        id = record.id,
        workspaceId = record.workspaceId,
        ruleJson = record.ruleJson,
        templateJson = record.templateJson,
        timezone = record.timezone,
        anchorAt = keep(previous?.anchorAt, record.anchorAt) ?: UtcText.format(record.anchorAt),
        createdAt = keep(previous?.createdAt, record.createdAt),
        updatedAt = keep(previous?.updatedAt, record.updatedAt),
    )

    fun toRow(entity: TaskEntity, hasAttachedContent: Boolean): OccurrenceRow = OccurrenceRow(
        id = entity.id,
        workspaceId = entity.workspaceId,
        title = entity.title,
        description = entity.description,
        status = entity.status,
        priority = entity.priority,
        dueAt = UtcText.parse(entity.dueAt),
        remindAt = UtcText.parse(entity.remindAt),
        snoozedUntil = UtcText.parse(entity.snoozedUntil),
        timezone = entity.timezone,
        isUrgent = entity.isUrgent,
        requiresAcknowledgement = entity.requiresAcknowledgement,
        seriesId = entity.seriesId,
        occurrenceDate = entity.occurrenceDate,
        spawnedFromTaskId = entity.spawnedFromTaskId,
        completedAt = UtcText.parse(entity.completedAt),
        createdAt = UtcText.parse(entity.createdAt),
        updatedAt = UtcText.parse(entity.updatedAt),
        hasAttachedContent = hasAttachedContent,
    )

    fun newEntity(row: OccurrenceRow): TaskEntity = TaskEntity(
        id = row.id,
        workspaceId = row.workspaceId,
        title = row.title,
        description = row.description,
        status = row.status,
        priority = row.priority,
        dueAt = row.dueAt?.let(UtcText::format),
        remindAt = row.remindAt?.let(UtcText::format),
        snoozedUntil = row.snoozedUntil?.let(UtcText::format),
        timezone = row.timezone,
        isUrgent = row.isUrgent,
        requiresAcknowledgement = row.requiresAcknowledgement,
        seriesId = row.seriesId,
        occurrenceDate = row.occurrenceDate,
        spawnedFromTaskId = row.spawnedFromTaskId,
        completedAt = row.completedAt?.let(UtcText::format),
        titleFold = Fold.searchText(row.title),
        descriptionFold = row.description?.let(Fold::searchText),
        createdAt = row.createdAt?.let(UtcText::format),
        updatedAt = row.updatedAt?.let(UtcText::format),
    )

    fun applyUpdate(entity: TaskEntity, update: TaskUpdate): TaskEntity {
        val after = update.after
        var next = entity
        for (field in update.changed) {
            next = when (field) {
                TaskField.Title -> next.copy(title = after.title, titleFold = Fold.searchText(after.title))
                TaskField.Description -> next.copy(description = after.description, descriptionFold = after.description?.let(Fold::searchText))
                TaskField.Status -> next.copy(status = after.status)
                TaskField.Priority -> next.copy(priority = after.priority)
                TaskField.DueAt -> next.copy(dueAt = after.dueAt?.let(UtcText::format))
                TaskField.RemindAt -> next.copy(remindAt = after.remindAt?.let(UtcText::format))
                TaskField.SnoozedUntil -> next.copy(snoozedUntil = after.snoozedUntil?.let(UtcText::format))
                TaskField.Timezone -> next.copy(timezone = after.timezone)
                TaskField.IsUrgent -> next.copy(isUrgent = after.isUrgent)
                TaskField.RequiresAcknowledgement -> next.copy(requiresAcknowledgement = after.requiresAcknowledgement)
                TaskField.SeriesId -> next.copy(seriesId = after.seriesId)
                TaskField.OccurrenceDate -> next.copy(occurrenceDate = after.occurrenceDate)
                TaskField.SpawnedFromTaskId -> next.copy(spawnedFromTaskId = after.spawnedFromTaskId)
                TaskField.CompletedAt -> next.copy(completedAt = after.completedAt?.let(UtcText::format))
            }
        }
        if (update.before.updatedAt != after.updatedAt) next = next.copy(updatedAt = after.updatedAt?.let(UtcText::format))
        return next
    }

    fun toSkip(entity: SeriesSkipEntity): SeriesSkip =
        SeriesSkip(entity.seriesId, entity.occurrenceDate, UtcText.parse(entity.createdAt))

    fun toEntity(skip: SeriesSkip): SeriesSkipEntity =
        SeriesSkipEntity(skip.seriesId, skip.occurrenceDate, skip.createdAt?.let(UtcText::format))

    private fun keep(original: String?, value: Instant?): String? = when {
        value == null -> null
        original != null && UtcText.parse(original) == value -> original
        else -> UtcText.format(value)
    }
}
