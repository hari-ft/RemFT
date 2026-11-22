package com.ftapps.remft.ui.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.R
import com.ftapps.remft.data.onedrive.OneDriveErrors
import com.ftapps.remft.data.onedrive.OneDriveException
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

@RunWith(AndroidJUnit4::class)
class FileOpenerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var rig: TestFiles? = null

    @After
    fun tearDown() {
        rig?.close()
    }

    private fun open(fetch: suspend (TestFiles, String) -> File?, fetchable: Boolean = true): TestFiles {
        lateinit var made: TestFiles
        made = TestFiles(fetchMissing = { fetch(made, it) }, canFetch = { fetchable })
        rig = made
        return made
    }

    private fun notHere(r: TestFiles, name: String = "scan.pdf"): String {
        val id = r.add(name, mime = "application/pdf")
        File(r.dir, r.query("SELECT local_path FROM file_rows WHERE id = '$id'").single()["local_path"]!!).delete()
        return id
    }

    private fun attempt(r: TestFiles, id: String): Pair<OpenOutcome, Int> = runBlocking {
        var fetching = 0
        val row = r.files.file(id)!!
        FileOpener.open(context, r.environment, row) { fetching++ } to fetching
    }

    private fun failingWith(code: String): suspend (TestFiles, String) -> File? = { _, _ -> throw OneDriveException(code) }

    @Test
    fun aFileOnlyInOneDriveIsFetchedWithANotice() {
        var asked: String? = null
        val r = open({ files, id ->
            asked = id
            val row = files.files.file(id)!!
            File(files.dir, row.localPath!!).apply { writeText("fetched") }
        })
        val id = notHere(r)
        val row = runBlocking { r.files.file(id)!! }
        assertTrue(runBlocking { r.attachments.needsFetch(row) })
        val copy = runBlocking { FileOpener.prepare(context, r.environment, row) }
        assertEquals(id, asked)
        assertEquals("fetched", copy!!.readText())
        assertEquals("scan.pdf", copy.name)
        assertFalse(runBlocking { r.attachments.needsFetch(row) })
    }

    @Test
    fun theNoticeIsShownOnlyWhenADownloadIsNeeded() {
        val r = open({ files, id -> File(files.dir, files.files.file(id)!!.localPath!!).apply { writeText("fetched") } })
        val here = r.add("here.txt")
        assertEquals(0, attempt(r, here).second)
        assertEquals(1, attempt(r, notHere(r)).second)
    }

    @Test
    fun withoutOneDriveTheUserIsToldToConnectIt() {
        val r = open(failingWith(OneDriveErrors.MIRROR_NEEDS_ONEDRIVE))
        val (outcome, fetching) = attempt(r, notHere(r))
        assertEquals(OpenOutcome.NeedsOneDrive, outcome)
        assertEquals(1, fetching)
        assertEquals(R.string.mirror_fetchNeedsOneDrive, FileOpener.messageOf(outcome))
    }

    @Test
    fun aSignedOutAccountAlsoNeedsOneDrive() {
        val r = open(failingWith(OneDriveErrors.SIGNED_OUT))
        assertEquals(OpenOutcome.NeedsOneDrive, attempt(r, notHere(r)).first)
    }

    @Test
    fun offlineIsReportedAsOffline() {
        val r = open(failingWith(OneDriveErrors.OFFLINE))
        val outcome = attempt(r, notHere(r)).first
        assertEquals(OpenOutcome.Offline, outcome)
        assertEquals(R.string.mirror_fetchOffline, FileOpener.messageOf(outcome))
    }

    @Test
    fun anyOtherDownloadProblemIsAFetchFailure() {
        val r = open(failingWith(OneDriveErrors.MIRROR_DAMAGED))
        val outcome = attempt(r, notHere(r)).first
        assertEquals(OpenOutcome.FetchFailed, outcome)
        assertEquals(R.string.mirror_fetchFailed, FileOpener.messageOf(outcome))
    }

    @Test
    fun aFileNeitherHereNorInOneDriveIsMissing() {
        val r = open({ _, _ -> null }, fetchable = false)
        val (outcome, fetching) = attempt(r, notHere(r))
        assertEquals(OpenOutcome.Missing, outcome)
        assertEquals(0, fetching)
        assertEquals(R.string.error_FILE_MISSING, FileOpener.messageOf(outcome))
    }

    @Test
    fun everyFailureHasAMessage() {
        assertTrue(OpenOutcome.entries.filter { it != OpenOutcome.Opened }.all { FileOpener.messageOf(it) != null })
    }

    @Test
    fun openingAnotherFileKeepsTheRecentCopyAndDropsOnlyStaleOnes() {
        val r = open({ _, _ -> null })
        val first = runBlocking { FileOpener.prepare(context, r.environment, r.files.file(r.add("first.txt"))!!) }!!
        val stale = runBlocking { FileOpener.prepare(context, r.environment, r.files.file(r.add("stale.txt"))!!) }!!
        val old = System.currentTimeMillis() - TempCopies.MAX_AGE_MS - 60_000
        stale.setLastModified(old)
        stale.parentFile!!.setLastModified(old)
        val second = runBlocking { FileOpener.prepare(context, r.environment, r.files.file(r.add("second.txt"))!!) }!!
        assertTrue(first.isFile)
        assertTrue(second.isFile)
        assertFalse(stale.exists())
    }
}
