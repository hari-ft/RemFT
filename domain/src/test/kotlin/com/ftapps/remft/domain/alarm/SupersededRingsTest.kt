package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class SupersededRingsTest {
    private val now = utc(2026, 7, 15, 12)
    private val planner = EnglishAlarmTexts.planner

    private fun alarm(id: String, task: String = "TASK") = AlarmInput(
        reminderId = pad26(id),
        taskId = pad26(task),
        taskTitle = "Sample task",
        kind = AlarmKind.DUE,
        remindAt = now,
        status = "scheduled",
        urgent = true,
        requiresAcknowledgement = true,
    )

    @Test
    fun aReRingReplacesTheEarlierRingsOfTheSameReminderOnly() {
        val mine = planner.planNotifications(listOf(alarm("R1")), now.minusSeconds(60), privacyMode = false).sortedBy { it.fireAt }
        val other = planner.planNotifications(listOf(alarm("R2", task = "OTHER")), now.minusSeconds(60), privacyMode = false).first()
        val first = mine[0]
        val second = mine[1]
        val third = mine[2]
        val shown = mapOf(
            first.id to first.payload,
            second.id to second.payload,
            third.id to third.payload,
            other.id to other.payload,
            AlarmPlanner.TEST_NOTIFICATION_ID to AlarmPlanner.TEST_PAYLOAD,
            42 to null,
        )
        assertEquals(listOf(first.id), AlarmBooking.supersededRings(shown, second))
        assertEquals(setOf(first.id, second.id), AlarmBooking.supersededRings(shown, third).toSet())
        assertEquals(emptyList<Int>(), AlarmBooking.supersededRings(shown, first))
    }
}
