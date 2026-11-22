package com.ftapps.remft.data.notes

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.db.entity.NoteEntity
import com.ftapps.remft.data.db.entity.NoteLinkEntity
import com.ftapps.remft.data.db.entity.TrashItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotesDao {
    @Query("SELECT * FROM notes WHERE workspace_id = :workspaceId ORDER BY id DESC")
    fun observeAll(workspaceId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Insert
    suspend fun insert(note: NoteEntity)

    @Update
    suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    @Query("SELECT * FROM note_link_rows WHERE note_id = :noteId")
    suspend fun links(noteId: String): List<NoteLinkEntity>

    @Query("DELETE FROM note_link_rows WHERE note_id = :noteId")
    suspend fun deleteLinks(noteId: String)

    @Query("SELECT * FROM file_rows WHERE target_type = 'note' AND target_id = :noteId")
    suspend fun files(noteId: String): List<FileRowEntity>

    @Query("DELETE FROM file_rows WHERE target_type = 'note' AND target_id = :noteId")
    suspend fun deleteFiles(noteId: String)

    @Query("SELECT * FROM file_rows WHERE id = :id")
    suspend fun file(id: String): FileRowEntity?

    @Insert
    suspend fun insertTrash(item: TrashItemEntity)
}
