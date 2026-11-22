package com.ftapps.remft.domain.tasks

import java.time.Instant

data class Task(
    val id: String,
    val workspaceId: String,
    val title: String,
    val description: String? = null,
    val status: String = "open",
    val priority: String = "none",
    val dueAt: Instant? = null,
    val remindAt: Instant? = null,
    val snoozedUntil: Instant? = null,
    val alarmsMutedAt: Instant? = null,
    val completedAt: Instant? = null,
    val timezone: String = "Asia/Kolkata",
    val isUrgent: Boolean = false,
    val requiresAcknowledgement: Boolean = false,
    val seriesId: String? = null,
    val occurrenceDate: String? = null,
    val spawnedFromTaskId: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    val isCompleted: Boolean get() = status == TaskStatus.Completed.db

    val isRecurring: Boolean get() = seriesId != null
}

data class ChecklistItem(
    val id: String,
    val taskId: String,
    val title: String,
    val isDone: Boolean,
    val sortOrder: Int,
)
