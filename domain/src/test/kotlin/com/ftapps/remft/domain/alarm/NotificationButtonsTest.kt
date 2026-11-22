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
        assertEquals(listOf(ButtonLabel.ACKNOWLEDGE, ButtonLabel.SNOOZE_5_MIN), urgentButtons.map { it.label })
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
    fun reminderButtonsAreCompleteThirtyMinutesAndOneHour() {
        val buttons = NotificationActions.buttonsFor(urgent = false)
        assertEquals(
            listOf(
                NotificationActions.COMPLETE,
                "${NotificationActions.SNOOZE_PREFIX}30_min",
                "${NotificationActions.SNOOZE_PREFIX}1_hour",
            ),
            buttons.map { it.actionId },
        )
        assertEquals(
            listOf(ButtonLabel.COMPLETE, ButtonLabel.SNOOZE_30_MIN, ButtonLabel.SNOOZE_1_HOUR),
            buttons.map { it.label },
        )
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
