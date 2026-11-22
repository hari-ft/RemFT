package com.ftapps.remft.data.bin

import com.ftapps.remft.data.db.entity.ChecklistItemEntity
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.db.entity.FolderEntity
import com.ftapps.remft.data.db.entity.NoteEntity
import com.ftapps.remft.data.db.entity.NoteLinkEntity
import com.ftapps.remft.data.db.entity.ReminderEntity
import com.ftapps.remft.data.db.entity.TaskEntity
import com.ftapps.remft.data.db.entity.TrashItemEntity
import com.ftapps.remft.domain.backup.bin.BinChecklistItem
import com.ftapps.remft.domain.backup.bin.BinFile
import com.ftapps.remft.domain.backup.bin.BinFolder
import com.ftapps.remft.domain.backup.bin.BinNote
import com.ftapps.remft.domain.backup.bin.BinNoteLink
import com.ftapps.remft.domain.backup.bin.BinPayload
import com.ftapps.remft.domain.backup.bin.BinReminder
import com.ftapps.remft.domain.backup.bin.BinTask
import com.ftapps.remft.domain.time.UtcText
import java.time.Instant

object BinMapping {
    private fun instant(text: String?): Instant? = UtcText.parse(text)

    private fun text(instant: Instant?): String? = instant?.let(UtcText::format)

    fun entry(
        id: String,
        workspaceId: String,
        kind: String,
        entityId: String,
        title: String,
        payload: BinPayload,
        deletedAt: Instant,
    ): TrashItemEntity = TrashItemEntity(
        id = id,
        workspaceId = workspaceId,
        kind = kind,
        entityId = entityId,
        title = title,
        payload = payload.encode(),
        localPaths = payload.localPathsJson(),
        sizeBytes = payload.sizeBytes,
        deletedAt = UtcText.format(deletedAt),
    )

    fun TaskEntity.toBin(): BinTask = BinTask(
        id = id, workspaceId = workspaceId, title = title, description = description, status = status, priority = priority,
        dueAt = instant(dueAt), remindAt = instant(remindAt), snoozedUntil = instant(snoozedUntil),
        alarmsMutedAt = instant(alarmsMutedAt), timezone = timezone, isUrgent = isUrgent,
        requiresAcknowledgement = requiresAcknowledgement, seriesId = seriesId, occurrenceDate = occurrenceDate,
        spawnedFromTaskId = spawnedFromTaskId, sortOrder = sortOrder.toLong(), completedAt = instant(completedAt),
        titleFold = titleFold, descriptionFold = descriptionFold, createdAt = instant(createdAt), updatedAt = instant(updatedAt),
    )

    fun BinTask.toEntity(): TaskEntity = TaskEntity(
        id = id, workspaceId = workspaceId, title = title, description = description, status = status, priority = priority,
        dueAt = text(dueAt), remindAt = text(remindAt), snoozedUntil = text(snoozedUntil), alarmsMutedAt = text(alarmsMutedAt),
        timezone = timezone, isUrgent = isUrgent, requiresAcknowledgement = requiresAcknowledgement, seriesId = seriesId,
        occurrenceDate = occurrenceDate, spawnedFromTaskId = spawnedFromTaskId, sortOrder = sortOrder.toInt(),
        completedAt = text(completedAt), titleFold = titleFold, descriptionFold = descriptionFold,
        createdAt = text(createdAt), updatedAt = text(updatedAt),
    )

    fun ChecklistItemEntity.toBin(): BinChecklistItem = BinChecklistItem(
        id = id, taskId = taskId, title = title, isDone = isDone, sortOrder = sortOrder.toLong(),
        createdAt = instant(createdAt), updatedAt = instant(updatedAt),
    )

    fun BinChecklistItem.toEntity(): ChecklistItemEntity = ChecklistItemEntity(
        id = id, taskId = taskId, title = title, isDone = isDone, sortOrder = sortOrder.toInt(),
        createdAt = text(createdAt), updatedAt = text(updatedAt),
    )

    fun ReminderEntity.toBin(): BinReminder = BinReminder(
        id = id, taskId = taskId, kind = kind, snoozeCount = snoozeCount.toLong(), remindAt = instant(remindAt) ?: Instant.EPOCH,
        alarmLevel = alarmLevel, requiresAcknowledgement = requiresAcknowledgement, status = status,
        snoozedUntil = instant(snoozedUntil), acknowledgedAt = instant(acknowledgedAt),
        createdAt = instant(createdAt), updatedAt = instant(updatedAt),
    )

    fun BinReminder.toEntity(): ReminderEntity = ReminderEntity(
        id = id, taskId = taskId, kind = kind, snoozeCount = snoozeCount.toInt(), remindAt = UtcText.format(remindAt),
        alarmLevel = alarmLevel, requiresAcknowledgement = requiresAcknowledgement, status = status,
        snoozedUntil = text(snoozedUntil), acknowledgedAt = text(acknowledgedAt),
        createdAt = text(createdAt), updatedAt = text(updatedAt),
    )

    fun NoteEntity.toBin(): BinNote = BinNote(
        id = id, workspaceId = workspaceId, title = title, contentMarkdown = contentMarkdown, plainText = plainText,
        isPinned = isPinned, isArchived = isArchived, titleFold = titleFold, bodyFold = bodyFold,
        createdAt = instant(createdAt), updatedAt = instant(updatedAt),
    )

    fun BinNote.toEntity(): NoteEntity = NoteEntity(
        id = id, workspaceId = workspaceId, title = title, contentMarkdown = contentMarkdown, plainText = plainText,
        isPinned = isPinned, isArchived = isArchived, titleFold = titleFold, bodyFold = bodyFold,
        createdAt = text(createdAt), updatedAt = text(updatedAt),
    )

    fun NoteLinkEntity.toBin(): BinNoteLink = BinNoteLink(id, noteId, entityType, entityId)

    fun BinNoteLink.toEntity(): NoteLinkEntity = NoteLinkEntity(id, noteId, entityType, entityId)

    fun FileRowEntity.toBin(): BinFile = BinFile(
        id = id, workspaceId = workspaceId, targetType = targetType, targetId = targetId, name = name, mime = mime,
        sizeBytes = sizeBytes, folderId = folderId, nameFold = nameFold, localPath = localPath,
        createdAt = instant(createdAt), updatedAt = instant(updatedAt),
    )

    fun BinFile.toEntity(): FileRowEntity = FileRowEntity(
        id = id, workspaceId = workspaceId, targetType = targetType, targetId = targetId, name = name, mime = mime,
        sizeBytes = sizeBytes, folderId = folderId, nameFold = nameFold, localPath = localPath,
        createdAt = text(createdAt), updatedAt = text(updatedAt),
    )

    fun FolderEntity.toBin(): BinFolder = BinFolder(
        id = id, workspaceId = workspaceId, parentId = parentId, name = name,
        createdAt = instant(createdAt), updatedAt = instant(updatedAt),
    )

    fun BinFolder.toEntity(): FolderEntity = FolderEntity(
        id = id, workspaceId = workspaceId, parentId = parentId, name = name,
        createdAt = text(createdAt), updatedAt = text(updatedAt),
    )
}
