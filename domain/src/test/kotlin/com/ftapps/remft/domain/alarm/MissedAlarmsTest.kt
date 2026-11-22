package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class MissedAlarmsTest {
    private val now = utc(2030, 6, 1, 12)

    private fun alarm(
        rid: String = "R1",
        title: String = "Pay the bill",
        urgent: Boolean = true,
        ago: Duration,
    ) = AlarmInput(
        reminderId = rid,
        taskId = "T-$rid",
        taskTitle = title,
        remindAt = now.minus(ago),
        status = ReminderStatus.SCHEDULED,
        urgent = urgent,
        requiresAcknowledgement = urgent,
    )

    private fun summaryOf(vararg alarms: AlarmInput) =
        AlarmRinging.missedSummary(AlarmRinging.missedAlarms(alarms.toList(), now))

    @Test
    fun nothingIsSummarisedWhenNothingWasMissed() {
        assertNull(summaryOf(alarm(ago = Duration.ofMinutes(2))))
    }

    @Test
    fun longGoneAlarmIsNamed() {
        val summary = summaryOf(alarm(ago = Duration.ofHours(8)))
        assertNotNull(summary)
        assertEquals("Pay the bill", summary!!.first.taskTitle)
        assertEquals(now.minus(Duration.ofHours(8)), summary.first.remindAt)
        assertEquals(0, summary.moreCount)
    }

    @Test
    fun severalMissedAlarmsAreCountedNewestNamed() {
        val summary = summaryOf(
            alarm(rid = "R1", title = "Eski", ago = Duration.ofHours(20)),
            alarm(rid = "R2", title = "Yeni", ago = Duration.ofHours(5)),
        )
        assertNotNull(summary)
        assertEquals("Yeni", summary!!.first.taskTitle)
        assertEquals(1, summary.moreCount)
        assertEquals(listOf("R2", "R1"), summary.all.map { it.reminderId })
    }

    @Test
    fun emptyMissedListHasNoSummary() {
        assertNull(AlarmRinging.missedSummary(emptyList()))
    }
}
