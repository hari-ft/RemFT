package com.ftapps.remft.domain.tasks

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import com.ftapps.remft.domain.text.Whitespace

data class Change<out T>(val value: T)

data class TaskDraft(
    val title: String,
    val description: String? = null,
    val status: String? = null,
    val priority: String? = null,
    val dueAt: Instant? = null,
    val remindAt: Instant? = null,
    val isUrgent: Boolean = false,
    val requiresAcknowledgement: Boolean? = null,
    val sortOrder: Int = 0,
)

data class TaskPatch(
    val title: String? = null,
    val description: Change<String?>? = null,
    val status: String? = null,
    val priority: String? = null,
    val dueAt: Change<Instant?>? = null,
    val remindAt: Change<Instant?>? = null,
    val isUrgent: Boolean? = null,
    val requiresAcknowledgement: Boolean? = null,
    val alarmsMutedAt: Change<Instant?>? = null,
    val sortOrder: Int? = null,
) {
    val touchesAlarms: Boolean get() = dueAt != null || remindAt != null || isUrgent != null
}

data class AlarmInstant(val kind: String, val at: Instant)

data class SnoozedReminder(val id: String, val kind: String, val remindAt: Instant)

object TaskRules {
    const val KIND_REMIND = "remind"
    const val KIND_DUE = "due"

    fun create(id: String, workspaceId: String, draft: TaskDraft, now: Instant): Task {
        val requested = draft.status ?: TaskStatus.Open.db
        val status = if (requested == TaskStatus.Inbox.db && draft.dueAt != null) TaskStatus.Open.db else requested
        return Task(
            id = id,
            workspaceId = workspaceId,
            title = Whitespace.trim(draft.title),
            description = draft.description,
            status = status,
            priority = draft.priority ?: TaskPriority.None.db,
            dueAt = draft.dueAt,
            remindAt = draft.remindAt,
            isUrgent = draft.isUrgent,
            requiresAcknowledgement = draft.requiresAcknowledgement ?: draft.isUrgent,
            sortOrder = draft.sortOrder,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun followedReminder(current: Task, patch: TaskPatch, zone: ZoneId): Instant? {
        val dueChange = patch.dueAt ?: return null
        if (patch.remindAt != null) return null
        val newDue = dueChange.value ?: return null
        val oldDue = current.dueAt ?: return null
        val remind = current.remindAt ?: return null
        if (!TaskDefaults.reminderFollowsDue(oldDue, newDue, remind, zone)) return null
        return remind.plus(Duration.between(oldDue, newDue))
    }

    fun update(current: Task, patch: TaskPatch, now: Instant, zone: ZoneId): Task {
        val requiresAcknowledgement = patch.requiresAcknowledgement
            ?: if (patch.isUrgent == true) true else null
        var status = patch.status
        if (current.status == TaskStatus.Inbox.db && status == null && patch.dueAt?.value != null) {
            status = TaskStatus.Open.db
        }
        val remindChange = patch.remindAt ?: followedReminder(current, patch, zone)?.let { Change(it) }

        val completedAt = when {
            status == null -> current.completedAt
            status == TaskStatus.Completed.db && current.status != TaskStatus.Completed.db -> now
            status != TaskStatus.Completed.db && current.status == TaskStatus.Completed.db -> null
            else -> current.completedAt
        }

        var snoozedUntil = current.snoozedUntil
        if (snoozedUntil != null) {
            val remindMoved = remindChange != null && remindChange.value != current.remindAt
            val dueMoved = patch.dueAt != null && patch.dueAt.value != current.dueAt
            val reopened = status != null &&
                TaskStatus.isTerminalDb(current.status) &&
                !TaskStatus.isTerminalDb(status)
            if (remindMoved || dueMoved || reopened) snoozedUntil = null
        }

        return current.copy(
            title = patch.title?.let { Whitespace.trim(it) } ?: current.title,
            description = if (patch.description != null) patch.description.value else current.description,
            status = status ?: current.status,
            priority = patch.priority ?: current.priority,
            dueAt = if (patch.dueAt != null) patch.dueAt.value else current.dueAt,
            remindAt = if (remindChange != null) remindChange.value else current.remindAt,
            snoozedUntil = snoozedUntil,
            alarmsMutedAt = if (patch.alarmsMutedAt != null) patch.alarmsMutedAt.value else current.alarmsMutedAt,
            completedAt = completedAt,
            isUrgent = patch.isUrgent ?: current.isUrgent,
            requiresAcknowledgement = requiresAcknowledgement ?: current.requiresAcknowledgement,
            sortOrder = patch.sortOrder ?: current.sortOrder,
            updatedAt = now,
        )
    }

    fun alarmInstants(task: Task): List<AlarmInstant> {
        if (TaskStatus.isTerminalDb(task.status)) return emptyList()
        if (task.alarmsMutedAt != null) return emptyList()
        val instants = mutableListOf<AlarmInstant>()
        val remindAt = task.remindAt
        val dueAt = task.dueAt
        if (remindAt != null) instants.add(AlarmInstant(KIND_REMIND, remindAt))
        if (dueAt != null && (task.isUrgent || remindAt == null)) instants.add(AlarmInstant(KIND_DUE, dueAt))
        if (instants.size == 2 && instants[0].at == instants[1].at) return listOf(instants[0])
        return instants
    }

    private fun alarmKey(kind: String, at: Instant): String = "$kind@${at.toEpochMilli()}"

    fun staleSnoozedReminderIds(task: Task, snoozed: List<SnoozedReminder>): List<String> {
        if (TaskStatus.isTerminalDb(task.status) || task.alarmsMutedAt != null) return emptyList()
        val wanted = alarmInstants(task).map { alarmKey(it.kind, it.at) }.toSet()
        return snoozed.filter { alarmKey(it.kind, it.remindAt) !in wanted }.map { it.id }
    }
}
