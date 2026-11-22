package com.ftapps.remft.domain.repeat

import java.time.Instant

internal class SeriesWorking(state: SeriesState, private val now: Instant) {
    private val originalSeries = state.series.associateBy { it.id }
    private val originalTasks = state.tasks.associateBy { it.id }
    private val originalSkips = state.skips.associateBy { it.key }

    val series = LinkedHashMap(originalSeries)
    val tasks = LinkedHashMap(originalTasks)
    val skips = LinkedHashMap(originalSkips)
    private val retired = LinkedHashMap<String, RetiredTask>()

    fun rowsOf(seriesId: String): List<OccurrenceRow> = tasks.values.filter { it.seriesId == seriesId }

    fun skipsOf(seriesId: String): Set<String> = skips.values.filter { it.seriesId == seriesId }.map { it.occurrenceDate }.toSet()

    fun insertTask(row: OccurrenceRow) {
        tasks[row.id] = row
    }

    fun writeRow(id: String, touch: Boolean = true, change: (OccurrenceRow) -> OccurrenceRow) {
        val current = tasks[id] ?: return
        val next = change(current)
        tasks[id] = if (touch) next.copy(updatedAt = now) else next
    }

    fun retire(row: OccurrenceRow, toRecycleBin: Boolean) {
        val current = tasks.remove(row.id) ?: return
        retired[row.id] = RetiredTask(current, toRecycleBin)
    }

    fun putSeries(record: SeriesRecord) {
        series[record.id] = record
    }

    fun deleteSeries(id: String) {
        series.remove(id)
    }

    fun insertSkipIfAbsent(seriesId: String, day: String) {
        val key = seriesId to day
        if (!skips.containsKey(key)) skips[key] = SeriesSkip(seriesId, day, now)
    }

    fun deleteSkip(seriesId: String, day: String) {
        skips.remove(seriesId to day)
    }

    fun changes(): SeriesChangeSet = SeriesChangeSet(
        now = now,
        insertSeries = series.values.filter { it.id !in originalSeries },
        updateSeries = series.values.filter { record -> originalSeries[record.id]?.let { it != record } == true },
        deleteSeriesIds = originalSeries.keys.filter { it !in series },
        insertTasks = tasks.values.filter { it.id !in originalTasks },
        updateTasks = tasks.values.mapNotNull { row ->
            originalTasks[row.id]?.takeIf { it != row }?.let { TaskUpdate(it, row) }
        },
        retireTasks = retired.values.filter { it.row.id in originalTasks },
        insertSkips = skips.values.filter { it.key !in originalSkips },
        deleteSkips = originalSkips.values.filter { it.key !in skips },
    )
}
