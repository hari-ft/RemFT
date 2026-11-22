package com.ftapps.remft.domain.tasks

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

enum class HomeBucket { SelectedDay, Overdue, NoDate, Today, Tomorrow, ThisWeek, Next30Days }

data class HomeGroup(
    val bucket: HomeBucket,
    val tasks: List<Task>,
    val dimmed: Boolean,
    val day: LocalDate? = null,
)

object HomeGrouping {
    const val HORIZON_DAYS = 30

    fun localDay(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

    fun isOverdueOn(task: Task, today: LocalDate, zone: ZoneId): Boolean {
        val due = task.dueAt ?: return false
        return localDay(due, zone).isBefore(today)
    }

    fun planDay(task: Task, zone: ZoneId): LocalDate? = task.dueAt?.let { localDay(it, zone) }

    fun order(sort: SortState?): Comparator<Task> {
        val chronologically = Comparator<Task> { a, b ->
            val ta = a.dueAt
            val tb = b.dueAt
            when {
                ta == null && tb == null -> a.sortOrder.compareTo(b.sortOrder)
                ta == null -> 1
                tb == null -> -1
                else -> ta.compareTo(tb)
            }
        }
        val byPriority = Comparator<Task> { a, b ->
            val byRank = TaskSort.priorityRank(a.priority).compareTo(TaskSort.priorityRank(b.priority))
            if (byRank != 0) byRank else chronologically.compare(a, b)
        }
        val byTitle = Comparator<Task> { a, b ->
            val byKey = TaskSort.titleKey(a.title).compareTo(TaskSort.titleKey(b.title))
            if (byKey != 0) byKey else chronologically.compare(a, b)
        }
        val chosen = when {
            sort == null -> chronologically
            sort.id == "priority" -> sort.comparator(byPriority)
            sort.id == "title" -> sort.comparator(byTitle)
            sort.id == "date" -> sort.comparator(chronologically)
            else -> chronologically
        }
        return Comparator { a, b ->
            val da = a.isCompleted
            val db = b.isCompleted
            if (da != db) {
                if (da) 1 else -1
            } else {
                chosen.compare(a, b)
            }
        }
    }

    fun group(
        tasks: List<Task>,
        now: LocalDateTime,
        zone: ZoneId,
        selectedDay: LocalDate? = null,
        sort: SortState? = null,
    ): List<HomeGroup> {
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val weekEnd = today.plusDays(7)
        val horizon = today.plusDays(HORIZON_DAYS.toLong())

        val byBucket = HomeBucket.entries.associateWith { mutableListOf<Task>() }

        fun futureBucketForDay(day: LocalDate): HomeBucket? = when {
            day == today -> HomeBucket.Today
            day == tomorrow -> HomeBucket.Tomorrow
            day.isBefore(weekEnd) -> HomeBucket.ThisWeek
            !day.isAfter(horizon) -> HomeBucket.Next30Days
            else -> null
        }

        for (task in tasks) {
            val due = task.dueAt
            val overdue = isOverdueOn(task, today, zone)
            val plan = if (overdue) null else planDay(task, zone)
            if (selectedDay != null && due != null && localDay(due, zone) == selectedDay) {
                byBucket.getValue(HomeBucket.SelectedDay).add(task)
                continue
            }
            if (overdue) {
                byBucket.getValue(HomeBucket.Overdue).add(task)
                continue
            }
            if (plan == null) {
                byBucket.getValue(HomeBucket.NoDate).add(task)
                continue
            }
            futureBucketForDay(plan)?.let { byBucket.getValue(it).add(task) }
        }

        val ordered = order(sort)
        val sequence = buildList {
            if (selectedDay != null) add(HomeBucket.SelectedDay)
            add(HomeBucket.Overdue)
            add(HomeBucket.NoDate)
            add(HomeBucket.Today)
            add(HomeBucket.Tomorrow)
            add(HomeBucket.ThisWeek)
            add(HomeBucket.Next30Days)
        }

        fun dimmedFor(bucket: HomeBucket): Boolean =
            selectedDay != null &&
                bucket != HomeBucket.SelectedDay &&
                bucket != HomeBucket.NoDate &&
                bucket != HomeBucket.Overdue &&
                bucket != HomeBucket.Today

        val groups = mutableListOf<HomeGroup>()
        for (bucket in sequence) {
            val items = byBucket.getValue(bucket)
            if (items.isEmpty()) continue
            val sorted = items.sortedWith(ordered)
            if (bucket != HomeBucket.ThisWeek) {
                groups.add(HomeGroup(bucket, sorted, dimmedFor(bucket)))
                continue
            }
            val byDay = linkedMapOf<LocalDate, MutableList<Task>>()
            for (task in sorted) {
                byDay.getOrPut(planDay(task, zone)!!) { mutableListOf() }.add(task)
            }
            for (day in byDay.keys.sorted()) {
                groups.add(HomeGroup(bucket, byDay.getValue(day), dimmedFor(bucket), day))
            }
        }
        return groups
    }

    fun dueByTodayCount(tasks: List<Task>, today: LocalDate, zone: ZoneId): Int =
        tasks.count { task ->
            val due = task.dueAt
            !task.isCompleted && due != null && !localDay(due, zone).isAfter(today)
        }

    fun daysWithTasks(tasks: List<Task>, zone: ZoneId): Set<LocalDate> =
        tasks.mapNotNullTo(linkedSetOf()) { task ->
            val due = task.dueAt
            if (!task.isCompleted && due != null) localDay(due, zone) else null
        }
}
