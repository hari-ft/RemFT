package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class CurrentRingTest {
    private val now = utc(2026, 7, 15, 12)
    private val planner = EnglishAlarmTexts.planner

    private fun alarm(id: String, kind: String = AlarmKind.DUE, remindAt: java.time.Instant = now) = AlarmInput(
        reminderId = pad26(id),
        taskId = pad26("TASK"),
        taskTitle = "Sample task",
        kind = kind,
        remindAt = remindAt,
        status = "scheduled",
        urgent = true,
        requiresAcknowledgement = true,
    )

    @Test
    fun theRingThatIsSoundingIsRebuiltExactlyAsItWasBooked() {
        val due = alarm("R1")
        val booked = planner.planNotifications(listOf(due), now.minusSeconds(60), privacyMode = false, alarmSoundName = "remft_alarm")
        val second = booked.single { it.slotIndex == 1 }
        val current = planner.currentRing(listOf(due), due, now.plus(Duration.ofMinutes(35)), privacyMode = false, alarmSoundName = "remft_alarm")
        assertEquals(second, current)
    }

    @Test
    fun nothingIsRebuiltBeforeTheFirstRing() {
        val due = alarm("R1")
        assertNull(planner.currentRing(listOf(due), due, now.minusSeconds(1), privacyMode = false))
    }

    @Test
    fun aDueScreenShownForAnEarlierRingRebuildsThatEarlierRing() {
        val due = alarm("R1", remindAt = now.plus(Duration.ofHours(2)))
        val early = alarm("R2", kind = AlarmKind.REMIND)
        val current = planner.currentRing(listOf(due, early), due, now.plusSeconds(30), privacyMode = true)!!
        assertEquals(early.reminderId, current.reminderId)
        assertEquals(0, current.slotIndex)
        assertEquals(EnglishAlarmTexts.texts.privacyTitle, current.title)
    }
}
