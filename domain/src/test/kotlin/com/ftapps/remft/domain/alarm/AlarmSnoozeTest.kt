package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class AlarmSnoozeTest {

    private val remindAt = utc(2030, 6, 1, 9)
    private val dueAt = utc(2030, 6, 1, 10)
    private val until = utc(2030, 6, 1, 9, 5)
    private val snoozedAt = utc(2030, 6, 1, 9, 1)
    private val tid = pad26("T1")
    private val remindSynthetic = AlarmSelection.syntheticReminderId(AlarmKind.REMIND, tid)

    private lateinit var world: AlarmWorld

    @Before
    fun seed() {
        world = AlarmWorld(
            listOf(
                alarmTask(
                    id = tid,
                    title = "Pay rent",
                    isUrgent = true,
                    requiresAcknowledgement = true,
                    remindAt = remindAt,
                    dueAt = dueAt,
                ),
            ),
        )
    }

    private fun plan(alarms: List<AlarmInput>, now: Instant = snoozedAt): List<PlannedNotification> =
        EnglishAlarmTexts.planner.planNotifications(alarms = alarms, now = now, privacyMode = false)

    private fun firstRings(planned: List<PlannedNotification>): Map<String?, Instant> =
        planned.filter { it.slotIndex == 0 }.associate { it.kind to it.fireAt }

    private fun snoozeReminder() {
        assertTrue(world.snooze(tid, until = until, now = snoozedAt, reminderId = remindSynthetic))
    }

    @Test
    fun snoozingReminderLeavesDeadlineAtItsOwnTime() {
        snoozeReminder()

        val alarms = world.alarms()
        assertEquals(2, alarms.size)
        val remind = alarms.single { it.kind == AlarmKind.REMIND }
        val due = alarms.single { it.kind == AlarmKind.DUE }
        assertEquals(ReminderStatus.SNOOZED, remind.status)
        assertEquals(until, remind.snoozedUntil)
        assertEquals(ReminderStatus.SCHEDULED, due.status)
        assertEquals(dueAt, due.remindAt)

        val planned = plan(alarms)
        assertEquals(mapOf<String?, Instant>(AlarmKind.REMIND to until, AlarmKind.DUE to dueAt), firstRings(planned))
        assertEquals(1, planned.count { it.fireAt == until })
    }

    @Test
    fun snoozingDeadlineDoesNotMoveReminder() {
        val dueUntil = utc(2030, 6, 1, 10, 30)
        world.snooze(
            tid,
            until = dueUntil,
            now = snoozedAt,
            reminderId = AlarmSelection.syntheticReminderId(AlarmKind.DUE, tid),
        )

        val alarms = world.alarms()
        val remind = alarms.single { it.kind == AlarmKind.REMIND }
        assertEquals(ReminderStatus.SCHEDULED, remind.status)
        assertEquals(remindAt, remind.remindAt)
        assertEquals(
            mapOf<String?, Instant>(AlarmKind.REMIND to remindAt, AlarmKind.DUE to dueUntil),
            firstRings(plan(alarms, now = utc(2030, 6, 1, 8))),
        )
    }

    @Test
    fun notificationShadeSnoozeTakesTheSamePath() {
        val payload = "{\"taskId\":\"$tid\",\"reminderId\":\"$remindSynthetic\"," +
            "\"at\":${remindAt.toEpochMilli()},\"chainIndex\":0}"
        val decision = NotificationActions.decide(
            NotificationEvent(actionId = NotificationActions.snooze(SnoozePresets.FIVE_MIN), payload = payload),
            asRang = world.alarmsOfTask(tid),
            now = snoozedAt,
        )

        assertEquals(NotificationDecision.Snooze(tid, remindSynthetic, remindAt, SnoozePresets.FIVE_MIN), decision)
        decision as NotificationDecision.Snooze
        assertTrue(decision.silenceOnlyThisAlarm)
        assertTrue(
            world.snooze(
                taskId = decision.taskId,
                until = SnoozePresets.untilFor(decision.preset, snoozedAt, ZoneOffset.UTC),
                now = snoozedAt,
                reminderId = decision.reminderId,
                at = decision.at,
            ),
        )

        val alarms = world.alarms()
        assertEquals(ReminderStatus.SNOOZED, alarms.single { it.kind == AlarmKind.REMIND }.status)
        val due = alarms.single { it.kind == AlarmKind.DUE }
        assertEquals(ReminderStatus.SCHEDULED, due.status)
        assertEquals(dueAt, due.remindAt)
    }

    @Test
    fun secondSnoozeMovesSameRowAndCountsTheRound() {
        snoozeReminder()
        val first = world.alarms().single { it.kind == AlarmKind.REMIND }
        val later = utc(2030, 6, 1, 9, 35)

        world.snooze(tid, until = later, now = until, reminderId = first.reminderId)

        val row = world.rows.single()
        assertEquals(2, row.snoozeCount)
        assertEquals(later, row.snoozedUntil)
        assertEquals(dueAt, world.alarms().single { it.kind == AlarmKind.DUE }.remindAt)
    }

    @Test
    fun wholeFeedAndTaskFeedAgreeAfterSnooze() {
        snoozeReminder()

        assertEquals(
            plan(world.alarms()).map { it.id }.toSet(),
            plan(world.alarmsOfTask(tid)).map { it.id }.toSet(),
        )
    }

    @Test
    fun acknowledgingSnoozedReminderEndsItAndDeadlineKeepsItsTime() {
        snoozeReminder()
        val snoozed = world.alarms().single { it.kind == AlarmKind.REMIND }

        world.acknowledge(snoozed.reminderId, now = until)

        val alarms = world.alarms()
        assertEquals(listOf(AlarmKind.DUE), alarms.map { it.kind })
        assertEquals(dueAt, alarms.single().remindAt)
    }

    @Test
    fun movedReminderTimeIgnoresSnoozeOfOldTime() {
        snoozeReminder()
        val moved = utc(2030, 6, 1, 14)

        world.updateTask(tid) { it.copy(remindAt = moved, snoozedUntil = null) }
        world.dropStaleSnoozes(tid)

        val remind = world.alarms().single { it.kind == AlarmKind.REMIND }
        assertEquals(ReminderStatus.SCHEDULED, remind.status)
        assertEquals(moved, remind.remindAt)
    }

    @Test
    fun finishedOrMutedTaskIgnoresItsSnoozedRow() {
        snoozeReminder()

        world.apply(AlarmAnswers.mute(tid, snoozedAt))
        assertTrue(world.alarms().isEmpty())

        world.apply(AlarmAnswers.unmute(tid))
        assertEquals(2, world.alarms().size)

        world.apply(AlarmAnswers.complete(tid))
        assertTrue(world.alarms().isEmpty())
    }

    @Test
    fun snoozeWithoutAlarmCoversWholeTask() {
        world.snooze(tid, until = until, now = snoozedAt)

        val alarms = world.alarms()
        assertEquals(setOf(ReminderStatus.SNOOZED), alarms.map { it.status }.toSet())
        assertEquals(2, alarms.size)
    }
}
