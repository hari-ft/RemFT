package com.ftapps.remft.domain.repeat

import java.time.Instant

data class SeriesRecord(
    val id: String,
    val workspaceId: String,
    val ruleJson: String,
    val templateJson: String,
    val timezone: String,
    val anchorAt: Instant,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    val rule: RepeatRule
        get() = RepeatRule.fromJsonString(ruleJson) ?: throw UnreadableRuleException(id)

    val template: SeriesTemplate get() = SeriesTemplate.fromJsonString(templateJson)

    val isCompletionSeries: Boolean get() = rule.isAfterCompletion

    val anchorDay: String get() = WallClock.wallDay(anchorAt, timezone)

    companion object {
        fun create(
            id: String,
            workspaceId: String,
            rule: RepeatRule,
            template: SeriesTemplate,
            timezone: String,
            anchorAt: Instant,
            now: Instant,
        ): SeriesRecord = SeriesRecord(
            id = id,
            workspaceId = workspaceId,
            ruleJson = rule.toJsonString(),
            templateJson = template.toJsonString(),
            timezone = timezone,
            anchorAt = anchorAt,
            createdAt = now,
            updatedAt = now,
        )
    }
}

data class OccurrenceRow(
    val id: String,
    val workspaceId: String,
    val title: String,
    val description: String? = null,
    val status: String = "open",
    val priority: String = "none",
    val dueAt: Instant? = null,
    val remindAt: Instant? = null,
    val snoozedUntil: Instant? = null,
    val timezone: String = "Asia/Kolkata",
    val isUrgent: Boolean = false,
    val requiresAcknowledgement: Boolean = false,
    val seriesId: String? = null,
    val occurrenceDate: String? = null,
    val spawnedFromTaskId: String? = null,
    val completedAt: Instant? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val hasAttachedContent: Boolean = false,
)

data class SeriesSkip(val seriesId: String, val occurrenceDate: String, val createdAt: Instant? = null) {
    val key: Pair<String, String> get() = seriesId to occurrenceDate
}

data class SeriesState(
    val series: List<SeriesRecord> = emptyList(),
    val tasks: List<OccurrenceRow> = emptyList(),
    val skips: List<SeriesSkip> = emptyList(),
) {
    fun seriesById(id: String): SeriesRecord? = series.firstOrNull { it.id == id }

    fun task(id: String): OccurrenceRow? = tasks.firstOrNull { it.id == id }

    fun rowsOf(seriesId: String): List<OccurrenceRow> = tasks.filter { it.seriesId == seriesId }

    fun skipsOf(seriesId: String): Set<String> = skips.filter { it.seriesId == seriesId }.map { it.occurrenceDate }.toSet()
}

enum class TaskField {
    Title, Description, Status, Priority, DueAt, RemindAt, SnoozedUntil, Timezone, IsUrgent,
    RequiresAcknowledgement, SeriesId, OccurrenceDate, SpawnedFromTaskId, CompletedAt,
}

data class TaskUpdate(val before: OccurrenceRow, val after: OccurrenceRow) {
    val id: String get() = after.id

    val changed: Set<TaskField>
        get() = buildSet {
            if (before.title != after.title) add(TaskField.Title)
            if (before.description != after.description) add(TaskField.Description)
            if (before.status != after.status) add(TaskField.Status)
            if (before.priority != after.priority) add(TaskField.Priority)
            if (before.dueAt != after.dueAt) add(TaskField.DueAt)
            if (before.remindAt != after.remindAt) add(TaskField.RemindAt)
            if (before.snoozedUntil != after.snoozedUntil) add(TaskField.SnoozedUntil)
            if (before.timezone != after.timezone) add(TaskField.Timezone)
            if (before.isUrgent != after.isUrgent) add(TaskField.IsUrgent)
            if (before.requiresAcknowledgement != after.requiresAcknowledgement) add(TaskField.RequiresAcknowledgement)
            if (before.seriesId != after.seriesId) add(TaskField.SeriesId)
            if (before.occurrenceDate != after.occurrenceDate) add(TaskField.OccurrenceDate)
            if (before.spawnedFromTaskId != after.spawnedFromTaskId) add(TaskField.SpawnedFromTaskId)
            if (before.completedAt != after.completedAt) add(TaskField.CompletedAt)
        }
}

data class RetiredTask(val row: OccurrenceRow, val toRecycleBin: Boolean)

data class SeriesChangeSet(
    val now: Instant,
    val insertSeries: List<SeriesRecord> = emptyList(),
    val updateSeries: List<SeriesRecord> = emptyList(),
    val deleteSeriesIds: List<String> = emptyList(),
    val insertTasks: List<OccurrenceRow> = emptyList(),
    val updateTasks: List<TaskUpdate> = emptyList(),
    val retireTasks: List<RetiredTask> = emptyList(),
    val insertSkips: List<SeriesSkip> = emptyList(),
    val deleteSkips: List<SeriesSkip> = emptyList(),
) {
    val isEmpty: Boolean
        get() = insertSeries.isEmpty() && updateSeries.isEmpty() && deleteSeriesIds.isEmpty() &&
            insertTasks.isEmpty() && updateTasks.isEmpty() && retireTasks.isEmpty() &&
            insertSkips.isEmpty() && deleteSkips.isEmpty()

    val binned: List<OccurrenceRow> get() = retireTasks.filter { it.toRecycleBin }.map { it.row }

    fun applyTo(state: SeriesState): SeriesState {
        val retired = retireTasks.map { it.row.id }.toSet()
        val updates = updateTasks.associateBy { it.id }
        val seriesUpdates = updateSeries.associateBy { it.id }
        val deletedSeries = deleteSeriesIds.toSet()
        val droppedSkips = deleteSkips.map { it.key }.toSet()
        val skips = state.skips.filter { it.key !in droppedSkips } +
            insertSkips.filter { skip -> state.skips.none { it.key == skip.key && it.key !in droppedSkips } }
        return SeriesState(
            series = state.series.filter { it.id !in deletedSeries }.map { seriesUpdates[it.id] ?: it } + insertSeries,
            tasks = state.tasks.filter { it.id !in retired }.map { updates[it.id]?.after ?: it } + insertTasks,
            skips = skips.distinctBy { it.key },
        )
    }
}

enum class EditScope(val wire: String) {
    This("this"),
    Future("future"),
    All("all");

    companion object {
        fun parse(value: String?): EditScope? = entries.firstOrNull { it.wire == value }

        fun preselected(isDateEdit: Boolean): EditScope = if (isDateEdit) This else Future
    }
}

data class Edit<out T>(val value: T)

data class TaskEdit(
    val title: Edit<String>? = null,
    val description: Edit<String?>? = null,
    val priority: Edit<String>? = null,
    val isUrgent: Edit<Boolean>? = null,
    val requiresAcknowledgement: Edit<Boolean>? = null,
    val dueAt: Edit<Instant?>? = null,
    val remindAt: Edit<Instant?>? = null,
)

class SeriesTooDenseException : IllegalStateException("this repeat rule would create too many tasks")

class UnreadableRuleException(seriesId: String) : IllegalStateException("series $seriesId has an unreadable rule")
