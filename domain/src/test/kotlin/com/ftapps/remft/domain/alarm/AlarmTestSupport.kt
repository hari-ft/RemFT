package com.ftapps.remft.domain.alarm

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

object EnglishAlarmTexts {
    val texts = AlarmTexts(
        privacyTitle = "RemFT",
        privateBody = "You have a reminder",
        urgentFirst = "Urgent reminder — waiting for acknowledgement",
        urgentRepeat = "Still waiting for acknowledgement (alert {count})",
        dueNow = "The time you set is here — waiting for you",
        afterSnooze = "Snoozed round {count} — still waiting for acknowledgement",
        reminder = "Reminder",
        testTitle = "⏰ Test alarm",
        testBody = "If you can hear this, urgent alarms work on this device.",
    )

    val planner = AlarmPlanner(texts)
}

fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Instant =
    ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneOffset.UTC).toInstant()

fun pad26(prefix: String): String = prefix.padEnd(26, '0')

val Instant.minuteOfHour: Int
    get() = atZone(ZoneOffset.UTC).minute

val Instant.hourOfDay: Int
    get() = atZone(ZoneOffset.UTC).hour

class IdSequence(private val prefix: String = "NEW") {
    private var next = 0

    fun next(): String {
        next++
        return "$prefix$next".padEnd(26, '0')
    }
}

class AlarmWorld(tasks: List<AlarmTask> = emptyList(), rows: List<ReminderRow> = emptyList()) {
    val tasks: MutableMap<String, AlarmTask> = tasks.associateByTo(LinkedHashMap()) { it.id }
    val rows: MutableList<ReminderRow> = rows.toMutableList()
    val ids = IdSequence()

    fun task(id: String): AlarmTask = tasks.getValue(id)

    fun rowsOf(taskId: String): List<ReminderRow> = rows.filter { it.taskId == taskId }

    fun alarms(): List<AlarmInput> = AlarmSelection.alarmsFor(tasks.values.toList(), rows)

    fun alarmsOfTask(taskId: String): List<AlarmInput> = AlarmSelection.alarmsOfTask(tasks[taskId], rowsOf(taskId))

    fun updateTask(id: String, change: (AlarmTask) -> AlarmTask) {
        tasks[id] = change(task(id))
    }

    fun acknowledge(reminderId: String, at: Instant? = null, now: Instant): Boolean {
        val synthetic = AlarmSelection.parseSyntheticReminderId(reminderId)
        val taskId = synthetic?.taskId ?: rows.firstOrNull { it.id == reminderId }?.taskId
        val write = AlarmAnswers.acknowledge(
            reminderId = reminderId,
            at = at,
            task = taskId?.let { tasks[it] },
            rows = if (taskId == null) emptyList() else rowsOf(taskId),
            now = now,
            newId = ids::next,
        )
        apply(write)
        return write.applied
    }

    fun snooze(
        taskId: String,
        until: Instant,
        now: Instant,
        reminderId: String? = null,
        at: Instant? = null,
    ): Boolean {
        val write = AlarmAnswers.snooze(
            taskId = taskId,
            task = tasks[taskId],
            rows = rowsOf(taskId),
            until = until,
            now = now,
            newId = ids::next,
            reminderId = reminderId,
            at = at,
        )
        apply(write)
        return write.applied
    }

    fun dropStaleSnoozes(taskId: String) {
        val stale = AlarmAnswers.staleSnoozeRowIds(task(taskId), rowsOf(taskId)).toSet()
        rows.removeAll { it.id in stale }
    }

    fun apply(write: AlarmWrite) {
        for (op in write.reminderOps) {
            when (op) {
                is ReminderOp.Insert -> rows.add(op.row)
                is ReminderOp.Update -> {
                    val index = rows.indexOfFirst { it.id == op.row.id }
                    if (index >= 0) rows[index] = op.row
                }
                is ReminderOp.Delete -> rows.removeAll { it.id == op.id }
            }
        }
        for (change in write.taskChanges) {
            val task = tasks[change.taskId] ?: continue
            tasks[change.taskId] = when (change) {
                is TaskAlarmChange.SetSnoozedUntil -> task.copy(snoozedUntil = change.until)
                is TaskAlarmChange.Complete -> task.copy(status = AlarmTaskStatus.COMPLETED)
                is TaskAlarmChange.MuteAlarms -> task.copy(alarmsMutedAt = change.mutedAt)
                is TaskAlarmChange.UnmuteAlarms -> task.copy(alarmsMutedAt = null)
            }
        }
    }
}

fun alarmTask(
    id: String,
    title: String = "Task",
    status: String = "open",
    isUrgent: Boolean = false,
    requiresAcknowledgement: Boolean = false,
    dueAt: Instant? = null,
    remindAt: Instant? = null,
    snoozedUntil: Instant? = null,
    alarmsMutedAt: Instant? = null,
): AlarmTask = AlarmTask(
    id = id,
    title = title,
    status = status,
    isUrgent = isUrgent,
    requiresAcknowledgement = requiresAcknowledgement,
    dueAt = dueAt,
    remindAt = remindAt,
    snoozedUntil = snoozedUntil,
    alarmsMutedAt = alarmsMutedAt,
)
