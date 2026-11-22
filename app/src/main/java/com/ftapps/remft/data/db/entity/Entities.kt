package com.ftapps.remft.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tasks",
    indices = [Index(name = "idx_tasks_completed", value = ["workspace_id", "status", "completed_at"])],
)
data class TaskEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "status", defaultValue = "'open'") val status: String = "open",
    @ColumnInfo(name = "priority", defaultValue = "'none'") val priority: String = "none",
    @ColumnInfo(name = "due_at") val dueAt: String? = null,
    @ColumnInfo(name = "remind_at") val remindAt: String? = null,
    @ColumnInfo(name = "snoozed_until") val snoozedUntil: String? = null,
    @ColumnInfo(name = "alarms_muted_at") val alarmsMutedAt: String? = null,
    @ColumnInfo(name = "timezone", defaultValue = "'Asia/Kolkata'") val timezone: String = "Asia/Kolkata",
    @ColumnInfo(name = "is_urgent", defaultValue = "0") val isUrgent: Boolean = false,
    @ColumnInfo(name = "requires_acknowledgement", defaultValue = "0") val requiresAcknowledgement: Boolean = false,
    @ColumnInfo(name = "series_id") val seriesId: String? = null,
    @ColumnInfo(name = "occurrence_date") val occurrenceDate: String? = null,
    @ColumnInfo(name = "spawned_from_task_id") val spawnedFromTaskId: String? = null,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(name = "completed_at") val completedAt: String? = null,
    @ColumnInfo(name = "title_fold") val titleFold: String? = null,
    @ColumnInfo(name = "description_fold") val descriptionFold: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)

@Entity(tableName = "checklist_items")
data class ChecklistItemEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "is_done", defaultValue = "0") val isDone: Boolean = false,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "content_markdown") val contentMarkdown: String? = null,
    @ColumnInfo(name = "plain_text") val plainText: String? = null,
    @ColumnInfo(name = "is_pinned", defaultValue = "0") val isPinned: Boolean = false,
    @ColumnInfo(name = "is_archived", defaultValue = "0") val isArchived: Boolean = false,
    @ColumnInfo(name = "title_fold") val titleFold: String? = null,
    @ColumnInfo(name = "body_fold") val bodyFold: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)

@Entity(tableName = "note_link_rows")
data class NoteLinkEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "kind", defaultValue = "'remind'") val kind: String = "remind",
    @ColumnInfo(name = "snooze_count", defaultValue = "0") val snoozeCount: Int = 0,
    @ColumnInfo(name = "remind_at") val remindAt: String,
    @ColumnInfo(name = "alarm_level", defaultValue = "'normal'") val alarmLevel: String = "normal",
    @ColumnInfo(name = "requires_acknowledgement", defaultValue = "0") val requiresAcknowledgement: Boolean = false,
    @ColumnInfo(name = "status", defaultValue = "'scheduled'") val status: String = "scheduled",
    @ColumnInfo(name = "snoozed_until") val snoozedUntil: String? = null,
    @ColumnInfo(name = "acknowledged_at") val acknowledgedAt: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)

@Entity(tableName = "file_rows")
data class FileRowEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
    @ColumnInfo(name = "target_type") val targetType: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "mime") val mime: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    @ColumnInfo(name = "name_fold") val nameFold: String? = null,
    @ColumnInfo(name = "local_path") val localPath: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)

@Entity(tableName = "task_series")
data class TaskSeriesEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
    @ColumnInfo(name = "rule_json") val ruleJson: String,
    @ColumnInfo(name = "template_json") val templateJson: String,
    @ColumnInfo(name = "timezone", defaultValue = "'Asia/Kolkata'") val timezone: String = "Asia/Kolkata",
    @ColumnInfo(name = "anchor_at") val anchorAt: String,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)

@Entity(tableName = "series_skips", primaryKeys = ["series_id", "occurrence_date"])
data class SeriesSkipEntity(
    @ColumnInfo(name = "series_id") val seriesId: String,
    @ColumnInfo(name = "occurrence_date") val occurrenceDate: String,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
)

@Entity(tableName = "trash_items")
data class TrashItemEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "local_paths", defaultValue = "'[]'") val localPaths: String = "[]",
    @ColumnInfo(name = "size_bytes", defaultValue = "0") val sizeBytes: Long = 0,
    @ColumnInfo(name = "deleted_at") val deletedAt: String,
)

@Entity(tableName = "alarm_events")
data class AlarmEventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "at") val at: String,
    @ColumnInfo(name = "event") val event: String,
    @ColumnInfo(name = "lane") val lane: String,
    @ColumnInfo(name = "kind") val kind: String? = null,
    @ColumnInfo(name = "slot_index") val slotIndex: Int? = null,
    @ColumnInfo(name = "urgent", defaultValue = "0") val urgent: Boolean = false,
    @ColumnInfo(name = "sound") val sound: String? = null,
    @ColumnInfo(name = "level") val level: String? = null,
    @ColumnInfo(name = "fire_at") val fireAt: String? = null,
    @ColumnInfo(name = "task_id") val taskId: String? = null,
    @ColumnInfo(name = "reminder_id") val reminderId: String? = null,
    @ColumnInfo(name = "detail") val detail: String? = null,
)

@Entity(tableName = "file_mirrors")
data class FileMirrorEntity(
    @PrimaryKey @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "quick_xor_hash") val quickXorHash: String? = null,
    @ColumnInfo(name = "state", defaultValue = "'uploading'") val state: String = "uploading",
    @ColumnInfo(name = "remote_path") val remotePath: String? = null,
    @ColumnInfo(name = "item_id") val itemId: String? = null,
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "mirrored_at") val mirroredAt: String? = null,
    @ColumnInfo(name = "listed_at") val listedAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
