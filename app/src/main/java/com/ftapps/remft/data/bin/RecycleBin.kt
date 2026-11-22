package com.ftapps.remft.data.bin

import androidx.room.withTransaction
import com.ftapps.remft.data.bin.BinMapping.toEntity
import com.ftapps.remft.data.db.RemftDatabase
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.db.entity.FolderEntity
import com.ftapps.remft.data.db.entity.TaskEntity
import com.ftapps.remft.data.db.entity.TrashItemEntity
import com.ftapps.remft.data.files.FilesDao
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.prefs.SettingsUnavailable
import com.ftapps.remft.domain.Ulid
import com.ftapps.remft.domain.backup.PathText
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.domain.backup.bin.BinFile
import com.ftapps.remft.domain.backup.bin.BinFolder
import com.ftapps.remft.domain.backup.bin.BinPayload
import com.ftapps.remft.domain.backup.bin.BinSettings
import com.ftapps.remft.domain.backup.bin.BinTask
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant

class RecycleBin(
    private val database: () -> RemftDatabase,
    private val settings: SettingsStore,
    private val attachmentsDir: File,
    private val workspaceId: suspend () -> String,
    private val clock: Clock = Clock.systemUTC(),
    private val afterRestore: suspend (BinPayload) -> Unit = {},
) {
    private val dao: FilesDao get() = database().filesDao()

    suspend fun enabled(): Boolean = settings.get(SettingKeys.BinEnabled.name) != "false"

    suspend fun readSettings(): BinSettings.Values = BinSettings.of(
        settings.get(SettingKeys.BinEnabled.name),
        settings.get(SettingKeys.BinAutoDelete.name),
        settings.get(SettingKeys.BinDays.name),
    )

    suspend fun readSettingsStrict(): BinSettings.Values? = try {
        val snapshot = settings.snapshot()
        BinSettings.of(
            snapshot[SettingKeys.BinEnabled.name],
            snapshot[SettingKeys.BinAutoDelete.name],
            snapshot[SettingKeys.BinDays.name],
        )
    } catch (_: SettingsUnavailable) {
        null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watch(): Flow<List<TrashItemEntity>> = flow { emit(workspaceId()) }.flatMapLatest { dao.watchTrash(it) }

    suspend fun add(kind: String, workspaceId: String, entityId: String, title: String, payload: BinPayload) {
        dao.insertTrash(BinMapping.entry(Ulid.next(clock.millis()), workspaceId, kind, entityId, title, payload, clock.instant()))
    }

    suspend fun keptPaths(): Set<String>? = withContext(Dispatchers.IO) { keptPathsNow() }

    private suspend fun keptPathsNow(): Set<String>? {
        val out = mutableSetOf<String>()
        for (raw in dao.trashLocalPaths()) {
            val paths = runCatching { BinPayload.parseLocalPaths(raw) }.getOrNull() ?: return null
            paths.forEach { out += PathText.basename(it) }
        }
        return out
    }

    suspend fun restore(trashId: String): Boolean = withContext(Dispatchers.IO) {
        val restored = try {
            database().withTransaction {
                val item = dao.trashItem(trashId) ?: return@withTransaction null
                val payload = BinPayload.parse(item.payload)
                putBack(payload)
                dao.deleteTrash(trashId)
                payload
            }
        } catch (_: Exception) {
            null
        } ?: return@withContext false
        runCatching { afterRestore(restored) }
        true
    }

    private suspend fun putBack(payload: BinPayload) {
        val dao = dao
        val taskIds = payload.tasks.map { it.id }.toSet()
        val noteIds = payload.notes.map { it.id }.toSet()
        val folderIds = payload.folders.map { it.id }.toSet()
        for (folder in payload.folders) {
            val parent = folder.parentId
            val keep = parent == null || parent in folderIds || dao.folderExists(parent)
            val placed = if (keep) folder else folder.copy(parentId = null)
            dao.putFolder(freeName(placed))
        }
        payload.notes.forEach { dao.putNote(it.toEntity()) }
        putBackSeries(payload)
        payload.tasks.forEach { dao.putTask(restoredTask(it)) }
        payload.checklist.forEach { dao.putChecklistItem(it.toEntity()) }
        payload.reminders.forEach { dao.putReminder(it.toEntity()) }
        for (link in payload.noteLinks) {
            if (link.noteId in noteIds || dao.noteExists(link.noteId) || dao.noteBinned(link.noteId)) {
                dao.putNoteLink(link.toEntity())
            }
        }
        payload.files.forEach { dao.putFile(restoredFile(it, taskIds, noteIds, folderIds)) }
    }

    private suspend fun putBackSeries(payload: BinPayload) {
        if (payload.series.isEmpty()) return
        val series = database().seriesDao()
        val revived = payload.series.filterNot { dao.seriesExists(it.id) }
        revived.forEach { series.insertSeries(it.toEntity()) }
        val ids = revived.map { it.id }.toSet()
        payload.seriesSkips.filter { it.seriesId in ids }.forEach { series.insertSkip(it.toEntity()) }
    }

    private suspend fun restoredTask(task: BinTask): TaskEntity {
        val seriesId = task.seriesId
        val day = task.occurrenceDate
        if (seriesId == null || day == null) return task.toEntity()
        dao.deleteSeriesSkip(seriesId, day)
        val live = dao.seriesExists(seriesId)
        val taken = dao.occupiedSlots(seriesId, day, task.id) > 0
        if (live && !taken) return task.toEntity()
        return task.copy(seriesId = null, occurrenceDate = null).toEntity()
    }

    private suspend fun restoredFile(file: BinFile, taskIds: Set<String>, noteIds: Set<String>, folderIds: Set<String>): FileRowEntity {
        val targetLive = when (file.targetType) {
            "task" -> file.targetId in taskIds || dao.taskExists(file.targetId)
            "note" -> file.targetId in noteIds || dao.noteExists(file.targetId)
            "workspace" -> true
            else -> false
        }
        if (!targetLive) return file.copy(targetType = "workspace", targetId = file.workspaceId, folderId = null).toEntity()
        val folder = file.folderId
        if (folder != null && folder !in folderIds && !dao.folderExists(folder)) return file.copy(folderId = null).toEntity()
        return file.toEntity()
    }

    private suspend fun freeName(folder: BinFolder): FolderEntity {
        val parent = folder.parentId
        val siblings = if (parent == null) dao.rootFolders(folder.workspaceId) else dao.childFolders(folder.workspaceId, parent)
        val name = BinPayload.freeFolderName(folder.name, siblings.filter { it.id != folder.id }.map { it.name })
        return (if (name == folder.name) folder else folder.copy(name = name)).toEntity()
    }

    suspend fun purge(trashId: String): Int = withContext(Dispatchers.IO) {
        val item = dao.trashItem(trashId) ?: return@withContext 0
        purgeAll(listOf(item))
    }

    suspend fun empty(): Int = withContext(Dispatchers.IO) { purgeAll(dao.allTrash()) }

    suspend fun purgeExpired(): Int = withContext(Dispatchers.IO) {
        val values = readSettingsStrict() ?: return@withContext 0
        if (!values.autoDelete) return@withContext 0
        val cutoff = clock.instant().minus(Duration.ofDays(values.days.toLong()))
        val expired = dao.allTrash().filter { item -> deletedAt(item.deletedAt)?.isBefore(cutoff) == true }
        purgeAll(expired)
    }

    private suspend fun purgeAll(items: List<TrashItemEntity>): Int {
        if (items.isEmpty()) return 0
        var purged = 0
        val doomed = mutableListOf<String>()
        for (candidate in items) {
            val paths = database().withTransaction {
                val item = dao.trashItem(candidate.id) ?: return@withTransaction null
                dao.deleteTrash(item.id)
                val mine = pathsOf(item)
                if (mine.isEmpty()) return@withTransaction emptyList()
                val live = dao.liveLocalPaths(mine).toSet()
                val kept = keptPathsNow() ?: return@withTransaction emptyList()
                mine.filter { it !in live && PathText.basename(it) !in kept }
            } ?: continue
            purged++
            doomed += paths
        }
        deleteBytes(doomed)
        return purged
    }

    private fun pathsOf(item: TrashItemEntity): List<String> =
        runCatching { BinPayload.parseLocalPaths(item.localPaths) }.getOrNull()
            ?: runCatching { BinPayload.parse(item.payload).localPaths }.getOrDefault(emptyList())

    private fun deleteBytes(names: List<String>) {
        names.forEach { name ->
            val base = PathText.basename(name)
            if (base.isEmpty() || base == "." || base == "..") return@forEach
            runCatching { File(attachmentsDir, base).takeIf { it.isFile }?.delete() }
        }
    }

    companion object {
        fun deletedAt(text: String): Instant? = UtcText.parse(text)

        fun fileEntry(row: FileRowEntity): BinPayload = BinPayload(files = listOf(with(BinMapping) { row.toBin() }))
    }
}
