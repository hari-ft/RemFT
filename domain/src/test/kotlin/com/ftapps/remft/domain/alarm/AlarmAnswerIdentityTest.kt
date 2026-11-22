package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class AlarmAnswerIdentityTest {

    private val tid = pad26("T1")
    private val nine = utc(2030, 6, 1, 9)
    private val remindSynthetic = AlarmSelection.syntheticReminderId(AlarmKind.REMIND, tid)

    private data class Pressed(val decision: NotificationDecision, val applied: Boolean)

    private fun seeded(
        remindAt: Instant? = null,
        dueAt: Instant? = null,
        snoozedUntil: Instant? = null,
        urgent: Boolean = true,
    ): AlarmWorld = AlarmWorld(
        listOf(
            alarmTask(
                id = tid,
                title = "X",
                isUrgent = urgent,
                requiresAcknowledgement = urgent,
                remindAt = remindAt,
                dueAt = dueAt,
                snoozedUntil = snoozedUntil,
            ),
        ),
    )

    private fun AlarmWorld.moveRemind(to: Instant) {
        updateTask(tid) { it.copy(remindAt = to, snoozedUntil = if (it.remindAt == to) it.snoozedUntil else null) }
        dropStaleSnoozes(tid)
    }

    private fun plan(alarms: List<AlarmInput>, now: Instant): List<PlannedNotification> =
        EnglishAlarmTexts.planner.planNotifications(alarms = alarms, now = now, privacyMode = false)

    private fun payload(at: Instant? = null): String {
        val atField = if (at == null) "" else ",\"at\":${at.toEpochMilli()}"
        return "{\"taskId\":\"$tid\",\"reminderId\":\"$remindSynthetic\"$atField,\"chainIndex\":0}"
    }

    private fun AlarmWorld.press(action: String, payload: String, now: Instant): Pressed {
        val decision = NotificationActions.decide(
            NotificationEvent(actionId = action, payload = payload),
            asRang = alarmsOfTask(tid),
            now = now,
        )
        val applied = when (decision) {
            is NotificationDecision.Acknowledge -> acknowledge(decision.reminderId, decision.at, now)
            is NotificationDecision.Snooze -> snooze(
                taskId = decision.taskId,
                until = SnoozePresets.untilFor(decision.preset, now, ZoneOffset.UTC),
                now = now,
                reminderId = decision.reminderId,
                at = decision.at,
            )
            is NotificationDecision.Complete -> {
                apply(AlarmAnswers.complete(decision.taskId))
                true
            }
            else -> false
        }
        return Pressed(decision, applied)
    }

    @Test
    fun acknowledgeAfterSnoozeAndTimeChangeAnswersTheAlarmThatRings() {
        val world = seeded(remindAt = nine)
        world.snooze(tid, until = nine.plus(Duration.ofMinutes(5)), now = nine, reminderId = remindSynthetic)
        val eleven = utc(2030, 6, 1, 11)
        world.moveRemind(eleven)

        val ringing = world.alarms().single()
        assertEquals(eleven, ringing.remindAt)
        val answeredAt = eleven.plus(Duration.ofSeconds(30))
        assertTrue(world.acknowledge(ringing.reminderId, at = ringing.remindAt, now = answeredAt))
        assertTrue(plan(world.alarms(), answeredAt).isEmpty())
    }

    @Test
    fun movingTheTimeRemovesSnoozeOfOldTime() {
        val world = seeded(remindAt = nine)
        world.snooze(tid, until = nine.plus(Duration.ofMinutes(5)), now = nine, reminderId = remindSynthetic)
        assertEquals(1, world.rows.size)

        world.moveRemind(utc(2030, 6, 1, 11))

        assertTrue(world.rows.isEmpty())
    }

    @Test
    fun unrelatedEditKeepsSnooze() {
        val world = seeded(remindAt = nine)
        world.snooze(tid, until = nine.plus(Duration.ofMinutes(5)), now = nine, reminderId = remindSynthetic)

        world.updateTask(tid) { it.copy(title = "Y", remindAt = nine) }
        world.dropStaleSnoozes(tid)

        assertEquals(ReminderStatus.SNOOZED, world.rows.single().status)
    }

    @Test
    fun payloadNamesTheAlarmInstance() {
        val planned = plan(
            listOf(
                AlarmInput(
                    reminderId = "r",
                    taskId = tid,
                    taskTitle = "X",
                    remindAt = nine,
                    status = ReminderStatus.SCHEDULED,
                    urgent = true,
                    requiresAcknowledgement = true,
                ),
            ),
            nine.minus(Duration.ofMinutes(1)),
        )

        val decoded = AlarmPlanner.parsePayload(planned.first().payload)!!
        assertEquals(nine, decoded.at)
        assertEquals(AlarmKind.REMIND, decoded.kind)
        assertTrue(planned.first().payload.contains("\"at\":${nine.toEpochMilli()}"))
    }

    @Test
    fun acknowledgeOnOldNotificationAfterMoveIsOnlyDismissal() {
        val world = seeded(remindAt = nine)
        val two = utc(2030, 6, 1, 14)
        world.moveRemind(two)
        val now = nine.plus(Duration.ofMinutes(10))

        val pressed = world.press(NotificationActions.ACKNOWLEDGE, payload(at = nine), now)

        assertTrue(pressed.decision is NotificationDecision.Acknowledge)
        assertFalse(pressed.applied)
        assertTrue(world.rows.isEmpty())
        assertTrue(plan(world.alarms(), now).map { it.fireAt }.contains(two))
        assertTrue(
            NotificationActions.notAppliedDetail(NotificationActions.ACKNOWLEDGE, NotificationActions.REASON_NOT_THE_ONE)
                .contains("not applied"),
        )
    }

    @Test
    fun aPayloadWithoutInstantNeverAnswersALaterAlarm() {
        val world = seeded(remindAt = nine)
        val two = utc(2030, 6, 1, 14)
        world.moveRemind(two)
        val now = nine.plus(Duration.ofMinutes(10))

        val acknowledged = world.press(NotificationActions.ACKNOWLEDGE, payload(), now)
        assertEquals(
            NotificationDecision.NotApplied(
                NotificationActions.ACKNOWLEDGE,
                tid,
                remindSynthetic,
                NotificationActions.REASON_MOVED_LATER,
            ),
            acknowledged.decision,
        )
        assertTrue(world.rows.isEmpty())

        val snoozed = world.press(NotificationActions.snooze(SnoozePresets.FIVE_MIN), payload(), now)
        assertTrue(snoozed.decision is NotificationDecision.NotApplied)
        assertTrue(world.rows.isEmpty())
        assertNull(world.task(tid).snoozedUntil)
    }

    @Test
    fun aPayloadWithoutInstantStillAnswersTheAlarmThatRang() {
        val world = seeded(remindAt = nine)

        val pressed = world.press(NotificationActions.ACKNOWLEDGE, payload(), nine.plus(Duration.ofMinutes(1)))

        assertTrue(pressed.applied)
        val row = world.rows.single()
        assertEquals(ReminderStatus.ACKNOWLEDGED, row.status)
        assertEquals(nine, row.remindAt)
    }

    @Test
    fun snoozeOnMatchingInstantLands() {
        val world = seeded(remindAt = nine)

        val pressed = world.press(
            NotificationActions.snooze(SnoozePresets.FIVE_MIN),
            payload(at = nine),
            nine.plus(Duration.ofMinutes(1)),
        )

        assertTrue(pressed.applied)
        assertEquals(ReminderStatus.SNOOZED, world.rows.single().status)
    }

    @Test
    fun storeRefusesInstantTheAlarmNoLongerHas() {
        val world = seeded(remindAt = nine)
        world.moveRemind(utc(2030, 6, 1, 14))

        assertFalse(world.acknowledge(remindSynthetic, at = nine, now = nine))
        assertFalse(
            world.snooze(
                tid,
                until = nine.plus(Duration.ofMinutes(5)),
                now = nine,
                reminderId = remindSynthetic,
                at = nine,
            ),
        )
        assertTrue(world.rows.isEmpty())
        assertNull(world.task(tid).snoozedUntil)
    }

    @Test
    fun completeThatLandsCompletesTheTask() {
        val world = seeded(remindAt = nine)
        val alarm = world.alarms().single()

        val pressed = world.press(
            NotificationActions.COMPLETE,
            "{\"taskId\":\"$tid\",\"reminderId\":\"${alarm.reminderId}\"}",
            nine,
        )

        assertEquals(NotificationDecision.Complete(tid, alarm.reminderId), pressed.decision)
        assertEquals(AlarmTaskStatus.COMPLETED, world.task(tid).status)
    }

    @Test
    fun snoozedAlarmTakesUrgencyFromTaskAsItIsNow() {
        val world = seeded(remindAt = nine)
        world.snooze(tid, until = nine.plus(Duration.ofMinutes(5)), now = nine, reminderId = remindSynthetic)

        world.updateTask(tid) { it.copy(isUrgent = false, requiresAcknowledgement = false) }
        world.dropStaleSnoozes(tid)

        val alarm = world.alarms().single()
        assertEquals(ReminderStatus.SNOOZED, alarm.status)
        assertFalse(alarm.urgent)
        assertFalse(alarm.requiresAcknowledgement)
        assertEquals(1, plan(listOf(alarm), nine).size)
    }

    @Test
    fun tomorrowMorningAfterMidnightIsThisMorning() {
        val zone = ZoneId.of("Europe/Berlin")
        fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
            LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant()

        assertEquals(
            local(2026, 11, 6, 9),
            SnoozePresets.untilFor(SnoozePresets.TOMORROW_MORNING, local(2026, 11, 6, 0, 30), zone),
        )
        assertEquals(
            local(2026, 11, 6, 9),
            SnoozePresets.untilFor(SnoozePresets.TOMORROW_MORNING, local(2026, 11, 5, 22), zone),
        )
    }
}
