package com.ftapps.remft.data.repeat

import android.app.AlarmManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.PassOutcome
import com.ftapps.remft.domain.repeat.EditScope
import com.ftapps.remft.domain.repeat.RepeatFreq
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.repeat.WeekdayPick
import com.ftapps.remft.domain.tasks.Change
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.testing.TestAlarms
import com.ftapps.remft.testing.TestTasks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SeriesReplaceTest {
    private val opened = mutableListOf<TestAlarms>()
    private val due: Instant = Instant.parse("2026-11-21T04:30:00Z")

    @After
    fun tearDown() {
        opened.forEach { it.close() }
    }

    private class Setup(
        val alarms: TestAlarms,
        val seriesId: String,
        val rows: List<Map<String, String?>>,
        val edited: String,
        val shared: String,
        val ownBytes: File,
        val liveBytes: File,
        val binnedBytes: File,
        val spare: String,
    ) {
        val t: TestTasks get() = alarms.tasks
    }

    private fun TestTasks.count(sql: String): Int = query(sql).size

    private suspend fun TestTasks.attach(targetType: String, targetId: String, fileId: String, stored: String = "$fileId.txt"): File {
        attachments.mkdirs()
        val bytes = File(attachments, stored).apply { if (!exists()) writeText("bytes of $stored") }
        holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, local_path) VALUES " +
                "('$fileId', '${repository.workspaceId()}', '$targetType', '$targetId', '$stored', 'text/plain', 1, '$stored')",
        )
        return bytes
    }

    private fun TestTasks.occurrences(seriesId: String): List<Map<String, String?>> =
        query("SELECT * FROM tasks WHERE series_id = '$seriesId' ORDER BY occurrence_date")

    private fun setUp(rule: RepeatRule, binOn: Boolean, editedIndex: Int, sharedIndex: Int): Setup = runBlocking {
        val alarms = TestAlarms().also { opened += it }
        val t = alarms.tasks
        val spare = t.repository.create(TaskDraft(title = "Spare key"))
        val binnedBytes = t.attach("task", spare, "F-BIN")
        t.repository.delete(spare)
        assertEquals(1, t.count("SELECT id FROM trash_items WHERE entity_id = '$spare'"))
        if (!binOn) t.settings.set("remft_bin_enabled", "false")
        val id = t.repository.create(TaskDraft(title = "Water the plants", dueAt = due, remindAt = due.minusSeconds(3_600)))
        val seriesId = t.series.start(id, rule, due)!!
        t.repository.setCompleted(id, true)
        alarms.clock.advance(Duration.ofDays(10))
        val rows = t.occurrences(seriesId)
        val edited = rows[editedIndex]["id"]!!
        t.repository.update(edited, TaskPatch(title = "Water the plants and the herbs"))
        t.repository.addChecklistItem(edited, "Fill the can")
        val ownBytes = t.attach("task", edited, "F-OWN")
        t.holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO reminders (id, task_id, kind, remind_at, status) VALUES " +
                "('R-OWN', '$edited', 'remind', '${rows[editedIndex]["remind_at"]}', 'scheduled')",
        )
        val shared = rows[sharedIndex]["id"]!!
        val liveBytes = t.attach("task", shared, "F-SHARED")
        t.attach("note", "N-KEEP", "F-NOTE", "F-SHARED.txt")
        t.attach("task", rows[sharedIndex]["id"]!!, "F-BINREF", "F-BIN.txt")
        Setup(alarms, seriesId, t.occurrences(seriesId), edited, shared, ownBytes, liveBytes, binnedBytes, spare)
    }

    private fun scheduledTimes(s: Setup): Set<Long> {
        val manager = s.alarms.context.getSystemService(AlarmManager::class.java)
        return shadowOf(manager).scheduledAlarms.map { it.triggerAtMs }.toSet()
    }

    private fun pass(s: Setup): Set<Long> = runBlocking {
        assertTrue(s.alarms.env.scheduler.pass() is PassOutcome.Done)
        scheduledTimes(s)
    }

    private fun millis(row: Map<String, String?>): Long = Instant.parse(row["remind_at"]!!).toEpochMilli()

    private fun assertReplaced(s: Setup, retired: List<Map<String, String?>>, fresh: List<Map<String, String?>>, bookedBefore: Set<Long>) {
        val t = s.t
        val retiredIds = retired.map { it["id"]!! }
        assertTrue(s.edited in retiredIds)
        assertTrue(s.shared in retiredIds)
        retiredIds.forEach { assertEquals(0, t.count("SELECT id FROM tasks WHERE id = '$it'")) }
        assertEquals(listOf(s.spare), t.query("SELECT entity_id FROM trash_items").map { it["entity_id"] })
        assertEquals(0, t.count("SELECT id FROM checklist_items WHERE task_id = '${s.edited}'"))
        assertEquals(0, t.count("SELECT id FROM reminders WHERE task_id NOT IN (SELECT id FROM tasks)"))
        assertEquals(0, t.count("SELECT id FROM file_rows WHERE target_type = 'task' AND target_id NOT IN (SELECT id FROM tasks)"))
        assertFalse(s.ownBytes.exists())
        assertTrue(s.liveBytes.isFile)
        assertTrue(s.binnedBytes.isFile)

        val head = t.query("SELECT * FROM tasks WHERE id = '${s.rows[0]["id"]}'").single()
        assertEquals(s.rows[0], head)
        assertEquals("completed", head["status"])
        assertEquals(s.rows[1], t.query("SELECT * FROM tasks WHERE id = '${s.rows[1]["id"]}'").single())

        assertTrue(fresh.isNotEmpty())
        val before = s.rows.map { it["id"] }.toSet()
        assertTrue(fresh.none { it["id"] in before })
        assertTrue(retired.any { millis(it) in bookedBefore })
        val booked = pass(s)
        retired.forEach { assertFalse(it["occurrence_date"], millis(it) in booked) }
        assertTrue(millis(fresh.first()) in booked)
    }

    private fun ruleChange(binOn: Boolean) {
        val s = setUp(RepeatRule(freq = RepeatFreq.Weekly), binOn, editedIndex = 3, sharedIndex = 5)
        val t = s.t
        val bookedBefore = pass(s)
        runBlocking { t.series.updateRule(s.seriesId, RepeatRule(freq = RepeatFreq.Daily, interval = 2)) }
        val after = t.occurrences(s.seriesId)
        val days = after.map { it["occurrence_date"]!! }.toSet()
        assertTrue("2026-12-01" in days)
        assertTrue("2026-12-12" !in days)
        val retired = s.rows.filter { r -> after.none { it["id"] == r["id"] } }
        val fresh = after.filter { r -> s.rows.none { it["id"] == r["id"] } }
        assertEquals("2026-12-01", fresh.first()["occurrence_date"])
        assertReplaced(s, retired, fresh, bookedBefore)
    }

    @Test
    fun aRuleChangeWithTheBinOnReplacesLaterOccurrencesDirectly() = ruleChange(binOn = true)

    @Test
    fun aRuleChangeWithTheBinOffReplacesLaterOccurrencesDirectly() = ruleChange(binOn = false)

    private fun thisAndFuture(binOn: Boolean) {
        val weekend = RepeatRule(freq = RepeatFreq.Weekly, interval = 2, byWeekday = listOf(WeekdayPick("SA"), WeekdayPick("SU")))
        val s = setUp(weekend, binOn, editedIndex = 4, sharedIndex = 6)
        val t = s.t
        val target = s.rows[2]
        assertEquals("2026-12-05", target["occurrence_date"])
        val bookedBefore = pass(s)
        val newDue = Instant.parse(target["due_at"]!!).plus(Duration.ofDays(7))
        runBlocking { t.repository.update(target["id"]!!, TaskPatch(dueAt = Change(newDue)), EditScope.Future) }
        val nextId = t.query("SELECT id FROM task_series WHERE id != '${s.seriesId}'").single()["id"]!!
        val next = t.occurrences(nextId)
        assertEquals(target["id"], next.first()["id"])
        assertEquals("2026-12-12", next.first()["occurrence_date"])
        val all = t.query("SELECT * FROM tasks")
        val retired = s.rows.filter { r -> all.none { it["id"] == r["id"] } }
        val fresh = next.filter { r -> s.rows.none { it["id"] == r["id"] } }
        assertEquals("2026-12-13", fresh.first()["occurrence_date"])
        assertEquals(listOf(s.rows[0]["id"], s.rows[1]["id"]), t.occurrences(s.seriesId).map { it["id"] })
        assertReplaced(s, retired, fresh, bookedBefore)
    }

    @Test
    fun thisAndFutureWithTheBinOnReplacesLaterOccurrencesDirectly() = thisAndFuture(binOn = true)

    @Test
    fun thisAndFutureWithTheBinOffReplacesLaterOccurrencesDirectly() = thisAndFuture(binOn = false)
}
