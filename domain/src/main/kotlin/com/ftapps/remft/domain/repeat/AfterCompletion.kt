package com.ftapps.remft.domain.repeat

import java.time.Duration
import java.time.Instant

data class SpawnTiming(val day: String, val dueAt: Instant, val remindAt: Instant?)

object AfterCompletion {
    fun nextInstant(rule: RepeatRule, completedAt: Instant, timeOfDayFrom: Instant, zone: String, emitted: Int = 1): Instant? {
        val day = Recurrence.nextAfterCompletion(rule, WallClock.wallDay(completedAt, zone), emitted) ?: return null
        return WallClock.toInstant(zone, day, WallClock.wallTime(timeOfDayFrom, zone))
    }

    fun spawnTiming(
        rule: RepeatRule,
        completedAt: Instant,
        zone: String,
        anchorAt: Instant,
        taskDueAt: Instant?,
        taskRemindAt: Instant?,
        templateRemindMinutesBefore: Int?,
        emitted: Int,
    ): SpawnTiming? {
        val day = Recurrence.nextAfterCompletion(rule, WallClock.wallDay(completedAt, zone), emitted) ?: return null
        return if (taskDueAt != null) {
            val dueAt = WallClock.toInstant(zone, day, WallClock.wallTime(taskDueAt, zone))
            val remindAt = when {
                taskRemindAt != null -> dueAt.minus(Duration.between(taskRemindAt, taskDueAt))
                templateRemindMinutesBefore != null -> dueAt.minus(Duration.ofMinutes(templateRemindMinutesBefore.toLong()))
                else -> null
            }
            SpawnTiming(day, dueAt, remindAt)
        } else {
            val dueAt = WallClock.toInstant(zone, day, WallClock.wallTime(anchorAt, zone))
            SpawnTiming(day, dueAt, templateRemindMinutesBefore?.let { dueAt.minus(Duration.ofMinutes(it.toLong())) })
        }
    }
}

object ReminderFollow {
    val AutoReminderGap: Duration = Duration.ofHours(1)

    fun follows(oldDue: Instant, newDue: Instant, remind: Instant, sameDay: (Instant, Instant) -> Boolean): Boolean {
        if (newDue == oldDue) return false
        val derived = Duration.between(remind, oldDue) == AutoReminderGap
        val otherDay = !sameDay(oldDue, newDue)
        val stillBefore = remind < newDue
        return derived || otherDay || !stillBefore
    }
}
