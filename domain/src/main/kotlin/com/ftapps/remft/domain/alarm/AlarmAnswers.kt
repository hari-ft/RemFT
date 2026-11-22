package com.ftapps.remft.domain.alarm

import java.time.Instant

sealed interface ReminderOp {
    data class Update(val row: ReminderRow) : ReminderOp
    data class Insert(val row: ReminderRow) : ReminderOp
    data class Delete(val id: String) : ReminderOp
}

sealed interface TaskAlarmChange {
    val taskId: String

    data class SetSnoozedUntil(override val taskId: String, val until: Instant?, val updatedAt: Instant?) : TaskAlarmChange
    data class Complete(override val taskId: String) : TaskAlarmChange
    data class MuteAlarms(override val taskId: String, val mutedAt: Instant) : TaskAlarmChange
    data class UnmuteAlarms(override val taskId: String) : TaskAlarmChange
}

data class AlarmWrite(
    val applied: Boolean,
    val reminderOps: List<ReminderOp> = emptyList(),
    val taskChanges: List<TaskAlarmChange> = emptyList(),
    val answeredTaskId: String? = null,
    val answeredKind: String? = null,
) {
    val isEmpty: Boolean
        get() = reminderOps.isEmpty() && taskChanges.isEmpty()

    companion object {
        val NotApplied = AlarmWrite(applied = false)
    }
}

private class RowWork(initial: List<ReminderRow>) {
    val rows: MutableList<ReminderRow> = initial.toMutableList()
    val ops: MutableList<ReminderOp> = ArrayList()

    fun byId(id: String): ReminderRow? = rows.firstOrNull { it.id == id }

    fun update(row: ReminderRow) {
        val index = rows.indexOfFirst { it.id == row.id }
        if (index >= 0) rows[index] = row else rows.add(row)
        ops.add(ReminderOp.Update(row))
    }

    fun insert(row: ReminderRow) {
        rows.add(row)
        ops.add(ReminderOp.Insert(row))
    }

    fun delete(id: String) {
        rows.removeAll { it.id == id }
        ops.add(ReminderOp.Delete(id))
    }
}

object AlarmAnswers {

    fun acknowledge(
        reminderId: String,
        at: Instant?,
        task: AlarmTask?,
        rows: List<ReminderRow>,
        now: Instant,
        newId: () -> String,
    ): AlarmWrite {
        val work = RowWork(rows)
        val answered = acknowledgeOne(work, reminderId, at, task, now, newId) ?: return AlarmWrite.NotApplied
        if (answered.second == AlarmKind.DUE) {
            val owner = task?.takeIf { it.id == answered.first }
            if (owner != null) {
                for (instant in AlarmSelection.taskAlarmInstants(owner)) {
                    answerInstant(work, owner, instant.kind, instant.at, now, newId)
                }
            }
        }
        return AlarmWrite(
            applied = true,
            reminderOps = work.ops.toList(),
            answeredTaskId = answered.first,
            answeredKind = answered.second,
        )
    }

    fun snooze(
        taskId: String,
        task: AlarmTask?,
        rows: List<ReminderRow>,
        until: Instant,
        now: Instant,
        newId: () -> String,
        reminderId: String? = null,
        at: Instant? = null,
    ): AlarmWrite {
        if (task == null || task.id != taskId) return AlarmWrite.NotApplied
        if (task.status == AlarmTaskStatus.COMPLETED || task.status == AlarmTaskStatus.CANCELLED) return AlarmWrite.NotApplied
        val work = RowWork(rows)
        val synthetic = reminderId?.let { AlarmSelection.parseSyntheticReminderId(it) }
        var done = false
        var answeredKind: String? = null
        if (reminderId != null && synthetic == null) {
            val row = work.byId(reminderId) ?: return AlarmWrite.NotApplied
            if (row.taskId != taskId) return AlarmWrite.NotApplied
            if (!ReminderStatus.isActive(row.status)) return AlarmWrite.NotApplied
            if (at != null && row.remindAt != at) return AlarmWrite.NotApplied
            snoozeRow(work, task, row, until, now)
            done = true
            answeredKind = row.kind
        } else {
            val kinds = if (synthetic != null) {
                listOf(synthetic.kind)
            } else {
                AlarmSelection.taskAlarmInstants(task).map { it.kind }.distinct()
            }
            for (kind in kinds) {
                if (snoozeKind(work, task, kind, until, now, if (synthetic == null) null else at, newId)) {
                    done = true
                    answeredKind = answeredKind ?: kind
                }
            }
        }
        if (!done) return AlarmWrite.NotApplied
        return AlarmWrite(
            applied = true,
            reminderOps = work.ops.toList(),
            taskChanges = listOf(TaskAlarmChange.SetSnoozedUntil(taskId, until, now)),
            answeredTaskId = taskId,
            answeredKind = if (synthetic != null || reminderId != null) answeredKind else null,
        )
    }

    fun complete(taskId: String): AlarmWrite = AlarmWrite(
        applied = true,
        taskChanges = listOf(TaskAlarmChange.Complete(taskId)),
        answeredTaskId = taskId,
    )

