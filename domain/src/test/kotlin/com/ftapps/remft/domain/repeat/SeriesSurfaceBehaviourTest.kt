package com.ftapps.remft.domain.repeat

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

class SeriesSurfaceBehaviourTest {
    private val zone = "Asia/Kolkata"
    private val daily = RepeatRule(freq = RepeatFreq.Daily)
    private val now = local(2027, 3, 20, 10)
    private val due = local(2027, 3, 21, 9)
    private val late = local(2027, 3, 20, 23, 30)
    private val tomorrow9 = local(2027, 3, 21, 9)
    private val db = SeriesTestDb(now)

    private fun local(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Instant = WallClock.toInstant(zone, y, m, d, h, min)

    private fun byDue(): List<OccurrenceRow> = db.state.tasks.sortedBy { it.dueAt }

    private fun later(): List<OccurrenceRow> = byDue().drop(1)

    private fun createAndRepeat(
        title: String,
        dueAt: Instant?,
        remindAt: Instant?,
        anchorAt: Instant,
        isUrgent: Boolean = true,
        priority: String = "none",
    ): Pair<String, String> {
        val id = db.createTask(
            title = title,
            dueAt = dueAt,
            remindAt = remindAt,
            isUrgent = isUrgent,
            requiresAcknowledgement = isUrgent,
            priority = priority,
        )
        val seriesId = db.start(daily, anchorAt = anchorAt, timezone = zone, template = null, fromTaskId = id)
        return id to seriesId
    }

    private fun repeatConfiguredTask(): Pair<String, String> =
        createAndRepeat("Medicine", dueAt = due, remindAt = due.minus(Duration.ofMinutes(30)), anchorAt = due)

    private fun expectDailyWithReminder() {
        val template = db.state.series.single().template
        assertEquals(60, template.remindMinutesBefore)
        assertTrue(template.isUrgent)
        assertTrue(template.requiresAcknowledgement)
        val later = later()
        assertTrue(later.isNotEmpty())
        for (t in later.take(5)) {
            assertEquals(Duration.ofHours(1), Duration.between(t.remindAt, t.dueAt))
            assertTrue(t.isUrgent)
        }
    }

    @Test
    fun `Repeat on the detail screen copies how the task rings`() {
        repeatConfiguredTask()

        val template = db.state.series.single().template
        assertEquals(30, template.remindMinutesBefore)
        assertTrue(template.isUrgent)
        assertTrue(template.requiresAcknowledgement)

        val later = later()
        assertTrue(later.isNotEmpty())
        for (t in later.take(5)) {
            assertEquals(Duration.ofMinutes(30), Duration.between(t.remindAt, t.dueAt))
            assertTrue(t.isUrgent)
            assertTrue(t.requiresAcknowledgement)
        }
    }

    @Test
    fun `turning Repeat off keeps the task as a one-off`() {
        val (_, seriesId) = repeatConfiguredTask()
        val medicine = db.state.tasks.first { it.dueAt == due }
        assertNotNull(medicine.seriesId)

        db.stop(seriesId, keepTaskId = medicine.id)

        val kept = db.state.tasks.single()
        assertEquals(medicine.id, kept.id)
        assertEquals("Medicine", kept.title)
        assertNull(kept.seriesId)
        assertNull(kept.occurrenceDate)
        assertEquals(medicine.dueAt, kept.dueAt)
        assertEquals(medicine.remindAt, kept.remindAt)
        assertTrue(kept.isUrgent)
        assertTrue(db.state.series.isEmpty())
    }

    @Test
    fun `turning urgent off on an occurrence asks, and carries forward`() {
        repeatConfiguredTask()
        val medicine = db.state.tasks.first { it.dueAt == due }
        assertEquals(EditScope.Future, EditScope.preselected(isDateEdit = false))

        db.update(medicine.id, TaskEdit(isUrgent = Edit(false)), EditScope.Future)

        for (t in later().take(5)) {
            assertFalse(t.isUrgent)
            assertNotNull(t.remindAt)
        }
        assertTrue(db.state.series.any { !it.template.isUrgent })
    }

    @Test
    fun `a repeat set in the create sheet copies its reminder too`() {
        val dueAt = local(2027, 3, 20, 18)
        createAndRepeat(
            "Water plants",
            dueAt = dueAt,
            remindAt = dueAt.minus(ReminderFollow.AutoReminderGap),
            anchorAt = dueAt,
            priority = "medium",
        )
        expectDailyWithReminder()
    }

    @Test
    fun `late at night (2330) - a date with its default time repeats from tomorrow at 9`() {
        db.now = late
        createAndRepeat(
            "Water plants",
            dueAt = tomorrow9,
            remindAt = tomorrow9.minus(ReminderFollow.AutoReminderGap),
            anchorAt = tomorrow9,
            priority = "medium",
        )
        expectDailyWithReminder()
        val first = byDue().first()
        assertEquals(tomorrow9, first.dueAt)
        assertEquals(tomorrow9.minus(Duration.ofHours(1)), first.remindAt)
    }

    @Test
    fun `late at night (2330) - no date at all, the same`() {
        db.now = late
        createAndRepeat(
            "Stretch",
            dueAt = tomorrow9,
            remindAt = tomorrow9.minus(ReminderFollow.AutoReminderGap),
            anchorAt = tomorrow9,
            priority = "medium",
        )
        expectDailyWithReminder()
        assertEquals(tomorrow9, byDue().first().dueAt)
    }

    @Test
    fun `late at night (2330) - a picked time is kept`() {
        db.now = late
        val picked = local(2027, 3, 20, 23, 59)
        createAndRepeat("Log", dueAt = picked, remindAt = null, anchorAt = picked, priority = "medium")
        assertEquals(picked, byDue().first().dueAt)
    }

    @Test
    fun `late at night (2330) - Repeat on the detail screen for a dateless task`() {
        db.now = late
        val id = db.createTask(title = "Journal")
        val anchor = WallClock.wallClock(tomorrow9, zone)
        val first: LocalDateTime = RepeatEditor.firstRepeatDue(daily, anchor)!!
        val firstAt = WallClock.toInstant(zone, first.toLocalDate(), first.toLocalTime())
        val autoReminder = firstAt.minus(ReminderFollow.AutoReminderGap).takeIf { it > late }
        db.update(id, TaskEdit(dueAt = Edit(firstAt), remindAt = Edit(autoReminder)))
        db.start(daily, anchorAt = firstAt, timezone = zone, template = null, fromTaskId = id)

        val row = byDue().first()
        assertEquals(tomorrow9, row.dueAt)
        assertEquals(tomorrow9.minus(Duration.ofHours(1)), row.remindAt)
    }

    @Test
    fun `Repeat on a task without a date gives it its first deadline`() {
        val anchor = WallClock.wallClock(local(2027, 3, 20, 18), zone)
        val first = RepeatEditor.firstRepeatDue(daily, anchor)!!
        val firstAt = WallClock.toInstant(zone, first.toLocalDate(), first.toLocalTime())
        createAndRepeat(
            "Pay insurance",
            dueAt = firstAt,
            remindAt = firstAt.minus(ReminderFollow.AutoReminderGap),
            anchorAt = firstAt,
            priority = "medium",
        )
        val rows = db.state.tasks
        assertTrue(rows.size > 1)
        assertTrue(rows.all { it.dueAt != null })
    }

    private val afterRule = RepeatRule(freq = RepeatFreq.Daily, interval = 90, basis = RepeatBasis.Completion)

    private fun seedAfterCompletion(): Pair<OccurrenceRow, String> {
        val seriesId = db.start(
            afterRule,
            anchorAt = due,
            timezone = zone,
            template = SeriesTemplate(mapOf(SeriesTemplate.Title to JsonPrimitive("Water filter"))),
        )
        val task = db.rowsOf(seriesId).single()
        assertEquals("Water filter", task.title)
        assertEquals(due, task.dueAt)
        return task to seriesId
    }

    @Test
    fun `a title edit asks no scope question and edits only the row`() {
        val (task, seriesId) = seedAfterCompletion()

        db.update(task.id, TaskEdit(title = Edit("Change the water filter")))

        assertEquals("Change the water filter", db.row(task.id).title)
        val series = db.state.series
        assertEquals(1, series.size)
        assertEquals("Water filter", series.single().template.title)
        assertEquals(seriesId, db.row(task.id).seriesId)
        assertEquals(1, db.state.tasks.size)
    }

    @Test
    fun `stopping says the task stays, and it does`() {
        val (task, seriesId) = seedAfterCompletion()

        db.stop(seriesId, keepTaskId = task.id)

        assertTrue(db.state.series.isEmpty())
        val kept = db.rowOrNull(task.id)
        assertNotNull(kept)
        assertEquals("open", kept!!.status)
        assertNull(kept.seriesId)
    }

    @Test
    fun `deleting the open one asks nothing and stops the repeat`() {
        val (task, seriesId) = seedAfterCompletion()

        db.deleteTask(task.id)
        db.stop(seriesId, fromDay = task.occurrenceDate)

        assertNull(db.rowOrNull(task.id))
        assertTrue(db.state.tasks.isEmpty())
        assertTrue(db.state.series.isEmpty())
        assertTrue(db.state.skips.isEmpty())
    }
}
