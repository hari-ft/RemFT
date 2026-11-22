package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AlarmRingingTest {
    private val now = utc(2030, 6, 1, 12)
    private val t0 = utc(2030, 6, 1, 11, 59)
    private val insistent = ReminderProfile(slots = listOf(0, 1, 2, 3, 5, 8, 12, 17, 25, 40))

    private fun alarm(
        rid: String = "R1",
        urgent: Boolean = true,
        remindAt: Instant,
        status: String = ReminderStatus.SCHEDULED,
        snoozedUntil: Instant? = null,
    ) = AlarmInput(
        reminderId = rid,
        taskId = "T-$rid",
        taskTitle = "Task $rid",
        remindAt = remindAt,
        status = status,
        urgent = urgent,
        requiresAcknowledgement = urgent,
        snoozedUntil = snoozedUntil,
    )

    private fun minutes(n: Long) = Duration.ofMinutes(n)

    @Test
    fun nothingRingsWhenThereAreNoAlarms() {
        assertNull(AlarmRinging.ringingAlarm(emptyList(), now))
    }

    @Test
    fun nonUrgentDueAlarmNeverTakesOverTheScreen() {
        val a = alarm(urgent = false, remindAt = now.minus(minutes(1)))
        assertNull(AlarmRinging.ringingAlarm(listOf(a), now))
    }

    @Test
    fun urgentAlarmRingsOnceItsFireTimeHasArrived() {
        val a = alarm(remindAt = now.minus(minutes(1)))
        assertEquals("R1", AlarmRinging.ringingAlarm(listOf(a), now)?.reminderId)
    }

    @Test
    fun urgentAlarmStillInTheFutureDoesNotRing() {
        val a = alarm(remindAt = now.plus(minutes(1)))
        assertNull(AlarmRinging.ringingAlarm(listOf(a), now))
    }

    @Test
    fun alarmThatRangLastWinsWhenSeveralAreDue() {
        val older = alarm(rid = "R1", remindAt = now.minus(minutes(10)))
        val newer = alarm(rid = "R2", remindAt = now.minus(minutes(1)))
        assertEquals("R2", AlarmRinging.ringingAlarm(listOf(newer, older), now)?.reminderId)
    }

    @Test
    fun snoozePushesTheFireOutThenRingsAgainWhenItElapses() {
        val a = alarm(
            remindAt = now.minus(Duration.ofHours(1)),
            status = ReminderStatus.SNOOZED,
            snoozedUntil = now.plus(minutes(5)),
        )
        assertNull(AlarmRinging.ringingAlarm(listOf(a), now))
        assertEquals("R1", AlarmRinging.ringingAlarm(listOf(a), now.plus(minutes(6)))?.reminderId)
    }

    @Test
    fun alarmRingsForAFewMinutesAfterItComesDue() {
        val a = alarm(remindAt = now.minus(minutes(5)))
        assertEquals("R1", AlarmRinging.ringingAlarm(listOf(a), now)?.reminderId)
        assertTrue(AlarmRinging.missedAlarms(listOf(a), now).isEmpty())
    }

    @Test
    fun openedLaterTheAppShowsNoAlarmScreenAndTheCardHasIt() {
        val a = alarm(remindAt = now.minus(minutes(6)))
        assertNull(AlarmRinging.ringingAlarm(listOf(a), now))
        assertEquals(listOf("R1"), AlarmRinging.missedAlarms(listOf(a), now).map { it.reminderId })
    }

    @Test
    fun nextReRingShowsTheScreenAgain() {
        val a = alarm(remindAt = now.minus(minutes(31)))
        assertEquals("R1", AlarmRinging.ringingAlarm(listOf(a), now)?.reminderId)
        assertTrue("never both", AlarmRinging.missedAlarms(listOf(a), now).isEmpty())
        val later = now.plus(minutes(5))
        assertNull(AlarmRinging.ringingAlarm(listOf(a), later))
        assertEquals(1, AlarmRinging.missedAlarms(listOf(a), later).size)
    }

    @Test
    fun windowIsPerRingWhateverTheChain() {
        val a = alarm(remindAt = now.minus(minutes(35)))
        assertNull(AlarmRinging.ringingAlarm(listOf(a), now, profile = insistent))
        val b = alarm(remindAt = now.minus(minutes(42)))
        assertEquals("R1", AlarmRinging.ringingAlarm(listOf(b), now, profile = insistent)?.reminderId)
    }

    @Test
    fun alarmFromThreeDaysAgoDoesNotRing() {
        val a = alarm(remindAt = now.minus(Duration.ofDays(3)))
        assertNull(AlarmRinging.ringingAlarm(listOf(a), now))
    }

    @Test
    fun recentAlarmIsNotForgottenEither() {
        val a = alarm(remindAt = now.minus(Duration.ofHours(8)))
        assertEquals(listOf("R1"), AlarmRinging.missedAlarms(listOf(a), now).map { it.reminderId })
    }

    @Test
    fun taskOverdueForAWeekIsTheListsJobNotACard() {
        val a = alarm(remindAt = now.minus(Duration.ofDays(7)))
        assertNull(AlarmRinging.ringingAlarm(listOf(a), now))
        assertTrue(AlarmRinging.missedAlarms(listOf(a), now).isEmpty())
    }

    @Test
    fun missedAlarmsComeBackNewestFirst() {
        val old = alarm(rid = "R1", remindAt = now.minus(Duration.ofHours(20)))
        val recent = alarm(rid = "R2", remindAt = now.minus(Duration.ofHours(2)))
        assertEquals(listOf("R2", "R1"), AlarmRinging.missedAlarms(listOf(old, recent), now).map { it.reminderId })
    }

    @Test
    fun oneMissedEntryPerTaskItsDeadlineStandsForItsReminder() {
        val remind = AlarmInput(
            reminderId = "R1",
            taskId = "T1",
            taskTitle = "Pay rent",
            remindAt = now.minus(Duration.ofHours(3)),
            status = ReminderStatus.SCHEDULED,
            urgent = true,
            requiresAcknowledgement = true,
        )
        val due = AlarmInput(
            reminderId = "D1",
            taskId = "T1",
            taskTitle = "Pay rent",
            remindAt = now.minus(Duration.ofHours(2)),
            status = ReminderStatus.SCHEDULED,
            urgent = true,
            requiresAcknowledgement = true,
            kind = AlarmKind.DUE,
        )
        assertEquals(listOf("D1"), AlarmRinging.missedAlarms(listOf(remind, due), now).map { it.reminderId })
        val early = now.minus(Duration.ofHours(2).plusMinutes(20))
        assertEquals(listOf("R1"), AlarmRinging.missedAlarms(listOf(remind, due), early).map { it.reminderId })
    }

    @Test
    fun nonUrgentAlarmIsNeverMissed() {
        val a = alarm(urgent = false, remindAt = now.minus(Duration.ofHours(8)))
        assertTrue(AlarmRinging.missedAlarms(listOf(a), now).isEmpty())
    }

    @Test
    fun nextRealertIsTheNextSlotOfTheChainAfterTheUserLookedAway() {
        val at = now.minus(minutes(1))
        val a = alarm(remindAt = at)
        val factory = ReminderProfile.Factory
        assertEquals(at.plus(minutes(30)), AlarmRinging.nextRealertAfter(a, now, factory))
        assertEquals(at.plus(minutes(60)), AlarmRinging.nextRealertAfter(a, at.plus(minutes(30)), factory))
        assertNull("the chain is over", AlarmRinging.nextRealertAfter(a, at.plus(minutes(60)), factory))
    }

    @Test
    fun alarmThatRingsOnceHasNoRealert() {
        val at = now.minus(minutes(1))
        val once = alarm(remindAt = at, urgent = false)
        assertNull(AlarmRinging.nextRealertAfter(once, now, ReminderProfile.Factory))
        val snoozed = alarm(remindAt = at, status = ReminderStatus.SNOOZED, snoozedUntil = at)
        assertNull(
            AlarmRinging.nextRealertAfter(
                snoozed,
                now,
                ReminderProfile(slots = listOf(0, 30, 60), repeatAfterSnooze = false),
            ),
        )
    }

    @Test
    fun nextUrgentFireReturnsTheSoonestFutureUrgentFire() {
        val soon = alarm(rid = "R1", remindAt = now.plus(minutes(5)))
        val later = alarm(rid = "R2", remindAt = now.plus(minutes(30)))
        val nonUrgent = alarm(rid = "R3", urgent = false, remindAt = now.plus(minutes(1)))
        assertEquals(now.plus(minutes(5)), AlarmRinging.nextUrgentFireAfter(listOf(later, soon, nonUrgent), now))
    }

    @Test
    fun nextUrgentFireIgnoresPastFiresAndIsNullWhenNoneRemain() {
        val past = alarm(remindAt = now.minus(minutes(61)))
        assertNull(AlarmRinging.nextUrgentFireAfter(listOf(past), now))
    }

    @Test
    fun realertOfARingingAlarmCountsAsANextUrgentFire() {
        val ringing = alarm(remindAt = now.minus(minutes(1)))
        assertEquals(now.plus(minutes(29)), AlarmRinging.nextUrgentFireAfter(listOf(ringing), now))
    }

    @Test
    fun ringsADueUrgentSyntheticAlarm() {
        val taskId = pad26("T1")
        val task = alarmTask(
            id = taskId,
            title = "Take pills",
            isUrgent = true,
            requiresAcknowledgement = true,
            dueAt = now.minus(minutes(1)),
        )
        val ringing = AlarmRinging.ringingAlarm(AlarmSelection.alarmsFor(listOf(task), emptyList()), now)
        assertNotNull(ringing)
        assertEquals(taskId, ringing!!.taskId)
        assertTrue(ringing.urgent)
    }

    @Test
    fun handledKeyChangesWithStatusAndFireTime() {
        val scheduled = alarm(remindAt = t0)
        val snoozed = alarm(remindAt = t0, status = ReminderStatus.SNOOZED, snoozedUntil = now.plus(minutes(5)))
        val moved = alarm(remindAt = now.plus(minutes(10)))
        assertEquals(AlarmRinging.handledKey(scheduled), AlarmRinging.handledKey(alarm(remindAt = t0)))
        assertTrue(AlarmRinging.handledKey(scheduled) != AlarmRinging.handledKey(snoozed))
        assertTrue(AlarmRinging.handledKey(scheduled) != AlarmRinging.handledKey(moved))
    }

    @Test
    fun handledAlarmStaysHiddenUntilTheWriteLandsThenRingsAgainWhenItsSnoozeEnds() {
        val overlay = OverlayHarness(now)
        overlay.emit(listOf(alarm(remindAt = t0)))
        assertEquals("R1", overlay.ringing?.reminderId)
        overlay.answer("R1")
        assertNull(overlay.ringing)
        overlay.emit(listOf(alarm(remindAt = t0)))
        assertNull(overlay.ringing)
        val snoozed = alarm(remindAt = t0, status = ReminderStatus.SNOOZED, snoozedUntil = now.plus(minutes(5)))
        overlay.emit(listOf(snoozed))
        assertNull(overlay.ringing)
        overlay.now = now.plus(minutes(6))
        overlay.emit(listOf(snoozed))
        assertEquals("R1", overlay.ringing?.reminderId)
    }

    @Test
    fun openHidesThisRingAndALaterReArmOfTheSameAlarmRings() {
        val overlay = OverlayHarness(now)
        overlay.emit(listOf(alarm(remindAt = t0)))
        overlay.answer("R1")
        overlay.emit(listOf(alarm(remindAt = t0)))
        assertNull(overlay.ringing)
        val rearmed = alarm(remindAt = t0, status = ReminderStatus.SNOOZED, snoozedUntil = now.plus(minutes(2)))
        overlay.now = now.plus(minutes(3))
        overlay.emit(listOf(rearmed))
        assertEquals("R1", overlay.ringing?.reminderId)
    }

    @Test
    fun openHidesTheRingOnlyUntilTheNextRealertSlot() {
        val overlay = OverlayHarness(now)
        overlay.emit(listOf(alarm(remindAt = t0)))
        overlay.lookAway("R1")
        assertNull(overlay.ringing)
        assertEquals(t0.plus(minutes(30)), overlay.wakeAt)
        overlay.emit(listOf(alarm(remindAt = t0)))
        assertNull(overlay.ringing)
        overlay.now = t0.plus(minutes(29))
        overlay.emit(listOf(alarm(remindAt = t0)))
        assertNull("the +30 slot has not rung yet", overlay.ringing)
        overlay.now = t0.plus(minutes(30))
        overlay.emit(listOf(alarm(remindAt = t0)))
        assertEquals("R1", overlay.ringing?.reminderId)
    }

    @Test
    fun openThenAppOpenedAgainShowsNoAlarmScreenUntilItsNextRing() {
        val overlay = OverlayHarness(now)
        overlay.emit(listOf(alarm(remindAt = t0)))
        overlay.lookAway("R1")
        assertNull(overlay.ringing)
        overlay.reconsider()
        assertNull(overlay.ringing)
        overlay.now = t0.plus(minutes(31))
        overlay.reconsider()
        assertEquals("R1", overlay.ringing?.reminderId)
    }

    @Test
    fun answeredAlarmStaysHiddenWhenTheAppIsOpenedAgain() {
        val overlay = OverlayHarness(now)
        overlay.emit(listOf(alarm(remindAt = t0)))
        overlay.answer("R1")
        overlay.reconsider()
        assertNull("its write is on the way", overlay.ringing)
    }

    @Test
    fun lookedAwayAlarmPastItsWindowIsAMissedAlarmNotARing() {
        val overlay = OverlayHarness(now)
        overlay.emit(listOf(alarm(remindAt = t0)))
        overlay.lookAway("R1")
        overlay.now = t0.plus(minutes(85))
        overlay.reconsider()
        assertNull(overlay.ringing)
        assertEquals(listOf("R1"), AlarmRinging.missedAlarms(overlay.feed, overlay.now).map { it.reminderId })
    }

    @Test
    fun reTimedReminderRingsAtItsNewTime() {
        val overlay = OverlayHarness(now)
        overlay.emit(listOf(alarm(remindAt = t0)))
        overlay.answer("R1")
        assertNull(overlay.ringing)
        val moved = now.plus(minutes(10))
        overlay.emit(listOf(alarm(remindAt = moved)))
        assertNull(overlay.ringing)
        overlay.now = moved.plusSeconds(1)
        overlay.emit(listOf(alarm(remindAt = moved)))
        assertEquals("R1", overlay.ringing?.reminderId)
    }

    @Test
    fun ringingAlarmWakesWhenItsLiveWindowEnds() {
        val overlay = OverlayHarness(now)
        overlay.emit(listOf(alarm(remindAt = t0)))
        assertEquals("R1", overlay.ringing?.reminderId)
        assertEquals(t0.plus(AlarmRinging.LiveWindow).plusSeconds(1), overlay.wakeAt)
        overlay.now = t0.plus(AlarmRinging.LiveWindow)
        overlay.reconsider()
        assertEquals("R1", overlay.ringing?.reminderId)
        overlay.now = t0.plus(AlarmRinging.LiveWindow).plusSeconds(1)
        overlay.reconsider()
        assertNull(overlay.ringing)
        assertEquals(t0.plus(minutes(30)), overlay.wakeAt)
    }

    @Test
    fun liveWindowEndsIsNullOnceEveryRingIsPastItsWindow() {
        val a = alarm(remindAt = t0)
        val times = AlarmPlanner.plannedRingTimes(listOf(a))
        assertEquals(t0.plus(AlarmRinging.LiveWindow).plusSeconds(1), AlarmRinging.liveWindowEnds(listOf(a), now, times))
        assertNull(AlarmRinging.liveWindowEnds(listOf(a), t0.plus(minutes(20)), times))
        assertNull(AlarmRinging.liveWindowEnds(listOf(a), t0.minus(minutes(1)), times))
        val quiet = alarm(rid = "R2", urgent = false, remindAt = t0)
        assertNull(AlarmRinging.liveWindowEnds(listOf(quiet), now, AlarmPlanner.plannedRingTimes(listOf(quiet))))
    }

    @Test
    fun ringingDisappearsOnceTheRingingAlarmIsAnswered() {
        val overlay = OverlayHarness(now)
        overlay.emit(listOf(alarm(remindAt = now.minus(minutes(1)))))
        assertNotNull(overlay.ringing)
        overlay.answer("R1")
        assertNull(overlay.ringing)
    }

    @Test
    fun snoozedUntilOfIsTheSoonestFutureSnoozeOfThatTask() {
        val first = alarm(rid = "R1", remindAt = t0, status = ReminderStatus.SNOOZED, snoozedUntil = now.plus(minutes(20)))
            .copy(taskId = "T1")
        val second = alarm(rid = "R2", remindAt = t0, status = ReminderStatus.SNOOZED, snoozedUntil = now.plus(minutes(10)))
            .copy(taskId = "T1")
        val other = alarm(rid = "R3", remindAt = t0, status = ReminderStatus.SNOOZED, snoozedUntil = now.plus(minutes(5)))
            .copy(taskId = "T2")
        assertEquals(now.plus(minutes(10)), AlarmSelection.snoozedUntilOf(listOf(first, second, other), "T1", now))
        assertEquals(now.plus(minutes(5)), AlarmSelection.snoozedUntilOf(listOf(first, second, other), "T2", now))
    }

    @Test
    fun snoozedUntilOfIgnoresElapsedAndNonSnoozedAlarms() {
        val elapsed = alarm(rid = "R1", remindAt = t0, status = ReminderStatus.SNOOZED, snoozedUntil = now)
            .copy(taskId = "T1")
        val answered = alarm(rid = "R2", remindAt = t0, status = ReminderStatus.SCHEDULED, snoozedUntil = now.plus(minutes(10)))
            .copy(taskId = "T1")
        assertNull(AlarmSelection.snoozedUntilOf(listOf(elapsed, answered), "T1", now))
        assertNull(AlarmSelection.snoozedUntilOf(emptyList(), "T1", now))
    }
}
