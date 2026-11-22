package com.ftapps.remft.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class TaskRulesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val now: Instant = Instant.parse("2027-03-20T06:00:00Z")
    private val later: Instant = Instant.parse("2027-03-21T06:00:00Z")

    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Instant =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant()

    private fun utc(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Instant =
        LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC)

    private fun created(draft: TaskDraft = TaskDraft("Task")): Task = TaskRules.create("T1", "W1", draft, now)

    private fun configured(due: Instant?, remind: Instant?, snoozed: Instant? = null, status: String = "open"): Task =
        created().copy(dueAt = due, remindAt = remind, snoozedUntil = snoozed, status = status)

    @Test
    fun createTrimsTheTitleAndFillsDefaults() {
        val task = created(TaskDraft("  Pay the bill \n"))
        assertEquals("T1", task.id)
        assertEquals("W1", task.workspaceId)
        assertEquals("Pay the bill", task.title)
        assertEquals("open", task.status)
        assertEquals("none", task.priority)
        assertFalse(task.isUrgent)
        assertFalse(task.requiresAcknowledgement)
        assertEquals(0, task.sortOrder)
        assertEquals(now, task.createdAt)
        assertEquals(now, task.updatedAt)
        assertNull(task.completedAt)
        assertNull(task.description)
    }

    @Test
    fun createKeepsTheGivenFields() {
        val due = at(2027, 3, 25, 18)
        val remind = at(2027, 3, 25, 17)
        val task = created(
            TaskDraft(
                title = "Task",
                description = " notes ",
                status = "waiting",
                priority = "high",
                dueAt = due,
                remindAt = remind,
                sortOrder = 4,
            ),
        )
        assertEquals(" notes ", task.description)
        assertEquals("waiting", task.status)
        assertEquals("high", task.priority)
        assertEquals(due, task.dueAt)
        assertEquals(remind, task.remindAt)
        assertEquals(4, task.sortOrder)
    }

    @Test
    fun createAnInboxCaptureWithADateIsPromoted() {
        assertEquals("open", created(TaskDraft("Task", status = "inbox", dueAt = at(2027, 3, 25, 9))).status)
        assertEquals("inbox", created(TaskDraft("Task", status = "inbox")).status)
    }

    @Test
    fun createUrgentDemandsAcknowledgementUnlessOptedOut() {
        assertTrue(created(TaskDraft("Task", isUrgent = true)).requiresAcknowledgement)
        assertFalse(created(TaskDraft("Task", isUrgent = true, requiresAcknowledgement = false)).requiresAcknowledgement)
        assertTrue(created(TaskDraft("Task", requiresAcknowledgement = true)).requiresAcknowledgement)
        assertFalse(created(TaskDraft("Task")).requiresAcknowledgement)
    }

    @Test
    fun updateTrimsTheTitleAndStampsUpdatedAt() {
        val task = TaskRules.update(created(), TaskPatch(title = "  Renamed  "), later, zone)
        assertEquals("Renamed", task.title)
        assertEquals(later, task.updatedAt)
        assertEquals(now, task.createdAt)
    }

    @Test
    fun anEmptyPatchChangesOnlyUpdatedAt() {
        val before = configured(at(2027, 3, 25, 18), at(2027, 3, 25, 17), snoozed = at(2027, 3, 25, 17, 30))
        val after = TaskRules.update(before, TaskPatch(), later, zone)
        assertEquals(before.copy(updatedAt = later), after)
    }

    @Test
    fun updateSetsAndClearsNullableFields() {
        val base = created(TaskDraft("Task", description = "old"))
        assertNull(TaskRules.update(base, TaskPatch(description = Change(null)), later, zone).description)
        assertEquals("new", TaskRules.update(base, TaskPatch(description = Change("new")), later, zone).description)
        val muted = TaskRules.update(base, TaskPatch(alarmsMutedAt = Change(later)), later, zone)
        assertEquals(later, muted.alarmsMutedAt)
        assertNull(TaskRules.update(muted, TaskPatch(alarmsMutedAt = Change(null)), later, zone).alarmsMutedAt)
        val reordered = TaskRules.update(base, TaskPatch(priority = "urgent", sortOrder = 7), later, zone)
        assertEquals("urgent", reordered.priority)
        assertEquals(7, reordered.sortOrder)
    }

    @Test
    fun updateUrgentDemandsAcknowledgementUnlessTheyAreSetTogether() {
        val base = created()
        assertTrue(TaskRules.update(base, TaskPatch(isUrgent = true), later, zone).requiresAcknowledgement)
        val optedOut = TaskRules.update(base, TaskPatch(isUrgent = true, requiresAcknowledgement = false), later, zone)
        assertTrue(optedOut.isUrgent)
        assertFalse(optedOut.requiresAcknowledgement)
        val acked = base.copy(isUrgent = true, requiresAcknowledgement = true)
        val calmed = TaskRules.update(acked, TaskPatch(isUrgent = false), later, zone)
        assertFalse(calmed.isUrgent)
        assertTrue(calmed.requiresAcknowledgement)
    }

    @Test
    fun planningACaptureWithADateMovesItToOpen() {
        val capture = created(TaskDraft("Task", status = "inbox"))
        assertEquals("open", TaskRules.update(capture, TaskPatch(dueAt = Change(at(2027, 3, 25, 9))), later, zone).status)
        assertEquals("inbox", TaskRules.update(capture, TaskPatch(dueAt = Change(null)), later, zone).status)
        assertEquals("inbox", TaskRules.update(capture, TaskPatch(title = "Other"), later, zone).status)
        assertEquals(
            "waiting",
            TaskRules.update(capture, TaskPatch(status = "waiting", dueAt = Change(at(2027, 3, 25, 9))), later, zone).status,
        )
        val open = created()
        assertEquals("open", TaskRules.update(open, TaskPatch(dueAt = Change(at(2027, 3, 25, 9))), later, zone).status)
    }

    @Test
    fun aHandSetReminderStaysWhenOnlyTheTimeOfDayChanges() {
        val remind = at(2027, 3, 25, 22, 35)
        val task = configured(at(2027, 3, 25, 22, 40), remind)
        val moved = TaskRules.update(task, TaskPatch(dueAt = Change(at(2027, 3, 25, 22, 45))), later, zone)
        assertEquals(remind, moved.remindAt)
        assertEquals(at(2027, 3, 25, 22, 45), moved.dueAt)
    }

    @Test
    fun theDerivedReminderFollowsATimeChange() {
        val task = configured(at(2027, 3, 25, 18), at(2027, 3, 25, 17))
        val moved = TaskRules.update(task, TaskPatch(dueAt = Change(at(2027, 3, 25, 20))), later, zone)
        assertEquals(at(2027, 3, 25, 19), moved.remindAt)
    }

    @Test
    fun aDeadlineMovedBeforeItsReminderTakesItAlong() {
        val task = configured(at(2027, 3, 25, 22, 40), at(2027, 3, 25, 22, 35))
        val moved = TaskRules.update(task, TaskPatch(dueAt = Change(at(2027, 3, 25, 22, 30))), later, zone)
        assertEquals(at(2027, 3, 25, 22, 25), moved.remindAt)
    }

    @Test
    fun aPlainTaskReminderFollowsAMoveToAnotherDay() {
        val utcZone = ZoneOffset.UTC
        var task = configured(utc(2027, 3, 25, 12), utc(2027, 3, 25, 11))
        task = TaskRules.update(task, TaskPatch(dueAt = Change(utc(2027, 3, 27, 9))), later, utcZone)
        assertEquals(utc(2027, 3, 27, 8), task.remindAt)

        task = TaskRules.update(
            task,
            TaskPatch(dueAt = Change(utc(2027, 3, 28, 9)), remindAt = Change(utc(2027, 3, 28, 7))),
            later,
            utcZone,
        )
        assertEquals(utc(2027, 3, 28, 7), task.remindAt)

        task = TaskRules.update(task, TaskPatch(dueAt = Change(null)), later, utcZone)
        assertNull(task.dueAt)
        assertEquals(utc(2027, 3, 28, 7), task.remindAt)
    }

    @Test
    fun aHandSetReminderMovedToAnotherDayKeepsItsOffset() {
        val task = configured(at(2027, 3, 25, 12), at(2027, 3, 25, 9, 15))
        val moved = TaskRules.update(task, TaskPatch(dueAt = Change(at(2027, 3, 26, 14))), later, zone)
        assertEquals(at(2027, 3, 26, 11, 15), moved.remindAt)
    }

    @Test
    fun noReminderOrNoOldDeadlineMeansNothingToFollow() {
        val noRemind = configured(at(2027, 3, 25, 12), null)
        assertNull(TaskRules.update(noRemind, TaskPatch(dueAt = Change(at(2027, 3, 26, 12))), later, zone).remindAt)
        val noDue = configured(null, at(2027, 3, 25, 9))
        assertEquals(at(2027, 3, 25, 9), TaskRules.update(noDue, TaskPatch(dueAt = Change(at(2027, 3, 26, 12))), later, zone).remindAt)
        assertNull(TaskRules.followedReminder(noDue, TaskPatch(dueAt = Change(at(2027, 3, 26, 12))), zone))
    }

    @Test
    fun theSameDayIsJudgedInTheGivenZone() {
        val task = configured(utc(2027, 3, 25, 17), utc(2027, 3, 25, 15))
        val patch = TaskPatch(dueAt = Change(utc(2027, 3, 25, 19)))
        assertEquals(utc(2027, 3, 25, 15), TaskRules.update(task, patch, later, ZoneOffset.UTC).remindAt)
        assertEquals(utc(2027, 3, 25, 17), TaskRules.update(task, patch, later, zone).remindAt)
    }

    @Test
    fun completingStampsCompletedAtAndReopeningClearsIt() {
        val done = TaskRules.update(created(), TaskPatch(status = "completed"), later, zone)
        assertEquals("completed", done.status)
        assertEquals(later, done.completedAt)
        val again = TaskRules.update(done, TaskPatch(status = "completed"), Instant.parse("2027-03-22T06:00:00Z"), zone)
        assertEquals(later, again.completedAt)
        val untouched = TaskRules.update(done, TaskPatch(title = "x"), Instant.parse("2027-03-22T06:00:00Z"), zone)
        assertEquals(later, untouched.completedAt)
        val reopened = TaskRules.update(done, TaskPatch(status = "open"), later, zone)
        assertEquals("open", reopened.status)
        assertNull(reopened.completedAt)
        val cancelled = TaskRules.update(created(), TaskPatch(status = "cancelled"), later, zone)
        assertNull(cancelled.completedAt)
    }

    @Test
    fun aMovedReminderClearsTheSnooze() {
        val task = configured(at(2027, 3, 25, 18), at(2027, 3, 25, 17), snoozed = at(2027, 3, 25, 17, 30))
        assertNull(TaskRules.update(task, TaskPatch(remindAt = Change(at(2027, 3, 25, 16))), later, zone).snoozedUntil)
        assertNull(TaskRules.update(task, TaskPatch(remindAt = Change(null)), later, zone).snoozedUntil)
        assertEquals(
            at(2027, 3, 25, 17, 30),
            TaskRules.update(task, TaskPatch(remindAt = Change(at(2027, 3, 25, 17))), later, zone).snoozedUntil,
        )
    }

    @Test
    fun aMovedDeadlineClearsTheSnooze() {
        val task = configured(at(2027, 3, 25, 18), null, snoozed = at(2027, 3, 25, 18, 10))
        assertNull(TaskRules.update(task, TaskPatch(dueAt = Change(at(2027, 3, 25, 19))), later, zone).snoozedUntil)
        assertNull(TaskRules.update(task, TaskPatch(dueAt = Change(null)), later, zone).snoozedUntil)
        assertEquals(
            at(2027, 3, 25, 18, 10),
            TaskRules.update(task, TaskPatch(dueAt = Change(at(2027, 3, 25, 18))), later, zone).snoozedUntil,
        )
    }

    @Test
    fun aFollowedReminderCountsAsAMovedReminder() {
        val task = configured(at(2027, 3, 25, 18), at(2027, 3, 25, 17), snoozed = at(2027, 3, 25, 17, 30))
        val moved = TaskRules.update(task, TaskPatch(dueAt = Change(at(2027, 3, 25, 20))), later, zone)
        assertEquals(at(2027, 3, 25, 19), moved.remindAt)
        assertNull(moved.snoozedUntil)
    }

    @Test
    fun reopeningAFinishedTaskClearsTheSnooze() {
        for (terminal in listOf("completed", "cancelled", "archived")) {
            val task = configured(at(2027, 3, 25, 18), null, snoozed = at(2027, 3, 25, 18, 10), status = terminal)
            assertNull(terminal, TaskRules.update(task, TaskPatch(status = "open"), later, zone).snoozedUntil)
            assertEquals(
                terminal,
                at(2027, 3, 25, 18, 10),
                TaskRules.update(task, TaskPatch(status = "archived"), later, zone).snoozedUntil,
            )
        }
        val open = configured(at(2027, 3, 25, 18), null, snoozed = at(2027, 3, 25, 18, 10))
        assertEquals(at(2027, 3, 25, 18, 10), TaskRules.update(open, TaskPatch(status = "waiting"), later, zone).snoozedUntil)
        assertEquals(at(2027, 3, 25, 18, 10), TaskRules.update(open, TaskPatch(status = "completed"), later, zone).snoozedUntil)
    }

    @Test
    fun touchesAlarmsOnlyForDueRemindOrUrgent() {
        assertFalse(TaskPatch().touchesAlarms)
        assertFalse(TaskPatch(title = "x", status = "completed", alarmsMutedAt = Change(now)).touchesAlarms)
        assertTrue(TaskPatch(dueAt = Change(null)).touchesAlarms)
        assertTrue(TaskPatch(remindAt = Change(now)).touchesAlarms)
        assertTrue(TaskPatch(isUrgent = false).touchesAlarms)
    }

    private fun alarmTask(remindAt: Instant? = null, dueAt: Instant? = utc(2030, 6, 2, 14), urgent: Boolean = false) =
        Task(
            id = "T1",
            workspaceId = "W1",
            title = "Task",
            dueAt = dueAt,
            remindAt = remindAt,
            timezone = "UTC",
            isUrgent = urgent,
            requiresAcknowledgement = urgent,
        )

    @Test
    fun alarmInstantsFollowTheNotificationRules() {
        val due = utc(2030, 6, 2, 14)
        val remind = utc(2030, 6, 2, 13)
        assertEquals(listOf(AlarmInstant("due", due)), TaskRules.alarmInstants(alarmTask()))
        assertEquals(listOf(AlarmInstant("remind", remind)), TaskRules.alarmInstants(alarmTask(remindAt = remind)))
        assertEquals(
            listOf(AlarmInstant("remind", remind), AlarmInstant("due", due)),
            TaskRules.alarmInstants(alarmTask(remindAt = remind, urgent = true)),
        )
        assertEquals(listOf(AlarmInstant("due", due)), TaskRules.alarmInstants(alarmTask(urgent = true)))
        assertEquals(listOf(AlarmInstant("remind", remind)), TaskRules.alarmInstants(alarmTask(remindAt = remind, dueAt = null)))
        assertTrue(TaskRules.alarmInstants(alarmTask(dueAt = null)).isEmpty())
    }

    @Test
    fun oneInstantNeverRingsTwice() {
        val same = utc(2030, 6, 5, 21, 45)
        assertEquals(listOf(AlarmInstant("remind", same)), TaskRules.alarmInstants(alarmTask(remindAt = same, dueAt = same, urgent = true)))
    }

    @Test
    fun finishedOrMutedTasksHaveNoAlarms() {
        val remind = utc(2030, 6, 2, 13)
        for (status in listOf("completed", "cancelled", "archived")) {
            assertTrue(status, TaskRules.alarmInstants(alarmTask(remindAt = remind, urgent = true).copy(status = status)).isEmpty())
        }
        assertTrue(TaskRules.alarmInstants(alarmTask(remindAt = remind).copy(alarmsMutedAt = remind)).isEmpty())
        assertEquals(2, TaskRules.alarmInstants(alarmTask(remindAt = remind, urgent = true).copy(status = "inbox")).size)
    }

    @Test
    fun staleSnoozesAreThoseForAnAlarmTheTaskNoLongerHas() {
        val due = utc(2030, 6, 2, 14)
        val remind = utc(2030, 6, 2, 13)
        val task = alarmTask(remindAt = remind, urgent = true)
        val snoozed = listOf(
            SnoozedReminder("R1", "remind", remind),
            SnoozedReminder("R2", "due", due),
            SnoozedReminder("R3", "remind", utc(2030, 6, 2, 12)),
            SnoozedReminder("R4", "due", due.plusMillis(1)),
            SnoozedReminder("R5", "other", remind),
        )
        assertEquals(listOf("R3", "R4", "R5"), TaskRules.staleSnoozedReminderIds(task, snoozed))
        assertEquals(listOf("R2"), TaskRules.staleSnoozedReminderIds(alarmTask(remindAt = remind), snoozed.take(2)))
    }

    @Test
    fun snoozesCompareToTheMillisecond() {
        val remind = utc(2030, 6, 2, 13)
        val task = alarmTask(remindAt = remind.plusNanos(400_000))
        assertTrue(TaskRules.staleSnoozedReminderIds(task, listOf(SnoozedReminder("R1", "remind", remind))).isEmpty())
    }

    @Test
    fun finishedOrMutedTasksKeepTheirSnoozes() {
        val remind = utc(2030, 6, 2, 13)
        val snoozed = listOf(SnoozedReminder("R1", "remind", utc(2030, 6, 2, 9)))
        assertTrue(TaskRules.staleSnoozedReminderIds(alarmTask(remindAt = remind).copy(status = "completed"), snoozed).isEmpty())
        assertTrue(TaskRules.staleSnoozedReminderIds(alarmTask(remindAt = remind).copy(alarmsMutedAt = remind), snoozed).isEmpty())
        assertEquals(listOf("R1"), TaskRules.staleSnoozedReminderIds(alarmTask(remindAt = remind), snoozed))
    }
}
