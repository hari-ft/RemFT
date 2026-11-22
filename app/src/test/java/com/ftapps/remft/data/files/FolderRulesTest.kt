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

    @Test
    fun deletingAFolderBinsTheWholeSubtreeWithItsFiles() = runBlocking {
        val (top, child) = chain(2)
        val inTop = rig.add("a.txt", folderId = top)
        val inChild = rig.add("b.txt", folderId = child)
        val outside = rig.add("c.txt")
        assertEquals(SubtreeCounts(2, 2), rig.files.subtreeCounts(top))
        rig.files.deleteFolder(top)
        assertTrue(rig.query("SELECT id FROM folders").isEmpty())
        assertEquals(listOf(outside), rig.query("SELECT id FROM file_rows").map { it["id"] })
        val trash = rig.query("SELECT kind, entity_id, title, size_bytes FROM trash_items").single()
        assertEquals("folder", trash["kind"])
        assertEquals(top, trash["entity_id"])
        assertEquals("L0", trash["title"])
        assertTrue(File(rig.dir, "$inTop.txt").exists())
        assertTrue(File(rig.dir, "$inChild.txt").exists())
    }

    @Test
    fun deletingAFolderWithTheBinOffRemovesBytes() = runBlocking {
        rig.settings.set("remft_bin_enabled", "false")
        val top = rig.files.createFolder("Temp", null)
        val file = rig.add("a.txt", folderId = top)
        rig.files.deleteFolder(top)
        assertTrue(rig.query("SELECT id FROM trash_items").isEmpty())
        assertTrue(!File(rig.dir, "$file.txt").exists())
    }
}
