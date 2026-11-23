package com.ftapps.remft.data.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.testing.TestFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FolderRulesTest {
    private val rig = TestFiles()

    @After
    fun tearDown() {
        rig.close()
    }

    private suspend fun refused(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: FolderRuleException) {
            assertEquals(code, e.code)
        }
    }

    private suspend fun chain(depth: Int, parent: String? = null, prefix: String = "L"): List<String> {
        val ids = mutableListOf<String>()
        var cursor = parent
        repeat(depth) { level ->
            cursor = rig.files.createFolder("$prefix$level", cursor)
            ids += cursor!!
        }
        return ids
    }

    @Test
    fun foldersNestAtMostTenLevels() = runBlocking {
        val ids = chain(10)
        assertEquals(10, rig.query("SELECT id FROM folders").size)
        refused(FilesRepository.TOO_DEEP) { rig.files.createFolder("Eleven", ids.last()) }
        assertEquals(ids, rig.files.folderChain(ids.last()).map { it.id })
    }

    @Test
    fun namesAreUniquePerFolderIgnoringCaseAndAccents() = runBlocking {
        val docs = rig.files.createFolder("  Café ", null)
        assertEquals("Café", rig.query("SELECT name FROM folders WHERE id = '$docs'").single()["name"])
        refused(FilesRepository.NAME_TAKEN) { rig.files.createFolder("CAFE", null) }
        refused(FilesRepository.NAME_REQUIRED) { rig.files.createFolder("   ", null) }
        rig.files.createFolder("cafe", docs)
        val other = rig.files.createFolder("Other", null)
        refused(FilesRepository.NAME_TAKEN) { rig.files.renameFolder(other, "café") }
        rig.files.renameFolder(docs, "CAFÉ")
        assertEquals("CAFÉ", rig.query("SELECT name FROM folders WHERE id = '$docs'").single()["name"])
        val inside = rig.files.createFolder("Other", docs)
        refused(FilesRepository.NAME_TAKEN) { rig.files.moveFolder(inside, null) }
    }

    @Test
    fun aFolderCannotMoveIntoItselfOrItsDescendants() = runBlocking {
        val (a, b, c) = chain(3)
        refused(FilesRepository.CYCLE) { rig.files.moveFolder(a, c) }
        refused(FilesRepository.CYCLE) { rig.files.moveFolder(a, a) }
        rig.files.moveFolder(c, null)
        assertEquals(null, rig.query("SELECT parent_id FROM folders WHERE id = '$c'").single()["parent_id"])
        rig.files.moveFolder(b, c)
        assertEquals(c, rig.query("SELECT parent_id FROM folders WHERE id = '$b'").single()["parent_id"])
    }

    @Test
    fun aMoveThatWouldPassTenLevelsIsRefused() = runBlocking {
        val deep = chain(7)
        val subtree = chain(4, prefix = "S")
        refused(FilesRepository.TOO_DEEP) { rig.files.moveFolder(subtree.first(), deep.last()) }
        rig.files.moveFolder(subtree.first(), deep[5])
        assertEquals(deep[5], rig.query("SELECT parent_id FROM folders WHERE id = '${subtree.first()}'").single()["parent_id"])
    }

    private fun snapshot(): List<List<Map<String, String?>>> =
        listOf("folders", "file_rows", "trash_items").map { rig.query("SELECT * FROM $it ORDER BY id") }

    @Test
    fun aFolderHoldingAFileCannotBeDeleted() = runBlocking {
        val top = rig.files.createFolder("Receipts", null)
        val file = rig.add("a.txt", folderId = top)
        assertEquals(FolderContents(0, 1), rig.files.folderContents(top))
        val before = snapshot()
        refused(FilesRepository.NOT_EMPTY) { rig.files.deleteFolder(top) }
        assertEquals(before, snapshot())
        assertTrue(File(rig.dir, "$file.txt").exists())
    }

    @Test
    fun aFolderHoldingOnlyAnEmptySubfolderCannotBeDeleted() = runBlocking {
        val (top, child) = chain(2)
        assertEquals(FolderContents(1, 0), rig.files.folderContents(top))
        val before = snapshot()
        refused(FilesRepository.NOT_EMPTY) { rig.files.deleteFolder(top) }
        assertEquals(before, snapshot())
        rig.settings.set("remft_bin_enabled", "false")
        refused(FilesRepository.NOT_EMPTY) { rig.files.deleteFolder(top) }
        assertEquals(before, snapshot())
        assertEquals(FolderContents(0, 0), rig.files.folderContents(child))
    }

    @Test
    fun anEmptyFolderGoesToTheBin() = runBlocking {
        val (top, child) = chain(2)
        val outside = rig.add("c.txt")
        rig.files.deleteFolder(child)
        assertEquals(listOf(top), rig.query("SELECT id FROM folders").map { it["id"] })
        assertEquals(listOf(outside), rig.query("SELECT id FROM file_rows").map { it["id"] })
        val trash = rig.query("SELECT kind, entity_id, title FROM trash_items").single()
        assertEquals("folder", trash["kind"])
        assertEquals(child, trash["entity_id"])
        assertEquals("L1", trash["title"])
        rig.files.deleteFolder(top)
        assertTrue(rig.query("SELECT id FROM folders").isEmpty())
        assertEquals(2, rig.query("SELECT id FROM trash_items").size)
    }

    @Test
    fun anEmptyFolderWithTheBinOffIsDeletedForGood() = runBlocking {
        rig.settings.set("remft_bin_enabled", "false")
        val top = rig.files.createFolder("Temp", null)
        val outside = rig.add("a.txt")
        rig.files.deleteFolder(top)
        assertTrue(rig.query("SELECT id FROM folders").isEmpty())
        assertTrue(rig.query("SELECT id FROM trash_items").isEmpty())
        assertEquals(listOf(outside), rig.query("SELECT id FROM file_rows").map { it["id"] })
        assertTrue(File(rig.dir, "$outside.txt").exists())
    }
}
