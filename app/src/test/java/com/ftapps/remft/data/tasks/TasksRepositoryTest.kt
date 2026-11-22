package com.ftapps.remft.data.tasks

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.Ulid
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.domain.backup.bin.BinPayload
import com.ftapps.remft.domain.tasks.Change
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.testing.TestTasks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TasksRepositoryTest {
    private var tasks: TestTasks? = null

    private fun open(seed: (android.database.sqlite.SQLiteDatabase) -> Unit = {}): TestTasks = TestTasks(seed = seed).also { tasks = it }

    @After
    fun tearDown() {
        tasks?.close()
    }

    @Test
    fun createAppliesStoreRulesAndWritesUtcTextAndFolds() = runBlocking {
        val t = open()
        val id = t.repository.create(TaskDraft(title = "  Pay İstanbul rent  ", isUrgent = true, dueAt = Instant.parse("2026-11-21T03:30:00Z"), status = "inbox"))
        val row = t.query("SELECT * FROM tasks WHERE id = '$id'").single()
        assertTrue(Ulid.isValid(id))
        assertEquals("Pay İstanbul rent", row["title"])
        assertEquals("pay istanbul rent", row["title_fold"])
        assertEquals("open", row["status"])
        assertEquals("none", row["priority"])
        assertEquals("1", row["is_urgent"])
        assertEquals("1", row["requires_acknowledgement"])
        assertEquals("2026-11-21T03:30:00.000Z", row["due_at"])
        assertEquals("2026-11-20T04:30:00.000Z", row["created_at"])
        assertEquals(t.identity.resolve().workspaceId, row["workspace_id"])
    }

    @Test
    fun completingAndReopeningKeepsCompletedAtInStep() = runBlocking {
        val t = open()
        val id = t.repository.create(TaskDraft(title = "Water plants"))
        t.repository.setCompleted(id, true)
        assertEquals("2026-11-20T04:30:00.000Z", t.query("SELECT completed_at FROM tasks").single()["completed_at"])
        t.repository.setCompleted(id, false)
        val row = t.query("SELECT status, completed_at FROM tasks").single()
        assertEquals("open", row["status"])
        assertNull(row["completed_at"])
    }

    @Test
    fun derivedReminderFollowsAMovedDueAndAHandSetOneStays() = runBlocking {
        val t = open()
        val derived = t.repository.create(
            TaskDraft(title = "Derived", dueAt = Instant.parse("2026-11-21T12:00:00Z"), remindAt = Instant.parse("2026-11-21T11:00:00Z")),
        )
        val manual = t.repository.create(
            TaskDraft(title = "Manual", dueAt = Instant.parse("2026-11-21T12:00:00Z"), remindAt = Instant.parse("2026-11-21T11:40:00Z")),
        )
        t.repository.update(derived, TaskPatch(dueAt = Change(Instant.parse("2026-11-21T12:30:00Z"))))
        t.repository.update(manual, TaskPatch(dueAt = Change(Instant.parse("2026-11-21T12:05:00Z"))))
        assertEquals("2026-11-21T11:30:00.000Z", t.query("SELECT remind_at FROM tasks WHERE id = '$derived'").single()["remind_at"])
        assertEquals("2026-11-21T11:40:00.000Z", t.query("SELECT remind_at FROM tasks WHERE id = '$manual'").single()["remind_at"])
    }

    @Test
    fun movingTheDueClearsTheSnoozeAndDropsStaleSnoozedRows() = runBlocking {
        val t = open()
        val id = t.repository.create(TaskDraft(title = "Call bank", dueAt = Instant.parse("2026-11-21T12:00:00Z"), isUrgent = true))
        val db = t.holder.db.openHelper.writableDatabase
        db.execSQL("UPDATE tasks SET snoozed_until = '2026-11-21T12:05:00.000Z' WHERE id = '$id'")
        db.execSQL(
            "INSERT INTO reminders (id, task_id, kind, remind_at, status, snoozed_until, snooze_count) VALUES " +
                "('R1', '$id', 'due', '2026-11-21T12:00:00.000Z', 'snoozed', '2026-11-21T12:05:00.000Z', 1)",
        )
        t.repository.update(id, TaskPatch(dueAt = Change(Instant.parse("2026-11-22T12:00:00Z"))))
        assertNull(t.query("SELECT snoozed_until FROM tasks").single()["snoozed_until"])
        assertTrue(t.query("SELECT id FROM reminders").isEmpty())
    }

    @Test
    fun untouchedDatesKeepTheirOriginalText() = runBlocking {
        val t = open { db ->
            db.execSQL(
                "INSERT INTO tasks (id, workspace_id, title, due_at, created_at) VALUES " +
                    "('01JAAAAAAAAAAAAAAAAAAAAAAA', 'W1', 'Passport', '2026-11-21T03:30:00.123Z', '2026-01-01T00:00:00.001Z')",
            )
        }
        t.repository.update("01JAAAAAAAAAAAAAAAAAAAAAAA", TaskPatch(title = "Passport renewed"))
        val row = t.query("SELECT * FROM tasks").single()
        assertEquals("2026-11-21T03:30:00.123Z", row["due_at"])
        assertEquals("2026-01-01T00:00:00.001Z", row["created_at"])
        assertEquals("passport renewed", row["title_fold"])
    }

    @Test
    fun deleteWritesTheBinPayloadAndRemovesTheLiveRows() = runBlocking {
        val t = open()
        val id = t.repository.create(TaskDraft(title = "Renew passport", description = "See notes", priority = "high", isUrgent = true, dueAt = Instant.parse("2026-11-21T03:30:00Z")))
        t.repository.addChecklistItem(id, "Photos")
        val db = t.holder.db.openHelper.writableDatabase
        db.execSQL("INSERT INTO reminders (id, task_id, kind, remind_at, status) VALUES ('R1', '$id', 'remind', '2026-11-21T02:30:00.000Z', 'acknowledged')")
        db.execSQL(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, local_path) VALUES " +
                "('F1', 'W1', 'task', '$id', 'scan.pdf', 'application/pdf', 42, 'F1.pdf')",
        )
        t.repository.delete(id)

        assertTrue(t.query("SELECT id FROM tasks").isEmpty())
        assertTrue(t.query("SELECT id FROM checklist_items").isEmpty())
        assertTrue(t.query("SELECT id FROM reminders").isEmpty())
        assertTrue(t.query("SELECT id FROM file_rows").isEmpty())
        val trash = t.query("SELECT * FROM trash_items").single()
        assertEquals("task", trash["kind"])
        assertEquals(id, trash["entity_id"])
        assertEquals("Renew passport", trash["title"])
        assertEquals("[\"F1.pdf\"]", trash["local_paths"])
        assertEquals("42", trash["size_bytes"])
        assertEquals("2026-11-20T04:30:00.000Z", trash["deleted_at"])

        val payload = Json.parseToJsonElement(trash["payload"]!!).jsonObject
        assertEquals(listOf("tasks", "checklist", "reminders", "notes", "noteLinks", "files", "folders"), payload.keys.toList())
        val task = payload["tasks"]!!.jsonArray.single().jsonObject
        assertEquals(
            listOf(
                "id", "workspaceId", "title", "description", "status", "priority", "dueAt", "remindAt", "snoozedUntil",
                "alarmsMutedAt", "timezone", "isUrgent", "requiresAcknowledgement", "seriesId", "occurrenceDate",
                "spawnedFromTaskId", "sortOrder", "completedAt", "titleFold", "descriptionFold", "createdAt", "updatedAt",
            ),
            task.keys.toList(),
        )
        assertTrue(task["isUrgent"]!!.jsonPrimitive.boolean)
        assertEquals("2026-11-21T03:30:00.000Z", task["dueAt"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(0), task["sortOrder"])
        assertEquals("high", task["priority"]!!.jsonPrimitive.content)
        val item = payload["checklist"]!!.jsonArray.single().jsonObject
        assertEquals("Photos", item["title"]!!.jsonPrimitive.content)
        assertFalse(item["isDone"]!!.jsonPrimitive.boolean)
        assertEquals("R1", payload["reminders"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(42), payload["files"]!!.jsonArray.single().jsonObject["sizeBytes"])
        assertEquals(JsonArray(emptyList()), payload["notes"])
    }

    @Test
    fun deleteWithTheBinOffRemovesForGoodIncludingBytes() = runBlocking {
        val t = open()
        t.settings.set("remft_bin_enabled", "false")
        val id = t.repository.create(TaskDraft(title = "Scratch"))
        t.attachments.mkdirs()
        val bytes = File(t.attachments, "F2.txt").apply { writeText("x") }
        t.holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, local_path) VALUES " +
                "('F2', 'W1', 'task', '$id', 'a.txt', 'text/plain', 1, 'F2.txt')",
        )
        t.repository.delete(id)
        assertTrue(t.query("SELECT id FROM trash_items").isEmpty())
        assertTrue(t.query("SELECT id FROM tasks").isEmpty())
        assertFalse(bytes.exists())
    }

    @Test
    fun deletingAnOccurrenceRecordsASeriesSkip() = runBlocking {
        val t = open { db ->
            db.execSQL("INSERT INTO tasks (id, workspace_id, title, series_id, occurrence_date) VALUES ('T1', 'W1', 'Gym', 'S1', '2026-11-21')")
        }
        t.repository.delete("T1")
        assertEquals("2026-11-21", t.query("SELECT occurrence_date FROM series_skips WHERE series_id = 'S1'").single()["occurrence_date"])
    }

    @Test
    fun convertToNoteCreatesANoteAndBinsTheCapture() = runBlocking {
        val t = open()
        val id = t.repository.create(TaskDraft(title = "Idea", description = "# Plan\nSee **this**", status = "inbox"))
        val noteId = t.repository.convertToNote(id)
        assertNotNull(noteId)
        val note = t.query("SELECT * FROM notes").single()
        assertEquals("Idea", note["title"])
        assertEquals("# Plan\nSee **this**", note["content_markdown"])
        assertEquals("idea", note["title_fold"])
        assertTrue(note["plain_text"]!!.contains("See this"))
        assertTrue(t.query("SELECT id FROM tasks").isEmpty())
        assertEquals(id, t.query("SELECT entity_id FROM trash_items").single()["entity_id"])
    }

    @Test
    fun homeShowsPlanningAndTodaysCompletedButNotInboxOrOverdueDone() = runBlocking {
        val t = open()
        val open = t.repository.create(TaskDraft(title = "Open"))
        t.repository.create(TaskDraft(title = "Captured", status = "inbox"))
        val doneToday = t.repository.create(TaskDraft(title = "Done today"))
        val overdueDone = t.repository.create(TaskDraft(title = "Overdue done", dueAt = Instant.parse("2026-11-18T04:30:00Z")))
        t.repository.setCompleted(doneToday, true)
        t.repository.setCompleted(overdueDone, true)
        val home = t.repository.watchHome().first().map { it.id }.toSet()
        assertEquals(setOf(open, doneToday), home)
        assertEquals(listOf("Captured"), t.repository.watchInbox().first().map { it.title })
    }

    @Test
    fun checklistAddRenameTickMoveAndDelete() = runBlocking {
        val t = open()
        val id = t.repository.create(TaskDraft(title = "Trip"))
        t.repository.addChecklistItem(id, "Tickets")
        t.repository.addChecklistItem(id, "Bags")
        t.repository.addChecklistItem(id, "  ")
        var items = t.repository.detail(id)!!.checklist
        assertEquals(listOf("Tickets", "Bags"), items.map { it.title })
        t.repository.renameChecklistItem(items[1].id, "Pack bags")
        t.repository.setChecklistItemDone(items[0].id, true)
        t.repository.moveChecklistItem(id, items[1].id, -1)
        items = t.repository.detail(id)!!.checklist
        assertEquals(listOf("Pack bags", "Tickets"), items.map { it.title })
        assertTrue(items[1].isDone)
        t.repository.deleteChecklistItem(items[0].id)
        assertEquals(listOf("Tickets"), t.repository.detail(id)!!.checklist.map { it.title })
    }

    @Test
    fun binPayloadDatesAreIsoUtcWithMilliseconds() {
        assertEquals("2026-11-21T03:30:00.000Z", UtcText.format(Instant.parse("2026-11-21T03:30:00Z")))
        assertEquals("2026-11-21T03:30:00.123Z", UtcText.format(Instant.parse("2026-11-21T03:30:00.123456Z")))
        val json = BinPayload().encode()
        assertEquals("{\"tasks\":[],\"checklist\":[],\"reminders\":[],\"notes\":[],\"noteLinks\":[],\"files\":[],\"folders\":[]}", json)
        assertTrue(Json.parseToJsonElement(json) is JsonObject)
    }
}
