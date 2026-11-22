package com.ftapps.remft.domain.repeat

import java.time.Instant

class SeriesTestDb(var now: Instant) {
    var state = SeriesState()
        private set
    val binned = mutableListOf<OccurrenceRow>()
    val dropped = mutableListOf<OccurrenceRow>()
    private var counter = 0

    val workspace = "W1".padEnd(26, '0')

    fun nextId(): String {
        counter += 1
        return "T" + counter.toString().padStart(25, '0')
    }

    fun engine(at: Instant = now): SeriesEngine = SeriesEngine(at, ::nextId)

    fun apply(changes: SeriesChangeSet): SeriesChangeSet {
        changes.retireTasks.forEach { if (it.toRecycleBin) binned.add(it.row) else dropped.add(it.row) }
        state = changes.applyTo(state)
        return changes
    }

    fun createTask(
        title: String,
        dueAt: Instant? = null,
        remindAt: Instant? = null,
        description: String? = null,
        status: String = "open",
        priority: String = "none",
        isUrgent: Boolean = false,
        requiresAcknowledgement: Boolean = false,
        completedAt: Instant? = null,
        hasAttachedContent: Boolean = false,
        createdAt: Instant = now,
    ): String {
        val id = nextId()
        state = state.copy(
            tasks = state.tasks + OccurrenceRow(
                id = id,
                workspaceId = workspace,
                title = title,
                description = description,
                status = status,
                priority = priority,
                dueAt = dueAt,
                remindAt = remindAt,
                isUrgent = isUrgent,
                requiresAcknowledgement = requiresAcknowledgement,
                completedAt = completedAt,
                createdAt = createdAt,
                updatedAt = createdAt,
                hasAttachedContent = hasAttachedContent,
            ),
        )
        return id
    }

    fun start(
        rule: RepeatRule,
        anchorAt: Instant,
        timezone: String = "UTC",
        template: SeriesTemplate? = SeriesTemplate(mapOf(SeriesTemplate.Title to kotlinx.serialization.json.JsonPrimitive("Drink water"))),
        fromTaskId: String? = null,
        at: Instant = now,
    ): String {
        val id = nextId()
        apply(engine(at).create(state, workspace, rule, anchorAt, timezone, template, fromTaskId, id))
        return id
    }

    fun roll(at: Instant = now): Int {
        var created = 0
        for (result in engine(at).rollAll(state)) {
            val changes = result.changes ?: continue
            created += changes.insertTasks.size
            apply(changes)
        }
        return created
    }

    fun rowsOf(seriesId: String): List<OccurrenceRow> = state.rowsOf(seriesId).sortedBy { it.occurrenceDate ?: "" }

    fun row(id: String): OccurrenceRow = state.task(id) ?: error("no task $id")

    fun rowOrNull(id: String): OccurrenceRow? = state.task(id)

    fun onDay(seriesId: String, day: String): OccurrenceRow = rowsOf(seriesId).single { it.occurrenceDate == day }

    fun series(id: String): SeriesRecord? = state.seriesById(id)

    fun skips(seriesId: String): Set<String> = state.skipsOf(seriesId)

    fun write(id: String, change: (OccurrenceRow) -> OccurrenceRow) {
        state = state.copy(tasks = state.tasks.map { if (it.id == id) change(it) else it })
    }

    fun setStatus(id: String, status: String, completedAt: Instant? = now) {
        val before = row(id)
        val after = before.copy(status = status, completedAt = if (status == "completed") completedAt else null)
        write(id) { after }
        if (after.seriesId != null) apply(engine().onStatusChange(state, before, after))
    }

    fun setStatusRaw(id: String, status: String, completedAt: Instant? = now) {
        write(id) { it.copy(status = status, completedAt = if (status == "completed") completedAt else null) }
    }

    fun update(id: String, edit: TaskEdit, scope: EditScope? = null) {
        val before = row(id)
        var after = before
        edit.title?.let { after = after.copy(title = it.value) }
        edit.description?.let { after = after.copy(description = it.value) }
        edit.priority?.let { after = after.copy(priority = it.value) }
        edit.isUrgent?.let { after = after.copy(isUrgent = it.value) }
        edit.requiresAcknowledgement?.let { after = after.copy(requiresAcknowledgement = it.value) }
        edit.dueAt?.let { after = after.copy(dueAt = it.value) }
        edit.remindAt?.let { after = after.copy(remindAt = it.value) }
        write(id) { after }
        if (after.seriesId != null && scope != null) apply(engine().propagateScope(state, after, edit, scope, before))
    }

    fun deleteTask(id: String) {
        val record = row(id)
        apply(engine().recordUserDelete(record, state))
        state = state.copy(tasks = state.tasks.filter { it.id != id })
    }

    fun updateRule(seriesId: String, rule: RepeatRule) {
        apply(engine().updateRule(state, seriesId, rule))
    }

    fun stop(seriesId: String, fromDay: String? = null, keepTaskId: String? = null) {
        apply(engine().stop(state, seriesId, fromDay, keepTaskId))
    }
}
