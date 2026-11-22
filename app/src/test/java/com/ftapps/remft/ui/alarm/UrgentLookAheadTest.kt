package com.ftapps.remft.ui.alarm

import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.ReminderProfile
import com.ftapps.remft.domain.alarm.ReminderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class UrgentLookAheadTest {
    private val now = Instant.parse("2026-11-22T10:00:00Z")

    private fun alarm(id: String, at: Instant, urgent: Boolean = true) = AlarmInput(
        reminderId = id,
        taskId = "task-$id",
        taskTitle = "Sample $id",
        remindAt = at,
        status = ReminderStatus.SCHEDULED,
        urgent = urgent,
        requiresAcknowledgement = urgent,
    )

    @Test
    fun picksTheEarliestUrgentRingWithinADay() {
        val feed = listOf(
            alarm("late", now.plusSeconds(5 * 3600)),
            alarm("soon", now.plusSeconds(2 * 3600 + 600)),
            alarm("plain", now.plusSeconds(600), urgent = false),
        )
        val next = UrgentLookAhead.next(feed, now, ReminderProfile.Factory)
        assertEquals("soon", next?.alarm?.reminderId)
        assertEquals(now.plusSeconds(2 * 3600 + 600), next?.at)
    }

    @Test
    fun ignoresRingsBeyondTheWindowAndNonUrgentOnes() {
        val feed = listOf(alarm("far", now.plusSeconds(30 * 3600)), alarm("plain", now.plusSeconds(60), urgent = false))
        assertNull(UrgentLookAhead.next(feed, now, ReminderProfile.Factory))
    }

    @Test
    fun hoursAndMinutesRoundUpToTheNextMinute() {
        assertEquals(2L to 10L, UrgentLookAhead.hoursAndMinutes(now, now.plusSeconds(2 * 3600 + 600)))
        assertEquals(0L to 1L, UrgentLookAhead.hoursAndMinutes(now, now.plusSeconds(5)))
        assertEquals(1L to 0L, UrgentLookAhead.hoursAndMinutes(now, now.plusSeconds(3599)))
    }
}
