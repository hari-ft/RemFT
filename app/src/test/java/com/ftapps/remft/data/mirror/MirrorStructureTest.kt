package com.ftapps.remft.data.mirror

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.onedrive.OneDriveRig
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.bin.BinFile
import com.ftapps.remft.domain.backup.bin.BinFolder
import com.ftapps.remft.domain.backup.bin.BinPayload
import com.ftapps.remft.domain.backup.bin.BinTask
import com.ftapps.remft.domain.backup.mirror.MirrorState
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.TestDatabases
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class MirrorStructureTest {
    private lateinit var rig: OneDriveRig
    private lateinit var filesDir: File
    private lateinit var sql: FileMirrorSql
    private val remote = FakeRemote()
    private val now: Instant = Instant.parse("2026-11-20T10:00:00Z")

    private val bill = BinFile("F3", "WS", "workspace", "WS", "bill.pdf", "application/pdf", 3, folderId = "D3", localPath = "01F3.pdf")

    @Before
    fun setUp() {
        rig = OneDriveRig()
        val dbFile = File(rig.root, "remft.sqlite")
        filesDir = File(rig.root, "files").apply { mkdirs() }
        TestDatabases.create(dbFile) { db -> seed(db) }
        File(filesDir, "01F1.png").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(filesDir, "01F2.txt").writeBytes("manual".toByteArray())
        File(filesDir, "01F3.pdf").writeBytes(byteArrayOf(7, 8, 9))
        sql = FileMirrorSql(dbFile)
        runBlocking { rig.store.set(SettingKeys.MirrorReviewSweptAt.name, "done") }
    }

    @After
    fun tearDown() {
        sql.close()
        rig.close()
    }

    private fun mirror(stop: () -> Boolean = { false }) =
        FileMirror(sql, remote, filesDir, rig.store, clock = { now }, log = BackupLog.None, stopRequested = stop)

    private fun seed(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO tasks (id, workspace_id, title, created_at, updated_at) VALUES ('T1', 'WS', 'Pay rent', '2026-11-01T08:00:00.000Z', '2026-11-01T08:00:00.000Z')")
        db.execSQL("INSERT INTO folders (id, workspace_id, name) VALUES ('D1', 'WS', 'Docs')")
        db.execSQL("INSERT INTO folders (id, workspace_id, name) VALUES ('D2', 'WS', 'Bills')")
        db.execSQL("INSERT INTO folders (id, workspace_id, name, parent_id) VALUES ('D3', 'WS', '2025', 'D2')")
        db.execSQL(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, local_path) VALUES " +
                "('F1', 'WS', 'task', 'T1', 'receipt.png', 'image/png', 4, '01F1.png')",
        )
        db.execSQL(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, folder_id, local_path) VALUES " +
                "('F2', 'WS', 'workspace', 'WS', 'manual.txt', 'text/plain', 6, 'D1', '01F2.txt')",
        )
        db.execSQL(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, folder_id, local_path) VALUES " +
                "('F3', 'WS', 'workspace', 'WS', 'bill.pdf', 'application/pdf', 3, 'D3', '01F3.pdf')",
        )
    }

    private fun row(name: String) = runBlocking { mirror().rows()[name] }

    private fun catchUp(m: FileMirror = mirror()) = runBlocking { m.catchUp() }

    private fun putInBin(trashId: String, kind: String, entityId: String, payload: BinPayload) {
        sql.exec(
            "INSERT INTO trash_items (id, workspace_id, kind, entity_id, title, payload, local_paths, size_bytes, deleted_at) VALUES (?, 'WS', ?, ?, 'x', ?, ?, 0, '2026-11-20T09:00:00.000Z')",
            arrayOf(trashId, kind, entityId, payload.encode(), payload.localPathsJson()),
        )
    }

    private fun binBill() {
        sql.exec("DELETE FROM file_rows WHERE id = 'F3'")
        putInBin("B1", "file", "F3", BinPayload(files = listOf(bill)))
    }

    private fun restoreBill() {
        sql.exec("DELETE FROM trash_items WHERE id = 'B1'")
        sql.exec(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, folder_id, local_path) VALUES " +
                "('F3', 'WS', 'workspace', 'WS', 'bill.pdf', 'application/pdf', 3, 'D3', '01F3.pdf')",
        )
    }

    @Test
    fun renamingAFileRenamesItsOneDriveCopy() {
        catchUp()
        val id = row("01F2.txt")!!.itemId!!
        val puts = remote.puts.size
        sql.exec("UPDATE file_rows SET name = 'guide.txt' WHERE id = 'F2'")
        val result = catchUp()
        assertEquals("Files/Docs/guide (F2).txt", remote.pathOf(id))
        assertEquals("Files/Docs/guide (F2).txt", row("01F2.txt")!!.remotePath)
        assertEquals(puts, remote.puts.size)
        assertEquals(1, result.moved)
        val moves = remote.moves
        catchUp()
        assertEquals(moves, remote.moves)
    }

    @Test
    fun movingAFileToAnotherFolderMovesItsCopy() {
        catchUp()
        val id = row("01F2.txt")!!.itemId!!
        sql.exec("UPDATE file_rows SET folder_id = 'D3' WHERE id = 'F2'")
        catchUp()
        assertEquals("Files/Bills/2025/manual (F2).txt", remote.pathOf(id))
        assertEquals("Files/Bills/2025/manual (F2).txt", row("01F2.txt")!!.remotePath)
        assertTrue(remote.folders().contains("Files/Docs"))
    }

    @Test
    fun renamingAFolderMovesTheFolderOnceAndRewritesThePathsUnderIt() {
        catchUp()
        val folder = remote.idOf("Files/Bills")!!
        val file = row("01F3.pdf")!!.itemId!!
        val before = remote.moves
        sql.exec("UPDATE folders SET name = 'Invoices' WHERE id = 'D2'")
        catchUp()
        assertEquals(1, remote.moves - before)
        assertEquals("Files/Invoices", remote.pathOf(folder))
        assertEquals("Files/Invoices/2025/bill (F3).pdf", remote.pathOf(file))
        assertEquals("Files/Invoices/2025/bill (F3).pdf", row("01F3.pdf")!!.remotePath)
        catchUp()
        assertEquals(1, remote.moves - before)
    }

    @Test
    fun movingAFolderUnderAnotherMovesItWithItsFiles() {
        catchUp()
        val folder = remote.idOf("Files/Bills/2025")!!
        sql.exec("UPDATE folders SET parent_id = 'D1' WHERE id = 'D3'")
        catchUp()
        assertEquals("Files/Docs/2025", remote.pathOf(folder))
        assertEquals("Files/Docs/2025/bill (F3).pdf", remote.pathOf(row("01F3.pdf")!!.itemId!!))
    }

    @Test
    fun anEmptyFolderIsMirroredBinnedAndRestored() {
        sql.exec("INSERT INTO folders (id, workspace_id, name) VALUES ('D4', 'WS', 'Empty')")
        catchUp()
        val id = remote.idOf("Files/Empty")!!
        sql.exec("DELETE FROM folders WHERE id = 'D4'")
        putInBin("B4", "folder", "D4", BinPayload(folders = listOf(BinFolder("D4", "WS", null, "Empty"))))
        catchUp()
        assertEquals("Files/Recycle bin/Empty", remote.pathOf(id))
        sql.exec("DELETE FROM trash_items WHERE id = 'B4'")
        sql.exec("INSERT INTO folders (id, workspace_id, name) VALUES ('D4', 'WS', 'Empty')")
        catchUp()
        assertEquals("Files/Empty", remote.pathOf(id))
    }

    @Test
    fun aBinnedFileKeepsItsFoldersInTheRecycleBinAndComesBackOnRestore() {
        catchUp()
        val id = row("01F3.pdf")!!.itemId!!
        binBill()
        catchUp()
        assertEquals("Files/Recycle bin/Bills/2025/bill (F3).pdf", remote.pathOf(id))
        assertEquals("Files/Recycle bin/Bills/2025/bill (F3).pdf", row("01F3.pdf")!!.remotePath)
        assertEquals(MirrorState.MIRRORED, row("01F3.pdf")!!.state)
        assertTrue(remote.folders().contains("Files/Bills/2025"))
        restoreBill()
        catchUp()
        assertEquals("Files/Bills/2025/bill (F3).pdf", remote.pathOf(id))
        assertEquals("Files/Bills/2025/bill (F3).pdf", row("01F3.pdf")!!.remotePath)
    }

    @Test
    fun emptyingTheBinMovesTheFileToReviewNeededWithItsFolders() {
        catchUp()
        val id = row("01F3.pdf")!!.itemId!!
        binBill()
        catchUp()
        sql.exec("DELETE FROM trash_items")
        val result = catchUp()
        assertEquals("Files/reviewneeded/Bills/2025/bill (F3).pdf", remote.pathOf(id))
        assertNull(row("01F3.pdf"))
        assertEquals(2, result.movedToReview)
    }

    @Test
    fun aNameAlreadyInReviewNeededGetsANumber() {
        catchUp()
        val id = row("01F3.pdf")!!.itemId!!
        remote.addFile("Files/reviewneeded/Bills/2025/bill (F3).pdf", "older".toByteArray())
        binBill()
        catchUp()
        sql.exec("DELETE FROM trash_items")
        catchUp()
        assertEquals("Files/reviewneeded/Bills/2025/bill (F3) (2).pdf", remote.pathOf(id))
        assertTrue(remote.paths().contains("Files/reviewneeded/Bills/2025/bill (F3).pdf"))
    }

    @Test
    fun deletingWithTheBinOffMovesTheFolderAndItsFileToReviewNeeded() {
        catchUp()
        val folder = remote.idOf("Files/Bills/2025")!!
        val file = row("01F3.pdf")!!.itemId!!
        sql.exec("DELETE FROM file_rows WHERE id = 'F3'")
        sql.exec("DELETE FROM folders WHERE id = 'D3'")
        catchUp()
        assertEquals("Files/reviewneeded/Bills/2025", remote.pathOf(folder))
        assertEquals("Files/reviewneeded/Bills/2025/bill (F3).pdf", remote.pathOf(file))
        assertTrue(remote.folders().contains("Files/Bills"))
        assertNull(row("01F3.pdf"))
    }

    @Test
    fun anEmptyFolderDeletedWithTheBinOffMovesToReviewNeeded() {
        sql.exec("INSERT INTO folders (id, workspace_id, name) VALUES ('D4', 'WS', 'Empty')")
        catchUp()
        val id = remote.idOf("Files/Empty")!!
        sql.exec("DELETE FROM folders WHERE id = 'D4'")
        catchUp()
        assertEquals("Files/reviewneeded/Empty", remote.pathOf(id))
    }

    @Test
    fun aFolderBinnedAfterItsFilesJoinsItsRecycleBinCopy() {
        catchUp()
        val file = row("01F3.pdf")!!.itemId!!
        val live = remote.idOf("Files/Bills/2025")!!
        binBill()
        catchUp()
        val binned = remote.idOf("Files/Recycle bin/Bills/2025")!!
        sql.exec("DELETE FROM folders WHERE id = 'D3'")
        putInBin("B3", "folder", "D3", BinPayload(folders = listOf(BinFolder("D3", "WS", "D2", "2025"))))
        catchUp()
        assertEquals("Files/Recycle bin/Bills/2025/bill (F3).pdf", remote.pathOf(file))
        assertEquals("Files/Recycle bin/Bills/2025", remote.pathOf(binned))
        assertFalse(remote.folders().contains("Files/Bills/2025"))
        assertEquals("Files/reviewneeded/Bills/2025", remote.pathOf(live))
        sql.exec("DELETE FROM trash_items WHERE id = 'B3'")
        sql.exec("INSERT INTO folders (id, workspace_id, name, parent_id) VALUES ('D3', 'WS', '2025', 'D2')")
        catchUp()
        assertEquals("Files/Bills/2025", remote.pathOf(binned))
        assertEquals("Files/Recycle bin/Bills/2025/bill (F3).pdf", remote.pathOf(file))
        assertEquals("Files/Recycle bin/Bills/2025/bill (F3).pdf", row("01F3.pdf")!!.remotePath)
    }

    @Test
    fun whatABackupRestoreLeftBehindMovesToReviewNeeded() {
        catchUp()
        val bills = remote.idOf("Files/Bills")!!
        val file = row("01F3.pdf")!!.itemId!!
        val manual = row("01F2.txt")!!.itemId!!
        val stray = remote.addFile("Files/Docs/stray.txt", "x".toByteArray())
        sql.exec("DELETE FROM file_rows WHERE id = 'F3'")
        sql.exec("DELETE FROM folders WHERE id IN ('D2', 'D3')")
        sql.exec("DELETE FROM file_mirrors WHERE local_path = '01F3.pdf'")
        File(filesDir, "01F3.pdf").delete()
        runBlocking { rig.store.remove(SettingKeys.MirrorReviewSweptAt.name) }
        catchUp()
        assertEquals("Files/reviewneeded/Bills", remote.pathOf(bills))
        assertEquals("Files/reviewneeded/Bills/2025/bill (F3).pdf", remote.pathOf(file))
        assertEquals("Files/reviewneeded/Docs/stray.txt", remote.pathOf(stray))
        assertEquals("Files/Docs/manual (F2).txt", remote.pathOf(manual))
        assertNotNull(runBlocking { rig.store.get(SettingKeys.MirrorReviewSweptAt.name) })
    }

    @Test
    fun aBinnedFileNeverUploadedGoesStraightToItsRecycleBinFolder() {
        File(filesDir, "01F5.pdf").writeBytes(byteArrayOf(5))
        File(filesDir, "01F6.txt").writeBytes(byteArrayOf(6))
        putInBin(
            "B5",
            "file",
            "F5",
            BinPayload(files = listOf(BinFile("F5", "WS", "workspace", "WS", "scan.pdf", "application/pdf", 1, folderId = "D3", localPath = "01F5.pdf"))),
        )
        putInBin(
            "B6",
            "folder",
            "D6",
            BinPayload(
                folders = listOf(BinFolder("D6", "WS", null, "Old")),
                files = listOf(BinFile("F6", "WS", "workspace", "WS", "note.txt", "text/plain", 1, folderId = "D6", localPath = "01F6.txt")),
            ),
        )
        catchUp()
        assertTrue(remote.puts.contains("Files/Recycle bin/Bills/2025/scan (F5).pdf"))
        assertTrue(remote.puts.contains("Files/Recycle bin/Old/note (F6).txt"))
        assertEquals(MirrorState.MIRRORED, row("01F6.txt")!!.state)
    }

    @Test
    fun flatRecycleBinCopiesFromOlderVersionsMoveIntoTheirFolders() {
        catchUp()
        val id = row("01F3.pdf")!!.itemId!!
        binBill()
        remote.place(id, "Files/Recycle bin/01F3.pdf")
        sql.exec("UPDATE file_mirrors SET remote_path = 'Files/Recycle bin/01F3.pdf' WHERE local_path = '01F3.pdf'")
        val old = remote.addFile("Files/reviewneeded/old (F9).txt", "x".toByteArray())
        runBlocking { rig.store.remove(SettingKeys.MirrorFolders.name) }
        catchUp()
        assertEquals("Files/Recycle bin/Bills/2025/bill (F3).pdf", remote.pathOf(id))
        assertEquals("Files/reviewneeded/old (F9).txt", remote.pathOf(old))
        assertEquals("Files/Docs/manual (F2).txt", remote.pathOf(row("01F2.txt")!!.itemId!!))
    }

    @Test
    fun renamingATaskMovesItsFolderOnce() {
        catchUp()
        val folder = remote.idOf("Files/Tasks/Pay rent")!!
        val before = remote.moves
        sql.exec("UPDATE tasks SET title = 'Rent' WHERE id = 'T1'")
        catchUp()
        assertEquals(1, remote.moves - before)
        assertEquals("Files/Tasks/Rent", remote.pathOf(folder))
        assertEquals("Files/Tasks/Rent/receipt (F1).png", row("01F1.png")!!.remotePath)
    }

    @Test
    fun binningATaskMovesItsFolderIntoTheRecycleBin() {
        catchUp()
        val folder = remote.idOf("Files/Tasks/Pay rent")!!
        val file = row("01F1.png")!!.itemId!!
        sql.exec("DELETE FROM file_rows WHERE id = 'F1'")
        sql.exec("DELETE FROM tasks WHERE id = 'T1'")
        putInBin(
            "B7",
            "task",
            "T1",
            BinPayload(
                tasks = listOf(BinTask("T1", "WS", "Pay rent")),
                files = listOf(BinFile("F1", "WS", "task", "T1", "receipt.png", "image/png", 4, localPath = "01F1.png")),
            ),
        )
        catchUp()
        assertEquals("Files/Recycle bin/Tasks/Pay rent", remote.pathOf(folder))
        assertEquals("Files/Recycle bin/Tasks/Pay rent/receipt (F1).png", remote.pathOf(file))
    }

    @Test
    fun aStopBetweenMovesLeavesTheRestForTheNextRun() {
        catchUp()
        val docs = remote.idOf("Files/Docs")!!
        val bills = remote.idOf("Files/Bills")!!
        val before = remote.moves
        sql.exec("UPDATE folders SET name = 'Guides' WHERE id = 'D1'")
        sql.exec("UPDATE folders SET name = 'Invoices' WHERE id = 'D2'")
        val first = catchUp(mirror { remote.moves > before })
        assertEquals(1, first.moved)
        assertEquals(1, remote.moves - before)
        catchUp()
        assertEquals("Files/Guides", remote.pathOf(docs))
        assertEquals("Files/Invoices", remote.pathOf(bills))
        assertEquals("Files/Invoices/2025/bill (F3).pdf", row("01F3.pdf")!!.remotePath)
        assertEquals(2, remote.moves - before)
    }

    @Test
    fun nothingIsEverDeletedInOneDrive() {
        catchUp()
        sql.exec("INSERT INTO folders (id, workspace_id, name) VALUES ('D4', 'WS', 'Empty')")
        catchUp()
        val known = listOf(
            row("01F1.png")!!.itemId!!,
            row("01F2.txt")!!.itemId!!,
            row("01F3.pdf")!!.itemId!!,
            remote.idOf("Files/Bills")!!,
            remote.idOf("Files/Bills/2025")!!,
            remote.idOf("Files/Empty")!!,
        )
        sql.exec("UPDATE folders SET name = 'Invoices' WHERE id = 'D2'")
        binBill()
        catchUp()
        sql.exec("DELETE FROM trash_items")
        sql.exec("DELETE FROM folders WHERE id IN ('D3', 'D4')")
        sql.exec("DELETE FROM file_rows WHERE id = 'F2'")
        catchUp()
        for (id in known) assertTrue(id, remote.exists(id))
        assertTrue(remote.deletes.isEmpty())
    }

    @Test
    fun aNeededFileMissingInOneDriveIsKeptAndMovedBackWhenItTurnsUpAgain() {
        catchUp()
        val id = row("01F2.txt")!!.itemId!!
        sql.exec("UPDATE file_mirrors SET state = 'remote-missing' WHERE local_path = '01F2.txt'")
        File(filesDir, "01F2.txt").delete()
        remote.place(id, "Files/reviewneeded/Docs/manual (F2).txt")
        catchUp()
        assertEquals("Files/Docs/manual (F2).txt", remote.pathOf(id))
        assertEquals(MirrorState.MIRRORED, row("01F2.txt")!!.state)
        assertEquals("Files/Docs/manual (F2).txt", row("01F2.txt")!!.remotePath)
    }

    @Test
    fun aRestoredFileWhoseCopyWaitsInReviewNeededIsFoundAndMovedBack() {
        catchUp()
        val id = row("01F2.txt")!!.itemId!!
        remote.place(id, "Files/reviewneeded/Docs/manual (F2).txt")
        runBlocking { rig.store.remove(SettingKeys.MirrorReviewSweptAt.name) }
        catchUp()
        assertNotNull(row("01F2.txt"))
        assertEquals("Files/reviewneeded/Docs/manual (F2).txt", row("01F2.txt")!!.remotePath)
        catchUp()
        assertEquals("Files/Docs/manual (F2).txt", remote.pathOf(id))
        assertEquals("Files/Docs/manual (F2).txt", row("01F2.txt")!!.remotePath)
    }

    private fun addEmptySubtree() {
        sql.exec("INSERT INTO folders (id, workspace_id, name) VALUES ('D4', 'WS', 'Box')")
        sql.exec("INSERT INTO folders (id, workspace_id, name, parent_id) VALUES ('D5', 'WS', 'Inner', 'D4')")
    }

    private fun dropEmptySubtree() {
        sql.exec("DELETE FROM folders WHERE id IN ('D4', 'D5')")
    }

    @Test
    fun emptyLeftoversInTheRecycleBinMoveToReviewNeededOnce() {
        catchUp()
        val id = row("01F3.pdf")!!.itemId!!
        binBill()
        catchUp()
        restoreBill()
        catchUp()
        assertEquals("Files/Bills/2025/bill (F3).pdf", remote.pathOf(id))
        assertTrue(remote.folders().none { it.startsWith("Files/Recycle bin/") })
        assertTrue(remote.folders().contains("Files/reviewneeded/Bills/2025"))
        assertNull(runBlocking { rig.store.get(SettingKeys.MirrorBinLeftovers.name) })
        val lists = remote.lists
        val moves = remote.moves
        catchUp()
        assertEquals(lists, remote.lists)
        assertEquals(moves, remote.moves)
    }

    @Test
    fun anEmptiedRecycleBinFolderFollowsItsFilesToReviewNeeded() {
        catchUp()
        binBill()
        catchUp()
        sql.exec("DELETE FROM trash_items")
        catchUp()
        assertTrue(remote.folders().none { it.startsWith("Files/Recycle bin/") })
        assertTrue(remote.paths().contains("Files/reviewneeded/Bills/2025/bill (F3).pdf"))
    }

    @Test
    fun aRecycleBinFolderStaysWhileAnotherBinnedFileStillNeedsIt() {
        sql.exec("UPDATE file_rows SET folder_id = 'D3' WHERE id = 'F2'")
        catchUp()
        val manual = row("01F2.txt")!!.itemId!!
        binBill()
        sql.exec("DELETE FROM file_rows WHERE id = 'F2'")
        val f2 = BinFile("F2", "WS", "workspace", "WS", "manual.txt", "text/plain", 6, folderId = "D3", localPath = "01F2.txt")
        putInBin("B2", "file", "F2", BinPayload(files = listOf(f2)))
        catchUp()
        sql.exec("DELETE FROM trash_items WHERE id = 'B2'")
        sql.exec(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, folder_id, local_path) VALUES " +
                "('F2', 'WS', 'workspace', 'WS', 'manual.txt', 'text/plain', 6, 'D3', '01F2.txt')",
        )
        catchUp()
        assertEquals("Files/Bills/2025/manual (F2).txt", remote.pathOf(manual))
        assertTrue(remote.paths().contains("Files/Recycle bin/Bills/2025/bill (F3).pdf"))
        assertFalse(remote.folders().any { it.startsWith("Files/reviewneeded") })
    }

    @Test
    fun aWholeEmptyFolderTreeGoesToTheBinAsOneMoveAndOnToReviewNeeded() {
        addEmptySubtree()
        catchUp()
        val box = remote.idOf("Files/Box")!!
        val inner = remote.idOf("Files/Box/Inner")!!
        dropEmptySubtree()
        putInBin("B8", "folder", "D4", BinPayload(folders = listOf(BinFolder("D4", "WS", null, "Box"), BinFolder("D5", "WS", "D4", "Inner"))))
        val before = remote.moves
        catchUp()
        assertEquals(1, remote.moves - before)
        assertEquals("Files/Recycle bin/Box", remote.pathOf(box))
        assertEquals("Files/Recycle bin/Box/Inner", remote.pathOf(inner))
        sql.exec("DELETE FROM trash_items")
        catchUp()
        assertEquals(2, remote.moves - before)
        assertEquals("Files/reviewneeded/Box", remote.pathOf(box))
        assertEquals("Files/reviewneeded/Box/Inner", remote.pathOf(inner))
    }

    @Test
    fun aWholeEmptyFolderTreeDeletedWithTheBinOffMovesToReviewNeeded() {
        addEmptySubtree()
        catchUp()
        val box = remote.idOf("Files/Box")!!
        val inner = remote.idOf("Files/Box/Inner")!!
        dropEmptySubtree()
        catchUp()
        assertEquals("Files/reviewneeded/Box", remote.pathOf(box))
        assertEquals("Files/reviewneeded/Box/Inner", remote.pathOf(inner))
    }

    @Test
    fun aMissingFileFoundFlatInTheRecycleBinMovesBackToItsPlace() {
        catchUp()
        val old = row("01F2.txt")!!.itemId!!
        remote.drop(old)
        sql.exec("UPDATE file_mirrors SET state = 'remote-missing' WHERE local_path = '01F2.txt'")
        File(filesDir, "01F2.txt").delete()
        val copy = remote.addFile("Files/Recycle bin/01F2.txt", "manual".toByteArray())
        catchUp()
        assertEquals("Files/Docs/manual (F2).txt", remote.pathOf(copy))
        val r = row("01F2.txt")!!
        assertEquals(copy, r.itemId)
        assertEquals(MirrorState.MIRRORED, r.state)
        assertEquals("Files/Docs/manual (F2).txt", r.remotePath)
    }

    @Test
    fun aMissingFileFoundUnderAClashNameInReviewNeededMovesBack() {
        catchUp()
        val old = row("01F2.txt")!!.itemId!!
        remote.drop(old)
        sql.exec("UPDATE file_mirrors SET state = 'remote-missing' WHERE local_path = '01F2.txt'")
        File(filesDir, "01F2.txt").delete()
        remote.addFile("Files/reviewneeded/Docs/manual (F2).txt", "other text".toByteArray())
        val copy = remote.addFile("Files/reviewneeded/Docs/manual (F2) (2).txt", "manual".toByteArray())
        catchUp()
        assertEquals("Files/Docs/manual (F2).txt", remote.pathOf(copy))
        assertEquals(copy, row("01F2.txt")!!.itemId)
        assertTrue(remote.paths().contains("Files/reviewneeded/Docs/manual (F2).txt"))
    }
}
