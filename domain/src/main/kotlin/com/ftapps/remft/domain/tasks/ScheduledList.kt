package com.ftapps.remft.domain.tasks

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface ScheduledBucket {
    data object Overdue : ScheduledBucket
    data class Day(val date: LocalDate) : ScheduledBucket
}

data class ScheduledEntry(val task: Task, val at: Instant, val snoozed: Boolean)

data class ScheduledSection(val bucket: ScheduledBucket, val entries: List<ScheduledEntry>)

object ScheduledList {
    fun baseTime(task: Task): Instant? = task.dueAt ?: task.remindAt

    fun scheduledAt(task: Task): Instant? {
        val base = baseTime(task) ?: return null
        val snoozed = task.snoozedUntil
        return if (snoozed != null && snoozed.isAfter(base)) snoozed else base
    }

    fun isScheduled(task: Task): Boolean =
        TaskStatus.parse(task.status)?.isPlanning == true && baseTime(task) != null

    fun entries(tasks: List<Task>): List<ScheduledEntry> =
        tasks.mapNotNull { task ->
            if (!isScheduled(task)) return@mapNotNull null
            val at = scheduledAt(task) ?: return@mapNotNull null
            ScheduledEntry(task, at, at != baseTime(task))
        }.sortedWith(compareBy<ScheduledEntry> { it.at }.thenBy { it.task.id })

    fun bucketOf(at: Instant, now: Instant, zone: ZoneId): ScheduledBucket =
        if (at.isAfter(now)) ScheduledBucket.Day(at.atZone(zone).toLocalDate()) else ScheduledBucket.Overdue

    fun sections(tasks: List<Task>, now: Instant, zone: ZoneId): List<ScheduledSection> {
        val out = mutableListOf<ScheduledSection>()
        var bucket: ScheduledBucket? = null
        var current = mutableListOf<ScheduledEntry>()
        for (entry in entries(tasks)) {
            val next = bucketOf(entry.at, now, zone)
            if (next != bucket) {
                bucket?.let { out += ScheduledSection(it, current) }
                bucket = next
                current = mutableListOf()
            }
            current += entry
        }
        bucket?.let { out += ScheduledSection(it, current) }
        return out
    }
}
