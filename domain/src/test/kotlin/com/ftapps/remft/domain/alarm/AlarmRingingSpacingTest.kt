package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AlarmRingingSpacingTest {
    private val nine = utc(2030, 6, 1, 9)
    private val shift = AlarmPlanner.RingCollisionShift

    private fun alarm(
        rid: String,
        at: Instant,
        taskId: String? = null,
        kind: String = AlarmKind.REMIND,
    ) = AlarmInput(
        reminderId = rid,
        taskId = taskId ?: "T-$rid",
        taskTitle = "Task $rid",
        remindAt = at,
        status = ReminderStatus.SCHEDULED,
        urgent = true,
        requiresAcknowledgement = true,
        kind = kind,
    )

    private fun minutes(n: Long) = Duration.ofMinutes(n)

    private val r1 = alarm("R1", nine)
    private val r2 = alarm("R2", nine)
    private val remind = alarm("REM", nine.minus(minutes(20)), taskId = "T1")
    private val due = alarm("DUE", nine, taskId = "T1", kind = AlarmKind.DUE)

    @Test
    fun overlayRingTimesAreThePlannersFireTimes() {
        val times = AlarmPlanner.plannedRingTimes(listOf(r1, r2))
        val planned = EnglishAlarmTexts.planner.planNotifications(
            alarms = listOf(r1, r2),
            now = nine.minus(minutes(1)),
            privacyMode = false,
        )
        fun firstOf(rid: String) = planned.single { it.reminderId == rid && it.slotIndex == 0 }.fireAt
        assertEquals(nine, firstOf("R1"))
        assertEquals(nine.plus(shift), firstOf("R2"))
        assertEquals(firstOf("R1"), times[r1]!!.first())
        assertEquals(firstOf("R2"), times[r2]!!.first())
        assertEquals(planned.map { it.fireAt }.sorted(), (times[r1]!! + times[r2]!!).sorted())
    }

    @Test
    fun atNineOnlyTheAlarmThatRangAtNineShowsWhateverTheFeedOrder() {
        assertSame(r1, AlarmRinging.ringingAlarm(listOf(r2, r1), nine))
        assertSame(r1, AlarmRinging.ringingAlarm(listOf(r1, r2), nine))
    }

    @Test
    fun withTheNineOClockOneAnsweredTheMovedOneWaitsForNineFifteen() {
        val hidden: (AlarmInput) -> Boolean = { it === r1 }
        assertNull(AlarmRinging.ringingAlarm(listOf(r2, r1), nine.plus(minutes(5)), hidden = hidden))
        assertSame(r2, AlarmRinging.ringingAlarm(listOf(r2, r1), nine.plus(shift), hidden = hidden))
    }

    @Test
    fun atNineFifteenTheMovedAlarmIsTheOneThatJustRang() {
        assertSame(r2, AlarmRinging.ringingAlarm(listOf(r1, r2), nine.plus(shift)))
    }

    @Test
    fun foregroundTimerWakesAtNineFifteenNotAtTheInstant() {
        assertEquals(nine.plus(shift), AlarmRinging.nextUrgentFireAfter(listOf(r1, r2), nine))
    }

    @Test
    fun realertAfterOpenComesBackAtItsSpacedTime() {
        val r3 = alarm("R3", nine.minus(minutes(30)))
        val feed = listOf(r1, r2, r3)
        val times = AlarmPlanner.plannedRingTimes(feed)
        val realert = AlarmRinging.nextRealertAfter(r3, nine.minus(minutes(29)), ReminderProfile.Factory, times)
        assertNotEquals(nine, realert)
        assertEquals(times[r3]!![1], realert)
        assertTrue(realert!!.isAfter(nine))
    }

    @Test
    fun onceTheDeadlineRingsItShowsOverTheTasksOlderReminder() {
        assertSame(remind, AlarmRinging.ringingAlarm(listOf(remind, due), nine.minus(minutes(18))))
        assertSame(due, AlarmRinging.ringingAlarm(listOf(remind, due), nine))
        assertSame(due, AlarmRinging.ringingAlarm(listOf(remind, due), nine.plus(minutes(12))))
    }

    @Test
    fun openOnTheDeadlineDoesNotBringTheReminderUpInstead() {
        assertNull(
            AlarmRinging.ringingAlarm(listOf(remind, due), nine.plus(minutes(1)), hidden = { it === due }),
        )
    }

    @Test
    fun ofTwoTasksRingingTheNewerRingShows() {
        val older = alarm("R1", nine.minus(minutes(10)))
        val newer = alarm("R2", nine.minus(minutes(1)))
        assertSame(newer, AlarmRinging.ringingAlarm(listOf(older, newer), nine))
    }

    @Test
    fun foregroundTimerWakesAtTheDeadlineAfterTheReminderRang() {
        assertEquals(nine, AlarmRinging.nextUrgentFireAfter(listOf(remind, due), nine.minus(minutes(5))))
    }

    @Test
    fun overlayShowsTheAlarmThatRangThenTheMovedOneAtNineFifteen() {
        val overlay = OverlayHarness(nine)
        overlay.emit(listOf(r2, r1))
        assertEquals("R1", overlay.ringing?.reminderId)
        overlay.answer("R1")
        overlay.now = nine.plus(minutes(5))
        overlay.emit(listOf(r2, r1))
        assertNull("R2 rings at 9:15, not 9:00", overlay.ringing)
        overlay.now = nine.plus(shift)
        overlay.emit(listOf(r2, r1))
        assertEquals("R2", overlay.ringing?.reminderId)
    }

    @Test
    fun overlayDeadlineReplacesItsReminderAndAnsweringItAnswersBoth() {
        val overlay = OverlayHarness(nine.minus(minutes(18)))
        overlay.emit(listOf(remind, due))
        assertEquals("REM", overlay.ringing?.reminderId)
        overlay.now = nine
        overlay.emit(listOf(remind, due))
        assertEquals("DUE", overlay.ringing?.reminderId)
        overlay.answer("DUE")
        assertNull(overlay.ringing)
        overlay.emit(listOf(remind, due))
        assertNull(overlay.ringing)
    }
}
