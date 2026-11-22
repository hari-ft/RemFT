package com.ftapps.remft.data.notes

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.Ulid
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.ui.notes.NotesTestEnvironment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NotesRepositoryTest {
    private val env = NotesTestEnvironment()
    private val repository get() = env.repository
    private val utc = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")

    @After
    fun tearDown() = env.close()

    private fun exec(sql: String, vararg args: Any?) {
        env.holder.db.openHelper.writableDatabase.execSQL(sql, args)
    }

    private fun count(sql: String): Int = env.holder.db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql)).use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun createDerivesTitlePlainTextAndFolds() = runBlocking {
        val id = repository.create("  ", "# Café plan\n\n- [ ] buy **milk**")
        val note = repository.get(id)!!
        assertTrue(Ulid.isValid(id))
        assertEquals("Untitled", note.title)
        assertEquals("untitled", note.titleFold)
        assertEquals("Café plan buy milk", note.plainText)
        assertEquals("cafe plan buy milk", note.bodyFold)
        assertEquals(NotesTestEnvironment.WORKSPACE, note.workspaceId)
        assertTrue(note.createdAt!!.matches(utc))
        assertEquals(note.createdAt, note.updatedAt)
        assertFalse(note.isPinned)
    }

    @Test
    fun updateRewritesBodyAndFlags() = runBlocking {
        val id = repository.create("Trip", "old")
        assertTrue(repository.save(id, " Trip 2 ", "new **text**"))
        repository.setPinned(id, true)
        repository.setArchived(id, true)
        val note = repository.get(id)!!
        assertEquals("Trip 2", note.title)
        assertEquals("new text", note.plainText)
        assertTrue(note.isPinned)
        assertTrue(note.isArchived)
        assertFalse(repository.save("01MISSING00000000000000000", "x", "y"))
    }

    @Test
    fun listIsScopedToTheWorkspace() = runBlocking {
        repository.create("Mine", "a")
        exec("INSERT INTO notes (id, workspace_id, title, is_pinned, is_archived) VALUES ('01OTHER0000000000000000000', '01WSOTHER00000000000000000', 'Theirs', 0, 0)")
        val titles = repository.observeList().first().map { it.title }
        assertEquals(listOf("Mine"), titles)
    }

    @Test
    fun deleteMovesNoteLinksAndFilesToTheBin() = runBlocking {
        val id = repository.create("Trip", "body")
        exec("INSERT INTO note_link_rows (id, note_id, entity_type, entity_id) VALUES ('L1', ?, 'task', 'T1')", id)
        exec(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, local_path, created_at) " +
                "VALUES ('F1', ?, 'note', ?, 'a.png', 'image/png', 42, 'F1.png', '2026-01-01T00:00:00.000Z')",
            NotesTestEnvironment.WORKSPACE, id,
        )
        File(env.attachments, "F1.png").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(repository.delete(id))
        assertNull(repository.get(id))
        assertEquals(0, count("SELECT count(*) FROM note_link_rows"))
        assertEquals(0, count("SELECT count(*) FROM file_rows"))
        assertTrue(File(env.attachments, "F1.png").exists())
        env.holder.db.openHelper.readableDatabase.query("SELECT kind, entity_id, title, payload, local_paths, size_bytes, deleted_at, workspace_id FROM trash_items").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("note", cursor.getString(0))
            assertEquals(id, cursor.getString(1))
            assertEquals("Trip", cursor.getString(2))
            val payload = Json.parseToJsonElement(cursor.getString(3)).jsonObject
            assertEquals(listOf("tasks", "checklist", "reminders", "notes", "noteLinks", "files", "folders"), payload.keys.toList())
            val note = (payload["notes"] as JsonArray)[0] as JsonObject
            assertEquals(JsonPrimitive(false), note["isPinned"])
            assertEquals(JsonPrimitive(id), note["id"])
            assertTrue((note["createdAt"] as JsonPrimitive).content.matches(utc))
            assertEquals(1, (payload["noteLinks"] as JsonArray).size)
            assertEquals(JsonPrimitive("F1.png"), ((payload["files"] as JsonArray)[0] as JsonObject)["localPath"])
            assertEquals("[\"F1.png\"]", cursor.getString(4))
            assertEquals(42, cursor.getLong(5))
            assertTrue(cursor.getString(6).matches(utc))
            assertEquals(NotesTestEnvironment.WORKSPACE, cursor.getString(7))
        }
    }

    @Test
    fun deleteWithTheBinOffRemovesBytes() = runBlocking {
        env.settings.set(SettingKeys.BinEnabled.name, "false")
        val id = repository.create("Trip", "body")
        exec(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, local_path) VALUES ('F2', ?, 'note', ?, 'b.png', 'image/png', 3, 'F2.png')",
            NotesTestEnvironment.WORKSPACE, id,
        )
        File(env.attachments, "F2.png").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(repository.delete(id))
        assertEquals(0, count("SELECT count(*) FROM trash_items"))
        assertFalse(File(env.attachments, "F2.png").exists())
        assertFalse(repository.delete(id))
    }

    @Test
    fun attachmentsResolveOnlyInsideTheFilesFolder() = runBlocking {
        exec("INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, local_path) VALUES ('01HZZZZZZZZZZZZZZZZZZZZZZZ', 'W', 'note', 'N', 'a.png', 'image/png', 3, 'ok.png')")
        exec("INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, local_path) VALUES ('BAD', 'W', 'note', 'N', 'b', 'image/png', 3, '../remft.sqlite')")
        File(env.attachments, "ok.png").writeBytes(byteArrayOf(1))
        assertEquals("ok.png", repository.attachmentFile("01HZZZZZZZZZZZZZZZZZZZZZZZ")?.name)
        assertNull(repository.attachmentFile("BAD"))
        assertNull(repository.attachmentFile("NOPE"))
    }
}
