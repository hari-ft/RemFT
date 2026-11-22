package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class AlarmAcknowledgeTest {

    private val remindAt = utc(2030, 6, 1, 22, 35)
    private val dueAt = utc(2030, 6, 1, 22, 50)
    private val ackTime = utc(2030, 6, 1, 22, 37)
    private val tid = pad26("T1")
    private val remindId = AlarmSelection.syntheticReminderId(AlarmKind.REMIND, tid)
    private val dueId = AlarmSelection.syntheticReminderId(AlarmKind.DUE, tid)

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

    private fun plan(alarms: List<AlarmInput>): List<PlannedNotification> =
        EnglishAlarmTexts.planner.planNotifications(alarms = alarms, now = ackTime, privacyMode = false)

    private fun shadePayload(reminderId: String, at: Instant): String =
        "{\"taskId\":\"$tid\",\"reminderId\":\"$reminderId\",\"at\":${at.toEpochMilli()}}"

    private fun press(reminderId: String, at: Instant): NotificationDecision {
        val decision = NotificationActions.decide(
            NotificationEvent(actionId = NotificationActions.ACKNOWLEDGE, payload = shadePayload(reminderId, at)),
            asRang = world.alarmsOfTask(tid),
            now = ackTime,
        )
        if (decision is NotificationDecision.Acknowledge) {
            assertTrue(world.acknowledge(decision.reminderId, decision.at, ackTime))
        }
        return decision
    }

    @Test
    fun acknowledgingReminderLeavesDeadlineRinging() {
        assertEquals(2, world.alarms().size)

        assertTrue(world.acknowledge(remindId, at = remindAt, now = ackTime))

        val alarms = world.alarms()
        assertEquals(listOf(AlarmKind.DUE), alarms.map { it.kind })
        assertEquals(dueAt, alarms.single().remindAt)
        assertEquals(dueAt, plan(alarms).map { it.fireAt }.first())
        val row = world.rows.single()
        assertEquals(AlarmKind.REMIND, row.kind)
        assertEquals(ReminderStatus.ACKNOWLEDGED, row.status)
    }

    @Test
    fun acknowledgingDeadlineStopsRemainingReminder() {
        assertTrue(world.acknowledge(dueId, at = dueAt, now = ackTime))

        assertTrue(world.alarms().isEmpty())
        assertEquals(
            mapOf(AlarmKind.REMIND to remindAt, AlarmKind.DUE to dueAt),
            world.rows.associate { it.kind to it.remindAt },
        )
        assertTrue(world.rows.all { it.status == ReminderStatus.ACKNOWLEDGED })
    }

    @Test
    fun secondAcknowledgeWritesNothingNew() {
        world.acknowledge(dueId, at = dueAt, now = ackTime)
        world.acknowledge(dueId, now = ackTime)

        assertEquals(2, world.rows.size)
    }

    @Test
    fun movingDeadlineAfterAnswerReArmsIt() {
        world.acknowledge(dueId, at = dueAt, now = ackTime)

        val moved = utc(2030, 6, 1, 23, 30)
        world.updateTask(tid) { it.copy(dueAt = moved) }

        val alarms = world.alarms()
        assertEquals(listOf(AlarmKind.DUE), alarms.map { it.kind })
        assertEquals(moved, alarms.single().remindAt)
    }

    @Test
    fun movingReminderAfterAnswerReArmsOnlyIt() {
        world.acknowledge(dueId, at = dueAt, now = ackTime)

        val moved = utc(2030, 6, 1, 23, 10)
        world.updateTask(tid) { it.copy(remindAt = moved) }

        val alarms = world.alarms()
        assertEquals(listOf(AlarmKind.REMIND), alarms.map { it.kind })
        assertEquals(moved, alarms.single().remindAt)
    }

    @Test
    fun staleAcknowledgeAnswersNothingNotEvenTheOtherAlarm() {
        val stale = utc(2030, 6, 1, 22)

        assertFalse(world.acknowledge(remindId, at = stale, now = ackTime))
        assertTrue(world.rows.isEmpty())
        assertEquals(2, world.alarms().size)
    }

    @Test
    fun snoozeStillBelongsToOneAlarm() {
        world.snooze(tid, until = utc(2030, 6, 1, 22, 40), now = ackTime, reminderId = remindId, at = remindAt)

        val due = world.alarms().single { it.kind == AlarmKind.DUE }
        assertEquals(ReminderStatus.SCHEDULED, due.status)
        assertEquals(dueAt, due.remindAt)
    }

    @Test
    fun shadeButtonOnReminderAnswersReminderOnly() {
        val asRang = world.alarmsOfTask(tid)
        val remindAlarm = asRang.single { it.kind == AlarmKind.REMIND }
        val dueAlarm = asRang.single { it.kind == AlarmKind.DUE }
        val dueShown = EnglishAlarmTexts.planner.notificationIdsForAlarm(dueAlarm, privacyMode = false).first()
        val remindShown = EnglishAlarmTexts.planner.notificationIdsForAlarm(remindAlarm, privacyMode = false).elementAt(1)

        val decision = press(remindId, remindAt)

        assertEquals(NotificationDecision.Acknowledge(tid, remindId, remindAt, silenceOnlyThisAlarm = true), decision)
        assertEquals(listOf(AlarmKind.DUE), world.alarms().map { it.kind })
        val silenced = EnglishAlarmTexts.planner.notificationIdsForAlarm(remindAlarm, privacyMode = false)
        assertTrue(remindShown in silenced)
        assertFalse(dueShown in silenced)
    }

    @Test
    fun shadeButtonOnDeadlineAnswersWholeTask() {
        val decision = press(dueId, dueAt)

        assertEquals(NotificationDecision.Acknowledge(tid, dueId, dueAt, silenceOnlyThisAlarm = false), decision)
        assertTrue(world.alarms().isEmpty())
    }
}
