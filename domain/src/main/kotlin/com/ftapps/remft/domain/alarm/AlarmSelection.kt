package com.ftapps.remft.domain.alarm

import java.time.Instant

object AlarmSelection {
    const val SYNTHETIC_PREFIX = "task:"

    fun syntheticReminderId(kind: String, taskId: String): String = "$SYNTHETIC_PREFIX$kind:$taskId"

    fun parseSyntheticReminderId(id: String): SyntheticReminderId? {
        if (!id.startsWith(SYNTHETIC_PREFIX)) return null
        val rest = id.substring(SYNTHETIC_PREFIX.length)
        val split = rest.indexOf(':')
        if (split <= 0 || split == rest.length - 1) return null
        return SyntheticReminderId(rest.substring(0, split), rest.substring(split + 1))
    }

    fun isSynthetic(id: String): Boolean = id.startsWith(SYNTHETIC_PREFIX)

    fun taskAlarmInstants(task: AlarmTask): List<AlarmInstant> {
        if (task.status in AlarmTaskStatus.silenced) return emptyList()
        if (task.alarmsMutedAt != null) return emptyList()
        val instants = ArrayList<AlarmInstant>(2)
        val remindAt = task.remindAt
        val dueAt = task.dueAt
        if (remindAt != null) instants.add(AlarmInstant(AlarmKind.REMIND, remindAt))
        if (dueAt != null && (task.isUrgent || remindAt == null)) instants.add(AlarmInstant(AlarmKind.DUE, dueAt))
        if (instants.size == 2 && instants[0].at == instants[1].at) return listOf(instants[0])
        return instants
    }

    fun instantOf(task: AlarmTask, kind: String): AlarmInstant? = taskAlarmInstants(task).firstOrNull { it.kind == kind }

    fun answeredKey(taskId: String, kind: String, at: Instant): String = "$taskId|$kind@${at.toEpochMilli()}"

    fun kindKey(taskId: String, kind: String): String = "$taskId|$kind"

    fun coveredAlarmKeys(rows: Iterable<ReminderRow>): Set<String> = rows.mapTo(LinkedHashSet()) { row ->
        if (ReminderStatus.isActive(row.status) && row.status != ReminderStatus.SNOOZED) {
            kindKey(row.taskId, row.kind)
        } else {
            answeredKey(row.taskId, row.kind, row.remindAt)
        }
    }

    fun syntheticAlarmFor(task: AlarmTask, kind: String, at: Instant): AlarmInput = AlarmInput(
        reminderId = syntheticReminderId(kind, task.id),
        taskId = task.id,
        taskTitle = task.title,
        kind = kind,
        remindAt = at,
        status = ReminderStatus.SCHEDULED,
        urgent = task.isUrgent,
        requiresAcknowledgement = task.requiresAcknowledgement,
    )

    fun toAlarm(row: ReminderRow, task: AlarmTask): AlarmInput = AlarmInput(
        reminderId = row.id,
        taskId = task.id,
        taskTitle = task.title,
        kind = row.kind,
        snoozeRound = row.snoozeCount,
        remindAt = row.remindAt,
        status = row.status,
        urgent = task.isUrgent,
        requiresAcknowledgement = task.requiresAcknowledgement,
        snoozedUntil = row.snoozedUntil,
    )

    fun mergeAlarms(fromRows: List<AlarmInput>, wanting: List<AlarmTask>, ownedKinds: Set<String>): List<AlarmInput> {
        val wanted = HashSet<String>()
        for (task in wanting) {
            for (instant in taskAlarmInstants(task)) wanted.add(answeredKey(task.id, instant.kind, instant.at))
        }
        val merged = ArrayList<AlarmInput>()
        for (row in fromRows) {
            if (row.status != ReminderStatus.SNOOZED || answeredKey(row.taskId, row.kind, row.remindAt) in wanted) {
                merged.add(row)
            }
        }
        for (task in wanting) {
            for (instant in taskAlarmInstants(task)) {
                if (kindKey(task.id, instant.kind) in ownedKinds) continue
                if (answeredKey(task.id, instant.kind, instant.at) in ownedKinds) continue
                merged.add(syntheticAlarmFor(task, instant.kind, instant.at))
            }
        }
        return merged
    }

    fun alarmsFor(tasks: List<AlarmTask>, rows: List<ReminderRow>): List<AlarmInput> {
        val byId = tasks.associateBy { it.id }
        val fromRows = rows.mapNotNull { row ->
            if (!ReminderStatus.isActive(row.status)) return@mapNotNull null
            val task = byId[row.taskId] ?: return@mapNotNull null
            if (!task.isLive) return@mapNotNull null
            toAlarm(row, task)
        }
        val wanting = tasks.filter { it.isLive && (it.remindAt != null || it.dueAt != null) }
        return mergeAlarms(fromRows, wanting, coveredAlarmKeys(rows))
    }

    fun alarmsOfTask(task: AlarmTask?, rowsOfTask: List<ReminderRow>): List<AlarmInput> {
        if (task == null) return emptyList()
        val rows = rowsOfTask.filter { it.taskId == task.id }
        val live = if (taskAlarmInstants(task).isEmpty()) emptyList() else rows.filter { ReminderStatus.isActive(it.status) }
        return mergeAlarms(live.map { toAlarm(it, task) }, listOf(task), coveredAlarmKeys(rows))
    }

    fun acknowledgesWholeTask(reminderId: String, kind: String? = null): Boolean =
        (parseSyntheticReminderId(reminderId)?.kind ?: kind) == AlarmKind.DUE

    fun snoozedUntilOf(alarms: List<AlarmInput>, taskId: String, now: Instant): Instant? {
        var soonest: Instant? = null
        for (alarm in alarms) {
            val until = alarm.snoozedUntil
            if (alarm.taskId != taskId || alarm.status != ReminderStatus.SNOOZED || until == null) continue
            if (!until.isAfter(now)) continue
            if (soonest == null || until.isBefore(soonest)) soonest = until
        }
        return soonest
    }
}
