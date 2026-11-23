package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class NotificationButtonsTest {

    private val urgentButtons = NotificationActions.buttonsFor(urgent = true)

    @Test
    fun urgentButtonsAreExactlyAcknowledgeAndFiveMinutesInThatOrder() {
        assertEquals(
            listOf(NotificationActions.ACKNOWLEDGE, "${NotificationActions.SNOOZE_PREFIX}5_min"),
            urgentButtons.map { it.actionId },
        )
        assertEquals(listOf(ButtonLabel.ACKNOWLEDGE, ButtonLabel.SNOOZE), urgentButtons.map { it.label })
        assertEquals(listOf(null, "5_min"), urgentButtons.map { it.preset })
    }

    @Test
    fun urgentButtonsCarryNoLongSnoozeAndNoMute() {
        val ids = urgentButtons.map { it.actionId }.toSet()
        assertFalse("${NotificationActions.SNOOZE_PREFIX}30_min" in ids)
        assertFalse("${NotificationActions.SNOOZE_PREFIX}1_hour" in ids)
        assertFalse("mute" in ids)
        assertFalse(NotificationActions.COMPLETE in ids)
    }

    @Test
    fun urgentButtonsFitInTheHeadsUpWithRoomToSpare() {
        assertTrue(urgentButtons.size < 3)
    }

    @Test
    fun reminderButtonsAreCompleteFifteenMinutesAndOneHour() {
        val buttons = NotificationActions.buttonsFor(urgent = false)
        assertEquals(
            listOf(
                NotificationActions.COMPLETE,
                "${NotificationActions.SNOOZE_PREFIX}m:15",
                "${NotificationActions.SNOOZE_PREFIX}1_hour",
            ),
            buttons.map { it.actionId },
        )
        assertEquals(listOf(ButtonLabel.COMPLETE, ButtonLabel.SNOOZE, ButtonLabel.SNOOZE), buttons.map { it.label })
    }

    @Test
    fun theUsersOrderDecidesTheUrgentSnoozeButtonButNotTheReminderOnes() {
        val order = listOf("m:90", "tomorrow_morning", "1_hour", "5_min")
        assertEquals(
            listOf(NotificationActions.ACKNOWLEDGE, "${NotificationActions.SNOOZE_PREFIX}m:90"),
            NotificationActions.buttonsFor(urgent = true, snoozeOrder = order).map { it.actionId },
        )
        val reminder = NotificationActions.buttonsFor(urgent = false, snoozeOrder = order)
        assertEquals(listOf(null, "m:15", "1_hour"), reminder.map { it.preset })
    }

    @Test
    fun noNotificationEverCarriesMoreThanThreeButtons() {
        val full = (1..8).map { "m:${it * 7}" }
        assertTrue(NotificationActions.buttonsFor(urgent = false, snoozeOrder = full).size <= NotificationActions.MAX_BUTTONS)
        assertTrue(NotificationActions.buttonsFor(urgent = true, snoozeOrder = full).size < NotificationActions.MAX_BUTTONS)
    }

    @Test
    fun theFifteenMinuteReminderButtonSnoozesFifteenMinutes() {
        assertEquals(now.plus(Duration.ofMinutes(15)), SnoozePresets.untilFor("m:15", now, java.time.ZoneOffset.UTC))
        assertEquals(now.plus(Duration.ofHours(1)), SnoozePresets.untilFor(SnoozePresets.ONE_HOUR, now, java.time.ZoneOffset.UTC))
    }

    @Test
    fun aCustomSnoozeButtonDecidesToItsOwnPreset() {
        val tid = pad26("T1")
        val rid = pad26("R1")
        val payload = "{\"taskId\":\"$tid\",\"reminderId\":\"$rid\",\"at\":${now.toEpochMilli()},\"chainIndex\":0}"
        val decision = NotificationActions.decide(NotificationEvent(actionId = NotificationActions.snooze("m:90"), payload = payload), emptyList(), now)
        assertEquals("m:90", (decision as NotificationDecision.Snooze).preset)
        assertEquals(now.plus(Duration.ofMinutes(90)), SnoozePresets.untilFor(decision.preset, now, java.time.ZoneOffset.UTC))
    }

    private val now = utc(2026, 11, 5, 12)

    private fun alarm(urgent: Boolean) = AlarmInput(
        reminderId = pad26("R1"),
        taskId = pad26("T1"),
        taskTitle = "Task",
        remindAt = now.plus(Duration.ofHours(1)),
        status = ReminderStatus.SCHEDULED,
        urgent = urgent,
        requiresAcknowledgement = urgent,
    )

    private fun keyOf(n: PlannedNotification, urgent: Boolean): String = AlarmPlanner.ringKey(
        reminderId = n.reminderId!!,
        slot = n.slotIndex!!,
        fireAtMillis = n.fireAt.toEpochMilli(),
        title = n.title,
        body = n.body,
        urgent = urgent,
        soundName = n.soundName,
    )

    @Test
    fun urgencyIsPartOfTheIdSoSwitchingItReschedules() {
        val plan = EnglishAlarmTexts.planner.planNotifications(listOf(alarm(urgent = true)), now, privacyMode = false)
        assertEquals(ReminderProfile.Factory.slots.size, plan.size)
        for (n in plan) {
            assertEquals(NotificationIds.ring(keyOf(n, urgent = true)), n.id)
            assertNotEquals(NotificationIds.ring(keyOf(n, urgent = false)), n.id)
        }
    }

    @Test
    fun aReminderKeepsItsId() {
        val n = EnglishAlarmTexts.planner.planNotifications(listOf(alarm(urgent = false)), now, privacyMode = false).single()
        assertEquals(NotificationIds.ring(keyOf(n, urgent = false)), n.id)
    }

    @Test
    fun theSilencerFindsTheIdsTheSchedulerUsed() {
        val a = alarm(urgent = true)
        val planned = EnglishAlarmTexts.planner.planNotifications(listOf(a), now, privacyMode = false).map { it.id }.toSet()
        assertTrue(planned.isNotEmpty())
        assertTrue(EnglishAlarmTexts.planner.notificationIdsForAlarm(a, privacyMode = false).containsAll(planned))
    }
}
