package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class UrgentRingAheadTest {
    private val at = utc(2030, 6, 1, 9)

    private fun alarm(at: Instant, urgent: Boolean = true) = AlarmInput(
        reminderId = "task:due:T1",
        taskId = "T1",
        taskTitle = "Test task",
        remindAt = at,
        status = ReminderStatus.SCHEDULED,
        urgent = urgent,
        requiresAcknowledgement = urgent,
        kind = AlarmKind.DUE,
    )

    private fun minutes(n: Long) = Duration.ofMinutes(n)

    @Test
    fun urgentAlarmBeforeItsFirstRingIsArmed() {
        assertTrue(AlarmRinging.urgentRingAhead(listOf(alarm(at)), now = at.minus(minutes(1))))
    }

    @Test
    fun afterTheFirstRingTheReRingsKeepItArmed() {
        val alarms = listOf(alarm(at))
        assertTrue(AlarmRinging.urgentRingAhead(alarms, now = at.plus(minutes(5)), profile = ReminderProfile.Factory))
        assertTrue(AlarmRinging.urgentRingAhead(alarms, now = at.plus(minutes(45))))
    }

    @Test
    fun onceTheLastReRingHasPassedNothingIsArmed() {
        assertFalse(AlarmRinging.urgentRingAhead(listOf(alarm(at)), now = at.plus(minutes(61))))
    }

    @Test
    fun nonUrgentAlarmNeverCounts() {
        assertFalse(AlarmRinging.urgentRingAhead(listOf(alarm(at, urgent = false)), now = at.minus(minutes(1))))
    }
}
