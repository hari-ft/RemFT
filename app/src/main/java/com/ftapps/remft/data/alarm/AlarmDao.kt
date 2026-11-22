package com.ftapps.remft.data.alarm

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ftapps.remft.data.db.entity.AlarmEventEntity
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.db.entity.FolderEntity
import com.ftapps.remft.data.db.entity.ReminderEntity
import com.ftapps.remft.data.db.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query(
        "SELECT * FROM tasks WHERE workspace_id = :workspaceId " +
            "AND status NOT IN ('completed', 'cancelled', 'archived') " +
            "AND alarms_muted_at IS NULL AND (remind_at IS NOT NULL OR due_at IS NOT NULL)",
    )
    suspend fun wantingTasks(workspaceId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: String): TaskEntity?

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Query("SELECT * FROM reminders")
    suspend fun allReminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE task_id = :taskId")
    suspend fun reminders(taskId: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun reminder(id: String): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(row: ReminderEntity)

    @Update
    suspend fun updateReminder(row: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: String)

    @Insert
    suspend fun insertEvent(event: AlarmEventEntity): Long

    @Query("SELECT * FROM alarm_events ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recentEvents(limit: Int): List<AlarmEventEntity>

    @Query("SELECT * FROM alarm_events ORDER BY at DESC, id DESC LIMIT :limit")
    fun watchRecentEvents(limit: Int): Flow<List<AlarmEventEntity>>

    @Query("DELETE FROM alarm_events WHERE id NOT IN (SELECT id FROM alarm_events ORDER BY at DESC, id DESC LIMIT :keep)")
    suspend fun trimEvents(keep: Int)

    @Query("DELETE FROM alarm_events")
    suspend fun clearEvents()

    @Query("SELECT * FROM folders WHERE workspace_id = :workspaceId AND parent_id IS NULL AND name = :name LIMIT 1")
    suspend fun rootFolder(workspaceId: String, name: String): FolderEntity?

    @Insert
    suspend fun insertFolder(folder: FolderEntity)

    @Insert
    suspend fun insertFileRow(row: FileRowEntity)

    @Query("SELECT * FROM file_rows WHERE folder_id = :folderId ORDER BY created_at DESC")
    suspend fun filesInFolder(folderId: String): List<FileRowEntity>

    @Query("SELECT * FROM file_rows WHERE id = :id")
    suspend fun fileRow(id: String): FileRowEntity?
}
