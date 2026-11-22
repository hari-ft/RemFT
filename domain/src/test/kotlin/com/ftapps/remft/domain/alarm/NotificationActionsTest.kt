package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class NotificationActionsTest {

    private val t1 = pad26("T1")
    private val r1 = pad26("R1")
    private val nine = utc(2030, 6, 1, 9)
    private val now = utc(2030, 6, 1, 9, 1)

    private lateinit var world: AlarmWorld

    @Before
    fun seed() {
        world = AlarmWorld(
            listOf(alarmTask(id = t1, title = "Urgent task", status = "open", isUrgent = true, remindAt = nine)),
            listOf(
                ReminderRow(
                    id = r1,
                    taskId = t1,
                    kind = AlarmKind.REMIND,
                    status = ReminderStatus.SCHEDULED,
                    remindAt = nine,
                    alarmLevel = AlarmLevel.URGENT,
                    requiresAcknowledgement = true,
                ),
            ),
        )
    }

    private fun payload(): String = "{\"taskId\":\"$t1\",\"reminderId\":\"$r1\",\"at\":${nine.toEpochMilli()}}"

    private fun decide(event: NotificationEvent, at: Instant = now, isOurs: ((Int?, String) -> Boolean)? = null) =
        NotificationActions.decide(event, world.alarmsOfTask(t1), at, isOurs)

    private fun handle(actionId: String?, at: Instant = now, isOurs: ((Int?, String) -> Boolean)? = null, id: Int? = null): NotificationDecision {
        val decision = decide(NotificationEvent(id = id, actionId = actionId, payload = payload()), at, isOurs)
        when (decision) {
            is NotificationDecision.Acknowledge -> world.acknowledge(decision.reminderId, decision.at, at)
            is NotificationDecision.Snooze -> world.snooze(
                taskId = decision.taskId,
                until = SnoozePresets.untilFor(decision.preset, at, ZoneOffset.UTC),
                now = at,
                reminderId = decision.reminderId,
                at = decision.at,
            )
            is NotificationDecision.Complete -> world.apply(AlarmAnswers.complete(decision.taskId))
            else -> Unit
        }
        return decision
    }

    @Test
    fun plainTapDeepLinksToTheTask() {
        val decision = handle(null)

        assertEquals(NotificationDecision.OpenTask(t1, r1, null), decision)
        assertEquals("/tasks/$t1", NotificationActions.taskRoute((decision as NotificationDecision.OpenTask).taskId))
    }

    @Test
    fun completeActionCompletesTheTask() {
        val decision = handle(NotificationActions.COMPLETE)

        assertEquals(NotificationDecision.Complete(t1, r1), decision)
        assertEquals(AlarmTaskStatus.COMPLETED, world.task(t1).status)
    }

    @Test
    fun pressFromNotificationThatIsNotOursChangesNothing() {
        val asked = mutableListOf<Pair<Int?, String>>()

        val decision = handle(
            NotificationActions.COMPLETE,
            id = 7,
            isOurs = { nid, reminderId ->
                asked.add(nid to reminderId)
                false
            },
        )

        assertEquals(NotificationDecision.NotOurs(NotificationActions.COMPLETE, t1, r1), decision)
        assertEquals(listOf<Pair<Int?, String>>(7 to r1), asked)
        assertEquals("open", world.task(t1).status)
    }

    @Test
    fun pressFromOurOwnNotificationStillCompletesTheTask() {
        handle(NotificationActions.COMPLETE, id = 7, isOurs = { nid, _ -> nid == 7 })

        assertEquals(AlarmTaskStatus.COMPLETED, world.task(t1).status)
    }

    @Test
    fun plainTapStillOnlyOpensTheTaskWhoeverSentIt() {
        val decision = handle(null, id = 7, isOurs = { _, _ -> false })

        assertEquals(NotificationDecision.OpenTask(t1, r1, null), decision)
        assertEquals("open", world.task(t1).status)
    }

    @Test
    fun snoozeActionMovesTheTaskAndItsAlarm() {
        val decision = handle(NotificationActions.snooze(SnoozePresets.THIRTY_MIN))

        assertEquals(NotificationDecision.Snooze(t1, r1, nine, SnoozePresets.THIRTY_MIN), decision)
        val task = world.task(t1)
        assertEquals(now.plus(Duration.ofMinutes(30)), task.snoozedUntil)
        val reminder = world.rows.single()
        assertEquals(ReminderStatus.SNOOZED, reminder.status)
        assertEquals(task.snoozedUntil, reminder.snoozedUntil)
        assertEquals(nine, task.remindAt)
    }

    @Test
    fun acknowledgeFlipsTheReminderLocally() {
        val decision = handle(NotificationActions.ACKNOWLEDGE)

        assertEquals(NotificationDecision.Acknowledge(t1, r1, nine, silenceOnlyThisAlarm = true), decision)
        val reminder = world.rows.single()
        assertEquals(ReminderStatus.ACKNOWLEDGED, reminder.status)
        assertNotNull(reminder.acknowledgedAt)

        handle(NotificationActions.ACKNOWLEDGE, at = now.plus(Duration.ofMinutes(5)))
        val again = world.rows.single()
        assertEquals(ReminderStatus.ACKNOWLEDGED, again.status)
        assertEquals(reminder.acknowledgedAt, again.acknowledgedAt)
    }

    @Test
    fun eachSnoozePresetLandsAtItsOwnTime() {
        val base = utc(2026, 7, 15, 12)

        assertEquals(base.plus(Duration.ofMinutes(5)), SnoozePresets.untilFor(SnoozePresets.FIVE_MIN, base, ZoneOffset.UTC))
        assertEquals(base.plus(Duration.ofMinutes(30)), SnoozePresets.untilFor(SnoozePresets.THIRTY_MIN, base, ZoneOffset.UTC))
        assertEquals(base.plus(Duration.ofHours(1)), SnoozePresets.untilFor(SnoozePresets.ONE_HOUR, base, ZoneOffset.UTC))
        assertEquals(9, SnoozePresets.untilFor(SnoozePresets.TOMORROW_MORNING, base, ZoneOffset.UTC).hourOfDay)
        try {
            SnoozePresets.untilFor("never", base, ZoneOffset.UTC)
            fail("an unknown preset is refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("never"))
        }
    }

    @Test
    fun garbagePayloadsAndUnknownActionsAreIgnoredSafely() {
        assertEquals(
            NotificationDecision.Ignore,
            decide(NotificationEvent(actionId = NotificationActions.COMPLETE, payload = "junk{")),
        )
        assertEquals(NotificationDecision.Ignore, decide(NotificationEvent(actionId = NotificationActions.COMPLETE, payload = null)))
        assertEquals(NotificationDecision.Ignore, decide(NotificationEvent(actionId = NotificationActions.COMPLETE, payload = "")))
        assertEquals(
            NotificationDecision.Ignore,
            decide(NotificationEvent(actionId = NotificationActions.COMPLETE, payload = "{\"reminderId\":\"$r1\"}")),
        )
        assertEquals(NotificationDecision.Ignore, handle("unknown_action"))

        val task = world.task(t1)
        assertEquals("open", task.status)
        assertNull(task.snoozedUntil)
        assertNull(task.alarmsMutedAt)
        assertNotEquals(ReminderStatus.ACKNOWLEDGED, world.rows.single().status)
    }
}
