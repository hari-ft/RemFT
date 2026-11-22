package com.ftapps.remft.domain.repeat

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class SeriesContentTest {
    private val t0 = utc(2027, 3, 20, 10)
    private val db = SeriesTestDb(t0)

    private val daily = RepeatRule(freq = RepeatFreq.Daily)
    private val mondays = RepeatRule(freq = RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("MO")))
    private val plants = SeriesTemplate(mapOf(SeriesTemplate.Title to JsonPrimitive("Water the plants")))

    private fun utc(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Instant =
        LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC)

    private fun start(rule: RepeatRule, anchorAt: Instant): String =
        db.start(rule, anchorAt, timezone = "UTC", template = plants)

    private fun daysOf(seriesId: String): List<String> = db.rowsOf(seriesId).map { it.occurrenceDate!! }

    private fun addContent(taskId: String) = db.write(taskId) { it.copy(hasAttachedContent = true) }

    private fun expectContent(taskId: String) {
        val row = db.row(taskId)
        assertTrue(row.hasAttachedContent)
        assertTrue(db.binned.none { it.id == taskId })
        assertTrue(db.dropped.none { it.id == taskId })
    }

    private fun configured(due: Instant, remind: Instant): String =
        db.createTask(title = "Medicine", dueAt = due, remindAt = remind)

    private fun startFrom(origin: String): String =
        db.start(daily, utc(2027, 3, 20, 12), timezone = "UTC", template = null, fromTaskId = origin)

    private fun storeUpdate(id: String, edit: TaskEdit, scope: EditScope? = null, zone: String = "UTC") {
        val record = db.row(id)
        val newDue = edit.dueAt?.value
        val oldDue = record.dueAt
        val remind = record.remindAt
        val followed = if (edit.dueAt != null && edit.remindAt == null && newDue != null && oldDue != null && remind != null &&
            ReminderFollow.follows(oldDue, newDue, remind) { a, b -> WallClock.wallDay(a, zone) == WallClock.wallDay(b, zone) }
        ) {
            remind.plus(Duration.between(oldDue, newDue))
        } else {
            null
        }
        db.update(id, edit, scope)
        val engineRan = record.seriesId != null && (scope == EditScope.Future || scope == EditScope.All)
        if (followed != null && !engineRan) db.write(id) { it.copy(remindAt = followed) }
    }

    private fun local(zone: String, y: Int, m: Int, d: Int, h: Int, min: Int = 0): Instant = WallClock.toInstant(zone, y, m, d, h, min)

    @Test
    fun `a rename - checklist, file, note link and alarm answer stay`() {
        val id = start(daily, utc(2027, 3, 20, 9))
        val later = db.onDay(id, "2027-03-28")
        addContent(later.id)
        db.update(db.onDay(id, "2027-03-25").id, TaskEdit(title = Edit("Water the ferns")), EditScope.Future)

        val kept = db.row(later.id)
        assertNotEquals(id, kept.seriesId)
        assertEquals("2027-03-28", kept.occurrenceDate)
        assertEquals("Water the ferns", kept.title)
        expectContent(later.id)
        assertTrue(db.binned.isEmpty())
    }

    @Test
    fun `a move to another weekday carries the row to its new day`() {
        val id = start(mondays, utc(2027, 3, 22, 9))
        val later = db.onDay(id, "2027-04-12")
        addContent(later.id)
        val newDue = utc(2027, 3, 31, 9)
        db.update(db.onDay(id, "2027-03-29").id, TaskEdit(dueAt = Edit(newDue)), EditScope.Future)

        val kept = db.row(later.id)
        assertEquals("2027-04-14", kept.occurrenceDate)
        assertEquals(utc(2027, 4, 14, 9), kept.dueAt)
        expectContent(later.id)
        val next = daysOf(kept.seriesId!!)
        assertEquals(listOf("2027-03-31", "2027-04-07", "2027-04-14"), next.take(3))
        assertEquals(next.size, next.toSet().size)
        assertTrue(db.binned.isEmpty())
    }

    @Test
    fun `"all" moving the pattern carries the row too`() {
        val id = start(mondays, utc(2027, 3, 22, 9))
        val later = db.onDay(id, "2027-04-12")
        addContent(later.id)
        val newDue = utc(2027, 3, 31, 9)
        db.update(db.onDay(id, "2027-03-29").id, TaskEdit(dueAt = Edit(newDue)), EditScope.All)

        val kept = db.row(later.id)
        assertEquals(id, kept.seriesId)
        assertEquals("2027-04-14", kept.occurrenceDate)
        expectContent(later.id)
        val days = daysOf(id)
        assertEquals(days.size, days.toSet().size)
        for (d in days) assertEquals(d, DayOfWeek.WEDNESDAY, LocalDate.parse(d).dayOfWeek)
    }

    @Test
    fun `a one-off edit of a later occurrence survives a rename`() {
        val id = start(daily, utc(2027, 3, 20, 9))
        val later = db.onDay(id, "2027-03-28")
        db.write(later.id) { it.copy(description = "Use rain water") }
        db.update(db.onDay(id, "2027-03-25").id, TaskEdit(priority = Edit("high")), EditScope.Future)
        val kept = db.row(later.id)
        assertEquals("Use rain water", kept.description)
        assertEquals("high", kept.priority)
    }

    @Test
    fun `"this and future" on an overdue occurrence keeps the other overdue ones`() {
        val id = start(daily, utc(2027, 3, 20, 9))
        val monday = db.onDay(id, "2027-03-22")
        val tuesday = db.onDay(id, "2027-03-23")
        db.now = utc(2027, 3, 24, 10)
        db.update(monday.id, TaskEdit(title = Edit("Water the ferns")), EditScope.Future)

        val next = db.row(monday.id).seriesId!!
        val kept = db.row(tuesday.id)
        assertEquals(next, kept.seriesId)
        assertEquals("2027-03-23", kept.occurrenceDate)
        assertEquals("Water the ferns", kept.title)
        assertEquals(listOf("2027-03-20", "2027-03-21"), daysOf(id))
        val days = daysOf(next)
        assertEquals(listOf("2027-03-22", "2027-03-23", "2027-03-24"), days.take(3))
        assertEquals(days.size, days.toSet().size)
    }

    @Test
    fun `a deleted occurrence stays deleted after a split - a rename`() {
        val id = start(daily, utc(2027, 3, 20, 9))
        db.deleteTask(db.onDay(id, "2027-03-27").id)
        val target = db.onDay(id, "2027-03-25")
        db.update(target.id, TaskEdit(title = Edit("Water the ferns")), EditScope.Future)
        val next = db.row(target.id).seriesId!!
        assertFalse(daysOf(next).contains("2027-03-27"))
        db.roll(at = t0.plus(Duration.ofDays(1)))
        assertFalse(daysOf(next).contains("2027-03-27"))
        assertTrue(daysOf(next).contains("2027-03-28"))
    }

    @Test
    fun `a deleted occurrence stays deleted after a split - a move, the deleted day moves with the pattern`() {
        val id = start(mondays, utc(2027, 3, 22, 9))
        db.deleteTask(db.onDay(id, "2027-04-12").id)
        val target = db.onDay(id, "2027-03-29")
        db.update(target.id, TaskEdit(dueAt = Edit(utc(2027, 3, 31, 9))), EditScope.Future)
        val next = db.row(target.id).seriesId!!
        db.roll(at = t0.plus(Duration.ofDays(1)))
        assertEquals(listOf("2027-03-31", "2027-04-07", "2027-04-21"), daysOf(next).take(3))
    }

    @Test
    fun `a rule edit dropping a day bins only the row with content`() {
        val id = start(daily, utc(2027, 3, 20, 9))
        val thursday = db.onDay(id, "2027-03-25")
        addContent(thursday.id)
        db.updateRule(id, RepeatRule(freq = RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("FR"))))

        val binned = db.binned.single()
        assertEquals(thursday.id, binned.id)
        assertEquals("Water the plants", binned.title)
        assertTrue(binned.hasAttachedContent)
        assertTrue(db.dropped.isNotEmpty())
        assertNull(db.rowOrNull(thursday.id))
    }

    @Test
    fun `deleting "this and future" bins the rows holding content`() {
        val id = start(daily, utc(2027, 3, 20, 9))
        val later = db.onDay(id, "2027-03-28")
        addContent(later.id)
        db.stop(id, fromDay = "2027-03-25")
        assertEquals(later.id, db.binned.single().id)
        assertNull(db.rowOrNull(later.id))
    }

    @Test
    fun `today's occurrence stays, with its content, as a one-off`() {
        val id = start(daily, utc(2027, 3, 20, 12))
        val today = db.onDay(id, "2027-03-20")
        addContent(today.id)
        val bare = db.onDay(id, "2027-03-22")
        val owned = db.onDay(id, "2027-03-24")
        addContent(owned.id)

        db.stop(id, keepTaskId = today.id)

        val kept = db.row(today.id)
        assertNull(kept.seriesId)
        assertNull(kept.occurrenceDate)
        assertEquals("Water the plants", kept.title)
        assertEquals(today.dueAt, kept.dueAt)
        expectContent(today.id)
        val left = db.state.tasks
        assertEquals(listOf(today.id), left.map { it.id })
        assertFalse(left.any { it.id == bare.id })
        assertTrue(db.dropped.any { it.id == bare.id })
        assertEquals(owned.id, db.binned.single().id)
        assertTrue(db.state.series.isEmpty())
    }

    @Test
    fun `a later occurrence being edited is the one kept`() {
        val id = start(daily, utc(2027, 3, 20, 12))
        val later = db.onDay(id, "2027-03-25")
        db.stop(id, keepTaskId = later.id)
        val left = db.state.tasks
        assertEquals(later.id, left.single().id)
        assertNull(left.single().seriesId)
        assertEquals(utc(2027, 3, 25, 12), left.single().dueAt)
        assertTrue(db.binned.isEmpty())
    }

    @Test
    fun `finished occurrences stay in the history`() {
        val id = start(daily, utc(2027, 3, 20, 12))
        val done = db.onDay(id, "2027-03-21")
        db.setStatus(done.id, "completed")
        val kept = db.onDay(id, "2027-03-22")
        db.stop(id, keepTaskId = kept.id)
        assertEquals(setOf(done.id, kept.id), db.state.tasks.map { it.id }.toSet())
    }

    @Test
    fun `"only this" on an occurrence`() {
        val origin = configured(utc(2027, 3, 20, 12), utc(2027, 3, 20, 11, 30))
        val id = startFrom(origin)
        val target = db.onDay(id, "2027-03-25")
        storeUpdate(target.id, TaskEdit(dueAt = Edit(utc(2027, 3, 26, 15))), EditScope.This)
        val moved = db.row(target.id)
        assertEquals(utc(2027, 3, 26, 15), moved.dueAt)
        assertEquals(utc(2027, 3, 26, 14, 30), moved.remindAt)
        val other = db.onDay(id, "2027-03-26")
        assertEquals(utc(2027, 3, 26, 11, 30), other.remindAt)
    }

    @Test
    fun `"this and future" keeps the series' reminder offset`() {
        val origin = configured(utc(2027, 3, 20, 12), utc(2027, 3, 20, 11, 30))
        val id = startFrom(origin)
        val target = db.onDay(id, "2027-03-25")
        storeUpdate(target.id, TaskEdit(dueAt = Edit(utc(2027, 3, 26, 15))), EditScope.Future)
        val moved = db.row(target.id)
        assertEquals(utc(2027, 3, 26, 14, 30), moved.remindAt)
        assertEquals(30, db.series(moved.seriesId!!)!!.template.remindMinutesBefore)
    }

    private fun handSetReminderStaysWhenOnlyTheTimeChanges(scope: EditScope) {
        val origin = configured(utc(2027, 3, 20, 12), utc(2027, 3, 20, 11, 30))
        val id = startFrom(origin)
        val target = db.onDay(id, "2027-03-25")
        storeUpdate(target.id, TaskEdit(dueAt = Edit(utc(2027, 3, 25, 15))), scope)
        val edited = db.row(target.id)
        assertEquals(utc(2027, 3, 25, 15), edited.dueAt)
        assertEquals(utc(2027, 3, 25, 11, 30), edited.remindAt)
        val seriesId = edited.seriesId!!
        for (day in listOf("2027-03-26", "2027-04-10")) {
            val r = db.onDay(seriesId, day)
            assertEquals(day, 15, LocalDateTime.ofInstant(r.dueAt, ZoneOffset.UTC).hour)
            assertEquals(day, Instant.parse("${day}T11:30:00Z"), r.remindAt)
        }
        assertEquals(3 * 60 + 30, db.series(seriesId)!!.template.remindMinutesBefore)
    }

    private fun derivedReminderFollows(scope: EditScope) {
        val origin = configured(utc(2027, 3, 20, 12), utc(2027, 3, 20, 11))
        val id = startFrom(origin)
        val target = db.onDay(id, "2027-03-25")
        storeUpdate(target.id, TaskEdit(dueAt = Edit(utc(2027, 3, 25, 15))), scope)
        val edited = db.row(target.id)
        assertEquals(utc(2027, 3, 25, 14), edited.remindAt)
        assertEquals(utc(2027, 3, 26, 14), db.onDay(edited.seriesId!!, "2027-03-26").remindAt)
    }

    private fun deadlineMovedBeforeHandSetReminderTakesItAlong(scope: EditScope) {
        val origin = configured(utc(2027, 3, 20, 12), utc(2027, 3, 20, 11, 30))
        val id = startFrom(origin)
        val target = db.onDay(id, "2027-03-25")
        storeUpdate(target.id, TaskEdit(dueAt = Edit(utc(2027, 3, 25, 11))), scope)
        val edited = db.row(target.id)
        assertEquals(utc(2027, 3, 25, 10, 30), edited.remindAt)
        assertEquals(utc(2027, 3, 26, 10, 30), db.onDay(edited.seriesId!!, "2027-03-26").remindAt)
    }

    @Test
    fun `"future" - a hand-set reminder stays when only the time changes`() = handSetReminderStaysWhenOnlyTheTimeChanges(EditScope.Future)

    @Test
    fun `"all" - a hand-set reminder stays when only the time changes`() = handSetReminderStaysWhenOnlyTheTimeChanges(EditScope.All)

    @Test
    fun `"future" - the derived reminder (1 h before) follows`() = derivedReminderFollows(EditScope.Future)

    @Test
    fun `"all" - the derived reminder (1 h before) follows`() = derivedReminderFollows(EditScope.All)

    @Test
    fun `"future" - a deadline moved before a hand-set reminder takes it along`() =
        deadlineMovedBeforeHandSetReminderTakesItAlong(EditScope.Future)

    @Test
    fun `"all" - a deadline moved before a hand-set reminder takes it along`() =
        deadlineMovedBeforeHandSetReminderTakesItAlong(EditScope.All)

    @Test
    fun `a hand-set reminder stays when only the time of day changes`() {
        val zone = "Europe/Berlin"
        val remind = local(zone, 2027, 3, 25, 22, 35)
        val id = configured(local(zone, 2027, 3, 25, 22, 40), remind)
        storeUpdate(id, TaskEdit(dueAt = Edit(local(zone, 2027, 3, 25, 22, 45))), zone = zone)
        assertEquals(remind, db.row(id).remindAt)
    }

    @Test
    fun `the derived reminder (1 h before) follows a time change`() {
        val zone = "Europe/Berlin"
        val id = configured(local(zone, 2027, 3, 25, 18), local(zone, 2027, 3, 25, 17))
        storeUpdate(id, TaskEdit(dueAt = Edit(local(zone, 2027, 3, 25, 20))), zone = zone)
        assertEquals(local(zone, 2027, 3, 25, 19), db.row(id).remindAt)
    }

    @Test
    fun `a deadline moved before its reminder takes it along`() {
        val zone = "Europe/Berlin"
        val id = configured(local(zone, 2027, 3, 25, 22, 40), local(zone, 2027, 3, 25, 22, 35))
        storeUpdate(id, TaskEdit(dueAt = Edit(local(zone, 2027, 3, 25, 22, 30))), zone = zone)
        assertEquals(local(zone, 2027, 3, 25, 22, 25), db.row(id).remindAt)
    }

    @Test
    fun `a plain task`() {
        val id = configured(utc(2027, 3, 25, 12), utc(2027, 3, 25, 11))
        storeUpdate(id, TaskEdit(dueAt = Edit(utc(2027, 3, 27, 9))))
        assertEquals(utc(2027, 3, 27, 8), db.row(id).remindAt)

        storeUpdate(id, TaskEdit(dueAt = Edit(utc(2027, 3, 28, 9)), remindAt = Edit(utc(2027, 3, 28, 7))))
        assertEquals(utc(2027, 3, 28, 7), db.row(id).remindAt)

        storeUpdate(id, TaskEdit(dueAt = Edit(null)))
        assertEquals(utc(2027, 3, 28, 7), db.row(id).remindAt)
    }
}
