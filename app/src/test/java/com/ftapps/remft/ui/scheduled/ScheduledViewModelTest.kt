package com.ftapps.remft.ui.scheduled

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.repeat.RepeatFreq
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.tasks.ScheduledBucket
import com.ftapps.remft.domain.tasks.ScheduledSection
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.tasks.ListLoad
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ScheduledViewModelTest {
    private val t = TestTasks()
    private val zone = ZoneId.of("Asia/Kolkata")

    @After
    fun tearDown() {
        t.close()
    }

    private fun utc(text: String): String = UtcText.format(LocalDateTime.parse(text).atZone(zone).toInstant())

    private fun insert(
        id: String,
        due: String? = null,
        remind: String? = null,
        snoozed: String? = null,
        status: String = "open",
        workspace: String? = null,
    ) {
        val ws = workspace ?: runBlocking { t.repository.workspaceId() }
        fun sql(value: String?) = value?.let { "'${utc(it)}'" } ?: "NULL"
        t.holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO tasks (id, workspace_id, title, status, due_at, remind_at, snoozed_until) VALUES " +
                "('$id', '$ws', 'Task $id', '$status', ${sql(due)}, ${sql(remind)}, ${sql(snoozed)})",
        )
    }

    private fun sections(): List<ScheduledSection> = runBlocking {
        val state = ScheduledViewModel.states(t.environment).first { it.load is ListLoad.Ready }
        (state.load as ListLoad.Ready).items
    }

    private fun ids(sections: List<ScheduledSection>) = sections.map { section -> section.bucket to section.entries.map { it.task.id } }

    @Test
    fun ordersByTimeWithOverdueOnTopAndLeavesOutInboxCompletedAndOtherWorkspaces() {
        insert("later", due = "2026-11-24T09:00")
        insert("tomorrow", remind = "2026-11-21T08:00")
        insert("tonight", due = "2026-11-20T21:00", remind = "2026-11-20T20:00")
        insert("morning", due = "2026-11-20T11:00")
        insert("yesterday", due = "2026-11-19T18:00")
        insert("earlier", remind = "2026-11-20T09:00", status = "in_progress")
        insert("noTime")
        insert("inbox", status = "inbox")
        insert("done", due = "2026-11-20T12:00", status = "completed")
        insert("cancelled", due = "2026-11-20T12:00", status = "cancelled")
        insert("elsewhere", due = "2026-11-20T12:00", workspace = "OTHER")
        assertEquals(
            listOf(
                ScheduledBucket.Overdue to listOf("yesterday", "earlier"),
                ScheduledBucket.Day(LocalDate.of(2026, 11, 20)) to listOf("morning", "tonight"),
                ScheduledBucket.Day(LocalDate.of(2026, 11, 21)) to listOf("tomorrow"),
                ScheduledBucket.Day(LocalDate.of(2026, 11, 24)) to listOf("later"),
            ),
            ids(sections()),
        )
    }

    @Test
    fun theQueryItselfReturnsRowsInScheduledOrder() = runBlocking {
        insert("b", due = "2026-11-21T09:00")
        insert("a", remind = "2026-11-20T12:00")
        insert("c", due = "2026-11-20T11:00", snoozed = "2026-11-22T08:00")
        insert("d", due = "2026-11-20T11:30", snoozed = "2026-11-20T11:00")
        val rows = t.holder.db.tasksDao().watchScheduled(t.repository.workspaceId()).first().map { it.id }
        assertEquals(listOf("d", "a", "b", "c"), rows)
    }

    @Test
    fun aSnoozedTaskSortsByItsSnoozedTime() {
        insert("snoozed", due = "2026-11-20T09:00", snoozed = "2026-11-20T15:00")
        insert("noon", due = "2026-11-20T12:00")
        insert("evening", due = "2026-11-20T18:00")
        val day = sections().single()
        assertEquals(ScheduledBucket.Day(LocalDate.of(2026, 11, 20)), day.bucket)
        assertEquals(listOf("noon", "snoozed", "evening"), day.entries.map { it.task.id })
        assertTrue(day.entries[1].snoozed)
        assertEquals(Instant.parse(utc("2026-11-20T15:00")), day.entries[1].at)
    }

    @Test
    fun binnedAndPendingDeleteTasksAreHidden() {
        insert("kept", due = "2026-11-21T09:00")
        insert("binned", due = "2026-11-21T10:00")
        insert("pending", due = "2026-11-21T11:00")
        runBlocking { t.repository.delete("binned") }
        t.pendingDeletes.schedule("pending") {}
        assertEquals(listOf("kept"), sections().flatMap { it.entries }.map { it.task.id })
        t.pendingDeletes.undo("pending")
        assertEquals(listOf("kept", "pending"), sections().flatMap { it.entries }.map { it.task.id })
    }

    @Test
    fun materialisedSeriesOccurrencesAreListedAndNothingIsInvented() {
        val due = Instant.parse("2026-11-21T04:30:00Z")
        val id = runBlocking { t.repository.create(TaskDraft(title = "Water the plants", dueAt = due)) }
        val seriesId = runBlocking { t.series.start(id, RepeatRule(freq = RepeatFreq.Daily), due) }!!
        val stored = t.query("SELECT id FROM tasks WHERE series_id = '$seriesId'").size
        val entries = sections().flatMap { it.entries }
        assertEquals(stored, entries.size)
        assertTrue(entries.all { it.task.seriesId == seriesId })
        assertEquals(id, entries.first().task.id)
        assertEquals(entries.map { it.at }.sorted(), entries.map { it.at })
    }

    @Test
    fun completingAnOccurrenceDropsItFromTheList() {
        insert("one", due = "2026-11-21T09:00")
        insert("two", due = "2026-11-21T10:00")
        runBlocking { t.repository.setCompleted("one", true) }
        assertEquals(listOf("two"), sections().flatMap { it.entries }.map { it.task.id })
    }
}
