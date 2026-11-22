package com.ftapps.remft.data.alarm

import com.ftapps.remft.data.db.entity.ReminderEntity
import com.ftapps.remft.data.db.entity.TaskEntity
import com.ftapps.remft.domain.alarm.AlarmTask
import com.ftapps.remft.domain.alarm.ReminderRow
import com.ftapps.remft.domain.time.UtcText
import java.time.Instant

object AlarmMapping {
    fun task(entity: TaskEntity): AlarmTask = AlarmTask(
        id = entity.id,
        title = entity.title,
        status = entity.status,
        isUrgent = entity.isUrgent,
        requiresAcknowledgement = entity.requiresAcknowledgement,
        dueAt = UtcText.parse(entity.dueAt),
        remindAt = UtcText.parse(entity.remindAt),
        snoozedUntil = UtcText.parse(entity.snoozedUntil),
        alarmsMutedAt = UtcText.parse(entity.alarmsMutedAt),
    )

    fun row(entity: ReminderEntity): ReminderRow? {
        val remindAt = UtcText.parse(entity.remindAt) ?: return null
        return ReminderRow(
            id = entity.id,
            taskId = entity.taskId,
            kind = entity.kind,
            status = entity.status,
            remindAt = remindAt,
            snoozedUntil = UtcText.parse(entity.snoozedUntil),
            snoozeCount = entity.snoozeCount,
            requiresAcknowledgement = entity.requiresAcknowledgement,
            acknowledgedAt = UtcText.parse(entity.acknowledgedAt),
            alarmLevel = entity.alarmLevel,
            createdAt = UtcText.parse(entity.createdAt),
            updatedAt = UtcText.parse(entity.updatedAt),
        )
    }

    fun entity(row: ReminderRow): ReminderEntity = ReminderEntity(
        id = row.id,
        taskId = row.taskId,
        kind = row.kind,
        snoozeCount = row.snoozeCount,
        remindAt = UtcText.format(row.remindAt),
        alarmLevel = row.alarmLevel,
        requiresAcknowledgement = row.requiresAcknowledgement,
        status = row.status,
        snoozedUntil = row.snoozedUntil?.let(UtcText::format),
        acknowledgedAt = row.acknowledgedAt?.let(UtcText::format),
        createdAt = row.createdAt?.let(UtcText::format),
        updatedAt = row.updatedAt?.let(UtcText::format),
    )

    fun text(instant: Instant?): String? = instant?.let(UtcText::format)
}
