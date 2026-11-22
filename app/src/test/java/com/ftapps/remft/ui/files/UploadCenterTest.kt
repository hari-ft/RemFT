package com.ftapps.remft.ui.files

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.notes.editing.MdEdit
import com.ftapps.remft.testing.TestFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UploadCenterTest {
    @get:Rule
    val compose = createComposeRule()

    private val rig = TestFiles()
    private val screen = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        screen.cancel()
        rig.close()
    }

    private fun gated(name: String, gate: CountDownLatch) = PickedFile(name, 4, "image/png", {
        gate.await(10, TimeUnit.SECONDS)
        "abcd".byteInputStream()
    })

    private fun waitUntil(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(20)
        }
    }

    private fun note(): String = runBlocking { rig.notes.create("Trip", "") }

    @Test
    fun leavingDuringAnUploadStillFinishesItAndKeepsTheLink() = runBlocking {
        val noteId = note()
        val uploads = rig.environment.uploads
        val gate = CountDownLatch(1)
        val waiter = screen.launch { NoteAttachments.store(rig.environment, noteId, gated("map.png", gate)) }
        waitUntil { uploads.jobs.value.isNotEmpty() }
        assertTrue(uploads.unclaimedLinks("note", noteId).first().isEmpty())
        waiter.cancelAndJoin()
        gate.countDown()
        waitUntil { uploads.jobs.value.isEmpty() }
        val fileId = rig.query("SELECT id FROM file_rows").single()["id"]!!
        val pending = withTimeout(10_000) { uploads.unclaimedLinks("note", noteId).first { it.isNotEmpty() } }.single()
        assertEquals(fileId, pending.fileId)
        assertEquals("map.png", pending.name)
        assertEquals(pending, uploads.claim(pending.localId))
        assertNull(uploads.claim(pending.localId))
        assertTrue(uploads.unclaimedLinks("note", noteId).first().isEmpty())
    }

    @Test
    fun aWaitingEditorGetsTheLinkAndNothingIsLeftPending() = runBlocking {
        val noteId = note()
        val uploads = rig.environment.uploads
        val markdown = NoteAttachments.store(rig.environment, noteId, PickedFile("a.png", 4, "image/png", { "abcd".byteInputStream() }))
        val fileId = rig.query("SELECT id FROM file_rows").single()["id"]!!
        assertEquals("![a.png](remft://file/$fileId)", markdown)
        assertTrue(uploads.jobs.value.isEmpty())
        assertTrue(uploads.unclaimedLinks("note", noteId).first().isEmpty())
    }

    @Test
    fun leavingTheFilesTabDuringABatchUploadFinishesEveryFile() = runBlocking {
        val uploads = rig.environment.uploads
        val gate = CountDownLatch(1)
        val target = UploadTarget(rig.workspace, "workspace", rig.workspace)
        val waiter = screen.launch { uploads.uploadAll(target, listOf(gated("one.txt", gate), gated("two.txt", gate))) }
        waitUntil { uploads.jobs.value.size == 2 }
        waiter.cancelAndJoin()
        gate.countDown()
        waitUntil { uploads.jobs.value.isEmpty() }
        assertEquals(setOf("one.txt", "two.txt"), rig.query("SELECT name FROM file_rows").map { it["name"] }.toSet())
        assertTrue(uploads.unclaimedLinks("workspace", rig.workspace).first().isEmpty())
    }

    @Test
    fun anOpenEditorAppendsALinkWhoseUploadOutlivedItsButton() {
        val noteId = note()
        val uploads = rig.environment.uploads
        val gate = CountDownLatch(1)
        val waiter = screen.launch { NoteAttachments.store(rig.environment, noteId, gated("late.png", gate)) }
        waitUntil { uploads.jobs.value.isNotEmpty() }
        runBlocking { waiter.cancelAndJoin() }
        val inserted = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalFilesEnvironment provides rig.environment) {
                PendingNoteLinks(noteId) { inserted += it }
            }
        }
        gate.countDown()
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            inserted.isNotEmpty()
        }
        val fileId = rig.query("SELECT id FROM file_rows").single()["id"]!!
        assertEquals(listOf("![late.png](remft://file/$fileId)"), inserted)
        assertTrue(runBlocking { uploads.unclaimedLinks("note", noteId).first() }.isEmpty())
    }

    @Test
    fun aLateLinkGoesOnItsOwnLineAtTheEnd() {
        assertEquals(MdEdit("x", 1), NoteAttachments.appendLink("", "x"))
        assertEquals(MdEdit("intro\nx", 7), NoteAttachments.appendLink("intro", "x"))
        assertEquals(MdEdit("intro\nx", 7), NoteAttachments.appendLink("intro\n", "x"))
    }
}
