package com.ftapps.remft.data.bin

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.bin.BinMapping.toBin
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.bin.BinPayload
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.BrokenDataStore
import com.ftapps.remft.testing.TestFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class RecycleBinTest {
    private var rig: TestFiles? = null

    private fun open(broken: Boolean = false): TestFiles =
        TestFiles(binSettings = if (broken) SettingsStore(BrokenDataStore()) else null).also { rig = it }

    @After
    fun tearDown() {
        rig?.close()
    }

    private fun TestFiles.snapshot(table: String): List<Map<String, String?>> = query("SELECT * FROM $table ORDER BY id")

    private fun TestFiles.trashId(): String = query("SELECT id FROM trash_items").single()["id"]!!

    private suspend fun TestFiles.binWholeTreeAsOlderVersionsDid(root: String, folderIds: List<String>, fileIds: List<String>) {
        val db = holder.db
        db.withTransaction {
            val dao = db.filesDao()
            val folders = dao.folders(folderIds)
            val files = fileIds.map { dao.file(it)!! }
            bin.add(
                kind = "folder",
                workspaceId = workspace,
                entityId = root,
                title = folders.first { it.id == root }.name,
                payload = BinPayload(folders = folders.map { it.toBin() }, files = files.map { it.toBin() }),
            )
            dao.deleteFiles(fileIds)
            dao.deleteFolders(folderIds)
        }
    }

    @Test
    fun aDeletedTaskWithChecklistRemindersAndFilesComesBackIdentical() = runBlocking {
        val r = open()
        val id = r.tasks.repository.create(TaskDraft(title = "Renew passport", description = "See **notes**", priority = "high", isUrgent = true, dueAt = Instant.parse("2026-11-21T03:30:00Z")))
        r.tasks.repository.addChecklistItem(id, "Photos")
        r.tasks.repository.addChecklistItem(id, "Form")
        r.exec("INSERT INTO reminders (id, task_id, kind, remind_at, status, created_at, updated_at) VALUES ('R1', '$id', 'remind', '2026-11-21T02:30:00.123Z', 'acknowledged', '2026-11-20T04:30:00.000Z', NULL)")
        r.exec("INSERT INTO note_link_rows (id, note_id, entity_type, entity_id) VALUES ('L1', 'N-gone', 'task', '$id')")
        val file = r.add("scan.pdf", targetType = "task", targetId = id, mime = "application/pdf")
        val before = listOf("tasks", "checklist_items", "reminders", "file_rows").associateWith { r.snapshot(it) }

        r.tasks.repository.delete(id)
        assertTrue(r.snapshot("tasks").isEmpty())
        assertTrue(File(r.dir, "$file.pdf").isFile)
        val trash = r.query("SELECT kind, size_bytes, local_paths FROM trash_items").single()
        assertEquals("task", trash["kind"])
        assertEquals("[\"$file.pdf\"]", trash["local_paths"])

        assertTrue(r.bin.restore(r.trashId()))

        before.forEach { (table, rows) -> assertEquals(table, rows, r.snapshot(table)) }
        assertTrue(r.query("SELECT id FROM trash_items").isEmpty())
        assertTrue(r.query("SELECT id FROM note_link_rows").isEmpty())
    }

    @Test
    fun aRestoredFolderSubtreeKeepsItsShapeAndDedupesTheRootName() = runBlocking {
        val r = open()
        val top = r.files.createFolder("Ringtones", null)
        val child = r.files.createFolder("Old", top)
        val inChild = r.add("bell.mp3", folderId = child, mime = "audio/mpeg")
        val before = r.snapshot("folders")
        r.binWholeTreeAsOlderVersionsDid(top, listOf(top, child), listOf(inChild))
        assertTrue(r.snapshot("folders").isEmpty())
        assertTrue(r.snapshot("file_rows").isEmpty())
        r.files.createFolder("ringtones", null)

        assertTrue(r.bin.restore(r.trashId()))

        val restored = r.query("SELECT id, name, parent_id FROM folders WHERE id IN ('$top', '$child') ORDER BY id").associateBy { it["id"] }
        assertEquals("Ringtones (2)", restored[top]!!["name"])
        assertNull(restored[top]!!["parent_id"])
        assertEquals(top, restored[child]!!["parent_id"])
        assertEquals(before.first { it["id"] == child }, r.query("SELECT * FROM folders WHERE id = '$child'").single())
        assertEquals(child, r.query("SELECT folder_id FROM file_rows WHERE id = '$inChild'").single()["folder_id"])
    }

    @Test
    fun aFolderWhoseParentIsGoneLandsAtTheRoot() = runBlocking {
        val r = open()
        val parent = r.files.createFolder("Parent", null)
        val child = r.files.createFolder("Child", parent)
        r.files.deleteFolder(child)
        r.settings.set("remft_bin_enabled", "false")
        r.files.deleteFolder(parent)
        assertTrue(r.bin.restore(r.trashId()))
        assertNull(r.query("SELECT parent_id FROM folders WHERE id = '$child'").single()["parent_id"])
    }

    @Test
    fun aNoteWithAnAttachmentRoundTrips() = runBlocking {
        val r = open()
        val note = r.notes.create("Trip", "![map](remft://file/X)")
        val file = r.add("map.png", targetType = "note", targetId = note, mime = "image/png")
        val before = listOf("notes", "file_rows").associateWith { r.snapshot(it) }
        r.notes.delete(note)
        assertTrue(r.snapshot("notes").isEmpty())
        assertTrue(r.snapshot("file_rows").isEmpty())
        assertTrue(File(r.dir, "$file.png").isFile)
        assertTrue(r.bin.restore(r.trashId()))
        before.forEach { (table, rows) -> assertEquals(table, rows, r.snapshot(table)) }
    }

    @Test
    fun aBinnedFileWhoseTaskIsGoneComesBackAtTheRoot() = runBlocking {
        val r = open()
        val task = r.tasks.repository.create(TaskDraft(title = "Temp"))
        val folder = r.files.createFolder("Docs", null)
        val file = r.add("a.txt", targetType = "task", targetId = task)
        r.exec("UPDATE file_rows SET folder_id = '$folder' WHERE id = '$file'")
        r.attachments.delete(file)
        r.settings.set("remft_bin_enabled", "false")
        r.tasks.repository.delete(task)
        assertTrue(r.bin.restore(r.trashId()))
        val row = r.query("SELECT target_type, target_id, folder_id FROM file_rows WHERE id = '$file'").single()
        assertEquals("workspace", row["target_type"])
        assertEquals(r.workspace, row["target_id"])
        assertNull(row["folder_id"])
    }

    @Test
    fun epochMillisecondDatesAreNotRestored() = runBlocking {
        val r = open()
        val payload = "{\"tasks\":[{\"id\":\"T1\",\"workspaceId\":\"${r.workspace}\",\"title\":\"Old\",\"description\":null,\"status\":\"open\"," +
            "\"priority\":\"none\",\"dueAt\":1763695800000,\"remindAt\":null,\"snoozedUntil\":null,\"alarmsMutedAt\":null," +
            "\"timezone\":\"Asia/Kolkata\",\"isUrgent\":false,\"requiresAcknowledgement\":false,\"seriesId\":null," +
            "\"occurrenceDate\":null,\"spawnedFromTaskId\":null,\"sortOrder\":0,\"completedAt\":null,\"titleFold\":\"old\"," +
            "\"descriptionFold\":null,\"createdAt\":1763695800000,\"updatedAt\":null}]}"
        r.exec(
            "INSERT INTO trash_items (id, workspace_id, kind, entity_id, title, payload, local_paths, size_bytes, deleted_at) VALUES " +
                "('B1', '${r.workspace}', 'task', 'T1', 'Old', '$payload', '[]', 0, '2026-11-19T00:00:00.000Z')",
        )
        assertFalse(r.bin.restore("B1"))
        assertTrue(r.query("SELECT id FROM tasks WHERE id = 'T1'").isEmpty())
        assertEquals(1, r.query("SELECT id FROM trash_items WHERE id = 'B1'").size)
    }

    @Test
    fun aTornPayloadIsNotRestoredAndStaysInTheBin() = runBlocking {
        val r = open()
        r.exec(
            "INSERT INTO trash_items (id, workspace_id, kind, entity_id, title, payload, local_paths, size_bytes, deleted_at) VALUES " +
                "('B1', '${r.workspace}', 'task', 'T1', 'Old', '{torn', '[]', 0, '2026-11-19T00:00:00.000Z')",
        )
        assertFalse(r.bin.restore("B1"))
        assertEquals(1, r.query("SELECT id FROM trash_items").size)
    }

    @Test
    fun purgeRemovesExpiredEntriesAndOnlyUnreferencedBytes() = runBlocking {
        val r = open()
        val old = r.add("old.txt")
        val shared = r.add("shared.txt")
        r.attachments.delete(old)
        r.clock.advance(Duration.ofDays(20))
        val young = r.add("young.txt")
        r.attachments.delete(young)
        r.exec(
            "INSERT INTO trash_items (id, workspace_id, kind, entity_id, title, payload, local_paths, size_bytes, deleted_at) VALUES " +
                "('OLDSHARED', '${r.workspace}', 'file', 'S', 's', '{}', '[\"$shared.txt\"]', 0, '2026-10-01T00:00:00.000Z')",
        )
        r.clock.advance(Duration.ofDays(11))

        assertEquals(2, r.bin.purgeExpired())

        val left = r.query("SELECT entity_id FROM trash_items").map { it["entity_id"] }
        assertEquals(listOf(young), left)
        assertFalse(File(r.dir, "$old.txt").exists())
        assertTrue(File(r.dir, "$shared.txt").exists())
        assertTrue(File(r.dir, "$young.txt").exists())
    }

    @Test
    fun purgeHonoursTheDaysAndAutoDeleteSettings() = runBlocking {
        val r = open()
        val id = r.add("a.txt")
        r.attachments.delete(id)
        r.clock.advance(Duration.ofDays(8))
        r.settings.set("remft_bin_auto_delete", "false")
        assertEquals(0, r.bin.purgeExpired())
        r.settings.set("remft_bin_auto_delete", "true")
        assertEquals(0, r.bin.purgeExpired())
        r.settings.set("remft_bin_days", "7")
        assertEquals(1, r.bin.purgeExpired())
        assertFalse(File(r.dir, "$id.txt").exists())
    }

    @Test
    fun purgeNeverRunsWhenSettingsCannotBeRead() = runBlocking {
        val r = open(broken = true)
        r.exec(
            "INSERT INTO trash_items (id, workspace_id, kind, entity_id, title, payload, local_paths, size_bytes, deleted_at) VALUES " +
                "('B1', '${r.workspace}', 'file', 'F', 'x', '{}', '[]', 0, '2020-01-01T00:00:00.000Z')",
        )
        assertEquals(null, r.bin.readSettingsStrict())
        assertEquals(0, r.bin.purgeExpired())
        assertEquals(1, r.query("SELECT id FROM trash_items").size)
    }

    @Test
    fun emptyAndPurgeOneDeleteForGood() = runBlocking {
        val r = open()
        val a = r.add("a.txt")
        val b = r.add("b.txt")
        r.attachments.delete(a)
        r.attachments.delete(b)
        val first = r.query("SELECT id FROM trash_items WHERE entity_id = '$a'").single()["id"]!!
        assertEquals(1, r.bin.purge(first))
        assertFalse(File(r.dir, "$a.txt").exists())
        assertTrue(File(r.dir, "$b.txt").exists())
        assertEquals(1, r.bin.empty())
        assertFalse(File(r.dir, "$b.txt").exists())
        assertTrue(r.query("SELECT id FROM trash_items").isEmpty())
    }
}
