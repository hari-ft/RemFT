package com.ftapps.remft.data.repeat

import androidx.room.withTransaction
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.db.RemftDatabase
import com.ftapps.remft.data.db.entity.TaskEntity
import com.ftapps.remft.data.db.entity.TaskSeriesEntity
import com.ftapps.remft.data.db.entity.TrashItemEntity
import com.ftapps.remft.data.bin.BinMapping
import com.ftapps.remft.data.bin.BinMapping.toBin
import com.ftapps.remft.domain.backup.bin.BinPayload
import com.ftapps.remft.domain.Ulid
import com.ftapps.remft.domain.backup.PathText
import com.ftapps.remft.domain.repeat.Edit
import com.ftapps.remft.domain.repeat.EditScope
import com.ftapps.remft.domain.repeat.OccurrenceRow
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.repeat.RetiredTask
import com.ftapps.remft.domain.repeat.SeriesChangeSet
import com.ftapps.remft.domain.repeat.SeriesEngine
import com.ftapps.remft.domain.repeat.SeriesRecord
import com.ftapps.remft.domain.repeat.SeriesState
import com.ftapps.remft.domain.repeat.TaskEdit
import com.ftapps.remft.domain.repeat.TaskUpdate
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.domain.time.UtcText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import java.time.Instant

interface TaskSeriesHooks {
    suspend fun afterTaskUpdate(before: TaskEntity, after: TaskEntity, edit: TaskEdit?, scope: EditScope?): List<String>

    fun discardBytes(paths: List<String>)
}

object SeriesEdits {
    fun fromPatch(patch: TaskPatch, savedTitle: String): TaskEdit = TaskEdit(
        title = patch.title?.let { Edit(savedTitle) },
        description = patch.description?.let { Edit(it.value) },
        priority = patch.priority?.let { Edit(it) },
        isUrgent = patch.isUrgent?.let { Edit(it) },
        requiresAcknowledgement = patch.requiresAcknowledgement?.let { Edit(it) },
        dueAt = patch.dueAt?.let { Edit(it.value) },
        remindAt = patch.remindAt?.let { Edit(it.value) },
    )
}

