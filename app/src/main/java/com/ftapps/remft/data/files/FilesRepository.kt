package com.ftapps.remft.data.files

import androidx.room.withTransaction
import com.ftapps.remft.data.bin.BinMapping.toBin
import com.ftapps.remft.data.bin.RecycleBin
import com.ftapps.remft.data.db.RemftDatabase
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.db.entity.FolderEntity
import com.ftapps.remft.data.db.entity.TaskEntity
import com.ftapps.remft.domain.Fold
import com.ftapps.remft.domain.Ulid
import com.ftapps.remft.domain.backup.bin.BinPayload
import com.ftapps.remft.domain.time.UtcText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.Clock

class FolderRuleException(val code: String) : Exception(code)

data class FolderContents(val folders: Int, val files: Int) {
    val isEmpty: Boolean get() = folders == 0 && files == 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class FilesRepository(
    private val database: () -> RemftDatabase,
    private val workspaceId: suspend () -> String,
    private val bin: RecycleBin,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dao: FilesDao get() = database().filesDao()

    suspend fun workspace(): String = workspaceId()

    private fun <T> scoped(block: (FilesDao, String) -> Flow<T>): Flow<T> =
        flow { emit(workspaceId()) }.flatMapLatest { block(dao, it) }.flowOn(Dispatchers.IO)

    fun watchFolders(): Flow<List<FolderEntity>> = scoped { dao, ws -> dao.watchFolders(ws) }

    fun watchWorkspaceFiles(): Flow<List<FileRowEntity>> = scoped { dao, ws -> dao.watchWorkspaceLevel(ws) }

    fun watchSources(): Flow<List<SourcedFileRow>> = scoped { dao, ws -> dao.watchSources(ws) }

    fun watchCompleted(limit: Int): Flow<List<TaskEntity>> = scoped { dao, ws -> dao.watchCompleted(ws, limit) }

    fun watchTarget(targetType: String, targetId: String): Flow<List<FileRowEntity>> =
        flow { emit(Unit) }.flatMapLatest { dao.watchForTarget(targetType, targetId) }.flowOn(Dispatchers.IO)

    fun watchFile(id: String): Flow<FileRowEntity?> =
        flow { emit(Unit) }.flatMapLatest { dao.watchFile(id) }.flowOn(Dispatchers.IO)

    suspend fun file(id: String): FileRowEntity? = withContext(Dispatchers.IO) { dao.file(id) }

    suspend fun folderChain(folderId: String?): List<FolderEntity> = withContext(Dispatchers.IO) {
        val chain = mutableListOf<FolderEntity>()
        var cursor = folderId
        while (cursor != null && chain.none { it.id == cursor }) {
            val folder = dao.folder(cursor) ?: break
            chain.add(0, folder)
            cursor = folder.parentId
        }
        chain
    }

    suspend fun createFolder(name: String, parentId: String?): String = withContext(Dispatchers.IO) {
        val ws = workspaceId()
        val id = Ulid.next(clock.millis())
        val db = database()
        db.withTransaction {
            val dao = db.filesDao()
            assertName(dao, ws, parentId, name, exceptId = null)
            if (depthUnder(dao, parentId) > MAX_DEPTH) throw FolderRuleException(TOO_DEEP)
            val stamp = UtcText.format(clock.instant())
            dao.insertFolder(FolderEntity(id = id, workspaceId = ws, parentId = parentId, name = name.trim(), createdAt = stamp, updatedAt = stamp))
        }
        id
    }

    suspend fun renameFolder(folderId: String, name: String) = update(folderId, newName = name.trim(), moveTo = null, moving = false)

    suspend fun moveFolder(folderId: String, parentId: String?) = update(folderId, newName = null, moveTo = parentId, moving = true)

    private suspend fun update(folderId: String, newName: String?, moveTo: String?, moving: Boolean) = withContext(Dispatchers.IO) {
        val db = database()
        db.withTransaction {
            val dao = db.filesDao()
            val record = dao.folder(folderId) ?: return@withTransaction
            val parentId = if (moving) moveTo else record.parentId
            val name = newName ?: record.name
            assertName(dao, record.workspaceId, parentId, name, exceptId = folderId)
            if (moving && parentId != record.parentId) {
                val subtree = subtreeIds(dao, record.workspaceId, folderId)
                if (parentId != null && parentId in subtree) throw FolderRuleException(CYCLE)
                val height = height(dao, folderId)
                if (depthUnder(dao, parentId) + height - 1 > MAX_DEPTH) throw FolderRuleException(TOO_DEEP)
            }
            dao.updateFolder(folderId, name, parentId, UtcText.format(clock.instant()))
        }
    }

    suspend fun folderContents(folderId: String): FolderContents = withContext(Dispatchers.IO) {
        FolderContents(dao.anyChildrenOf(listOf(folderId)).size, dao.filesInFolder(folderId))
    }

    suspend fun deleteFolder(folderId: String) = withContext(Dispatchers.IO) {
        val ws = workspaceId()
        val toBin = bin.enabled()
        val db = database()
        db.withTransaction {
            val dao = db.filesDao()
            val folder = dao.folder(folderId) ?: return@withTransaction
            if (dao.anyChildrenOf(listOf(folderId)).isNotEmpty() || dao.filesInFolder(folderId) > 0) throw FolderRuleException(NOT_EMPTY)
            if (toBin) {
                bin.add(
                    kind = "folder",
                    workspaceId = ws,
                    entityId = folderId,
                    title = folder.name,
                    payload = BinPayload(folders = listOf(folder.toBin()), files = emptyList()),
                )
            }
            dao.deleteFolders(listOf(folderId))
        }
    }

    private suspend fun assertName(dao: FilesDao, ws: String, parentId: String?, name: String, exceptId: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw FolderRuleException(NAME_REQUIRED)
        val siblings = if (parentId == null) dao.rootFolders(ws) else dao.childFolders(ws, parentId)
        val folded = Fold.searchText(trimmed)
        if (siblings.any { it.id != exceptId && Fold.searchText(it.name) == folded }) throw FolderRuleException(NAME_TAKEN)
    }

    private suspend fun depthUnder(dao: FilesDao, parentId: String?): Int {
        var depth = 1
        var cursor = parentId
        val seen = mutableSetOf<String>()
        while (cursor != null) {
            if (!seen.add(cursor) || depth > MAX_DEPTH + 1) return UNREACHABLE
            val row = dao.folder(cursor) ?: return UNREACHABLE
            depth += 1
            cursor = row.parentId
        }
        return depth
    }

    private suspend fun height(dao: FilesDao, folderId: String): Int {
        var height = 0
        var level = listOf(folderId)
        val seen = mutableSetOf<String>()
        while (level.isNotEmpty() && height <= MAX_DEPTH + 1) {
            height += 1
            seen += level
            level = dao.anyChildrenOf(level).map { it.id }.filter { it !in seen }
        }
        return height
    }

    private suspend fun subtreeIds(dao: FilesDao, ws: String, rootId: String): List<String> {
        val out = mutableListOf(rootId)
        var frontier = listOf(rootId)
        while (frontier.isNotEmpty()) {
            frontier = dao.childrenOf(ws, frontier).map { it.id }.filter { it !in out }.distinct()
            out += frontier
        }
        return out
    }

    companion object {
        const val MAX_DEPTH = 10
        const val NAME_REQUIRED = "FOLDER_NAME_REQUIRED"
        const val NAME_TAKEN = "FOLDER_NAME_TAKEN"
        const val TOO_DEEP = "FOLDER_TOO_DEEP"
        const val CYCLE = "FOLDER_CYCLE"
        const val NOT_EMPTY = "FOLDER_NOT_EMPTY"
        private const val UNREACHABLE = 1 shl 20
    }
}
