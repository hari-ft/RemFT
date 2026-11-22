package com.ftapps.remft.data.files

import androidx.room.withTransaction
import com.ftapps.remft.data.bin.RecycleBin
import com.ftapps.remft.data.db.RemftDatabase
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.domain.Fold
import com.ftapps.remft.domain.Ulid
import com.ftapps.remft.domain.backup.PathText
import com.ftapps.remft.data.onedrive.OneDriveErrors
import com.ftapps.remft.data.onedrive.OneDriveException
import com.ftapps.remft.domain.time.UtcText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Duration

class AttachmentTooLarge(val sizeBytes: Long) : IOException("FILE_TOO_LARGE")

enum class FetchProblem { NeedsOneDrive, Offline, Failed }

class AttachmentFetchFailed(val problem: FetchProblem) : IOException(problem.name)

data class SweepResult(val removed: Int, val young: Int, val failed: Int, val skipped: Boolean = false)

class AttachmentStore(
    private val database: () -> RemftDatabase,
    val directory: File,
    private val bin: RecycleBin,
    private val clock: Clock = Clock.systemUTC(),
    private val orphanGrace: Duration = ORPHAN_GRACE,
    private val maxBytes: Long = MAX_BYTES,
    private val fetchMissing: suspend (String) -> File? = { null },
    private val canFetch: suspend (String) -> Boolean = { false },
) {
    private val dao: FilesDao get() = database().filesDao()

    suspend fun add(
        workspaceId: String,
        targetType: String,
        targetId: String,
        name: String,
        mime: String,
        declaredSize: Long? = null,
        folderId: String? = null,
        open: () -> InputStream,
    ): String = withContext(Dispatchers.IO) {
        if (declaredSize != null && declaredSize > maxBytes) throw AttachmentTooLarge(declaredSize)
        val id = Ulid.next(clock.millis())
        val stored = storedName(id, name)
        directory.mkdirs()
        val target = File(directory, stored)
        val partial = File(directory, "$stored$PART_SUFFIX")
        var written = 0L
        try {
            open().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > maxBytes) throw AttachmentTooLarge(written)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
        } catch (e: Throwable) {
            partial.delete()
            throw e
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("rename")
        }
        try {
            val db = database()
            db.withTransaction {
                val dao = db.filesDao()
                val landing = landing(dao, workspaceId, targetType, targetId, folderId)
                val stamp = UtcText.format(clock.instant())
                dao.insertFile(
                    FileRowEntity(
                        id = id,
                        workspaceId = workspaceId,
                        targetType = landing.targetType,
                        targetId = landing.targetId,
                        name = name,
                        mime = mime,
                        sizeBytes = written,
                        folderId = landing.folderId,
                        nameFold = Fold.searchText(name),
                        localPath = stored,
                        createdAt = stamp,
                        updatedAt = stamp,
                    ),
                )
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        id
    }

    private data class Landing(val targetType: String, val targetId: String, val folderId: String?)

    private suspend fun landing(dao: FilesDao, workspaceId: String, targetType: String, targetId: String, folderId: String?): Landing {
        val targetLive = when (targetType) {
            "task" -> dao.taskExists(targetId)
            "note" -> dao.noteExists(targetId)
            "workspace" -> true
            else -> false
        }
        if (!targetLive) return Landing("workspace", workspaceId, null)
        val folderLive = folderId == null || dao.folderExists(folderId)
        return Landing(targetType, targetId, if (folderLive) folderId else null)
    }

    suspend fun fileFor(fileId: String): File? = withContext(Dispatchers.IO) {
        val row = dao.file(fileId) ?: return@withContext null
        fileOf(row)
    }

    suspend fun needsFetch(row: FileRowEntity): Boolean {
        if (fileOf(row) != null) return false
        return try {
            canFetch(row.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun ensureLocal(row: FileRowEntity): File? {
        fileOf(row)?.let { return it }
        return withContext(Dispatchers.IO) {
            val fetched = try {
                fetchMissing(row.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AttachmentFetchFailed(fetchProblemOf(e))
            }
            fetched?.takeIf { it.isFile }
        }
    }

    fun fileOf(row: FileRowEntity): File? {
        val name = row.localPath?.let(::safeName) ?: return null
        return File(directory, name).takeIf { it.isFile }
    }

    suspend fun rename(fileId: String, name: String) = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext
        dao.renameFile(fileId, trimmed, Fold.searchText(trimmed), UtcText.format(clock.instant()))
    }

    suspend fun move(fileId: String, folderId: String?) = withContext(Dispatchers.IO) {
        dao.moveFile(fileId, folderId, UtcText.format(clock.instant()))
    }

    suspend fun delete(fileId: String) = withContext(Dispatchers.IO) {
        if (!bin.enabled()) return@withContext discard(fileId)
        val db = database()
        db.withTransaction {
            val dao = db.filesDao()
            val row = dao.file(fileId) ?: return@withTransaction
            bin.add("file", row.workspaceId, row.id, row.name, RecycleBin.fileEntry(row))
            dao.deleteFile(fileId)
        }
    }

    suspend fun discard(fileId: String) = withContext(Dispatchers.IO) {
        val row = dao.file(fileId) ?: return@withContext
        dao.deleteFile(fileId)
        deleteBytes(listOf(row.localPath))
    }

    fun deleteBytes(names: Iterable<String?>) {
        names.forEach { raw ->
            val name = raw?.let(::safeName) ?: return@forEach
            runCatching { File(directory, name).takeIf { it.isFile }?.delete() }
        }
    }

    suspend fun sweepOrphans(): SweepResult = withContext(Dispatchers.IO) {
        if (!directory.isDirectory) return@withContext SweepResult(0, 0, 0)
        val youngAfter = clock.millis() - orphanGrace.toMillis()
        val kept = bin.keptPaths() ?: return@withContext SweepResult(0, 0, 0, skipped = true)
        val referenced = dao.allLocalPaths().map(PathText::basename).toSet() + kept
        var removed = 0
        var young = 0
        var failed = 0
        directory.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || file.name in referenced) return@forEach
            if (file.lastModified() > youngAfter) {
                young++
                return@forEach
            }
            if (file.delete()) removed++ else failed++
        }
        SweepResult(removed, young, failed)
    }

    companion object {
        const val MAX_BYTES: Long = 100L * 1024 * 1024
        const val PART_SUFFIX = ".part"
        val ORPHAN_GRACE: Duration = Duration.ofMinutes(10)
        private const val BUFFER = 64 * 1024
        private val safeExtension = Regex("^\\.[a-z0-9]{1,10}$")

        fun fetchProblemOf(error: Throwable): FetchProblem = when ((error as? OneDriveException)?.code) {
            OneDriveErrors.MIRROR_NEEDS_ONEDRIVE, OneDriveErrors.RECONNECT, OneDriveErrors.SIGNED_OUT -> FetchProblem.NeedsOneDrive
            OneDriveErrors.OFFLINE -> FetchProblem.Offline
            else -> FetchProblem.Failed
        }

        fun storedName(id: String, originalName: String): String {
            val ext = PathText.extension(originalName).lowercase()
            return if (safeExtension.matches(ext)) "$id$ext" else id
        }

        fun safeName(name: String): String? {
            val base = PathText.basename(name)
            if (base.isEmpty() || base == "." || base == ".." || base.contains('\\')) return null
            return base
        }
    }
}
