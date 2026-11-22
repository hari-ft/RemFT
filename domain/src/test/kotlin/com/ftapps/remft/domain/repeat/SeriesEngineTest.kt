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

class SeriesEngineTest {
    private val t0 = utc(2027, 3, 20, 10)
    private val db = SeriesTestDb(t0)
    private val daily = RepeatRule(freq = RepeatFreq.Daily)
    private val drinkWater = titled("Drink water")

    private fun titled(title: String): SeriesTemplate = SeriesTemplate(mapOf(SeriesTemplate.Title to JsonPrimitive(title)))

    private fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC)

    private fun start(
        rule: RepeatRule,
        anchorAt: Instant,
        timezone: String = "UTC",
        template: SeriesTemplate? = drinkWater,
        fromTaskId: String? = null,
        now: Instant = t0,
    ): String = db.start(rule, anchorAt, timezone, template, fromTaskId, now)

    private fun days(seriesId: String): List<String> = db.rowsOf(seriesId).map { it.occurrenceDate!! }

    private fun ruleOf(seriesId: String): RepeatRule = db.series(seriesId)!!.rule

    private fun templateOf(seriesId: String): SeriesTemplate = db.series(seriesId)!!.template

    private fun weekday(day: String): DayOfWeek = LocalDate.parse(day).dayOfWeek

    private fun allRows(): List<OccurrenceRow> = db.state.tasks.sortedBy { it.dueAt }

    private fun allDueDays(): List<String> = allRows().map { it.dueAt!!.toString().substring(0, 10) }

    private fun expectNoDuplicates(days: List<String>) = assertEquals(days.toString(), days.size, days.toSet().size)

    private fun propagate(at: Instant, id: String, edit: TaskEdit, scope: EditScope) {
        db.apply(db.engine(at).propagateScope(db.state, db.row(id), edit, scope))
    }

    private fun moveTo(target: OccurrenceRow, newDue: Instant, scope: EditScope, at: Instant = t0): OccurrenceRow {
        db.write(target.id) { it.copy(dueAt = newDue) }
        propagate(at, target.id, TaskEdit(dueAt = Edit(newDue)), scope)
        return db.row(target.id)
    }

    private fun until(rule: RepeatRule): String? = (rule.end as? RepeatEnd.Until)?.until

    private fun count(rule: RepeatRule): Int? = (rule.end as? RepeatEnd.Count)?.count

    @Test
    fun `a daily series fills today → +12 months, adopting its origin task`() {
        val origin = db.createTask(title = "Drink water", dueAt = utc(2027, 3, 20, 9))
        val id = start(daily, anchorAt = utc(2027, 3, 20, 9), fromTaskId = origin)

        val rows = db.rowsOf(id)
        assertEquals("2027-03-20", rows.first().occurrenceDate)
        assertEquals("2028-03-19", rows.last().occurrenceDate)
        assertEquals(366, rows.size)
        assertEquals(origin, rows.first().id)
        assertEquals(1, rows.count { it.occurrenceDate == "2027-03-20" })
        assertEquals(utc(2027, 3, 21, 9), rows[1].dueAt)
        assertEquals("Drink water", rows[1].title)

        assertEquals(0, db.roll(t0))
        assertEquals(366, db.rowsOf(id).size)
    }

    @Test
    fun `a later day extends the window by exactly the new days`() {
        val id = start(daily, anchorAt = utc(2027, 3, 20, 9))
        val later = t0.plus(Duration.ofDays(10))
        assertEquals(10, db.roll(later))
        assertEquals("2028-03-29", db.rowsOf(id).last().occurrenceDate)
    }

    @Test
    fun `0900 stays 0900 across a daylight-saving change`() {
        val id = start(
            RepeatRule(freq = RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("MO"))),
            anchorAt = utc(2027, 3, 22, 8),
            timezone = "Europe/Berlin",
        )
        assertEquals(utc(2027, 3, 22, 8), db.onDay(id, "2027-03-22").dueAt)
        assertEquals(utc(2027, 3, 29, 7), db.onDay(id, "2027-03-29").dueAt)
        assertEquals(utc(2027, 11, 1, 8), db.onDay(id, "2027-11-01").dueAt)
    }

    @Test
    fun `"the 31st" clamps to each month's last day`() {
        val id = start(
            RepeatRule(freq = RepeatFreq.Monthly, byMonthDay = listOf(31)),
            anchorAt = utc(2027, 3, 31, 9),
        )
        val days = db.rowsOf(id).map { it.occurrenceDate }
        assertEquals(listOf("2027-03-31", "2027-04-30", "2027-05-31", "2027-06-30"), days.take(4))
        assertTrue(days.contains("2028-02-29"))
        assertEquals(12, days.size)
    }

    @Test
    fun `a 29 February yearly series lands on the 28th in a common year`() {
        val now = utc(2028, 3, 1, 10)
        val id = start(RepeatRule(freq = RepeatFreq.Yearly), anchorAt = utc(2028, 2, 29, 9), now = now)
        assertEquals(listOf("2029-02-28"), db.rowsOf(id).map { it.occurrenceDate })
    }

    private val completion = RepeatRule(freq = RepeatFreq.Daily, interval = 2, basis = RepeatBasis.Completion)

    private fun startCompletion(): Pair<String, String> {
        val due = utc(2027, 3, 20, 12)
        val head = db.createTask(title = "Water the plants", dueAt = due)
        val id = start(
            completion,
            anchorAt = due,
            template = titled("Water the plants"),
            fromTaskId = head,
        )
        return id to head
    }

    private fun today(): String = WallClock.wallDay(t0, "UTC")

    private fun plusDays(day: String, n: Int): String = RepeatDays.addDays(day, n)

    private fun complete(id: String) = db.setStatus(id, "completed", t0)

    private fun reopen(id: String) = db.setStatus(id, "open")

    @Test
    fun `the adopted task is the one open occurrence`() {
        val (id, head) = startCompletion()
        val rows = db.rowsOf(id)
        assertEquals(head, rows.single().id)
        assertEquals(today(), rows.single().occurrenceDate)
        assertEquals(0, db.roll())
    }

    @Test
    fun `completing spawns the next, two days after the completion`() {
        val (id, head) = startCompletion()
        complete(head)

        val rows = db.rowsOf(id)
        assertEquals(2, rows.size)
        val next = rows.single { it.id != head }
        assertEquals(head, next.spawnedFromTaskId)
        assertEquals("open", next.status)
        assertEquals(plusDays(today(), 2), next.occurrenceDate)
        assertEquals(12, next.dueAt!!.atZone(ZoneOffset.UTC).hour)
        assertEquals("Water the plants", next.title)
    }

    @Test
    fun `uncompleting withdraws the spawn, completing again makes ONE`() {
        val (id, head) = startCompletion()
        complete(head)
        reopen(head)
        assertEquals(head, db.rowsOf(id).single().id)

        complete(head)
        assertEquals(2, db.rowsOf(id).size)
        val again = db.engine(t0).spawnAfterCompletion(db.state, db.row(head))
        assertTrue(again.insertTasks.isEmpty())
        db.apply(again)
        assertEquals(2, db.rowsOf(id).size)
    }

    @Test
    fun `re-completing a row whose successor is already done spawns nothing`() {
        val (id, head) = startCompletion()
        complete(head)
        val second = db.rowsOf(id).single { it.id != head }
        complete(second.id)
        val third = db.rowsOf(id).single { it.spawnedFromTaskId == second.id }
        db.deleteTask(third.id)
        reopen(head)
        complete(head)
        val rows = db.rowsOf(id)
        assertEquals(1, rows.count { it.spawnedFromTaskId == head })
        assertEquals(2, rows.size)
    }

    @Test
    fun `completing an older occurrence while a newer one is open forks nothing`() {
        val (id, head) = startCompletion()
        val old = db.createTask(title = "Water the plants")
        db.write(old) { it.copy(seriesId = id, occurrenceDate = plusDays(today(), -4)) }
        complete(old)
        val rows = db.rowsOf(id)
        assertEquals(2, rows.size)
        assertTrue(rows.none { it.spawnedFromTaskId != null })
        assertEquals("open", db.row(head).status)
    }

    @Test
    fun `a count-limited series ends`() {
        val now = t0
        val head = db.createTask(title = "Twice", dueAt = now)
        val id = start(
            completion.copy(end = RepeatEnd.Count(2)),
            anchorAt = now,
            template = titled("Twice"),
            fromTaskId = head,
        )
        complete(head)
        val second = db.rowsOf(id).single { it.id != head }
        complete(second.id)
        assertEquals(2, db.rowsOf(id).size)
    }

    @Test
    fun `rebuilds the future, keeping completed and one-off-edited rows`() {
        val id = start(daily, anchorAt = utc(2027, 3, 20, 9))
        val monday = db.onDay(id, "2027-03-22")
        val tuesday = db.onDay(id, "2027-03-23")
        val thursday = db.onDay(id, "2027-03-25")
        db.setStatusRaw(monday.id, "completed", t0)
        db.setStatusRaw(tuesday.id, "completed", t0)
        db.write(thursday.id) { it.copy(title = "Custom") }

        db.updateRule(id, RepeatRule(freq = RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("TH"))))

        val rows = db.rowsOf(id)
        assertTrue(rows.map { it.id }.containsAll(listOf(monday.id, tuesday.id)))
        val kept = rows.single { it.occurrenceDate == "2027-03-25" }
        assertEquals(thursday.id, kept.id)
        assertEquals("Custom", kept.title)
        val open = rows.filter { it.status == "open" }
        for (r in open) {
            assertEquals(r.occurrenceDate, DayOfWeek.THURSDAY, weekday(r.occurrenceDate!!))
        }
        assertTrue(open.map { it.occurrenceDate }.contains("2027-04-01"))
        assertEquals(52, open.size)
    }

    @Test
    fun `an after-completion rule edit keeps exactly one open occurrence`() {
        val rule = RepeatRule(freq = RepeatFreq.Daily, basis = RepeatBasis.Completion)
        val id = start(rule, anchorAt = utc(2027, 3, 20, 9))
        assertEquals(1, db.rowsOf(id).size)
        db.updateRule(id, rule.copy(interval = 3))
        assertEquals(1, db.rowsOf(id).count { it.status == "open" })
    }

    @Test
    fun `stopping - the future goes, the past and the finished stay`() {
        val id = start(daily, anchorAt = utc(2027, 3, 20, 9))
        val done = db.onDay(id, "2027-04-05")
        db.setStatusRaw(done.id, "completed", t0)

        db.now = t0.plus(Duration.ofDays(10))
        db.stop(id)

        val left = db.rowsOf(id)
        assertEquals((20..29).map { "2027-03-$it" }, left.filter { it.status == "open" }.map { it.occurrenceDate })
        assertEquals(done.id, left.single { it.status == "completed" }.id)
        assertNull(db.series(id))
        assertTrue(db.state.skips.isEmpty())
    }

    @Test
    fun `stopping - "this and future" from a swiped occurrence`() {
        val id = start(daily, anchorAt = utc(2027, 3, 20, 9))
        db.stop(id, fromDay = "2027-03-25")
        assertEquals((20..24).map { "2027-03-$it" }, db.rowsOf(id).map { it.occurrenceDate })
    }

    @Test
    fun `an after-completion series keeps its open task as a plain one`() {
        val id = start(
            RepeatRule(freq = RepeatFreq.Daily, basis = RepeatBasis.Completion),
            anchorAt = utc(2027, 3, 20, 9),
        )
        val head = db.rowsOf(id).single()
        db.stop(id)
        val plain = db.row(head.id)
        assertNull(plain.seriesId)
        assertNull(plain.occurrenceDate)
        assertEquals("open", plain.status)
    }

    @Test
    fun `a deleted occurrence stays deleted across later window rolls`() {
        val id = start(daily, anchorAt = utc(2027, 3, 20, 9))
        val victim = db.onDay(id, "2027-03-25")
        db.deleteTask(victim.id)

        assertEquals(0, db.roll(t0))
        assertEquals(30, db.roll(t0.plus(Duration.ofDays(30))))
        val days = db.rowsOf(id).map { it.occurrenceDate }
        assertFalse(days.contains("2027-03-25"))
        assertTrue(days.contains("2027-03-26"))
    }

    @Test
    fun `"this" changes only this occurrence`() {
        val id = start(daily, anchorAt = utc(2027, 3, 20, 9))
        val target = db.onDay(id, "2027-03-25")
        db.update(target.id, TaskEdit(title = Edit("Just today")), EditScope.This)
        val rows = db.rowsOf(id)
        assertEquals(target.id, rows.single { it.title == "Just today" }.id)
        assertEquals("Drink water", templateOf(id).title)
    }

    @Test
    fun `"all" carries the edit to every unfinished occurrence and the template`() {
        val id = start(daily, anchorAt = utc(2027, 3, 20, 9))
        val done = db.onDay(id, "2027-03-21")
        db.setStatusRaw(done.id, "completed", t0)
        val target = db.onDay(id, "2027-03-25")
        db.update(target.id, TaskEdit(title = Edit("Drink plenty of water"), priority = Edit("high")), EditScope.All)
        val rows = db.rowsOf(id)
        assertEquals("Drink water", db.row(done.id).title)
        val open = rows.filter { it.status == "open" }
        assertTrue(open.all { it.title == "Drink plenty of water" })
        assertTrue(open.all { it.priority == "high" })
        assertEquals("Drink plenty of water", templateOf(id).title)
        db.roll(t0.plus(Duration.ofDays(1)))
        assertEquals("Drink plenty of water", db.rowsOf(id).last().title)
    }

    @Test
    fun `"all" with a new time of day moves every occurrence, not the rule`() {
        val id = start(daily, anchorAt = utc(2027, 3, 20, 8), timezone = "Europe/Berlin")
        val target = db.onDay(id, "2027-03-25")
        val newDue = utc(2027, 3, 25, 10)
        db.write(target.id) { it.copy(dueAt = newDue) }
        propagate(t0, target.id, TaskEdit(dueAt = Edit(newDue)), EditScope.All)

        assertEquals(utc(2027, 3, 26, 10), db.onDay(id, "2027-03-26").dueAt)
        assertEquals(utc(2027, 4, 2, 9), db.onDay(id, "2027-04-02").dueAt)
        val s = db.series(id)!!
        assertEquals(RepeatFreq.Daily, s.rule.freq)
        assertEquals(utc(2027, 3, 20, 10), s.anchorAt)
        assertEquals(366, db.rowsOf(id).size)
    }

    @Test
    fun `"all" moving a weekly occurrence to another day moves the pattern`() {
        val id = start(
            RepeatRule(freq = RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("TH"))),
            anchorAt = utc(2027, 3, 25, 9),
        )
        val target = db.onDay(id, "2027-03-25")
        val friday = utc(2027, 3, 26, 9)
        db.write(target.id) { it.copy(dueAt = friday) }
        propagate(t0, target.id, TaskEdit(dueAt = Edit(friday)), EditScope.All)

        assertEquals("FR", ruleOf(id).byWeekday.single().day)
        val rows = db.rowsOf(id)
        assertEquals("2027-03-26", db.row(target.id).occurrenceDate)
        for (r in rows) {
            assertEquals(DayOfWeek.FRIDAY, weekday(r.occurrenceDate!!))
        }
        assertEquals(1, rows.count { it.occurrenceDate == "2027-04-02" })
    }

    @Test
    fun `"this and future" splits the series at the occurrence`() {
        val id = start(daily, anchorAt = utc(2027, 3, 20, 9))
        val target = db.onDay(id, "2027-03-25")
        db.write(target.id) { it.copy(title = "New layout") }
        propagate(t0, target.id, TaskEdit(title = Edit("New layout")), EditScope.Future)

        val old = db.rowsOf(id)
        assertEquals((20..24).map { "2027-03-$it" }, old.map { it.occurrenceDate })
        assertEquals("2027-03-24", until(ruleOf(id)))

        val moved = db.row(target.id)
        assertNotEquals(id, moved.seriesId)
        val next = db.rowsOf(moved.seriesId!!)
        assertEquals(target.id, next.first().id)
        assertEquals("2027-03-25", next.first().occurrenceDate)
        assertTrue(next.all { it.title == "New layout" })
        assertEquals(366, old.size + next.size)
    }

    @Test
    fun `this and future - monthly, no day set - 15th → 20th, nothing before or twice`() {
        val id = start(RepeatRule(freq = RepeatFreq.Monthly), anchorAt = utc(2027, 4, 15, 9))
        val target = db.onDay(id, "2027-05-15")
        val moved = moveTo(target, utc(2027, 6, 20, 9), EditScope.Future)

        assertEquals(listOf("2027-04-15"), days(id))
        assertEquals("2027-05-14", until(ruleOf(id)))
        val next = moved.seriesId!!
        assertNotEquals(id, next)
        assertEquals("2027-06-20", moved.occurrenceDate)
        assertEquals(
            listOf("06", "07", "08", "09", "10", "11", "12").map { "2027-$it-20" } + listOf("2028-01-20", "2028-02-20"),
            days(next),
        )
        assertEquals(target.id, db.rowsOf(next).first().id)
        assertEquals(listOf(20), ruleOf(next).byMonthDay)
        assertEquals(utc(2027, 6, 20, 9), db.series(next)!!.anchorAt)
        val due = allDueDays()
        expectNoDuplicates(due)
        assertFalse(due.contains("2027-06-15"))
        assertFalse(due.contains("2027-05-20"))
        assertEquals(10, due.size)
    }

    @Test
    fun `this and future - weekly on Monday → Wednesday - no stray Wednesday before it`() {
        val id = start(
            RepeatRule(freq = RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("MO"))),
            anchorAt = utc(2027, 3, 22, 9),
        )
        val target = db.onDay(id, "2027-03-29")
        val moved = moveTo(target, utc(2027, 4, 7, 9), EditScope.Future)

        assertEquals(listOf("2027-03-22"), days(id))
        val next = days(moved.seriesId!!)
        assertEquals(listOf("2027-04-07", "2027-04-14", "2027-04-21"), next.take(3))
        assertEquals(50, next.size)
        for (d in next) {
            assertEquals(DayOfWeek.WEDNESDAY, weekday(d))
        }
        assertEquals("WE", ruleOf(moved.seriesId!!).byWeekday.single().day)
        val due = allDueDays()
        expectNoDuplicates(due)
        assertFalse(due.contains("2027-03-31"))
        assertEquals(51, due.size)
    }

    @Test
    fun `this and future - weekly, no day set, moved earlier than the one before it`() {
        val id = start(RepeatRule(freq = RepeatFreq.Weekly), anchorAt = utc(2027, 3, 22, 9))
        val target = db.onDay(id, "2027-04-05")
        val moved = moveTo(target, utc(2027, 3, 28, 9), EditScope.Future)

        assertEquals(listOf("2027-03-22"), days(id))
        assertEquals("2027-03-27", until(ruleOf(id)))
        val next = days(moved.seriesId!!)
        assertEquals(listOf("2027-03-28", "2027-04-04", "2027-04-11"), next.take(3))
        expectNoDuplicates(allDueDays())
    }

    @Test
    fun `this and future - quarterly on named months moves to the new cycle and day`() {
        val id = start(
            RepeatRule(freq = RepeatFreq.Monthly, byMonthDay = listOf(15), byMonth = listOf(3, 6, 9, 12)),
            anchorAt = utc(2027, 6, 15, 9),
        )
        assertEquals(listOf("2027-06-15", "2027-09-15", "2027-12-15", "2028-03-15"), days(id))
        val target = db.onDay(id, "2027-09-15")
        val moved = moveTo(target, utc(2027, 10, 20, 9), EditScope.Future)

        assertEquals(listOf("2027-06-15"), days(id))
        val rule = ruleOf(moved.seriesId!!)
        assertEquals(listOf(1, 4, 7, 10), rule.byMonth)
        assertEquals(listOf(20), rule.byMonthDay)
        assertEquals(3, Recurrence.monthStep(rule))
        assertEquals(listOf("2027-10-20", "2028-01-20"), days(moved.seriesId!!))
        val due = allDueDays()
        expectNoDuplicates(due)
        assertFalse(due.contains("2027-09-20"))
        assertEquals(3, due.size)
    }

    @Test
    fun `this and future - quarterly, interval form - months follow the new day`() {
        val id = start(RepeatRule(freq = RepeatFreq.Monthly, interval = 3), anchorAt = utc(2027, 6, 15, 9))
        val target = db.onDay(id, "2027-09-15")
        val moved = moveTo(target, utc(2027, 10, 20, 9), EditScope.Future)
        assertEquals(listOf("2027-06-15"), days(id))
        assertEquals(listOf("2027-10-20", "2028-01-20"), days(moved.seriesId!!))
        expectNoDuplicates(allDueDays())
    }

    @Test
    fun `this and future - half-yearly moves to the new pair of months`() {
        val id = start(
            RepeatRule(freq = RepeatFreq.Monthly, byMonthDay = listOf(25), byMonth = listOf(3, 9)),
            anchorAt = utc(2027, 3, 25, 9),
        )
        val target = db.onDay(id, "2027-09-25")
        val moved = moveTo(target, utc(2027, 11, 2, 9), EditScope.Future)
        assertEquals(listOf("2027-03-25"), days(id))
        val rule = ruleOf(moved.seriesId!!)
        assertEquals(listOf(5, 11), rule.byMonth)
        assertEquals(listOf(2), rule.byMonthDay)
        assertEquals(6, Recurrence.monthStep(rule))
        assertEquals(listOf("2027-11-02"), days(moved.seriesId!!))
        db.roll(utc(2028, 4, 1, 10))
        assertEquals(listOf("2027-11-02", "2028-05-02", "2028-11-02"), days(moved.seriesId!!))
        assertEquals(listOf("2027-03-25"), days(id))
    }

    @Test
    fun `this and future - yearly moves to the new month and day`() {
        val id = start(RepeatRule(freq = RepeatFreq.Yearly), anchorAt = utc(2027, 4, 10, 9))
        val target = db.onDay(id, "2027-04-10")
        val moved = moveTo(target, utc(2027, 5, 3, 9), EditScope.Future)
        assertTrue(days(id).isEmpty())
        val rule = ruleOf(moved.seriesId!!)
        assertEquals(listOf(5), rule.byMonth)
        assertEquals(listOf(3), rule.byMonthDay)
        assertEquals(listOf("2027-05-03"), days(moved.seriesId!!))
        db.roll(utc(2028, 4, 1, 10))
        assertEquals(listOf("2027-05-03", "2028-05-03"), days(moved.seriesId!!))
        assertTrue(days(id).isEmpty())
    }

    @Test
    fun `this and future - daily - the new series starts on the new day`() {
        val id = start(daily, anchorAt = utc(2027, 3, 20, 9))
        val target = db.onDay(id, "2027-03-25")
        val moved = moveTo(target, utc(2027, 3, 27, 9), EditScope.Future)
        assertEquals((20..24).map { "2027-03-$it" }, days(id))
        val next = days(moved.seriesId!!)
        assertEquals("2027-03-27", next.first())
        assertEquals("2028-03-19", next.last())
        val due = allDueDays()
        expectNoDuplicates(due)
        assertFalse(due.contains("2027-03-25"))
        assertFalse(due.contains("2027-03-26"))
    }

    @Test
    fun `this and future - a pattern with several days continues from the new day`() {
        val id = start(
            RepeatRule(freq = RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("MO"), WeekdayPick("WE"))),
            anchorAt = utc(2027, 3, 22, 9),
        )
        val target = db.onDay(id, "2027-03-29")
        val moved = moveTo(target, utc(2027, 4, 1, 9), EditScope.Future)
        assertEquals(listOf("2027-03-22", "2027-03-24"), days(id))
        val next = days(moved.seriesId!!)
        assertEquals(listOf("2027-04-01", "2027-04-05", "2027-04-07"), next.take(3))
        assertEquals(2, ruleOf(moved.seriesId!!).byWeekday.size)
        val due = allDueDays()
        expectNoDuplicates(due)
        assertFalse(due.contains("2027-03-31"))
    }

    @Test
    fun `all - quarterly - the whole series moves to the new months`() {
        val id = start(
            RepeatRule(freq = RepeatFreq.Monthly, byMonthDay = listOf(15), byMonth = listOf(3, 6, 9, 12)),
            anchorAt = utc(2027, 6, 15, 9),
        )
        val target = db.onDay(id, "2027-09-15")
        val moved = moveTo(target, utc(2027, 10, 20, 9), EditScope.All)
        assertEquals(id, moved.seriesId)
        assertEquals("2027-10-20", moved.occurrenceDate)
        val rule = ruleOf(id)
        assertEquals(listOf(1, 4, 7, 10), rule.byMonth)
        assertEquals(listOf(20), rule.byMonthDay)
        assertEquals(listOf("2027-07-20", "2027-10-20", "2028-01-20"), days(id))
        assertEquals(utc(2027, 6, 20, 9), db.series(id)!!.anchorAt)
        expectNoDuplicates(allDueDays())
    }

    @Test
    fun `all - monthly, no day set - every month on the new day`() {
        val id = start(RepeatRule(freq = RepeatFreq.Monthly), anchorAt = utc(2027, 4, 15, 9))
        val target = db.onDay(id, "2027-05-15")
        moveTo(target, utc(2027, 5, 20, 9), EditScope.All)
        val days = days(id)
        assertEquals(listOf("2027-04-20", "2027-05-20", "2027-06-20"), days.take(3))
        assertTrue(days.all { it.endsWith("-20") })
        assertEquals(11, days.size)
        assertEquals(target.id, db.onDay(id, "2027-05-20").id)
        expectNoDuplicates(allDueDays())
    }

    @Test
    fun `all - weekly Monday → Wednesday - every one on Wednesday`() {
        val id = start(
            RepeatRule(freq = RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("MO"))),
            anchorAt = utc(2027, 3, 22, 9),
        )
        val before = db.rowsOf(id).size
        val target = db.onDay(id, "2027-03-29")
        moveTo(target, utc(2027, 4, 7, 9), EditScope.All)
        val days = days(id)
        assertEquals(listOf("2027-03-24", "2027-03-31", "2027-04-07"), days.take(3))
        for (d in days) {
            assertEquals(DayOfWeek.WEDNESDAY, weekday(d))
        }
        assertEquals(target.id, db.onDay(id, "2027-04-07").id)
        assertEquals(before, days.size)
        expectNoDuplicates(allDueDays())
    }

    @Test
    fun `all - half-yearly and yearly follow the new month`() {
        val half = start(
            RepeatRule(freq = RepeatFreq.Monthly, byMonthDay = listOf(25), byMonth = listOf(3, 9)),
            anchorAt = utc(2027, 3, 25, 9),
        )
        moveTo(db.onDay(half, "2027-09-25"), utc(2027, 11, 2, 9), EditScope.All)
        assertEquals(listOf(5, 11), ruleOf(half).byMonth)
        assertEquals(listOf("2027-05-02", "2027-11-02"), days(half))

        val yearly = start(RepeatRule(freq = RepeatFreq.Yearly), anchorAt = utc(2027, 4, 10, 9))
        moveTo(db.onDay(yearly, "2027-04-10"), utc(2027, 5, 3, 9), EditScope.All)
        assertEquals(listOf(5), ruleOf(yearly).byMonth)
        assertEquals(listOf("2027-05-03"), days(yearly))
        db.roll(utc(2028, 4, 1, 10))
        assertEquals(listOf("2027-05-03", "2028-05-03"), days(yearly))
    }

    @Test
    fun `all - daily - every day still has exactly one occurrence`() {
        val id = start(daily, anchorAt = utc(2027, 3, 20, 9))
        val target = db.onDay(id, "2027-03-25")
        moveTo(target, utc(2027, 3, 27, 9), EditScope.All)
        val days = days(id)
        expectNoDuplicates(days)
        assertEquals(366, days.size)
        assertEquals(target.id, db.onDay(id, "2027-03-27").id)
        expectNoDuplicates(allDueDays())
    }

    @Test
    fun `all - several days picked - only the edited one moves`() {
        val id = start(
            RepeatRule(freq = RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("MO"), WeekdayPick("WE"))),
            anchorAt = utc(2027, 3, 22, 9),
        )
        val before = days(id)
        val target = db.onDay(id, "2027-03-29")
        val moved = moveTo(target, utc(2027, 4, 1, 9), EditScope.All)
        assertEquals(before, days(id))
        assertEquals("2027-03-29", moved.occurrenceDate)
        assertEquals("2027-03-22", WallClock.wallDay(db.series(id)!!.anchorAt, "UTC"))
    }

    @Test
    fun `the moved occurrence rings at its new deadline`() {
        val origin = db.createTask(title = "Medicine", dueAt = utc(2027, 3, 20, 12), remindAt = utc(2027, 3, 20, 11, 30))
        val id = start(
            RepeatRule(freq = RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("SA"))),
            anchorAt = utc(2027, 3, 20, 12),
            template = null,
            fromTaskId = origin,
        )
        val a = moveTo(db.onDay(id, "2027-03-27"), utc(2027, 3, 29, 15), EditScope.Future)
        assertEquals(utc(2027, 3, 29, 14, 30), a.remindAt)
        val after = db.onDay(a.seriesId!!, "2027-04-05")
        assertEquals(utc(2027, 4, 5, 14, 30), after.remindAt)
        val b = moveTo(db.onDay(a.seriesId!!, "2027-04-12"), utc(2027, 4, 12, 8), EditScope.All)
        assertEquals(utc(2027, 4, 12, 7, 30), b.remindAt)
        val c = moveTo(b, utc(2027, 4, 14, 8), EditScope.All)
        assertEquals("2027-04-14", c.occurrenceDate)
        assertEquals(utc(2027, 4, 14, 7, 30), c.remindAt)
    }

    private fun follow(rule: RepeatRule, from: String, to: String): RepeatRule? = SeriesEngine.ruleFollowingDay(rule, to, from)

    @Test
    fun `ruleFollowingDay - the last day stays the last day`() {
        val last = RepeatRule(freq = RepeatFreq.Monthly, byMonthDay = listOf(-1))
        assertEquals(listOf(-1), follow(last, "2027-04-30", "2027-05-31")!!.byMonthDay)
        assertEquals(listOf(20), follow(last, "2027-04-30", "2027-05-20")!!.byMonthDay)
    }

    @Test
    fun `ruleFollowingDay - the Nth weekday becomes the new day's Nth weekday`() {
        val second = RepeatRule(freq = RepeatFreq.Monthly, byWeekday = listOf(WeekdayPick("TU", ordinal = 2)))
        val third = follow(second, "2027-04-13", "2027-04-21")!!.byWeekday
        assertEquals("WE", third.single().day)
        assertEquals(3, third.single().ordinal)
        val fifth = follow(second, "2027-04-13", "2027-04-29")!!.byWeekday
        assertEquals(-1, fifth.single().ordinal)
        val lastFriday = RepeatRule(freq = RepeatFreq.Monthly, byWeekday = listOf(WeekdayPick("FR", ordinal = -1)))
        val moved = follow(lastFriday, "2027-04-30", "2027-04-29")!!
        assertEquals("TH", moved.byWeekday.single().day)
        assertEquals(-1, moved.byWeekday.single().ordinal)
    }

    @Test
    fun `ruleFollowingDay - an Nth-weekday series moved under "this and future"`() {
        val id = start(
            RepeatRule(freq = RepeatFreq.Monthly, byWeekday = listOf(WeekdayPick("TU", ordinal = 2))),
            anchorAt = utc(2027, 4, 13, 9),
        )
        val moved = moveTo(db.onDay(id, "2027-05-11"), utc(2027, 5, 19, 9), EditScope.Future)
        val next = days(moved.seriesId!!)
        assertEquals(listOf("2027-05-19", "2027-06-16", "2027-07-21"), next.take(3))
        expectNoDuplicates(allDueDays())
    }

    @Test
    fun `ruleFollowingDay - patterns the move cannot express stay put`() {
        assertNull(follow(RepeatRule(freq = RepeatFreq.Monthly, byMonthDay = listOf(1, 15)), "2027-04-15", "2027-04-20"))
        assertNull(
            follow(
                RepeatRule(
                    freq = RepeatFreq.Monthly,
                    byMonthDay = listOf(22, 23, 24, 25, 26, 27, 28),
                    byWeekday = listOf(WeekdayPick("MO")),
                ),
                "2027-04-26",
                "2027-04-27",
            ),
        )
        assertNull(
            follow(
                RepeatRule(freq = RepeatFreq.Daily, interval = 3, basis = RepeatBasis.Completion),
                "2027-04-26",
                "2027-04-27",
            ),
        )
    }

    private fun configured(due: Instant): String = db.createTask(
        title = "Rent",
        dueAt = due,
        remindAt = due.minus(Duration.ofHours(1)),
        isUrgent = true,
        requiresAcknowledgement = true,
    )

    private fun repeat(origin: String, rule: RepeatRule, due: Instant): String =
        start(rule, anchorAt = due, template = null, fromTaskId = origin)

    private fun expectMovesOntoFirstPlannedDay(rule: RepeatRule, first: String) {
        val due = utc(2027, 3, 22, 9)
        val origin = configured(due)
        val id = repeat(origin, rule, due)

        val adopted = db.row(origin)
        assertEquals(id, adopted.seriesId)
        assertEquals(first, adopted.occurrenceDate)
        val p = first.split('-').map { it.toInt() }
        val newDue = utc(p[0], p[1], p[2], 9)
        assertEquals(newDue, adopted.dueAt)
        assertEquals(newDue.minus(Duration.ofHours(1)), adopted.remindAt)
        assertTrue(adopted.isUrgent)
        val all = db.state.tasks
        assertTrue(all.all { it.seriesId == id })
        val days = all.map { it.occurrenceDate }
        assertEquals(days.size, days.toSet().size)
        assertEquals(origin, db.rowsOf(id).first().id)
    }

    @Test
    fun `monthly on the 28th - the task moves onto the first planned day`() {
        expectMovesOntoFirstPlannedDay(RepeatRule(freq = RepeatFreq.Monthly, byMonthDay = listOf(28)), "2027-03-28")
    }

    @Test
    fun `quarterly in Feb, May, Aug, Nov - the task moves onto the first planned day`() {
        expectMovesOntoFirstPlannedDay(
            RepeatRule(freq = RepeatFreq.Monthly, byMonthDay = listOf(22), byMonth = listOf(2, 5, 8, 11)),
            "2027-05-22",
        )
    }

    @Test
    fun `yearly in July - the task moves onto the first planned day`() {
        expectMovesOntoFirstPlannedDay(RepeatRule(freq = RepeatFreq.Yearly, byMonth = listOf(7)), "2027-07-22")
    }

    @Test
    fun `a task on a planned day stays where it is`() {
        val due = utc(2027, 3, 28, 9)
        val origin = configured(due)
        val id = repeat(origin, RepeatRule(freq = RepeatFreq.Monthly, byMonthDay = listOf(28)), due)
        val adopted = db.row(origin)
        assertEquals(id, adopted.seriesId)
        assertEquals("2027-03-28", adopted.occurrenceDate)
        assertEquals(due, adopted.dueAt)
    }

    private val due = utc(2027, 3, 20, 12)

    private fun fromConfiguredTask(
        dueAt: Instant? = null,
        remindAt: Instant? = null,
        urgent: Boolean = true,
        anchorAt: Instant? = null,
    ): String {
        val origin = db.createTask(
            title = "Medicine",
            priority = "high",
            dueAt = dueAt,
            remindAt = remindAt,
            isUrgent = urgent,
            requiresAcknowledgement = urgent,
        )
        return start(daily, anchorAt = anchorAt ?: dueAt!!, template = null, fromTaskId = origin)
    }

    @Test
    fun `the template copies the task - reminder offset, urgent, ack`() {
        val id = fromConfiguredTask(dueAt = due, remindAt = due.minus(Duration.ofMinutes(30)))
        val template = templateOf(id)
        assertEquals("Medicine", template.title)
        assertEquals("high", template.priority)
        assertEquals(30, template.remindMinutesBefore)
        assertTrue(template.isUrgent)
        assertTrue(template.requiresAcknowledgement)

        for (day in listOf("2027-03-21", "2027-06-30", "2028-03-19")) {
            val r = db.onDay(id, day)
            assertEquals(r.dueAt!!.minus(Duration.ofMinutes(30)), r.remindAt)
            assertTrue(r.isUrgent)
            assertTrue(r.requiresAcknowledgement)
            assertEquals("high", r.priority)
        }
    }

    @Test
    fun `no reminder on the task, none in the template`() {
        val id = fromConfiguredTask(dueAt = due, urgent = false)
        assertFalse(templateOf(id).fields.containsKey(SeriesTemplate.RemindMinutesBefore))
        val next = db.onDay(id, "2027-03-21")
        assertNull(next.remindAt)
        assertFalse(next.isUrgent)
    }

    @Test
    fun `a task with only a reminder repeats at the reminder`() {
        val remind = utc(2027, 3, 21, 8)
        val id = fromConfiguredTask(remindAt = remind, urgent = false, anchorAt = remind)
        val rows = db.rowsOf(id)
        assertEquals("2027-03-21", rows.first().occurrenceDate)
        assertEquals(remind, rows.first().remindAt)
        assertEquals(0, templateOf(id).remindMinutesBefore)
        val next = db.onDay(id, "2027-03-22")
        assertEquals(utc(2027, 3, 22, 8), next.dueAt)
        assertEquals(utc(2027, 3, 22, 8), next.remindAt)
    }

    @Test
    fun `"this and future" - a new reminder and urgent go forward`() {
        val id = fromConfiguredTask(dueAt = due, remindAt = due.minus(Duration.ofMinutes(30)))
        val target = db.onDay(id, "2027-03-25")
        val newRemind = utc(2027, 3, 25, 11)
        db.write(target.id) { it.copy(remindAt = newRemind, isUrgent = false) }
        propagate(t0, target.id, TaskEdit(remindAt = Edit(newRemind), isUrgent = Edit(false)), EditScope.Future)

        val moved = db.row(target.id)
        val next = db.series(moved.seriesId!!)!!
        assertEquals(60, next.template.remindMinutesBefore)
        assertFalse(next.template.isUrgent)
        val later = db.onDay(next.id, "2027-03-26")
        assertEquals(utc(2027, 3, 26, 11), later.remindAt)
        assertFalse(later.isUrgent)
        val before = db.onDay(id, "2027-03-24")
        assertEquals(utc(2027, 3, 24, 11, 30), before.remindAt)
        assertTrue(before.isUrgent)
        db.roll(t0.plus(Duration.ofDays(1)))
        val last = db.rowsOf(next.id).last()
        assertEquals(last.dueAt!!.minus(Duration.ofHours(1)), last.remindAt)
        assertFalse(last.isUrgent)
    }

    @Test
    fun `"all" - clearing the reminder clears it everywhere`() {
        val id = fromConfiguredTask(dueAt = due, remindAt = due.minus(Duration.ofMinutes(30)))
        val target = db.onDay(id, "2027-03-25")
        db.write(target.id) { it.copy(remindAt = null) }
        propagate(t0, target.id, TaskEdit(remindAt = Edit(null)), EditScope.All)

        assertFalse(templateOf(id).fields.containsKey(SeriesTemplate.RemindMinutesBefore))
        val open = db.rowsOf(id).filter { it.status == "open" }
        assertTrue(open.all { it.remindAt == null })
    }

    @Test
    fun `"all" - a new deadline time keeps the reminder before it`() {
        val id = fromConfiguredTask(dueAt = due, remindAt = due.minus(Duration.ofHours(1)))
        val target = db.onDay(id, "2027-03-25")
        val newDue = utc(2027, 3, 25, 15)
        db.write(target.id) { it.copy(dueAt = newDue) }
        propagate(t0, target.id, TaskEdit(dueAt = Edit(newDue)), EditScope.All)

        val other = db.onDay(id, "2027-04-10")
        assertEquals(utc(2027, 4, 10, 15), other.dueAt)
        assertEquals(utc(2027, 4, 10, 14), other.remindAt)
    }

    @Test
    fun `"all" - urgent on brings acknowledgement along`() {
        val id = fromConfiguredTask(dueAt = due, urgent = false)
        val target = db.onDay(id, "2027-03-25")
        db.write(target.id) { it.copy(isUrgent = true, requiresAcknowledgement = true) }
        propagate(t0, target.id, TaskEdit(isUrgent = Edit(true), requiresAcknowledgement = Edit(true)), EditScope.All)

        val template = templateOf(id)
        assertTrue(template.isUrgent)
        assertTrue(template.requiresAcknowledgement)
        val other = db.onDay(id, "2027-04-10")
        assertTrue(other.isUrgent)
        assertTrue(other.requiresAcknowledgement)
    }

    private fun moveAt(now: Instant, target: OccurrenceRow, newDue: Instant, scope: EditScope) {
        moveTo(target, newDue, scope, now)
    }

    @Test
    fun `"this and future" does not recreate a later occurrence already completed`() {
        val id = start(daily, anchorAt = utc(2027, 3, 20, 9))
        val tomorrow = db.onDay(id, "2027-03-21")
        db.setStatusRaw(tomorrow.id, "completed", t0)
        val today = db.onDay(id, "2027-03-20")
        moveAt(t0, today, utc(2027, 3, 20, 11), EditScope.Future)

        val on21 = allRows().filter { it.occurrenceDate == "2027-03-21" }
        assertEquals(1, on21.size)
        assertEquals(tomorrow.id, on21.single().id)
        assertEquals("completed", on21.single().status)
        val next = db.row(today.id).seriesId!!
        assertEquals(next, on21.single().seriesId)
        db.roll(t0.plus(Duration.ofDays(1)))
        assertEquals(1, allRows().count { it.occurrenceDate == "2027-03-21" })
    }

    @Test
    fun `"this and future" splits a 10-times series into 4 + 6`() {
        val id = start(daily.copy(end = RepeatEnd.Count(10)), anchorAt = utc(2027, 3, 20, 9))
        assertEquals(10, db.rowsOf(id).size)
        val fifth = db.onDay(id, "2027-03-24")
        moveAt(t0, fifth, utc(2027, 3, 24, 11), EditScope.Future)

        val next = db.row(fifth.id).seriesId!!
        assertEquals(4, db.rowsOf(id).size)
        assertEquals(6, db.rowsOf(next).size)
        assertEquals(6, count(ruleOf(next)))
        db.roll(t0.plus(Duration.ofDays(3)))
        assertEquals(10, allRows().size)
    }

    @Test
    fun `"this and future" counts an occurrence completed early once`() {
        val id = start(daily.copy(end = RepeatEnd.Count(10)), anchorAt = utc(2027, 3, 20, 9))
        db.setStatusRaw(db.onDay(id, "2027-03-26").id, "completed", t0)
        val fifth = db.onDay(id, "2027-03-24")
        moveAt(t0, fifth, utc(2027, 3, 24, 11), EditScope.Future)
        val rows = allRows()
        assertEquals(10, rows.size)
        assertEquals("2027-03-29", rows.last().occurrenceDate)
    }

    private val mondays10 = RepeatRule(
        freq = RepeatFreq.Weekly,
        byWeekday = listOf(WeekdayPick("MO")),
        end = RepeatEnd.Count(10),
    )

    @Test
    fun `"all" moving a 10-times pattern keeps 10`() {
        val id = start(mondays10, anchorAt = utc(2027, 3, 22, 9))
        assertEquals(10, db.rowsOf(id).size)
        val fifth = db.onDay(id, "2027-04-19")
        moveAt(t0, fifth, utc(2027, 4, 18, 9), EditScope.All)
        val rows = db.rowsOf(id)
        assertEquals(10, rows.size)
        for (r in rows) {
            assertEquals(DayOfWeek.SUNDAY, weekday(r.occurrenceDate!!))
        }
    }

    @Test
    fun `"all" moving a 10-times pattern with history keeps 10`() {
        val id = start(mondays10, anchorAt = utc(2027, 3, 22, 9))
        for (day in listOf("2027-03-22", "2027-03-29", "2027-04-05")) {
            db.setStatusRaw(db.onDay(id, day).id, "completed", t0)
        }
        db.setStatusRaw(db.onDay(id, "2027-04-12").id, "completed", t0)
        val later = utc(2027, 4, 6, 10)
        val target = db.onDay(id, "2027-04-19")
        moveAt(later, target, utc(2027, 4, 21, 9), EditScope.All)
        assertEquals(10, db.rowsOf(id).size)
        db.roll(later.plus(Duration.ofDays(30)))
        assertEquals(10, db.rowsOf(id).size)
    }
}