    fun mute(taskId: String, now: Instant): AlarmWrite = AlarmWrite(
        applied = true,
        taskChanges = listOf(TaskAlarmChange.MuteAlarms(taskId, now)),
        answeredTaskId = taskId,
    )

    fun unmute(taskId: String): AlarmWrite = AlarmWrite(
        applied = true,
        taskChanges = listOf(TaskAlarmChange.UnmuteAlarms(taskId)),
        answeredTaskId = taskId,
    )

    fun staleSnoozeRowIds(task: AlarmTask, rows: List<ReminderRow>): List<String> {
        if (task.status in AlarmTaskStatus.silenced || task.alarmsMutedAt != null) return emptyList()
        val wanted = AlarmSelection.taskAlarmInstants(task).map { "${it.kind}@${it.at.toEpochMilli()}" }.toSet()
        return rows
            .filter { it.taskId == task.id && it.status == ReminderStatus.SNOOZED }
            .filter { "${it.kind}@${it.remindAt.toEpochMilli()}" !in wanted }
            .map { it.id }
    }

    private fun acknowledgeOne(
        work: RowWork,
        reminderId: String,
        at: Instant?,
        task: AlarmTask?,
        now: Instant,
        newId: () -> String,
    ): Pair<String, String>? {
        val synthetic = AlarmSelection.parseSyntheticReminderId(reminderId)
        if (synthetic != null) {
            if (task == null || task.id != synthetic.taskId) return null
            val instant = AlarmSelection.instantOf(task, synthetic.kind) ?: return null
            if (at != null && instant.at != at) return null
            answerInstant(work, task, synthetic.kind, instant.at, now, newId)
            return task.id to synthetic.kind
        }
        val row = work.byId(reminderId) ?: return null
        if (at != null && row.remindAt != at) return null
        if (row.status == ReminderStatus.ACKNOWLEDGED) return row.taskId to row.kind
        if (!ReminderStatus.isActive(row.status)) return null
        markAcknowledged(work, row, now)
        return row.taskId to row.kind
    }

    private fun answerInstant(
        work: RowWork,
        task: AlarmTask,
        kind: String,
        at: Instant,
        now: Instant,
        newId: () -> String,
    ) {
        val rows = work.rows
            .filter { it.taskId == task.id && it.kind == kind }
            .sortedWith(compareBy(nullsLast(reverseOrder())) { it.createdAt })
        val active = rows.firstOrNull {
            ReminderStatus.isActive(it.status) && (it.status != ReminderStatus.SNOOZED || it.remindAt == at)
        }
        if (active != null) {
            markAcknowledged(work, active, now)
            if (active.remindAt == at) return
        } else if (rows.any { !ReminderStatus.isActive(it.status) && it.remindAt == at }) {
            return
        }
        work.insert(
            ReminderRow(
                id = newId(),
                taskId = task.id,
                kind = kind,
                status = ReminderStatus.ACKNOWLEDGED,
                remindAt = at,
                requiresAcknowledgement = task.requiresAcknowledgement,
                acknowledgedAt = now,
                alarmLevel = AlarmLevel.of(task.isUrgent),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun markAcknowledged(work: RowWork, row: ReminderRow, now: Instant) {
        val current = work.byId(row.id) ?: row
        work.update(current.copy(status = ReminderStatus.ACKNOWLEDGED, acknowledgedAt = now, updatedAt = now))
    }

    private fun snoozeRow(work: RowWork, task: AlarmTask, row: ReminderRow, until: Instant, now: Instant) {
        val instant = AlarmSelection.instantOf(task, row.kind)
        val current = work.byId(row.id) ?: row
        work.update(
            current.copy(
                status = ReminderStatus.SNOOZED,
                remindAt = instant?.at ?: current.remindAt,
                snoozedUntil = until,
                snoozeCount = current.snoozeCount + 1,
                updatedAt = now,
            ),
        )
    }

    private fun snoozeKind(
        work: RowWork,
        task: AlarmTask,
        kind: String,
        until: Instant,
        now: Instant,
        at: Instant?,
        newId: () -> String,
    ): Boolean {
        val instant = AlarmSelection.instantOf(task, kind)
        if (at != null && (instant == null || instant.at != at)) return false
        val active = work.rows.filter { it.taskId == task.id && it.kind == kind && ReminderStatus.isActive(it.status) }
        var snoozed = false
        for (row in active) {
            val current = instant != null && row.remindAt == instant.at
            if (row.status == ReminderStatus.SNOOZED && !current) {
                work.delete(row.id)
                continue
            }
            snoozeRow(work, task, row, until, now)
            snoozed = true
        }
        if (snoozed) return true
        if (instant == null) return false
        work.insert(
            ReminderRow(
                id = newId(),
                taskId = task.id,
                kind = kind,
                status = ReminderStatus.SNOOZED,
                remindAt = instant.at,
                snoozedUntil = until,
                snoozeCount = 1,
                requiresAcknowledgement = task.requiresAcknowledgement,
                alarmLevel = AlarmLevel.of(task.isUrgent),
                createdAt = now,
                updatedAt = now,
            ),
        )
        return true
    }
}
