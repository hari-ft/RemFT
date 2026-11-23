package com.ftapps.remft.data.files

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ftapps.remft.data.db.entity.ChecklistItemEntity
import com.ftapps.remft.data.db.entity.FileMirrorEntity
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.db.entity.FolderEntity
import com.ftapps.remft.data.db.entity.NoteEntity
import com.ftapps.remft.data.db.entity.NoteLinkEntity
import com.ftapps.remft.data.db.entity.ReminderEntity
import com.ftapps.remft.data.db.entity.TaskEntity
import com.ftapps.remft.data.db.entity.TrashItemEntity
import kotlinx.coroutines.flow.Flow

data class SourcedFileRow(
    @Embedded val file: FileRowEntity,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_title") val sourceTitle: String,
)

@Dao
interface FilesDao {
    @Query("SELECT * FROM file_rows WHERE id = :id")
    suspend fun file(id: String): FileRowEntity?

    @Query("SELECT * FROM file_rows WHERE id = :id")
    fun watchFile(id: String): Flow<FileRowEntity?>

    @Insert
    suspend fun insertFile(row: FileRowEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFile(row: FileRowEntity)

    @Query("UPDATE file_rows SET name = :name, name_fold = :nameFold, updated_at = :at WHERE id = :id")
    suspend fun renameFile(id: String, name: String, nameFold: String, at: String)

    @Query("UPDATE file_rows SET folder_id = :folderId, updated_at = :at WHERE id = :id")
    suspend fun moveFile(id: String, folderId: String?, at: String)

    @Query("DELETE FROM file_rows WHERE id = :id")
    suspend fun deleteFile(id: String)

    @Query("DELETE FROM file_rows WHERE id IN (:ids)")
    suspend fun deleteFiles(ids: List<String>)

    @Query("SELECT local_path FROM file_rows WHERE local_path IS NOT NULL")
    suspend fun allLocalPaths(): List<String>

    @Query("SELECT local_path FROM file_rows WHERE local_path IN (:paths)")
    suspend fun liveLocalPaths(paths: List<String>): List<String>

    @Query("SELECT * FROM file_rows WHERE target_type = :targetType AND target_id = :targetId ORDER BY created_at DESC, id DESC")
    fun watchForTarget(targetType: String, targetId: String): Flow<List<FileRowEntity>>

    @Query(
        "SELECT * FROM file_rows WHERE workspace_id = :workspaceId AND target_type = 'workspace' " +
            "ORDER BY created_at DESC, id DESC",
    )
    fun watchWorkspaceLevel(workspaceId: String): Flow<List<FileRowEntity>>

    @Query(
        "SELECT f.*, 'task' AS source_type, t.title AS source_title FROM file_rows f JOIN tasks t ON t.id = f.target_id " +
            "WHERE f.target_type = 'task' AND f.workspace_id = :workspaceId " +
            "UNION ALL " +
            "SELECT f.*, 'note' AS source_type, n.title AS source_title FROM file_rows f JOIN notes n ON n.id = f.target_id " +
            "WHERE f.target_type = 'note' AND f.workspace_id = :workspaceId " +
            "ORDER BY created_at DESC",
    )
    fun watchSources(workspaceId: String): Flow<List<SourcedFileRow>>

    @Query("SELECT * FROM folders WHERE workspace_id = :workspaceId ORDER BY name ASC")
    fun watchFolders(workspaceId: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun folder(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE workspace_id = :workspaceId AND parent_id IS NULL")
    suspend fun rootFolders(workspaceId: String): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE workspace_id = :workspaceId AND parent_id = :parentId")
    suspend fun childFolders(workspaceId: String, parentId: String): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE workspace_id = :workspaceId AND parent_id IN (:parentIds)")
    suspend fun childrenOf(workspaceId: String, parentIds: List<String>): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE parent_id IN (:parentIds)")
    suspend fun anyChildrenOf(parentIds: List<String>): List<FolderEntity>

    @Query("SELECT * FROM file_mirrors WHERE local_path = :localPath")
    fun watchMirror(localPath: String): Flow<FileMirrorEntity?>

    @Query("SELECT COUNT(*) FROM file_rows WHERE folder_id IN (:folderIds)")
    suspend fun filesInFolders(folderIds: List<String>): Int

    @Query("SELECT * FROM folders WHERE id IN (:ids)")
    suspend fun folders(ids: List<String>): List<FolderEntity>

    @Insert
    suspend fun insertFolder(folder: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFolder(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name, parent_id = :parentId, updated_at = :at WHERE id = :id")
    suspend fun updateFolder(id: String, name: String, parentId: String?, at: String)

    @Query("DELETE FROM folders WHERE id IN (:ids)")
    suspend fun deleteFolders(ids: List<String>)

    @Insert
    suspend fun insertTrash(item: TrashItemEntity)

    @Query("SELECT * FROM trash_items WHERE workspace_id = :workspaceId ORDER BY deleted_at DESC, id DESC")
    fun watchTrash(workspaceId: String): Flow<List<TrashItemEntity>>

    @Query("SELECT * FROM trash_items")
    suspend fun allTrash(): List<TrashItemEntity>

    @Query("SELECT * FROM trash_items WHERE id = :id")
    suspend fun trashItem(id: String): TrashItemEntity?

    @Query("SELECT local_paths FROM trash_items")
    suspend fun trashLocalPaths(): List<String>

    @Query("DELETE FROM trash_items WHERE id = :id")
    suspend fun deleteTrash(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM trash_items WHERE kind = 'note' AND entity_id = :noteId)")
    suspend fun noteBinned(noteId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM tasks WHERE id = :id)")
    suspend fun taskExists(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM notes WHERE id = :id)")
    suspend fun noteExists(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM folders WHERE id = :id)")
    suspend fun folderExists(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM task_series WHERE id = :id)")
    suspend fun seriesExists(id: String): Boolean

    @Query("SELECT COUNT(*) FROM tasks WHERE series_id = :seriesId AND occurrence_date = :day AND id != :taskId")
    suspend fun occupiedSlots(seriesId: String, day: String, taskId: String): Int

    @Query("DELETE FROM series_skips WHERE series_id = :seriesId AND occurrence_date = :day")
    suspend fun deleteSeriesSkip(seriesId: String, day: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTask(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putChecklistItem(item: ChecklistItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReminder(reminder: ReminderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putNote(note: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putNoteLink(link: NoteLinkEntity)

    @Query(
        "SELECT * FROM tasks WHERE workspace_id = :workspaceId AND status = 'completed' " +
            "ORDER BY COALESCE(due_at, completed_at) DESC, id DESC LIMIT :limit",
    )
    fun watchCompleted(workspaceId: String, limit: Int): Flow<List<TaskEntity>>
}
