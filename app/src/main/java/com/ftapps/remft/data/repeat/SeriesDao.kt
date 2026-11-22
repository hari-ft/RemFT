package com.ftapps.remft.data.repeat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ftapps.remft.data.db.entity.SeriesSkipEntity
import com.ftapps.remft.data.db.entity.TaskEntity
import com.ftapps.remft.data.db.entity.TaskSeriesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Query("SELECT * FROM task_series ORDER BY id")
    suspend fun allSeries(): List<TaskSeriesEntity>

    @Query("SELECT * FROM task_series WHERE id = :id")
    suspend fun series(id: String): TaskSeriesEntity?

    @Query("SELECT * FROM task_series WHERE id = :id")
    fun watchSeries(id: String): Flow<TaskSeriesEntity?>

    @Query("SELECT * FROM tasks WHERE series_id IS NOT NULL")
    suspend fun allSeriesTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE series_id = :seriesId")
    suspend fun tasksOf(seriesId: String): List<TaskEntity>

    @Query("SELECT * FROM series_skips")
    suspend fun allSkips(): List<SeriesSkipEntity>

    @Query("SELECT * FROM series_skips WHERE series_id = :seriesId")
    suspend fun skipsOf(seriesId: String): List<SeriesSkipEntity>

    @Query(
        "SELECT task_id FROM checklist_items WHERE task_id IN (:ids) " +
            "UNION SELECT task_id FROM reminders WHERE task_id IN (:ids) " +
            "UNION SELECT entity_id FROM note_link_rows WHERE entity_type = 'task' AND entity_id IN (:ids) " +
            "UNION SELECT target_id FROM file_rows WHERE target_type = 'task' AND target_id IN (:ids)",
    )
    suspend fun withAttachedContent(ids: List<String>): List<String>

    @Insert
    suspend fun insertSeries(series: TaskSeriesEntity)

    @Update
    suspend fun updateSeries(series: TaskSeriesEntity)

    @Query("DELETE FROM task_series WHERE id = :id")
    suspend fun deleteSeries(id: String)

    @Insert
    suspend fun insertTask(task: TaskEntity)

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSkip(skip: SeriesSkipEntity)

    @Query("DELETE FROM series_skips WHERE series_id = :seriesId AND occurrence_date = :day")
    suspend fun deleteSkip(seriesId: String, day: String)
}
