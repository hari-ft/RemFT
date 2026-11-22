package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AlarmSelectionTest {

    private val t1 = pad26("T1")
    private val r1 = pad26("R1")
    private val now = utc(2030, 5, 1, 12)

    private fun seededTask(
        tid: String = "T1",
        remindAt: Instant? = null,
        dueAt: Instant? = null,
        urgent: Boolean = false,
        status: String = "open",
        snoozedUntil: Instant? = null,
        alarmsMutedAt: Instant? = null,
    ): AlarmTask = alarmTask(
        id = pad26(tid),
        title = "Task $tid",
        status = status,
        isUrgent = urgent,
        requiresAcknowledgement = urgent,
        remindAt = remindAt,
        dueAt = dueAt,
        snoozedUntil = snoozedUntil,
        alarmsMutedAt = alarmsMutedAt,
    )

    private fun seededReminder(
        rid: String = "R1",
        tid: String = "T1",
        remindAt: Instant,
        status: String = ReminderStatus.SCHEDULED,
        kind: String = AlarmKind.REMIND,
    ): ReminderRow = ReminderRow(
        id = pad26(rid),
        taskId = pad26(tid),
        kind = kind,
        status = status,
        remindAt = remindAt,
        alarmLevel = AlarmLevel.URGENT,
        requiresAcknowledgement = true,
    )

    @Test
    fun taskWithRemindAtAndNoRowYieldsSyntheticAlarm() {
        val at = utc(2030, 6, 1, 9)
        val world = AlarmWorld(listOf(seededTask(remindAt = at)))

        val alarms = world.alarms()
        assertEquals(1, alarms.size)
        assertEquals(AlarmSelection.syntheticReminderId(AlarmKind.REMIND, t1), alarms.single().reminderId)
        assertEquals(at, alarms.single().remindAt)
        assertEquals(ReminderStatus.SCHEDULED, alarms.single().status)
        assertFalse(alarms.single().urgent)
    }

    @Test
    fun urgentTaskAlarmsAtDeadlineWithoutAnyRow() {
        val due = utc(2030, 6, 2, 14)
        val world = AlarmWorld(listOf(seededTask(dueAt = due, urgent = true)))

        val alarms = world.alarms()
        assertEquals(1, alarms.size)
        assertEquals(due, alarms.single().remindAt)
        assertTrue(alarms.single().urgent)
        assertTrue(alarms.single().requiresAcknowledgement)
    }

    @Test
    fun nonUrgentDeadlineRingsOnceAsPlainReminder() {
        val due = utc(2030, 6, 2, 14)
        val world = AlarmWorld(listOf(seededTask(dueAt = due)))

        val alarm = world.alarms().single()
        assertEquals(AlarmKind.DUE, alarm.kind)
        assertEquals(due, alarm.remindAt)
        assertFalse(alarm.urgent)
        assertFalse(alarm.requiresAcknowledgement)
        assertEquals(AlarmSelection.syntheticReminderId(AlarmKind.DUE, t1), alarm.reminderId)

        val planned = EnglishAlarmTexts.planner.planNotifications(
            alarms = listOf(alarm),
            now = utc(2030, 6, 1),
            privacyMode = false,
        )
        assertEquals(1, planned.size)
        assertFalse(planned.single().urgent)
        assertEquals(EnglishAlarmTexts.texts.reminder, planned.single().body)
        assertEquals(due, planned.single().fireAt)
    }

    @Test
    fun ownReminderReplacesNonUrgentDeadline() {
        val at = utc(2030, 6, 2, 13)
        val world = AlarmWorld(listOf(seededTask(remindAt = at, dueAt = utc(2030, 6, 2, 14))))

        val alarm = world.alarms().single()
        assertEquals(AlarmKind.REMIND, alarm.kind)
        assertEquals(at, alarm.remindAt)
    }

    @Test
    fun taskAlarmInstantsAgreeAndUrgentTasksAreUnchanged() {
        val due = utc(2030, 6, 2, 14)
        val remind = utc(2030, 6, 2, 13)
        fun task(remindAt: Instant? = null, urgent: Boolean = false) = alarmTask(
            id = t1,
            dueAt = due,
            remindAt = remindAt,
            isUrgent = urgent,
            requiresAcknowledgement = urgent,
        )

        assertEquals(listOf(AlarmInstant(AlarmKind.DUE, due)), AlarmSelection.taskAlarmInstants(task()))
        assertEquals(listOf(AlarmInstant(AlarmKind.REMIND, remind)), AlarmSelection.taskAlarmInstants(task(remindAt = remind)))
        assertEquals(
            listOf(AlarmInstant(AlarmKind.REMIND, remind), AlarmInstant(AlarmKind.DUE, due)),
            AlarmSelection.taskAlarmInstants(task(remindAt = remind, urgent = true)),
        )
        assertEquals(listOf(AlarmInstant(AlarmKind.DUE, due)), AlarmSelection.taskAlarmInstants(task(urgent = true)))
    }

    @Test
    fun answeredDeadlineDoesNotRingAgainUntilItMoves() {
        val due = utc(2030, 6, 2, 14)
        val world = AlarmWorld(listOf(seededTask(dueAt = due)))
        world.acknowledge(AlarmSelection.syntheticReminderId(AlarmKind.DUE, t1), now = now)

        assertTrue(world.alarms().isEmpty())

        val later = due.plus(Duration.ofHours(2))
        world.updateTask(t1) { it.copy(dueAt = later) }
        val alarm = world.alarms().single()
        assertEquals(later, alarm.remindAt)
    }

    @Test
    fun taskSnoozedUntilDoesNotSnoozeSyntheticAlarms() {
        val due = utc(2030, 6, 2, 14)
        val until = utc(2030, 6, 2, 15)
        val world = AlarmWorld(listOf(seededTask(dueAt = due, urgent = true, snoozedUntil = until)))

        val alarm = world.alarms().single()
        assertEquals(ReminderStatus.SCHEDULED, alarm.status)
        assertEquals(due, alarm.remindAt)
    }

    @Test
    fun realReminderRowReplacesSyntheticTwin() {
        val at = utc(2030, 6, 1, 9)
        val world = AlarmWorld(listOf(seededTask(remindAt = at, urgent = true)), listOf(seededReminder(remindAt = at)))

        val alarms = world.alarms()
        assertEquals(1, alarms.size)
        assertEquals(r1, alarms.single().reminderId)
    }

    @Test
    fun terminalReminderRowNeverResurrectsAsSyntheticAlarm() {
        val at = utc(2030, 6, 1, 9)
        val world = AlarmWorld(
            listOf(seededTask(remindAt = at, urgent = true)),
            listOf(seededReminder(remindAt = at, status = ReminderStatus.ACKNOWLEDGED)),
        )

        assertTrue(world.alarms().isEmpty())
    }

    @Test
    fun completedAndArchivedTasksSynthesizeNothing() {
        val world = AlarmWorld(
            listOf(
                seededTask(tid = "T1", remindAt = utc(2030, 6, 1, 9), status = AlarmTaskStatus.COMPLETED),
                seededTask(tid = "T2", dueAt = utc(2030, 6, 2, 9), urgent = true, status = AlarmTaskStatus.ARCHIVED),
            ),
        )

        assertTrue(world.alarms().isEmpty())
    }

    @Test
    fun acknowledgingSyntheticIdResolvesToTasksActiveRow() {
        val at = utc(2030, 6, 1, 9)
        val world = AlarmWorld(listOf(seededTask(remindAt = at, urgent = true)), listOf(seededReminder(remindAt = at)))

        world.acknowledge(AlarmSelection.syntheticReminderId(AlarmKind.REMIND, t1), now = now)

        val row = world.rows.single()
        assertEquals(r1, row.id)
        assertEquals(ReminderStatus.ACKNOWLEDGED, row.status)
        assertEquals(now, row.acknowledgedAt)
    }

    @Test
    fun acknowledgingSyntheticIdForAlarmTheTaskDoesNotHaveIsSafeNoOp() {
        val world = AlarmWorld(listOf(seededTask(dueAt = utc(2030, 6, 2, 14), urgent = true)))

        val applied = world.acknowledge(AlarmSelection.syntheticReminderId(AlarmKind.REMIND, t1), now = now)

        assertFalse(applied)
        assertTrue(world.rows.isEmpty())
    }

    @Test
    fun urgentTaskWithBothTimesYieldsTwoAlarms() {
        val remind = utc(2030, 6, 5, 21, 42)
        val due = utc(2030, 6, 5, 21, 45)
        val world = AlarmWorld(listOf(seededTask(remindAt = remind, dueAt = due, urgent = true)))

        val alarms = world.alarms()
        assertEquals(2, alarms.size)
        val byKind = alarms.associateBy { it.kind }
        assertEquals(remind, byKind.getValue(AlarmKind.REMIND).remindAt)
        assertEquals(due, byKind.getValue(AlarmKind.DUE).remindAt)
        assertTrue(byKind.getValue(AlarmKind.DUE).urgent)
        assertEquals(AlarmSelection.syntheticReminderId(AlarmKind.DUE, t1), byKind.getValue(AlarmKind.DUE).reminderId)
    }

    @Test
    fun equalTimesCollapseIntoOneAlarm() {
        val at = utc(2030, 6, 5, 21, 45)
        val world = AlarmWorld(listOf(seededTask(remindAt = at, dueAt = at, urgent = true)))

        val alarms = world.alarms()
        assertEquals(1, alarms.size)
        assertEquals(AlarmKind.REMIND, alarms.single().kind)
    }

    @Test
    fun nonUrgentTaskNeverGetsDeadlineAlarmBesideReminder() {
        val world = AlarmWorld(listOf(seededTask(remindAt = utc(2030, 6, 5, 21, 42), dueAt = utc(2030, 6, 5, 21, 45))))

        assertEquals(listOf(AlarmKind.REMIND), world.alarms().map { it.kind })
    }

    @Test
    fun storedReminderRowReplacesOnlyItsOwnKind() {
        val remind = utc(2030, 6, 5, 21, 42)
        val due = utc(2030, 6, 5, 21, 45)
        val world = AlarmWorld(
            listOf(seededTask(remindAt = remind, dueAt = due, urgent = true)),
            listOf(seededReminder(rid = "R1", remindAt = remind, kind = AlarmKind.REMIND)),
        )

        val alarms = world.alarms()
        assertEquals(2, alarms.size)
        val byKind = alarms.associateBy { it.kind }
        assertEquals(r1, byKind.getValue(AlarmKind.REMIND).reminderId)
        assertEquals(AlarmSelection.syntheticReminderId(AlarmKind.DUE, t1), byKind.getValue(AlarmKind.DUE).reminderId)
    }

    @Test
    fun acknowledgingNudgeLeavesDeadlineArmed() {
        val remind = utc(2030, 6, 5, 21, 42)
        val due = utc(2030, 6, 5, 21, 45)
        val world = AlarmWorld(
            listOf(seededTask(remindAt = remind, dueAt = due, urgent = true)),
            listOf(
                seededReminder(rid = "R1", remindAt = remind, kind = AlarmKind.REMIND),
                seededReminder(rid = "R2", remindAt = due, kind = AlarmKind.DUE),
            ),
        )

        world.acknowledge(AlarmSelection.syntheticReminderId(AlarmKind.REMIND, t1), now = now)

        assertEquals(2, world.rows.size)
        val byKind = world.rows.associateBy { it.kind }
        assertEquals(ReminderStatus.ACKNOWLEDGED, byKind.getValue(AlarmKind.REMIND).status)
        assertEquals(ReminderStatus.SCHEDULED, byKind.getValue(AlarmKind.DUE).status)
        assertEquals(listOf(AlarmKind.DUE), world.alarms().map { it.kind })
    }

    @Test
    fun acknowledgingDeadlineAnswersNudgeToo() {
        val remind = utc(2030, 6, 5, 21, 42)
        val due = utc(2030, 6, 5, 21, 45)
        val world = AlarmWorld(
            listOf(seededTask(remindAt = remind, dueAt = due, urgent = true)),
            listOf(
                seededReminder(rid = "R1", remindAt = remind, kind = AlarmKind.REMIND),
                seededReminder(rid = "R2", remindAt = due, kind = AlarmKind.DUE),
            ),
        )

        world.acknowledge(AlarmSelection.syntheticReminderId(AlarmKind.DUE, t1), now = now)

        assertEquals(2, world.rows.size)
        assertTrue(world.rows.all { it.status == ReminderStatus.ACKNOWLEDGED })
        assertTrue(world.alarms().isEmpty())
    }

    @Test
    fun acknowledgedKindDoesNotComeBackAsSyntheticTwin() {
        val remind = utc(2030, 6, 5, 21, 42)
        val due = utc(2030, 6, 5, 21, 45)
        val world = AlarmWorld(
            listOf(seededTask(remindAt = remind, dueAt = due, urgent = true)),
            listOf(seededReminder(rid = "R1", remindAt = remind, kind = AlarmKind.REMIND, status = ReminderStatus.ACKNOWLEDGED)),
        )

        assertEquals(listOf(AlarmKind.DUE), world.alarms().map { it.kind })
    }

    @Test
    fun mutedTaskSynthesizesNoAlarms() {
        val world = AlarmWorld(
            listOf(
                seededTask(
                    remindAt = utc(2030, 6, 5, 21, 42),
                    dueAt = utc(2030, 6, 5, 21, 45),
                    urgent = true,
                    alarmsMutedAt = utc(2030, 6, 1),
                ),
            ),
        )

        assertTrue(world.alarms().isEmpty())
    }

    @Test
    fun givingAlarmsBackBringsThemStraightBack() {
        val world = AlarmWorld(
            listOf(seededTask(remindAt = utc(2030, 6, 5, 21, 42), urgent = true, alarmsMutedAt = utc(2030, 6, 1))),
        )
        assertTrue(world.alarms().isEmpty())

        world.updateTask(t1) { it.copy(alarmsMutedAt = null) }

        assertEquals(listOf(AlarmKind.REMIND), world.alarms().map { it.kind })
    }

    @Test
    fun mutedTaskStaysNormalOpenTask() {
        val world = AlarmWorld(listOf(seededTask(remindAt = utc(2030, 6, 5, 21, 42), alarmsMutedAt = utc(2030, 6, 1))))

        val task = world.task(t1)
        assertEquals("open", task.status)
        assertNotNull(task.remindAt)
        assertFalse(task.isLive)
    }

    @Test
    fun syntheticIdNamesKindAndTask() {
        val id = AlarmSelection.syntheticReminderId(AlarmKind.DUE, t1)

        assertEquals("task:due:$t1", id)
        assertTrue(AlarmSelection.isSynthetic(id))
        assertEquals(SyntheticReminderId(AlarmKind.DUE, t1), AlarmSelection.parseSyntheticReminderId(id))
    }

    @Test
    fun aSyntheticIdMustNameBothKindAndTask() {
        assertNull(AlarmSelection.parseSyntheticReminderId("task:$t1"))
        assertNull(AlarmSelection.parseSyntheticReminderId("task::$t1"))
        assertNull(AlarmSelection.parseSyntheticReminderId("task:due:"))
    }

    @Test
    fun realRowIdIsNotSynthetic() {
        assertFalse(AlarmSelection.isSynthetic(r1))
        assertNull(AlarmSelection.parseSyntheticReminderId(r1))
    }

    @Test
    fun aSyntheticIdWithoutAKindAnswersNothing() {
        val at = utc(2030, 6, 1, 9)
        val world = AlarmWorld(listOf(seededTask(remindAt = at)))

        assertFalse(world.acknowledge("task:$t1", now = now))
        assertTrue(world.rows.isEmpty())
    }

    @Test
    fun onlyDeadlineAcknowledgementAnswersWholeTask() {
        assertTrue(AlarmSelection.acknowledgesWholeTask(AlarmSelection.syntheticReminderId(AlarmKind.DUE, t1)))
        assertFalse(AlarmSelection.acknowledgesWholeTask(AlarmSelection.syntheticReminderId(AlarmKind.REMIND, t1)))
        assertTrue(AlarmSelection.acknowledgesWholeTask(r1, AlarmKind.DUE))
        assertFalse(AlarmSelection.acknowledgesWholeTask(r1, null))
    }
}
