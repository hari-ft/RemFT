package com.ftapps.remft.domain.alarm

import java.time.Instant

object AlarmKind {
    const val REMIND = "remind"
    const val DUE = "due"
}

object ReminderStatus {
    const val SCHEDULED = "scheduled"
    const val SNOOZED = "snoozed"
    const val DELIVERED = "delivered"
    const val ACKNOWLEDGED = "acknowledged"
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"

    val active: Set<String> = setOf(SCHEDULED, SNOOZED, DELIVERED)

    fun isActive(status: String): Boolean = status in active
}

object AlarmLevel {
    const val NORMAL = "normal"
    const val URGENT = "urgent"

    fun of(urgent: Boolean): String = if (urgent) URGENT else NORMAL
}

object AlarmTaskStatus {
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"
    const val ARCHIVED = "archived"

    val silenced: Set<String> = setOf(COMPLETED, CANCELLED, ARCHIVED)
}

data class AlarmTask(
    val id: String,
    val title: String,
    val status: String,
    val isUrgent: Boolean,
    val requiresAcknowledgement: Boolean,
    val dueAt: Instant?,
    val remindAt: Instant?,
    val snoozedUntil: Instant? = null,
    val alarmsMutedAt: Instant? = null,
) {
    val isLive: Boolean
        get() = status !in AlarmTaskStatus.silenced && alarmsMutedAt == null
}

data class ReminderRow(
    val id: String,
    val taskId: String,
    val kind: String,
    val status: String,
    val remindAt: Instant,
    val snoozedUntil: Instant? = null,
    val snoozeCount: Int = 0,
    val requiresAcknowledgement: Boolean = false,
    val acknowledgedAt: Instant? = null,
    val alarmLevel: String = AlarmLevel.NORMAL,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

data class AlarmInput(
    val reminderId: String,
    val taskId: String,
    val taskTitle: String,
    val remindAt: Instant,
    val status: String,
    val urgent: Boolean,
    val requiresAcknowledgement: Boolean,
    val kind: String = AlarmKind.REMIND,
    val snoozeRound: Int = 0,
    val snoozedUntil: Instant? = null,
) {
    val fireAt: Instant
        get() = (if (status == ReminderStatus.SNOOZED) snoozedUntil else null) ?: remindAt
}

data class AlarmInstant(val kind: String, val at: Instant)

data class SyntheticReminderId(val kind: String, val taskId: String)
