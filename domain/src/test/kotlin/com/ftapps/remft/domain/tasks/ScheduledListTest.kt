package com.ftapps.remft.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduledListTest {
    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")
    private val london: ZoneId = ZoneId.of("Europe/London")

    private fun at(text: String, zone: ZoneId = kolkata): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun task(
        id: String,
        due: Instant? = null,
        remind: Instant? = null,
        snoozed: Instant? = null,
        status: String = "open",
    ) = Task(id = id, workspaceId = "W1", title = id, status = status, dueAt = due, remindAt = remind, snoozedUntil = snoozed)

    private fun ids(sections: List<ScheduledSection>): List<Pair<ScheduledBucket, List<String>>> =
        sections.map { section -> section.bucket to section.entries.map { it.task.id } }

    @Test
    fun dueWinsOverRemindAndRemindIsUsedWithoutADue() {
        val due = at("2026-11-21T12:00")
        val remind = at("2026-11-21T11:00")
        assertEquals(due, ScheduledList.scheduledAt(task("A", due = due, remind = remind)))
        assertEquals(remind, ScheduledList.scheduledAt(task("B", remind = remind)))
        assertNull(ScheduledList.scheduledAt(task("C")))
    }

    @Test
    fun aLaterSnoozeMovesTheTaskAndAStaleEarlierOneDoesNot() {
        val due = at("2026-11-21T12:00")
        val snooze = at("2026-11-21T12:30")
        val later = ScheduledList.entries(listOf(task("A", due = due, snoozed = snooze))).single()
        assertEquals(snooze, later.at)
        assertTrue(later.snoozed)
        val earlier = ScheduledList.entries(listOf(task("B", due = due, snoozed = at("2026-11-21T11:00")))).single()
        assertEquals(due, earlier.at)
        assertFalse(earlier.snoozed)
    }

    @Test
    fun onlyOpenTasksWithATimeAreScheduled() {
        val time = at("2026-11-21T12:00")
        val tasks = listOf(
            task("open", due = time),
            task("scheduled", due = time, status = "scheduled"),
            task("progress", due = time, status = "in_progress"),
            task("waiting", remind = time, status = "waiting"),
            task("done", due = time, status = "completed"),
            task("cancelled", due = time, status = "cancelled"),
            task("archived", due = time, status = "archived"),
            task("inbox", due = time, status = "inbox"),
            task("noTime"),
        )
        assertEquals(listOf("open", "progress", "scheduled", "waiting"), ScheduledList.entries(tasks).map { it.task.id })
    }

    @Test
    fun overdueSitsOnTopThenTodayTomorrowAndLaterDaysInTimeOrder() {
        val now = at("2026-11-20T10:00")
        val tasks = listOf(
            task("later", due = at("2026-11-24T09:00")),
            task("tomorrow", remind = at("2026-11-21T08:00")),
            task("today", due = at("2026-11-20T18:00")),
            task("old", due = at("2026-11-18T09:00")),
            task("justNow", due = at("2026-11-20T10:00")),
            task("snoozedPast", due = at("2026-11-20T09:00"), snoozed = at("2026-11-20T09:30")),
            task("snoozedAhead", due = at("2026-11-20T09:00"), snoozed = at("2026-11-20T10:15")),
        )
        assertEquals(
            listOf(
                ScheduledBucket.Overdue to listOf("old", "snoozedPast", "justNow"),
                ScheduledBucket.Day(LocalDate.of(2026, 11, 20)) to listOf("snoozedAhead", "today"),
                ScheduledBucket.Day(LocalDate.of(2026, 11, 21)) to listOf("tomorrow"),
                ScheduledBucket.Day(LocalDate.of(2026, 11, 24)) to listOf("later"),
            ),
            ids(ScheduledList.sections(tasks, now, kolkata)),
        )
    }

    @Test
    fun aMinutePassingMovesTheTaskIntoOverdue() {
        val tasks = listOf(task("A", due = at("2026-11-20T10:01")))
        assertEquals(ScheduledBucket.Day(LocalDate.of(2026, 11, 20)), ScheduledList.sections(tasks, at("2026-11-20T10:00"), kolkata).single().bucket)
        assertEquals(ScheduledBucket.Overdue, ScheduledList.sections(tasks, at("2026-11-20T10:01"), kolkata).single().bucket)
    }

    @Test
    fun daysFollowTheLocalCalendarAcrossTheAutumnClockChange() {
        val now = Instant.parse("2026-10-24T12:00:00Z")
        val tasks = listOf(
            task("bstAfterMidnight", due = Instant.parse("2026-10-24T23:30:00Z")),
            task("gmtLateEvening", due = Instant.parse("2026-10-25T23:30:00Z")),
            task("gmtAfterMidnight", due = Instant.parse("2026-10-26T00:30:00Z")),
        )
        assertEquals(
            listOf(
                ScheduledBucket.Day(LocalDate.of(2026, 10, 25)) to listOf("bstAfterMidnight", "gmtLateEvening"),
                ScheduledBucket.Day(LocalDate.of(2026, 10, 26)) to listOf("gmtAfterMidnight"),
            ),
            ids(ScheduledList.sections(tasks, now, london)),
        )
        assertEquals(
            listOf(
                ScheduledBucket.Day(LocalDate.of(2026, 10, 25)) to listOf("bstAfterMidnight"),
                ScheduledBucket.Day(LocalDate.of(2026, 10, 26)) to listOf("gmtLateEvening", "gmtAfterMidnight"),
            ),
            ids(ScheduledList.sections(tasks, now, kolkata)),
        )
    }
}