class SeriesRepository(
    private val database: () -> RemftDatabase,
    private val clock: Clock,
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val binEnabled: suspend () -> Boolean = { true },
    private val attachmentsDir: File? = null,
) : TaskSeriesHooks {
    private class Snapshot(
        val state: SeriesState,
        val tasks: Map<String, TaskEntity>,
        val series: Map<String, TaskSeriesEntity>,
    )

    val zoneId: String get() = clock.zone.id

    fun now(): Instant = clock.instant()

    private fun engine(): SeriesEngine = SeriesEngine(clock.instant()) { Ulid.next(clock.millis()) }

    fun watch(seriesId: String): Flow<SeriesRecord?> =
        database().seriesDao().watchSeries(seriesId).map { row -> row?.let(SeriesMapping::toRecord) }

    suspend fun byId(seriesId: String): SeriesRecord? = database().seriesDao().series(seriesId)?.let(SeriesMapping::toRecord)

    suspend fun isAfterCompletion(seriesId: String?): Boolean {
        val record = seriesId?.let { byId(it) } ?: return false
        return runCatching { record.isCompletionSeries }.getOrDefault(false)
    }

    suspend fun start(taskId: String, rule: RepeatRule, anchorAt: Instant): String? {
        val db = database()
        return db.withTransaction {
            val task = db.tasksDao().task(taskId) ?: return@withTransaction null
            val existing = task.seriesId
            if (existing != null) return@withTransaction existing
            val attached = attachedIds(db, listOf(task.id))
            val snapshot = Snapshot(
                SeriesState(tasks = listOf(SeriesMapping.toRow(task, task.id in attached))),
                mapOf(task.id to task),
                emptyMap(),
            )
            val seriesId = Ulid.next(clock.millis())
            val changes = engine().create(
                state = snapshot.state,
                workspaceId = task.workspaceId,
                rule = rule,
                anchorAt = anchorAt,
                timezone = zoneId,
                fromTaskId = taskId,
                seriesId = seriesId,
            )
            apply(db, snapshot, changes)
            seriesId
        }
    }

    suspend fun updateRule(seriesId: String, rule: RepeatRule) = run(seriesId, binned = false) { engine().updateRule(it, seriesId, rule) }

    suspend fun stop(seriesId: String, fromDay: String? = null, keepTaskId: String? = null) =
        run(seriesId, binned = binEnabled()) { engine().stop(it, seriesId, fromDay, keepTaskId) }

    suspend fun stoppedIds(seriesId: String, fromDay: String?, taskId: String): Set<String> {
        val db = database()
        val snapshot = load(db, seriesId)
        return engine().stop(snapshot.state, seriesId, fromDay).retireTasks.map { it.row.id }.toSet() + taskId
    }

    suspend fun stopFromHere(seriesId: String, fromDay: String?, taskId: String) {
        val toBin = binEnabled()
        val db = database()
        val doomed = db.withTransaction {
            val snapshot = load(db, seriesId)
            val changes = engine().stop(snapshot.state, seriesId, fromDay)
            val tasks = db.tasksDao()
            val target = snapshot.tasks[taskId] ?: tasks.task(taskId)
            val removed = (listOfNotNull(target) + changes.retireTasks.mapNotNull { snapshot.tasks[it.row.id] }).distinctBy { it.id }
            val series = snapshot.series[seriesId]
            val head = target ?: removed.firstOrNull()
            val ids = removed.map { it.id }
            val paths = if (toBin) emptyList() else ids.flatMap { id -> tasks.files(id).mapNotNull { it.localPath } }
            if (toBin && head != null && (removed.isNotEmpty() || series != null)) {
                val payload = BinPayload(
                    tasks = removed.map { it.toBin() },
                    checklist = ids.flatMap { id -> tasks.checklist(id).map { it.toBin() } },
                    reminders = ids.flatMap { id -> tasks.reminders(id).map { it.toBin() } },
                    noteLinks = ids.flatMap { id -> tasks.noteLinks(id).map { it.toBin() } },
                    files = ids.flatMap { id -> tasks.files(id).map { it.toBin() } },
                    series = listOfNotNull(series?.toBin()),
                    seriesSkips = db.seriesDao().skipsOf(seriesId).map { it.toBin() },
                )
                tasks.insertTrashItem(BinMapping.entry(Ulid.next(clock.millis()), head.workspaceId, "task", head.id, head.title, payload, changes.now))
            }
            apply(db, snapshot, changes.copy(retireTasks = emptyList()))
            removed.forEach { remove(db, it.id) }
            unreferenced(db, paths)
        }
        deleteBytes(doomed)
    }

    fun launch(block: suspend SeriesRepository.() -> Unit) {
        background.launch { runCatching { block() }.onFailure { AppLogs.warn("series.background_failed", it) } }
    }

    suspend fun rollAll(): Int {
        val db = database()
        return db.withTransaction {
            val snapshot = load(db, null)
            var created = 0
            for (result in engine().rollAll(snapshot.state)) {
                val changes = result.changes ?: continue
                if (changes.isEmpty) continue
                apply(db, snapshot, changes)
                created += changes.insertTasks.size
            }
            created
        }
    }

    override suspend fun afterTaskUpdate(before: TaskEntity, after: TaskEntity, edit: TaskEdit?, scope: EditScope?): List<String> {
        val seriesId = after.seriesId ?: return emptyList()
        val statusChanged = before.status != after.status
        if (!statusChanged && scope == null) return emptyList()
        val db = database()
        return db.withTransaction {
            val beforeRow = SeriesMapping.toRow(before, false)
            if (statusChanged) {
                val snapshot = load(db, seriesId)
                val current = snapshot.state.task(after.id) ?: return@withTransaction emptyList()
                apply(db, snapshot, engine().onStatusChange(snapshot.state, beforeRow, current))
            }
            val removedPaths = mutableListOf<String>()
            if (scope != null && edit != null) {
                val snapshot = load(db, seriesId)
                val current = snapshot.state.task(after.id) ?: return@withTransaction emptyList()
                apply(db, snapshot, engine().propagateScope(snapshot.state, current, edit, scope, beforeRow), binned = false, removedPaths)
            }
            unreferenced(db, removedPaths)
        }
    }

    override fun discardBytes(paths: List<String>) = deleteBytes(paths)

    private suspend fun run(seriesId: String, binned: Boolean = true, change: (SeriesState) -> SeriesChangeSet) {
        val db = database()
        val doomed = db.withTransaction {
            val snapshot = load(db, seriesId)
            val removedPaths = mutableListOf<String>()
            apply(db, snapshot, change(snapshot.state), binned, removedPaths)
            unreferenced(db, removedPaths)
        }
        deleteBytes(doomed)
    }

    private suspend fun unreferenced(db: RemftDatabase, paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val files = db.filesDao()
        val live = paths.chunked(ATTACHED_CHUNK).flatMap { files.liveLocalPaths(it) }.map(PathText::basename).toSet()
        val kept = mutableSetOf<String>()
        for (raw in files.trashLocalPaths()) {
            val listed = runCatching { BinPayload.parseLocalPaths(raw) }.getOrNull() ?: return emptyList()
            listed.forEach { kept += PathText.basename(it) }
        }
        return paths.filter { PathText.basename(it) !in live && PathText.basename(it) !in kept }
    }

    private fun deleteBytes(paths: List<String>) {
        val dir = attachmentsDir ?: return
        var failed = 0
        paths.forEach { path ->
            val base = PathText.basename(path)
            if (base.isEmpty() || base == "." || base == "..") return@forEach
            if (!runCatching { File(dir, base).takeIf { it.isFile }?.delete() ?: true }.getOrDefault(false)) failed++
        }
        if (failed > 0) AppLogs.warn("files.delete_failed", fields = mapOf("files" to failed))
    }

    private suspend fun attachedIds(db: RemftDatabase, ids: List<String>): Set<String> =
        ids.chunked(ATTACHED_CHUNK).flatMap { db.seriesDao().withAttachedContent(it) }.toSet()

    private suspend fun load(db: RemftDatabase, seriesId: String?): Snapshot {
        val dao = db.seriesDao()
        val seriesRows = if (seriesId == null) dao.allSeries() else listOfNotNull(dao.series(seriesId))
        val taskRows = if (seriesId == null) dao.allSeriesTasks() else dao.tasksOf(seriesId)
        val skipRows = if (seriesId == null) dao.allSkips() else dao.skipsOf(seriesId)
        val attached = attachedIds(db, taskRows.map { it.id })
        return Snapshot(
            state = SeriesState(
                series = seriesRows.mapNotNull(SeriesMapping::toRecord),
                tasks = taskRows.map { SeriesMapping.toRow(it, it.id in attached) },
                skips = skipRows.map(SeriesMapping::toSkip),
            ),
            tasks = taskRows.associateBy { it.id },
            series = seriesRows.associateBy { it.id },
        )
    }

    private suspend fun apply(
        db: RemftDatabase,
        snapshot: Snapshot,
        changes: SeriesChangeSet,
        binned: Boolean = true,
        removedPaths: MutableList<String>? = null,
    ) {
        if (changes.isEmpty) return
        val dao = db.seriesDao()
        changes.insertSeries.forEach { dao.insertSeries(SeriesMapping.toEntity(it)) }
        changes.updateSeries.forEach { dao.updateSeries(SeriesMapping.toEntity(it, snapshot.series[it.id])) }
        changes.updateTasks.forEach { update ->
            val entity = snapshot.tasks[update.id] ?: return@forEach
            dao.updateTask(SeriesMapping.applyUpdate(entity, update))
        }
        changes.retireTasks.forEach { retire(db, snapshot, it, changes.now, binned, removedPaths) }
        changes.insertTasks.forEach { dao.insertTask(SeriesMapping.newEntity(it)) }
        changes.insertSkips.forEach { dao.insertSkip(SeriesMapping.toEntity(it)) }
        changes.deleteSkips.forEach { dao.deleteSkip(it.seriesId, it.occurrenceDate) }
        changes.deleteSeriesIds.forEach { dao.deleteSeries(it) }
    }

    private suspend fun retire(
        db: RemftDatabase,
        snapshot: Snapshot,
        retired: RetiredTask,
        now: Instant,
        binned: Boolean,
        removedPaths: MutableList<String>?,
    ) {
        val tasks = db.tasksDao()
        val id = retired.row.id
        val original = snapshot.tasks[id] ?: tasks.task(id) ?: return
        val originalRow: OccurrenceRow? = snapshot.state.task(id)
        val entity = if (originalRow != null && originalRow != retired.row) {
            SeriesMapping.applyUpdate(original, TaskUpdate(originalRow, retired.row))
        } else {
            original
        }
        if (retired.toRecycleBin && binned) {
            val payload = BinPayload(
                tasks = listOf(entity.toBin()),
                checklist = tasks.checklist(id).map { it.toBin() },
                reminders = tasks.reminders(id).map { it.toBin() },
                noteLinks = tasks.noteLinks(id).map { it.toBin() },
                files = tasks.files(id).map { it.toBin() },
            )
            tasks.insertTrashItem(BinMapping.entry(Ulid.next(clock.millis()), entity.workspaceId, "task", entity.id, entity.title, payload, now))
        } else if (!binned) {
            removedPaths?.addAll(tasks.files(id).mapNotNull { it.localPath })
        }
        remove(db, id)
    }

    private suspend fun remove(db: RemftDatabase, id: String) {
        val tasks = db.tasksDao()
        tasks.deleteTask(id)
        tasks.deleteChecklistFor(id)
        tasks.deleteRemindersFor(id)
        tasks.deleteNoteLinksFor(id)
        tasks.deleteFilesFor(id)
    }

    private companion object {
        const val ATTACHED_CHUNK = 200
    }
}
