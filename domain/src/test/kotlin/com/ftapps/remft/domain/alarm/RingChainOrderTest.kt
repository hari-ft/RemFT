package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RingChainOrderTest {
    private val now = utc(2026, 11, 7, 8)
    private val planner = EnglishAlarmTexts.planner
    private val insistent = ReminderProfile(slots = ReminderProfile.preset(ReminderProfile.PRESET_INSISTENT)!!.slots)

    private fun t(h: Int, m: Int) = utc(2026, 11, 7, h, m)

    private fun alarm(task: String, at: Instant, kind: String = "remind", urgent: Boolean = true) = AlarmInput(
        reminderId = "task:$kind:$task",
        taskId = task,
        taskTitle = "Task $task",
        kind = kind,
        remindAt = at,
        status = "scheduled",
        urgent = urgent,
        requiresAcknowledgement = urgent,
    )

    @Test
    fun anUrgentFirstRingIsNotPushedByANonUrgentDeadline() {
        val urgent = alarm("T1", t(9, 0))
        val due = alarm("T2", t(9, 0), kind = "due", urgent = false)
        val p = planner.planNotifications(listOf(due, urgent), now, privacyMode = false).filter { it.slotIndex == 0 }
        assertEquals(t(9, 0), p.single { it.taskId == "T1" }.fireAt)
        assertEquals(t(9, 15), p.single { it.taskId == "T2" }.fireAt)
    }

    @Test
    fun aMovedInsistentChainMovesTogetherAndStaysInOrder() {
        val first = alarm("T1", t(9, 0))
        val second = alarm("T2", t(9, 0))
        val times = AlarmPlanner.plannedRingTimes(listOf(first, second), insistent)
        val moved = times.getValue(second)
        assertEquals(moved.sorted(), moved)
        assertEquals(t(9, 15), moved.first())
        assertEquals(t(9, 16), moved[1])
        val planned = planner.planNotifications(listOf(first, second), now, privacyMode = false, profile = insistent)
            .filter { it.taskId == "T2" }
            .sortedBy { it.slotIndex }
        assertEquals(planned.map { it.fireAt }.sorted(), planned.map { it.fireAt })
        assertEquals(planned.size, planned.map { it.fireAt.toEpochMilli() / 60_000 }.toSet().size)
    }

    @Test
    fun readersDoNotAssumeSortedSlots() {
        val a = alarm("T1", t(9, 0))
        val unsorted = listOf(t(9, 15), t(9, 1), t(9, 2))
        assertEquals(t(9, 2), AlarmRinging.latestRing(unsorted, t(9, 5)))
        assertEquals(t(9, 15), AlarmRinging.latestRing(unsorted, t(9, 20)))
        assertNull(AlarmRinging.latestRing(unsorted, t(8, 59)))
        val times = mapOf(a to unsorted)
        assertEquals(t(9, 1), AlarmRinging.nextUrgentFireAfter(listOf(a), t(9, 0), times = times))
        assertEquals(t(9, 2), AlarmRinging.nextRealertAfter(a, t(9, 1), ReminderProfile.Factory, times))
    }
}
