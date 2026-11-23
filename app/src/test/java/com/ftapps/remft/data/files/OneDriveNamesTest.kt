package com.ftapps.remft.data.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.backup.mirror.MirrorPaths
import com.ftapps.remft.domain.backup.mirror.MirrorState
import com.ftapps.remft.testing.TestFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OneDriveNamesTest {
    private val rig = TestFiles()

    @After
    fun tearDown() {
        rig.close()
    }

    private fun names(on: Boolean?) = OneDriveNames(rig.database, MutableStateFlow(on))

    private suspend fun nameOf(names: OneDriveNames, stored: String?): OneDriveName = withTimeout(5_000) { names.watch(stored).first() }

    @Test
    fun aFileWithNoMirrorRowIsNotInOneDriveYet() = runBlocking {
        val stored = rig.storedName(rig.add("invoice.pdf"))
        assertEquals(OneDriveName.NotYet, nameOf(names(true), stored))
        assertEquals(OneDriveName.NotYet, nameOf(names(true), null))
    }

    @Test
    fun anUploadingOrFailedFileWithNoRecordedPathIsNotInOneDriveYet() = runBlocking {
        val stored = rig.storedName(rig.add("invoice.pdf"))
        rig.mirror(stored, MirrorState.UPLOADING, null)
        assertEquals(OneDriveName.NotYet, nameOf(names(true), stored))
        rig.mirror(stored, MirrorState.FAILED, " ")
        assertEquals(OneDriveName.NotYet, nameOf(names(true), stored))
    }

    @Test
    fun anUploadingOrFailedFileWithARecordedPathIsUpdatingThere() = runBlocking {
        val stored = rig.storedName(rig.add("invoice.pdf"))
        rig.mirror(stored, MirrorState.UPLOADING, "Files/invoice.pdf")
        assertEquals(OneDriveName.Updating("Files/invoice.pdf"), nameOf(names(true), stored))
        rig.mirror(stored, MirrorState.FAILED, "Files/invoice.pdf")
        assertEquals(OneDriveName.Updating("Files/invoice.pdf"), nameOf(names(true), stored))
    }

    @Test
    fun aMirroredFileShowsItsSavedPath() = runBlocking {
        val stored = rig.storedName(rig.add("invoice.pdf"))
        rig.mirror(stored, MirrorState.MIRRORED, "Files/Bills/2025/invoice (ab12cd).pdf")
        assertEquals(OneDriveName.Saved("Files/Bills/2025/invoice (ab12cd).pdf"), nameOf(names(true), stored))
        assertEquals(OneDriveName.Saved("Files/Bills/2025/invoice (ab12cd).pdf"), nameOf(names(true), "files/$stored"))
        assertEquals("Files › Bills › 2025 › invoice (ab12cd).pdf", OneDriveNames.display("Files/Bills/2025/invoice (ab12cd).pdf"))
    }

    @Test
    fun aFileMissingInOneDriveKeepsItsPathItemAndLastSeenDate() = runBlocking {
        val stored = rig.storedName(rig.add("invoice.pdf"))
        rig.mirror(stored, MirrorState.REMOTE_MISSING, "Files/invoice.pdf")
        assertEquals(OneDriveName.Missing("Files/invoice.pdf"), nameOf(names(true), stored))
        rig.mirror(stored, MirrorState.MISSING, "Files/invoice.pdf")
        rig.exec("UPDATE file_mirrors SET item_id = 'ITEM-1', mirrored_at = '2026-11-01T10:00:00.000Z', listed_at = '2026-11-12T08:00:00.000Z' WHERE local_path = '$stored'")
        assertEquals(OneDriveName.Missing("Files/invoice.pdf", "ITEM-1", "2026-11-12T08:00:00.000Z"), nameOf(names(true), stored))
        rig.exec("UPDATE file_mirrors SET listed_at = '2026-10-01T08:00:00.000Z' WHERE local_path = '$stored'")
        assertEquals(OneDriveName.Missing("Files/invoice.pdf", "ITEM-1", "2026-11-01T10:00:00.000Z"), nameOf(names(true), stored))
    }

    @Test
    fun aMissingFileWithNoRecordedPathShowsWhereItBelongs() = runBlocking {
        val id = rig.add("invoice.pdf")
        val stored = rig.storedName(id)
        rig.mirror(stored, MirrorState.REMOTE_MISSING, null)
        val expected = MirrorPaths.folderPath(emptyList()) + "/" + MirrorPaths.fileName(id, "invoice.pdf", stored)
        assertEquals(OneDriveName.Missing(expected, expected = true), nameOf(names(true), stored))
    }

    @Test
    fun aMissingFileWithNoRecordedPathAndNoRowIsStillReportedMissing() = runBlocking {
        rig.mirror("ghost.pdf", MirrorState.REMOTE_MISSING, null)
        assertEquals(OneDriveName.Missing(null), nameOf(names(true), "ghost.pdf"))
    }

    @Test
    fun oneDriveOffWinsOverAnyMirrorRow() = runBlocking {
        val stored = rig.storedName(rig.add("invoice.pdf"))
        rig.mirror(stored, MirrorState.MIRRORED, "Files/invoice.pdf")
        assertEquals(OneDriveName.Off, nameOf(names(false), stored))
    }

    @Test
    fun anUnknownConnectionWaitsForTheCheck() = runBlocking {
        val stored = rig.storedName(rig.add("invoice.pdf"))
        val on = MutableStateFlow<Boolean?>(null)
        var checked = false
        val names = OneDriveNames(rig.database, on, checkConnected = { checked = true; on.value = true })
        assertEquals(OneDriveName.NotYet, nameOf(names, stored))
        assertTrue(checked)
    }

    @Test
    fun theNameFollowsTheMirrorLive() = runBlocking {
        val stored = rig.storedName(rig.add("invoice.pdf"))
        val seen = names(true).watch(stored)
        assertEquals(OneDriveName.NotYet, withTimeout(5_000) { seen.first() })
        rig.mirror(stored, MirrorState.MIRRORED, "Files/Bills/invoice.pdf")
        assertEquals(OneDriveName.Saved("Files/Bills/invoice.pdf"), withTimeout(5_000) { seen.first { it is OneDriveName.Saved } })
        rig.mirrorSql.exec("UPDATE file_mirrors SET remote_path = ? WHERE local_path = ?", arrayOf("Files/Moved/invoice.pdf", stored))
        assertEquals(OneDriveName.Saved("Files/Moved/invoice.pdf"), withTimeout(5_000) { seen.first { it == OneDriveName.Saved("Files/Moved/invoice.pdf") } })
    }
}
