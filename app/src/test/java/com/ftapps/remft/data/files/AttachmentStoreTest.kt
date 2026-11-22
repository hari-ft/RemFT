package com.ftapps.remft.data.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.Ulid
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class AttachmentStoreTest {
    private var rig: TestFiles? = null

    private fun open(maxBytes: Long = AttachmentStore.MAX_BYTES): TestFiles = TestFiles(maxBytes = maxBytes).also { rig = it }

    @After
    fun tearDown() {
        rig?.close()
    }

    private fun zeros(count: Long): () -> InputStream = {
        object : InputStream() {
            var left = count
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                left -= n
                return n
            }
        }
    }

    @Test
    fun addWritesTheBytesUnderTheIdThenRecordsTheRow() = runBlocking {
        val r = open()
        val id = r.attachments.add(r.workspace, "workspace", r.workspace, "Scan Ünïcode.PDF", "application/pdf", null, null, r.bytes("hello"))
        assertTrue(Ulid.isValid(id))
        val row = r.query("SELECT * FROM file_rows WHERE id = '$id'").single()
        assertEquals("$id.pdf", row["local_path"])
        assertEquals("Scan Ünïcode.PDF", row["name"])
        assertEquals("scan unicode.pdf", row["name_fold"])
        assertEquals("application/pdf", row["mime"])
        assertEquals("5", row["size_bytes"])
        assertEquals("workspace", row["target_type"])
        assertEquals("2026-11-20T04:30:00.000Z", row["created_at"])
        assertEquals("hello", File(r.dir, "$id.pdf").readText())
        assertTrue(r.dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        assertEquals(File(r.dir, "$id.pdf"), r.attachments.fileFor(id))
    }

    @Test
    fun storedNameKeepsOnlySaneExtensions() {
        assertEquals("ID.jpg", AttachmentStore.storedName("ID", "photo.JPG"))
        assertEquals("ID.gz", AttachmentStore.storedName("ID", "archive.tar.gz"))
        assertEquals("ID", AttachmentStore.storedName("ID", "weird.ext with space"))
        assertEquals("ID", AttachmentStore.storedName("ID", "toolongextension.abcdefghijk"))
        assertEquals("ID", AttachmentStore.storedName("ID", ".bashrc"))
        assertEquals("ID", AttachmentStore.storedName("ID", "noext"))
    }

    @Test
    fun aFailedInsertDeletesTheBytesItWrote() = runBlocking {
        val r = open()
        r.dir.mkdirs()
        r.failDatabase = true
        try {
            r.attachments.add(r.workspace, "workspace", r.workspace, "a.txt", "text/plain", null, null, r.bytes("x"))
            fail("insert should fail")
        } catch (_: IllegalStateException) {
        }
        r.failDatabase = false
        assertTrue(r.dir.listFiles().orEmpty().isEmpty())
        assertTrue(r.query("SELECT id FROM file_rows").isEmpty())
    }

    @Test
    fun aDeclaredSizeOverTheLimitIsRefusedBeforeCopying() = runBlocking {
        val r = open()
        var opened = false
        try {
            r.attachments.add(r.workspace, "workspace", r.workspace, "big.bin", "application/octet-stream", AttachmentStore.MAX_BYTES + 1, null) {
                opened = true
                "x".byteInputStream()
            }
            fail("too large")
        } catch (e: AttachmentTooLarge) {
            assertEquals(AttachmentStore.MAX_BYTES + 1, e.sizeBytes)
        }
        assertFalse(opened)
        assertEquals(100L * 1024 * 1024, AttachmentStore.MAX_BYTES)
    }

    @Test
    fun aSourceThatGrowsPastTheLimitIsCutOffAndItsPartRemoved() = runBlocking {
        val r = open(maxBytes = 1024)
        try {
            r.attachments.add(r.workspace, "workspace", r.workspace, "lie.bin", "application/octet-stream", 10, null, zeros(4096))
            fail("too large")
        } catch (_: AttachmentTooLarge) {
        }
        assertTrue(r.dir.listFiles().orEmpty().isEmpty())
        assertTrue(r.query("SELECT id FROM file_rows").isEmpty())
        val ok = r.attachments.add(r.workspace, "workspace", r.workspace, "fits.bin", "application/octet-stream", null, null, zeros(1024))
        assertEquals("1024", r.query("SELECT size_bytes FROM file_rows WHERE id = '$ok'").single()["size_bytes"])
    }

    @Test
    fun aFileForAGoneTaskLandsAtTheWorkspaceRoot() = runBlocking {
        val r = open()
        val folder = r.files.createFolder("Docs", null)
        val id = r.add("orphan.txt", targetType = "task", targetId = "01JDGONETASK0000000000000Z", folderId = folder)
        val row = r.query("SELECT target_type, target_id, folder_id FROM file_rows WHERE id = '$id'").single()
        assertEquals("workspace", row["target_type"])
        assertEquals(r.workspace, row["target_id"])
        assertNull(row["folder_id"])
        val task = r.tasks.repository.create(TaskDraft(title = "Live"))
        val attached = r.add("kept.txt", targetType = "task", targetId = task)
        assertEquals(task, r.query("SELECT target_id FROM file_rows WHERE id = '$attached'").single()["target_id"])
    }

    @Test
    fun renameMoveAndDeleteToTheBin() = runBlocking {
        val r = open()
        val folder = r.files.createFolder("Inbox scans", null)
        val id = r.add("a.txt")
        r.attachments.rename(id, "  Café notes.txt ")
        r.attachments.move(id, folder)
        val row = r.query("SELECT name, name_fold, folder_id FROM file_rows WHERE id = '$id'").single()
        assertEquals("Café notes.txt", row["name"])
        assertEquals("cafe notes.txt", row["name_fold"])
        assertEquals(folder, row["folder_id"])
        r.attachments.delete(id)
        assertTrue(r.query("SELECT id FROM file_rows").isEmpty())
        val trash = r.query("SELECT kind, entity_id, title, local_paths FROM trash_items").single()
        assertEquals("file", trash["kind"])
        assertEquals(id, trash["entity_id"])
        assertEquals("[\"$id.txt\"]", trash["local_paths"])
        assertTrue(File(r.dir, "$id.txt").isFile)
    }

    @Test
    fun deleteWithTheBinOffRemovesRowAndBytes() = runBlocking {
        val r = open()
        r.settings.set("remft_bin_enabled", "false")
        val id = r.add("gone.txt")
        r.attachments.delete(id)
        assertTrue(r.query("SELECT id FROM file_rows").isEmpty())
        assertTrue(r.query("SELECT id FROM trash_items").isEmpty())
        assertFalse(File(r.dir, "$id.txt").exists())
    }

    @Test
    fun sweepRemovesOldOrphansButKeepsYoungAndReferencedFiles() = runBlocking {
        val r = open()
        val live = r.add("live.txt")
        val binned = r.add("binned.txt")
        r.attachments.delete(binned)
        val old = r.clock.millis() - Duration.ofMinutes(11).toMillis()
        val young = r.clock.millis() - Duration.ofMinutes(9).toMillis()
        File(r.dir, "$live.txt").setLastModified(old)
        File(r.dir, "$binned.txt").setLastModified(old)
        val orphan = File(r.dir, "01JDORPHAN000000000000000Z.png").apply { writeText("o"); setLastModified(old) }
        val part = File(r.dir, "01JDPART00000000000000000Z.png.part").apply { writeText("p"); setLastModified(old) }
        val fresh = File(r.dir, "01JDFRESH0000000000000000Z.png").apply { writeText("f"); setLastModified(young) }

        val result = r.attachments.sweepOrphans()

        assertEquals(2, result.removed)
        assertEquals(1, result.young)
        assertFalse(orphan.exists())
        assertFalse(part.exists())
        assertTrue(fresh.exists())
        assertTrue(File(r.dir, "$live.txt").exists())
        assertTrue(File(r.dir, "$binned.txt").exists())
    }

    @Test
    fun sweepDoesNothingWhenABinEntryCannotBeRead() = runBlocking {
        val r = open()
        r.add("live.txt")
        r.exec(
            "INSERT INTO trash_items (id, workspace_id, kind, entity_id, title, payload, local_paths, size_bytes, deleted_at) VALUES " +
                "('T1', '${r.workspace}', 'file', 'F', 'x', '{}', 'not json', 0, '2026-11-01T00:00:00.000Z')",
        )
        val orphan = File(r.dir, "01JDORPHAN000000000000000Z.png").apply { writeText("o"); setLastModified(0) }
        val result = r.attachments.sweepOrphans()
        assertTrue(result.skipped)
        assertTrue(orphan.exists())
    }

    @Test
    fun maintenanceRunsPurgeThenSweep() = runBlocking {
        val r = open()
        val result = FilesMaintenance(r.bin, r.attachments).runAtStart()
        assertEquals(0, result.purged)
        assertNotNull(result.sweep)
    }
}
