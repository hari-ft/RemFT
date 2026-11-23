package com.ftapps.remft.data.bin

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.data.tasks.PendingDeletes
import com.ftapps.remft.domain.repeat.RepeatFreq
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.testing.UnreadableSettings
import com.ftapps.remft.ui.repeat.OccurrenceDeleter
import com.ftapps.remft.ui.tasks.TasksEnvironment
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BinSettingDeletesTest {
    private var opened: TestTasks? = null
    private val due: Instant = Instant.parse("2026-11-21T04:30:00Z")

    @After
    fun tearDown() {
        opened?.close()
    }

    private fun open(unreadable: Boolean = false, binOn: Boolean = true): TestTasks = runBlocking {
        TestTasks(unreadableBinSetting = unreadable).also {
            opened = it
            if (!binOn) it.settings.set("remft_bin_enabled", "false")
        }
    }

    private fun TestTasks.environment(): TasksEnvironment =
        TasksEnvironment(repository, settings, PendingDeletes(scope, window = WINDOW_MS, settle = 0), series = series)

    private fun TestTasks.count(sql: String): Int = query(sql).size

    private fun TestTasks.table(name: String): List<Map<String, String?>> = query("SELECT * FROM $name ORDER BY 1, 2")

    private fun TestTasks.snapshot(): Map<String, List<Map<String, String?>>> =
        listOf("tasks", "task_series", "series_skips", "checklist_items", "reminders", "file_rows", "notes", "trash_items")
            .associateWith { table(it) }

    private suspend fun TestTasks.attach(targetType: String, targetId: String, fileId: String): File {
        attachments.mkdirs()
        val bytes = File(attachments, "$fileId.txt").apply { writeText("bytes of $fileId") }
        holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, local_path) VALUES " +
                "('$fileId', '${repository.workspaceId()}', '$targetType', '$targetId', '$fileId.txt', 'text/plain', 1, '$fileId.txt')",
        )
        return bytes
    }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private class Series(val seriesId: String, val rows: List<Map<String, String?>>, val target: Map<String, String?>, val bytes: File)

    private fun TestTasks.weeklySeries(): Series = runBlocking {
        val id = repository.create(TaskDraft(title = "Water the plants", dueAt = due, remindAt = due.minusSeconds(3_600)))
        val seriesId = series.start(id, RepeatRule(freq = RepeatFreq.Weekly), due)!!
        repository.setCompleted(id, true)
        val rows = query("SELECT * FROM tasks WHERE series_id = '$seriesId' ORDER BY occurrence_date")
        val target = rows[3]
        repository.addChecklistItem(rows[5]["id"]!!, "Buy soil")
        val bytes = attach("task", rows[6]["id"]!!, "F-SERIES")
        Series(seriesId, rows, target, bytes)
    }

    private suspend fun TestTasks.deleteThisAndFuture(env: TasksEnvironment, s: Series) {
        val deleter = OccurrenceDeleter(env, scope)
        val task = repository.detail(s.target["id"]!!)!!.task
        deleter.request(task, "deleted", deleteThis = { error("only this one was not chosen") })
        waitFor("the scope question") { deleter.request != null }
        deleter.answer(thisOnly = false)
    }

    private fun TestTasks.futureCount(s: Series): Int =
        count("SELECT id FROM tasks WHERE occurrence_date >= '${s.target["occurrence_date"]}'")

    @Test
    fun thisAndFutureWithTheBinOffIsGoneForGoodOnlyAfterTheWindow() = runBlocking {
        val t = open(binOn = false)
        val s = t.weeklySeries()
        val env = t.environment()
        t.deleteThisAndFuture(env, s)
        assertTrue(s.target["id"] in env.pendingDeletes.ids.value)
        assertTrue(t.futureCount(s) > 0)
        assertTrue(s.bytes.isFile)

        waitFor("the commit") { t.futureCount(s) == 0 }
        waitFor("the bytes to go") { !s.bytes.exists() }
        assertEquals(0, t.count("SELECT id FROM trash_items"))
        assertEquals(0, t.count("SELECT id FROM task_series"))
        assertEquals(0, t.count("SELECT series_id FROM series_skips"))
        assertEquals(0, t.count("SELECT id FROM checklist_items"))
        assertEquals(0, t.count("SELECT id FROM file_rows"))
        assertEquals(0, t.count("SELECT id FROM reminders WHERE task_id NOT IN (SELECT id FROM tasks)"))
        val kept = t.query("SELECT * FROM tasks ORDER BY occurrence_date")
        assertEquals(s.rows.take(3).map { it["id"] }, kept.map { it["id"] })
        assertEquals("completed", kept.first()["status"])
    }

    @Test
    fun thisAndFutureWithTheBinOffIsFullyRestoredByUndoInsideTheWindow() = runBlocking {
        val t = open(binOn = false)
        val s = t.weeklySeries()
        val before = t.snapshot()
        val env = t.environment()
        t.deleteThisAndFuture(env, s)
        val request = env.undo.current.value!!
        assertTrue(env.undo.resolve(request, undo = true))
        delay(WINDOW_MS * 3)
        assertEquals(before, t.snapshot())
        assertTrue(s.bytes.isFile)
        assertTrue(env.pendingDeletes.ids.value.isEmpty())
    }

    @Test
    fun thisAndFutureWithTheBinOnGoesToTheBinWithTheSeries() = runBlocking {
        val t = open()
        val s = t.weeklySeries()
        t.deleteThisAndFuture(t.environment(), s)
        waitFor("the commit") { t.futureCount(s) == 0 }
        val entry = t.query("SELECT * FROM trash_items WHERE entity_id = '${s.target["id"]}'").single()
        val payload = Json.parseToJsonElement(entry["payload"]!!).jsonObject
        assertEquals(1, payload.getValue("series").jsonArray.size)
        assertTrue(s.bytes.isFile)
    }

    @Test
    fun thisAndFutureWithUnreadableSettingsGoesToTheBin() = runBlocking {
        val t = open(unreadable = true)
        val s = t.weeklySeries()
        t.deleteThisAndFuture(t.environment(), s)
        waitFor("the commit") { t.futureCount(s) == 0 }
        assertEquals(1, t.count("SELECT id FROM trash_items WHERE entity_id = '${s.target["id"]}'"))
        assertTrue(s.bytes.isFile)
    }

    private fun TestTasks.seriesWithChangedOccurrences(): Triple<String, String, File> = runBlocking {
        val id = repository.create(TaskDraft(title = "Water the plants", dueAt = due))
        val seriesId = series.start(id, RepeatRule(freq = RepeatFreq.Weekly), due)!!
        val rows = query("SELECT id FROM tasks WHERE series_id = '$seriesId' ORDER BY occurrence_date")
        repository.update(rows[2]["id"]!!, TaskPatch(title = "Water the plants and the herbs"))
        val bytes = attach("task", rows[4]["id"]!!, "F-STOP")
        Triple(id, seriesId, bytes)
    }

    @Test
    fun stopRepeatingWithTheBinOffDeletesChangedOccurrencesForGood() = runBlocking {
        val t = open(binOn = false)
        val (id, seriesId, bytes) = t.seriesWithChangedOccurrences()
        t.series.stop(seriesId, keepTaskId = id)
        val kept = t.query("SELECT * FROM tasks").single()
        assertEquals(id, kept["id"])
        assertNull(kept["series_id"])
        assertEquals(0, t.count("SELECT id FROM trash_items"))
        assertEquals(0, t.count("SELECT id FROM task_series"))
        assertEquals(0, t.count("SELECT id FROM file_rows"))
        assertFalse(bytes.exists())
    }

    @Test
    fun stopRepeatingWithTheBinOnOrUnreadableSettingsBinsChangedOccurrences() = runBlocking {
        for (unreadable in listOf(false, true)) {
            opened?.close()
            val t = open(unreadable = unreadable)
            val (id, seriesId, bytes) = t.seriesWithChangedOccurrences()
            t.series.stop(seriesId, keepTaskId = id)
            assertEquals(2, t.count("SELECT id FROM trash_items"))
            assertTrue(bytes.isFile)
            opened = null
            t.close()
        }
    }

    private suspend fun TestTasks.taskWithFile(): Pair<String, File> {
        val id = repository.create(TaskDraft(title = "Call the plumber", dueAt = due, remindAt = due))
        repository.addChecklistItem(id, "Find the number")
        return id to attach("task", id, "F-TASK")
    }

    @Test
    fun aTaskDeleteWithTheBinOffKeepsBytesUntilTheWindowEnds() = runBlocking {
        val t = open(binOn = false)
        val (id, bytes) = t.taskWithFile()
        val env = t.environment()
        env.undo.post(id, "deleted") { t.repository.delete(id) }
        assertTrue(bytes.isFile)
        assertEquals(1, t.count("SELECT id FROM tasks"))
        waitFor("the commit") { t.count("SELECT id FROM tasks") == 0 }
        waitFor("the bytes") { !bytes.exists() }
        assertEquals(0, t.count("SELECT id FROM trash_items"))
        assertEquals(0, t.count("SELECT id FROM checklist_items"))
        assertEquals(0, t.count("SELECT id FROM reminders"))
        assertEquals(0, t.count("SELECT id FROM file_rows"))
    }

    @Test
    fun aTaskDeleteWithTheBinOffIsFullyRestoredByUndo() = runBlocking {
        val t = open(binOn = false)
        val (id, bytes) = t.taskWithFile()
        val before = t.snapshot()
        val env = t.environment()
        env.undo.post(id, "deleted") { t.repository.delete(id) }
        assertTrue(env.undo.resolve(env.undo.current.value!!, undo = true))
        delay(WINDOW_MS * 3)
        assertEquals(before, t.snapshot())
        assertTrue(bytes.isFile)
    }

    @Test
    fun aTaskDeleteWithTheBinOnOrUnreadableSettingsGoesToTheBin() = runBlocking {
        for (unreadable in listOf(false, true)) {
            opened?.close()
            val t = open(unreadable = unreadable)
            val (id, bytes) = t.taskWithFile()
            t.environment().undo.post(id, "deleted") { t.repository.delete(id) }
            waitFor("the commit") { t.count("SELECT id FROM tasks") == 0 }
            assertEquals(1, t.count("SELECT id FROM trash_items WHERE entity_id = '$id'"))
            assertTrue(bytes.isFile)
            opened = null
            t.close()
        }
    }

    private fun TestTasks.notes(unreadable: Boolean = false): NotesRepository = NotesRepository(
        holder = holder,
        workspaceId = { repository.workspaceId() },
        settings = if (unreadable) UnreadableSettings.store() else settings,
        attachmentsDir = attachments,
        untitled = { "Untitled" },
    )

    @Test
    fun aNoteDeleteWithTheBinOffKeepsBytesUntilTheWindowEndsAndUndoRestores() = runBlocking {
        val t = open(binOn = false)
        val notes = t.notes()
        val id = notes.create("Shopping", "Milk")
        val bytes = t.attach("note", id, "F-NOTE")
        val before = t.snapshot()
        val env = t.environment()
        env.undo.post(id, "deleted") { notes.delete(id) }
        assertTrue(env.undo.resolve(env.undo.current.value!!, undo = true))
        delay(WINDOW_MS * 3)
        assertEquals(before, t.snapshot())

        env.undo.post(id, "deleted") { notes.delete(id) }
        assertTrue(bytes.isFile)
        waitFor("the commit") { t.count("SELECT id FROM notes") == 0 }
        waitFor("the bytes") { !bytes.exists() }
        assertEquals(0, t.count("SELECT id FROM trash_items"))
        assertEquals(0, t.count("SELECT id FROM file_rows"))
    }

    @Test
    fun aNoteDeleteWithUnreadableSettingsGoesToTheBin() = runBlocking {
        val t = open()
        val notes = t.notes(unreadable = true)
        val id = notes.create("Shopping", "Milk")
        val bytes = t.attach("note", id, "F-NOTE")
        assertTrue(notes.delete(id))
        assertEquals(1, t.count("SELECT id FROM trash_items WHERE entity_id = '$id'"))
        assertTrue(bytes.isFile)
    }

    @Test
    fun filesAndFoldersTreatUnreadableSettingsAsBinOn() = runBlocking {
        val t = open()
        val store = UnreadableSettings.store()
        assertTrue(store.binEnabled())
        val bin = RecycleBin({ t.holder.db }, store, t.attachments, { t.repository.workspaceId() })
        assertTrue(bin.enabled())
    }

    private companion object {
        const val WINDOW_MS = 300L
    }
}
