package com.ftapps.remft.data.tasks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ftapps.remft.data.db.entity.ChecklistItemEntity
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.db.entity.NoteEntity
import com.ftapps.remft.data.db.entity.NoteLinkEntity
import com.ftapps.remft.data.db.entity.ReminderEntity
import com.ftapps.remft.data.db.entity.SeriesSkipEntity
import com.ftapps.remft.data.db.entity.TaskEntity
import com.ftapps.remft.data.db.entity.TrashItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TasksDao {
    @Query(
        "SELECT * FROM tasks WHERE workspace_id = :workspaceId AND (" +
            "status IN ('open', 'scheduled', 'in_progress', 'waiting') OR " +
            "(status = 'completed' AND completed_at >= :since AND (due_at IS NULL OR due_at >= :since))" +
            ") ORDER BY id DESC",
    )
    fun watchOpen(workspaceId: String, since: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE workspace_id = :workspaceId AND status = 'inbox' ORDER BY id DESC")
    fun watchInbox(workspaceId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun watchTask(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM checklist_items WHERE task_id = :taskId ORDER BY sort_order ASC, created_at ASC")
    fun watchChecklist(taskId: String): Flow<List<ChecklistItemEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: String): TaskEntity?

    @Insert
    suspend fun insertTask(task: TaskEntity)

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("SELECT * FROM checklist_items WHERE task_id = :taskId ORDER BY sort_order ASC, created_at ASC")
    suspend fun checklist(taskId: String): List<ChecklistItemEntity>

    @Query("SELECT * FROM checklist_items WHERE id = :id")
    suspend fun checklistItem(id: String): ChecklistItemEntity?

    @Insert
    suspend fun insertChecklistItem(item: ChecklistItemEntity)

    @Update
    suspend fun updateChecklistItem(item: ChecklistItemEntity)

    @Query("DELETE FROM checklist_items WHERE id = :id")
    suspend fun deleteChecklistItem(id: String)

    @Query("DELETE FROM checklist_items WHERE task_id = :taskId")
    suspend fun deleteChecklistFor(taskId: String)

    @Query("SELECT * FROM reminders WHERE task_id = :taskId")
    suspend fun reminders(taskId: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE task_id = :taskId AND status = 'snoozed'")
    suspend fun snoozedReminders(taskId: String): List<ReminderEntity>

    @Query("DELETE FROM reminders WHERE id IN (:ids)")
    suspend fun deleteReminders(ids: List<String>)

    @Query("DELETE FROM reminders WHERE task_id = :taskId")
    suspend fun deleteRemindersFor(taskId: String)

    @Query("SELECT * FROM note_link_rows WHERE entity_type = 'task' AND entity_id = :taskId")
    suspend fun noteLinks(taskId: String): List<NoteLinkEntity>

    @Query("DELETE FROM note_link_rows WHERE entity_type = 'task' AND entity_id = :taskId")
    suspend fun deleteNoteLinksFor(taskId: String)

    @Query("SELECT * FROM file_rows WHERE target_type = 'task' AND target_id = :taskId")
    suspend fun files(taskId: String): List<FileRowEntity>

    @Query("DELETE FROM file_rows WHERE target_type = 'task' AND target_id = :taskId")
    suspend fun deleteFilesFor(taskId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeriesSkip(skip: SeriesSkipEntity)

    @Insert
    suspend fun insertTrashItem(item: TrashItemEntity)

    @Query("SELECT * FROM trash_items ORDER BY deleted_at DESC")
    suspend fun trashItems(): List<TrashItemEntity>

    @Insert
    suspend fun insertNote(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: String): NoteEntity?
}
