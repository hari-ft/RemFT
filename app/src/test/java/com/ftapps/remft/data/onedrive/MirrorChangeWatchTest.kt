package com.ftapps.remft.data.onedrive

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.backup.mirror.MirrorState
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.testing.TestFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MirrorChangeWatchTest {
    private lateinit var t: TestFiles
    private val seen = AtomicInteger()

    @Before
    fun setUp() {
        t = TestFiles()
        assertTrue(MirrorChangeWatch { seen.incrementAndGet() }.watch(t.holder.db))
    }

    @After
    fun tearDown() {
        t.close()
    }

    private fun quiet() {
        var last = -1
        while (last != seen.get()) {
            last = seen.get()
            Thread.sleep(QUIET_MS)
        }
    }

    private fun schedules(action: suspend () -> Unit) {
        quiet()
        val before = seen.get()
        runBlocking { action() }
        val until = System.currentTimeMillis() + WAIT_MS
        while (seen.get() == before && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("no catch-up was scheduled", seen.get() > before)
    }

    private fun trashId(): String = t.query("SELECT id FROM trash_items").first()["id"]!!

    @Test
    fun folderChangesScheduleACatchUp() {
        lateinit var box: String
        lateinit var other: String
        schedules { box = t.files.createFolder("Box", null) }
        schedules { t.files.renameFolder(box, "Crate") }
        schedules { other = t.files.createFolder("Shelf", null) }
        schedules { t.files.moveFolder(box, other) }
        schedules { t.files.deleteFolder(box) }
    }

    @Test
    fun binChangesScheduleACatchUp() {
        lateinit var first: String
        lateinit var second: String
        schedules { first = t.files.createFolder("One", null) }
        schedules { t.files.deleteFolder(first) }
        schedules { t.bin.purge(trashId()) }
        schedules { second = t.files.createFolder("Two", null) }
        schedules { t.files.deleteFolder(second) }
        schedules { t.bin.empty() }
        schedules { t.files.deleteFolder(t.files.createFolder("Three", null)) }
        t.clock.now = t.clock.now.plus(Duration.ofDays(60))
        schedules { assertEquals(1, t.bin.purgeExpired()) }
    }

    @Test
    fun taskAndNoteRenamesScheduleACatchUp() {
        lateinit var task: String
        lateinit var note: String
        schedules { task = t.tasks.repository.create(TaskDraft(title = "Pay rent")) }
        schedules { t.tasks.repository.update(task, TaskPatch(title = "Rent")) }
        schedules { note = t.notes.create("Trip", "body") }
        schedules { t.notes.save(note, "Journey", "body") }
    }

    @Test
    fun theMirrorsOwnBookkeepingDoesNotScheduleACatchUp() {
        quiet()
        val before = seen.get()
        t.mirror("01A.txt", MirrorState.MIRRORED, "Files/a.txt")
        t.mirror("01A.txt", MirrorState.MISSING, "Files/b.txt")
        Thread.sleep(WAIT_MS / 4)
        quiet()
        assertEquals(before, seen.get())
    }

    @Test
    fun watchingTheSameDatabaseTwiceAddsOneObserver() {
        val again = MirrorChangeWatch { }
        assertTrue(again.watch(t.holder.db))
        assertTrue(!again.watch(t.holder.db))
    }

    private companion object {
        const val QUIET_MS = 250L
        const val WAIT_MS = 4000L
    }
}
