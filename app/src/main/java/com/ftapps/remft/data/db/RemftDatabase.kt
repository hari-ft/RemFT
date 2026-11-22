package com.ftapps.remft.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import com.ftapps.remft.data.alarm.AlarmDao
import com.ftapps.remft.data.db.entity.AlarmEventEntity
import com.ftapps.remft.data.db.entity.ChecklistItemEntity
import com.ftapps.remft.data.db.entity.FileMirrorEntity
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.db.entity.FolderEntity
import com.ftapps.remft.data.db.entity.NoteEntity
import com.ftapps.remft.data.db.entity.NoteLinkEntity
import com.ftapps.remft.data.db.entity.ReminderEntity
import com.ftapps.remft.data.db.entity.SeriesSkipEntity
import com.ftapps.remft.data.db.entity.TaskEntity
import com.ftapps.remft.data.db.entity.TaskSeriesEntity
import com.ftapps.remft.data.db.entity.TrashItemEntity
import com.ftapps.remft.data.files.FilesDao
import com.ftapps.remft.data.notes.NotesDao
import com.ftapps.remft.data.repeat.SeriesDao
import com.ftapps.remft.data.tasks.TasksDao

object DatabaseSchema {
    const val VERSION = 1
}

data class WorkspaceCount(val ws: String, val n: Int)

@Dao
interface IdentityDao {
    @Query(
        "SELECT ws, count(*) AS n FROM (" +
            "SELECT workspace_id AS ws FROM tasks UNION ALL " +
            "SELECT workspace_id AS ws FROM notes UNION ALL " +
            "SELECT workspace_id AS ws FROM folders UNION ALL " +
            "SELECT workspace_id AS ws FROM file_rows UNION ALL " +
            "SELECT workspace_id AS ws FROM task_series UNION ALL " +
            "SELECT workspace_id AS ws FROM trash_items" +
            ") GROUP BY ws",
    )
    suspend fun workspaceCounts(): List<WorkspaceCount>
}

@Database(
    entities = [
        TaskEntity::class,
        ChecklistItemEntity::class,
        NoteEntity::class,
        NoteLinkEntity::class,
        ReminderEntity::class,
        FileRowEntity::class,
        FolderEntity::class,
        TaskSeriesEntity::class,
        SeriesSkipEntity::class,
        TrashItemEntity::class,
        AlarmEventEntity::class,
        FileMirrorEntity::class,
    ],
    version = DatabaseSchema.VERSION,
    exportSchema = true,
)
abstract class RemftDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao

    abstract fun notesDao(): NotesDao

    abstract fun tasksDao(): TasksDao

    abstract fun filesDao(): FilesDao

    abstract fun seriesDao(): SeriesDao

    abstract fun alarmDao(): AlarmDao

    companion object {
        const val FILE_NAME = "remft.sqlite"
    }
}
