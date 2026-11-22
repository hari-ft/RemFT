package com.ftapps.remft.data.repeat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.bin.RecycleBin
import com.ftapps.remft.domain.repeat.EditScope
import com.ftapps.remft.domain.repeat.RepeatBasis
import com.ftapps.remft.domain.repeat.RepeatFreq
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.tasks.Change
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.testing.TestTasks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SeriesRepositoryTest {
    private val t = TestTasks()
    private val due: Instant = Instant.parse("2026-11-21T04:30:00Z")
    private val weekly = RepeatRule(freq = RepeatFreq.Weekly)

    @After
    fun tearDown() {
        t.close()
    }

    private fun startSeries(rule: RepeatRule, title: String = "Water the plants"): Pair<String, String> = runBlocking {
        val id = t.repository.create(TaskDraft(title = title, dueAt = due, remindAt = due.minusSeconds(3_600), priority = "high"))
        val seriesId = t.series.start(id, rule, due)
        assertNotNull(seriesId)
        id to seriesId!!
    }

    private fun occurrences(seriesId: String): List<Map<String, String?>> =
        t.query("SELECT * FROM tasks WHERE series_id = '$seriesId' ORDER BY occurrence_date")

    private fun count(sql: String): Int = t.query(sql).size

    @Test
    fun createMaterialisesTheWindowAndAdoptsTheTask() {
        val (id, seriesId) = startSeries(RepeatRule(freq = RepeatFreq.Daily))
        val rows = occurrences(seriesId)
        assertEquals(365, rows.size)
        assertEquals(id, rows.first()["id"])
        assertEquals("2026-11-21", rows.first()["occurrence_date"])
        assertEquals("2027-11-20", rows.last()["occurrence_date"])
        val later = rows[1]
        assertEquals("Water the plants", later["title"])
        assertEquals("water the plants", later["title_fold"])
        assertEquals("high", later["priority"])
        assertEquals("2026-11-22T04:30:00.000Z", later["due_at"])
        assertEquals("2026-11-22T03:30:00.000Z", later["remind_at"])
        val series = t.query("SELECT * FROM task_series").single()
        assertEquals("Asia/Kolkata", series["timezone"])
        assertEquals("2026-11-21T04:30:00.000Z", series["anchor_at"])
        assertTrue(series["rule_json"]!!.contains("\"daily\""))
    }

    @Test
    fun rollingIsIdempotent() = runBlocking {
        val (_, seriesId) = startSeries(weekly)
        val before = occurrences(seriesId).size
        assertEquals(0, t.series.rollAll())
        assertEquals(0, t.series.rollAll())
        assertEquals(before, occurrences(seriesId).size)
    }

    @Test
    fun rollingRecreatesAMissingOccurrenceButNotADeletedOne() = runBlocking {
        val (_, seriesId) = startSeries(weekly)
        val rows = occurrences(seriesId)
        t.holder.db.openHelper.writableDatabase.execSQL("DELETE FROM tasks WHERE id = '${rows[3]["id"]}'")
        t.repository.delete(rows[4]["id"]!!)
        assertEquals(1, t.series.rollAll())
        val days = occurrences(seriesId).map { it["occurrence_date"] }
        assertTrue(rows[3]["occurrence_date"] in days)
        assertTrue(rows[4]["occurrence_date"] !in days)
    }

    @Test
    fun deletingOnlyThisOneWritesASkip() = runBlocking {
        val (_, seriesId) = startSeries(weekly)
        val target = occurrences(seriesId)[2]
        t.repository.delete(target["id"]!!)
        val skip = t.query("SELECT * FROM series_skips").single()
        assertEquals(seriesId, skip["series_id"])
        assertEquals(target["occurrence_date"], skip["occurrence_date"])
        t.series.rollAll()
        assertEquals(0, count("SELECT id FROM tasks WHERE occurrence_date = '${target["occurrence_date"]}'"))
        assertNotNull(t.query("SELECT id FROM task_series WHERE id = '$seriesId'").singleOrNull())
    }

    @Test
    fun deletingThisAndFutureStopsTheSeriesFromThatDay() = runBlocking {
        val (_, seriesId) = startSeries(weekly)
        val rows = occurrences(seriesId)
        val day = rows[3]["occurrence_date"]!!
        t.series.stop(seriesId, fromDay = day)
        assertTrue(t.query("SELECT id FROM task_series").isEmpty())
        assertEquals(0, count("SELECT id FROM tasks WHERE occurrence_date >= '$day'"))
        assertEquals(3, count("SELECT id FROM tasks WHERE occurrence_date < '$day'"))
        assertEquals(0, t.series.rollAll())
        assertTrue(t.query("SELECT id FROM trash_items").isEmpty())
    }

    private fun bin(): RecycleBin =
        RecycleBin({ t.holder.db }, t.settings, t.attachments, { t.identity.resolve().workspaceId })

    private fun table(name: String): List<Map<String, String?>> = t.query("SELECT * FROM $name ORDER BY 1, 2")

    @Test
    fun stoppingFromHereBinsTheOccurrenceWithTheRuleAndARestoreBringsTheSeriesBack() = runBlocking {
        val (_, seriesId) = startSeries(weekly)
        val rows = occurrences(seriesId)
        t.repository.delete(rows[1]["id"]!!)
        val target = rows[3]
        val day = target["occurrence_date"]!!
        t.repository.addChecklistItem(rows[5]["id"]!!, "Buy soil")
        val before = listOf("tasks", "task_series", "series_skips", "checklist_items").associateWith { table(it) }
        val stopped = t.series.stoppedIds(seriesId, day, target["id"]!!)
        assertEquals(rows.filter { it["occurrence_date"]!! >= day }.map { it["id"] }.toSet(), stopped)

        t.series.stopToBin(seriesId, day, target["id"]!!)
        assertTrue(t.query("SELECT id FROM task_series").isEmpty())
        assertEquals(0, count("SELECT id FROM tasks WHERE occurrence_date >= '$day'"))
        val entry = t.query("SELECT * FROM trash_items WHERE entity_id = '${target["id"]}'").single()
        assertEquals("task", entry["kind"])
        assertEquals("Water the plants", entry["title"])
        assertEquals(2, count("SELECT id FROM trash_items"))

        assertTrue(bin().restore(entry["id"]!!))
        before.forEach { (name, rowsBefore) -> assertEquals(name, rowsBefore, table(name)) }
        assertEquals(1, count("SELECT id FROM trash_items"))
        assertEquals(0, t.series.rollAll())
    }

    @Test
    fun stoppingAnAfterCompletionSeriesFromItsOpenOccurrenceIsRestorable() = runBlocking {
        val rule = RepeatRule(freq = RepeatFreq.Daily, interval = 2, basis = RepeatBasis.Completion)
        val (id, seriesId) = startSeries(rule)
        val before = listOf("tasks", "task_series").associateWith { table(it) }
        t.series.stopToBin(seriesId, occurrences(seriesId).single()["occurrence_date"], id)
        assertTrue(t.query("SELECT id FROM tasks").isEmpty())
        assertTrue(t.query("SELECT id FROM task_series").isEmpty())
        assertTrue(bin().restore(t.query("SELECT id FROM trash_items").single()["id"]!!))
        before.forEach { (name, rowsBefore) -> assertEquals(name, rowsBefore, table(name)) }
        assertTrue(t.series.isAfterCompletion(seriesId))
    }

    @Test
    fun completingAnAfterCompletionOccurrenceSpawnsTheNextAndReopeningRetractsIt() = runBlocking {
        val rule = RepeatRule(freq = RepeatFreq.Daily, interval = 2, basis = RepeatBasis.Completion)
        val (id, seriesId) = startSeries(rule)
        assertEquals(1, occurrences(seriesId).size)
        t.repository.setCompleted(id, true)
        val spawned = t.query("SELECT * FROM tasks WHERE spawned_from_task_id = '$id'").single()
        assertEquals(seriesId, spawned["series_id"])
        assertEquals("open", spawned["status"])
        assertEquals("2026-11-22T04:30:00.000Z", spawned["due_at"])
        assertEquals("Water the plants", spawned["title"])
        t.repository.setCompleted(id, false)
        assertTrue(t.query("SELECT id FROM tasks WHERE spawned_from_task_id = '$id'").isEmpty())
        assertEquals(1, occurrences(seriesId).size)
        assertTrue(t.query("SELECT id FROM trash_items").isEmpty())
    }

    @Test
    fun completingACalendarOccurrenceSpawnsNothing() = runBlocking {
        val (id, seriesId) = startSeries(weekly)
        val before = occurrences(seriesId).size
        t.repository.setCompleted(id, true)
        assertEquals(before, occurrences(seriesId).size)
        assertEquals("completed", t.query("SELECT status FROM tasks WHERE id = '$id'").single()["status"])
    }

    @Test
    fun editScopeAllRenamesEveryUnfinishedOccurrenceAndTheTemplate() = runBlocking {
        val (id, seriesId) = startSeries(weekly)
        val target = occurrences(seriesId)[2]["id"]!!
        t.repository.setCompleted(id, true)
        t.repository.update(target, TaskPatch(title = "Feed the cat"), EditScope.All)
        val rows = occurrences(seriesId)
        assertEquals("Water the plants", rows.first { it["id"] == id }["title"])
        assertTrue(rows.filter { it["id"] != id }.all { it["title"] == "Feed the cat" && it["title_fold"] == "feed the cat" })
        assertTrue(t.query("SELECT template_json FROM task_series").single()["template_json"]!!.contains("Feed the cat"))
    }

    @Test
    fun editScopeFutureSplitsTheSeries() = runBlocking {
        val (_, seriesId) = startSeries(weekly)
        val rows = occurrences(seriesId)
        val target = rows[3]
        t.repository.update(target["id"]!!, TaskPatch(priority = "low"), EditScope.Future)
        val series = t.query("SELECT * FROM task_series ORDER BY created_at, id")
        assertEquals(2, series.size)
        val old = series.single { it["id"] == seriesId }
        assertTrue(old["rule_json"]!!.contains("until"))
        val nextId = series.single { it["id"] != seriesId }["id"]!!
        assertEquals(3, occurrences(seriesId).size)
        assertTrue(occurrences(seriesId).all { it["priority"] == "high" })
        val moved = occurrences(nextId)
        assertEquals(target["id"], moved.first()["id"])
        assertTrue(moved.all { it["priority"] == "low" })
        assertEquals(rows.size, occurrences(seriesId).size + moved.size)
    }

    @Test
    fun editScopeThisChangesOnlyThatOccurrence() = runBlocking {
        val (_, seriesId) = startSeries(weekly)
        val rows = occurrences(seriesId)
        val target = rows[2]["id"]!!
        val newDue = Instant.parse(rows[2]["due_at"]!!).plusSeconds(7_200)
        t.repository.update(target, TaskPatch(dueAt = Change(newDue)), EditScope.This)
        val after = occurrences(seriesId)
        assertEquals(1, t.query("SELECT id FROM task_series").size)
        assertEquals(rows.size, after.size)
        rows.zip(after).forEach { (b, a) ->
            if (a["id"] == target) assertEquals(newDue.toString().replace("Z", ".000Z"), a["due_at"]) else assertEquals(b["due_at"], a["due_at"])
        }
    }

    @Test
    fun repeatOffKeepsTheTaskAsAOneOffAndBinsEditedOccurrences() = runBlocking {
        val (id, seriesId) = startSeries(weekly)
        val rows = occurrences(seriesId)
        val edited = rows[2]["id"]!!
        val withChecklist = rows[4]["id"]!!
        t.repository.update(edited, TaskPatch(title = "Water the plants and the herbs"))
        t.repository.addChecklistItem(withChecklist, "Fill the can")
        t.series.stop(seriesId, keepTaskId = id)
        val kept = t.query("SELECT * FROM tasks").single()
        assertEquals(id, kept["id"])
        assertNull(kept["series_id"])
        assertNull(kept["occurrence_date"])
        assertTrue(t.query("SELECT id FROM task_series").isEmpty())
        assertTrue(t.query("SELECT task_id FROM checklist_items").isEmpty())
        val trash = t.query("SELECT * FROM trash_items ORDER BY entity_id")
        assertEquals(setOf(edited, withChecklist), trash.map { it["entity_id"] }.toSet())
        val payload = Json.parseToJsonElement(trash.single { it["entity_id"] == withChecklist }["payload"]!!).jsonObject
        assertEquals(1, payload.getValue("checklist").jsonArray.size)
        assertEquals(1, payload.getValue("tasks").jsonArray.size)
    }

    @Test
    fun updatingTheRuleRebuildsTheFuture() = runBlocking {
        val (id, seriesId) = startSeries(weekly)
        t.series.updateRule(seriesId, RepeatRule(freq = RepeatFreq.Monthly))
        val rows = occurrences(seriesId)
        assertEquals(id, rows.first()["id"])
        assertTrue(rows.size in 12..13)
        assertTrue(rows.all { it["occurrence_date"]!!.endsWith("-21") })
    }

    @Test
    fun watchAndAfterCompletionLookup() = runBlocking {
        val (_, seriesId) = startSeries(RepeatRule(freq = RepeatFreq.Weekly, interval = 1, basis = RepeatBasis.Completion))
        assertTrue(t.series.isAfterCompletion(seriesId))
        assertEquals(false, t.series.isAfterCompletion(null))
        assertEquals(seriesId, t.series.byId(seriesId)?.id)
    }
}
