package com.ftapps.remft.data.alarm

import androidx.room.withTransaction
import com.ftapps.remft.data.db.RemftDatabase
import com.ftapps.remft.data.tasks.TasksRepository
import com.ftapps.remft.domain.Ulid
import com.ftapps.remft.domain.alarm.AlarmAnswers
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.AlarmSelection
import com.ftapps.remft.domain.alarm.AlarmWrite
import com.ftapps.remft.domain.alarm.ReminderOp
import com.ftapps.remft.domain.alarm.TaskAlarmChange
import com.ftapps.remft.domain.tasks.Change
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.domain.tasks.TaskStatus
import java.time.Clock
import java.time.Instant

class AlarmStore(
    private val database: () -> RemftDatabase,
    private val tasks: TasksRepository,
    private val clock: Clock,
) {
    private val dao: AlarmDao get() = database().alarmDao()

    private fun newId(): () -> String = { Ulid.next(clock.millis()) }

    suspend fun feed(workspaceId: String): List<AlarmInput> {
        val wanting = dao.wantingTasks(workspaceId).map(AlarmMapping::task)
        val rows = dao.allReminders().mapNotNull(AlarmMapping::row)
        return AlarmSelection.alarmsFor(wanting, rows)
    }

    suspend fun alarmsOfTask(taskId: String): List<AlarmInput> {
        val task = dao.task(taskId)?.let(AlarmMapping::task) ?: return emptyList()
        val rows = dao.reminders(taskId).mapNotNull(AlarmMapping::row)
        return AlarmSelection.alarmsOfTask(task, rows)
    }

    suspend fun acknowledge(reminderId: String, at: Instant?): AlarmWrite = database().withTransaction {
        val synthetic = AlarmSelection.parseSyntheticReminderId(reminderId)
        val taskId = synthetic?.taskId ?: dao.reminder(reminderId)?.taskId
        val task = taskId?.let { dao.task(it) }?.let(AlarmMapping::task)
        val rows = taskId?.let { dao.reminders(it) }.orEmpty().mapNotNull(AlarmMapping::row)
        val write = AlarmAnswers.acknowledge(reminderId, at, task, rows, clock.instant(), newId())
        applyInside(write)
        write
    }

    suspend fun snooze(taskId: String, until: Instant, reminderId: String?, at: Instant?): AlarmWrite =
        database().withTransaction {
            val task = dao.task(taskId)?.let(AlarmMapping::task)
            val rows = dao.reminders(taskId).mapNotNull(AlarmMapping::row)
            val write = AlarmAnswers.snooze(taskId, task, rows, until, clock.instant(), newId(), reminderId, at)
            applyInside(write)
            write
        }

    suspend fun complete(taskId: String): AlarmWrite {
        val write = AlarmAnswers.complete(taskId)
        apply(write)
        return write
    }

    suspend fun mute(taskId: String): AlarmWrite {
        val write = AlarmAnswers.mute(taskId, clock.instant())
        apply(write)
        return write
    }

    suspend fun unmute(taskId: String): AlarmWrite {
        val write = AlarmAnswers.unmute(taskId)
        apply(write)
        return write
    }

    suspend fun apply(write: AlarmWrite) {
        if (write.isEmpty) return
        database().withTransaction { applyInside(write) }
    }

    private suspend fun applyInside(write: AlarmWrite) {
        for (op in write.reminderOps) {
            when (op) {
                is ReminderOp.Insert -> dao.insertReminder(AlarmMapping.entity(op.row))
                is ReminderOp.Update -> dao.insertReminder(AlarmMapping.entity(op.row))
                is ReminderOp.Delete -> dao.deleteReminder(op.id)
            }
        }
        for (change in write.taskChanges) {
            when (change) {
                is TaskAlarmChange.SetSnoozedUntil -> {
                    val current = dao.task(change.taskId) ?: continue
                    dao.updateTask(
                        current.copy(
                            snoozedUntil = AlarmMapping.text(change.until),
                            updatedAt = AlarmMapping.text(change.updatedAt) ?: current.updatedAt,
                        ),
                    )
                }
                is TaskAlarmChange.Complete -> tasks.update(change.taskId, TaskPatch(status = TaskStatus.Completed.db))
                is TaskAlarmChange.MuteAlarms -> tasks.update(change.taskId, TaskPatch(alarmsMutedAt = Change(change.mutedAt)))
                is TaskAlarmChange.UnmuteAlarms -> tasks.update(change.taskId, TaskPatch(alarmsMutedAt = Change(null)))
            }
        }
    }
}
