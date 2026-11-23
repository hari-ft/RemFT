package com.ftapps.remft.data.tasks

import androidx.room.withTransaction
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.bin.BinMapping
import com.ftapps.remft.data.bin.BinMapping.toBin
import com.ftapps.remft.data.db.RemftDatabase
import com.ftapps.remft.data.db.entity.ChecklistItemEntity
import com.ftapps.remft.data.db.entity.NoteEntity
import com.ftapps.remft.data.db.entity.SeriesSkipEntity
import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.repeat.SeriesEdits
import com.ftapps.remft.data.repeat.TaskSeriesHooks
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.Fold
import com.ftapps.remft.domain.Ulid
import com.ftapps.remft.domain.repeat.EditScope
import com.ftapps.remft.domain.backup.bin.BinPayload
import com.ftapps.remft.domain.tasks.Change
import com.ftapps.remft.domain.tasks.ChecklistItem
import com.ftapps.remft.domain.tasks.DayBoundary
import com.ftapps.remft.domain.tasks.MarkdownPlainText
import com.ftapps.remft.domain.tasks.SnoozedReminder
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.domain.tasks.TaskRules
import com.ftapps.remft.domain.tasks.TaskStatus
import com.ftapps.remft.domain.time.UtcText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

data class TaskDetail(val task: Task, val checklist: List<ChecklistItem>)

