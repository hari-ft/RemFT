package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class RingIdentityTest {
    private val fireAt = utc(2030, 6, 1, 9, 0)
    private val now = fireAt.minus(Duration.ofMinutes(5))
    private val planner = EnglishAlarmTexts.planner

    private val alarm = AlarmInput(
        reminderId = pad26("R1"),
        taskId = pad26("T1"),
        taskTitle = "Take pills",
        remindAt = fireAt,
        status = ReminderStatus.SCHEDULED,
        urgent = true,
        requiresAcknowledgement = true,
    )

    private fun shown(): PlannedNotification = planner.planNotifications(listOf(alarm), now, privacyMode = false).first { it.slotIndex == 1 }

    @Test
    fun aShownRingStaysOursAfterTitlePrivacyProfileAndSoundChange() {
        val ring = shown()
        val payload = AlarmPlanner.parsePayload(ring.payload)!!
        val renamed = alarm.copy(taskTitle = "Something else")
        assertTrue(AlarmPlanner.isCurrentRing(ring.id, payload, listOf(renamed)))
        val changedIds = planner.notificationIdsForAlarm(renamed, privacyMode = true, profile = ReminderProfile(listOf(0, 5)), alarmSoundName = "x")
        assertFalse(ring.id in changedIds)
    }

    @Test
    fun aRingIsNoLongerOursOnceAnsweredMovedOrSnoozed() {
        val ring = shown()
        val payload = AlarmPlanner.parsePayload(ring.payload)!!
        assertFalse(AlarmPlanner.isCurrentRing(ring.id, payload, listOf(alarm.copy(status = ReminderStatus.ACKNOWLEDGED))))
        assertFalse(AlarmPlanner.isCurrentRing(ring.id, payload, listOf(alarm.copy(remindAt = fireAt.plusSeconds(3600)))))
        assertFalse(
            AlarmPlanner.isCurrentRing(
                ring.id,
                payload,
                listOf(alarm.copy(status = ReminderStatus.SNOOZED, snoozedUntil = fireAt.plusSeconds(600), snoozeRound = 1)),
            ),
        )
        assertFalse(AlarmPlanner.isCurrentRing(ring.id, payload, emptyList()))
        assertFalse(AlarmPlanner.isCurrentRing(12345, payload, listOf(alarm)))
        assertFalse(AlarmPlanner.isCurrentRing(null, payload, listOf(alarm)))
    }

    @Test
    fun aPayloadWithoutABaseFallsBackToThePlannedTime() {
        val legacy = AlarmPlanner.encodePayload(alarm.taskId, alarm.reminderId, "remind", fireAt.toEpochMilli(), 0, fireAt.toEpochMilli(), fireAt.toEpochMilli())
        val payload = AlarmPlanner.parsePayload(legacy)!!
        assertEquals(null, payload.base)
        assertTrue(AlarmPlanner.isCurrentRing(NotificationIds.FIRST_RING, payload, listOf(alarm)))
        val snoozed = alarm.copy(status = ReminderStatus.SNOOZED, snoozedUntil = fireAt.plusSeconds(600), snoozeRound = 1)
        assertFalse(AlarmPlanner.isCurrentRing(NotificationIds.FIRST_RING, payload, listOf(snoozed)))
    }

    @Test
    fun silencingFindsAShownRingByItsPayloadAfterTheTextChanged() {
        val ring = shown()
        val renamed = alarm.copy(taskTitle = "Something else")
        val targets = AlarmBooking.silenceTargets(
            planner = planner,
            taskId = alarm.taskId,
            reminderId = alarm.reminderId,
            known = listOf(renamed),
            live = emptyList(),
            shownIds = setOf(ring.id, 77),
            privacyMode = true,
            shownPayloads = mapOf(ring.id to ring.payload, 77 to null),
        )
        assertEquals(setOf(ring.id), targets)
        val other = AlarmBooking.silenceTargets(
            planner = planner,
            taskId = alarm.taskId,
            reminderId = pad26("R2"),
            known = emptyList(),
            live = emptyList(),
            shownIds = setOf(ring.id),
            privacyMode = false,
            shownPayloads = mapOf(ring.id to ring.payload),
        )
        assertTrue(other.isEmpty())
    }
}