class TasksRepository(
    private val database: () -> RemftDatabase,
    private val identity: LocalIdentityRepository,
    private val settings: SettingsStore,
    private val clock: Clock,
    private val attachmentsDir: File? = null,
    private val seriesHooks: TaskSeriesHooks? = null,
    private val timeChanges: Flow<Unit> = emptyFlow(),
) {
    private val dao: TasksDao get() = database().tasksDao()

    val zone: ZoneId get() = clock.zone

    fun now(): Instant = clock.instant()

    suspend fun workspaceId(): String = identity.workspaceId()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> restartedOnTimeChange(block: () -> Flow<T>): Flow<T> =
        timeChanges.onStart { emit(Unit) }.flatMapLatest { block() }

    fun dayStarts(): Flow<Instant> = restartedOnTimeChange {
        flow {
            while (true) {
                val nowZoned = ZonedDateTime.now(clock)
                emit(DayBoundary.startOfDayInstant(nowZoned.toInstant(), nowZoned.zone))
                delay(DayBoundary.delayUntilNextDay(nowZoned).toMillis().coerceAtLeast(1_000L))
            }
        }
    }.distinctUntilChanged()

    fun minuteTicks(): Flow<Instant> = restartedOnTimeChange {
        flow {
            while (true) {
                val now = clock.instant()
                emit(now)
                delay(MINUTE_MS - Math.floorMod(now.toEpochMilli(), MINUTE_MS))
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchHome(): Flow<List<Task>> = flow { emit(workspaceId()) }
        .flatMapLatest { workspace ->
            dayStarts().flatMapLatest { since -> dao.watchOpen(workspace, UtcText.format(since)) }
        }
        .map { rows -> rows.map(TaskMapping::toDomain) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchInbox(): Flow<List<Task>> = flow { emit(workspaceId()) }
        .flatMapLatest { dao.watchInbox(it) }
        .map { rows -> rows.map(TaskMapping::toDomain) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchScheduled(): Flow<List<Task>> = flow { emit(workspaceId()) }
        .flatMapLatest { dao.watchScheduled(it) }
        .map { rows -> rows.map(TaskMapping::toDomain) }

    fun watchDetail(id: String): Flow<TaskDetail?> = combine(dao.watchTask(id), dao.watchChecklist(id)) { task, checklist ->
        task?.let { TaskDetail(TaskMapping.toDomain(it), checklist.map(TaskMapping::toDomain)) }
    }

    suspend fun detail(id: String): TaskDetail? {
        val task = dao.task(id) ?: return null
        return TaskDetail(TaskMapping.toDomain(task), dao.checklist(id).map(TaskMapping::toDomain))
    }

    suspend fun create(draft: TaskDraft, alsoInTransaction: (suspend (String) -> Unit)? = null): String {
        val workspace = workspaceId()
        val id = Ulid.next(clock.millis())
        val task = TaskRules.create(id, workspace, draft, now())
        database().withTransaction {
            dao.insertTask(TaskMapping.toEntity(task))
            alsoInTransaction?.invoke(id)
        }
        return id
    }

    suspend fun update(id: String, patch: TaskPatch, scope: EditScope? = null) {
        val db = database()
        db.withTransaction {
            val previous = dao.task(id) ?: return@withTransaction
            val updated = TaskRules.update(TaskMapping.toDomain(previous), patch, now(), zone)
            val saved = TaskMapping.toEntity(updated, previous)
            dao.updateTask(saved)
            seriesHooks?.afterTaskUpdate(previous, saved, scope?.let { SeriesEdits.fromPatch(patch, saved.title) }, scope)
            if (patch.touchesAlarms) {
                val snoozed = dao.snoozedReminders(id).mapNotNull { row ->
                    UtcText.parse(row.remindAt)?.let { SnoozedReminder(row.id, row.kind, it) }
                }
                val stale = TaskRules.staleSnoozedReminderIds(updated, snoozed)
                if (stale.isNotEmpty()) dao.deleteReminders(stale)
            }
        }
    }

    suspend fun setCompleted(id: String, completed: Boolean) =
        update(id, TaskPatch(status = if (completed) TaskStatus.Completed.db else TaskStatus.Open.db))

    suspend fun muteAlarms(id: String, muted: Boolean) =
        update(id, TaskPatch(alarmsMutedAt = Change(if (muted) now() else null)))

    suspend fun binEnabled(): Boolean = settings.binEnabled()

    suspend fun delete(id: String) {
        val toBin = binEnabled()
        val removedPaths = mutableListOf<String>()
        database().withTransaction {
            val task = dao.task(id) ?: return@withTransaction
            val seriesId = task.seriesId
            val day = task.occurrenceDate
            if (seriesId != null && day != null) {
                dao.insertSeriesSkip(SeriesSkipEntity(seriesId, day, UtcText.format(now())))
            }
            val files = dao.files(id)
            if (toBin) {
                val payload = BinPayload(
                    tasks = listOf(task.toBin()),
                    checklist = dao.checklist(id).map { it.toBin() },
                    reminders = dao.reminders(id).map { it.toBin() },
                    noteLinks = dao.noteLinks(id).map { it.toBin() },
                    files = files.map { it.toBin() },
                )
                dao.insertTrashItem(BinMapping.entry(Ulid.next(clock.millis()), task.workspaceId, "task", task.id, task.title, payload, now()))
            }
            dao.deleteTask(id)
            dao.deleteChecklistFor(id)
            dao.deleteRemindersFor(id)
            dao.deleteNoteLinksFor(id)
            dao.deleteFilesFor(id)
            removedPaths += files.mapNotNull { it.localPath }
        }
        if (!toBin) {
            val dir = attachmentsDir ?: return
            val failed = removedPaths.count { name -> !runCatching { File(dir, name).let { !it.exists() || it.delete() } }.getOrDefault(false) }
            if (failed > 0) AppLogs.warn("files.delete_failed", fields = mapOf("files" to failed))
        }
    }

    suspend fun convertToNote(id: String): String? {
        val task = dao.task(id) ?: return null
        val noteId = Ulid.next(clock.millis())
        val stamp = UtcText.format(now())
        val title = task.title.trim()
        val plain = MarkdownPlainText.from(task.description)
        database().withTransaction {
            dao.insertNote(
                NoteEntity(
                    id = noteId,
                    workspaceId = task.workspaceId,
                    title = title,
                    contentMarkdown = task.description,
                    plainText = plain,
                    titleFold = Fold.searchText(title),
                    bodyFold = Fold.searchText(plain),
                    createdAt = stamp,
                    updatedAt = stamp,
                ),
            )
        }
        delete(id)
        return noteId
    }

    suspend fun addChecklistItem(taskId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        database().withTransaction {
            dao.task(taskId) ?: return@withTransaction
            val existing = dao.checklist(taskId)
            val stamp = UtcText.format(now())
            dao.insertChecklistItem(
                ChecklistItemEntity(
                    id = Ulid.next(clock.millis()),
                    taskId = taskId,
                    title = trimmed,
                    sortOrder = existing.size,
                    createdAt = stamp,
                    updatedAt = stamp,
                ),
            )
        }
    }

    suspend fun renameChecklistItem(itemId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        database().withTransaction {
            val item = dao.checklistItem(itemId) ?: return@withTransaction
            if (item.title == trimmed) return@withTransaction
            dao.updateChecklistItem(item.copy(title = trimmed, updatedAt = UtcText.format(now())))
        }
    }

    suspend fun setChecklistItemDone(itemId: String, done: Boolean) {
        database().withTransaction {
            val item = dao.checklistItem(itemId) ?: return@withTransaction
            dao.updateChecklistItem(item.copy(isDone = done, updatedAt = UtcText.format(now())))
        }
    }

    suspend fun deleteChecklistItem(itemId: String) {
        database().withTransaction { dao.deleteChecklistItem(itemId) }
    }

    suspend fun moveChecklistItem(taskId: String, itemId: String, offset: Int) {
        database().withTransaction {
            val items = dao.checklist(taskId).toMutableList()
            val from = items.indexOfFirst { it.id == itemId }
            if (from < 0) return@withTransaction
            val to = (from + offset).coerceIn(0, items.lastIndex)
            if (to == from) return@withTransaction
            items.add(to, items.removeAt(from))
            val stamp = UtcText.format(now())
            items.forEachIndexed { index, item ->
                if (item.sortOrder != index) dao.updateChecklistItem(item.copy(sortOrder = index, updatedAt = stamp))
            }
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
