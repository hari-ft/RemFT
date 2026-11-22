package com.ftapps.remft.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DefaultTimeTest {
    private fun dt(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0, s: Int = 0) = LocalDateTime.of(y, m, d, h, min, s)
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    @Test
    fun todayAtTheLastMinuteLandsAheadStillToday() {
        val now = dt(2026, 11, 5, 23, 59, 30)
        val due = DefaultTime.timeOn(day(2026, 11, 5), now)
        assertTrue(due.isAfter(now))
        assertEquals(5, due.dayOfMonth)
        assertEquals(23, due.hour)
        assertEquals(59, due.minute)
        assertEquals(LocalDateTime.of(2026, 11, 5, 23, 59, 59, 999_000_000), due)
    }

    @Test
    fun atExactly2359Too() {
        val now = dt(2026, 11, 5, 23, 59)
        val due = DefaultTime.timeOn(day(2026, 11, 5), now)
        assertTrue(due.isAfter(now))
        assertEquals(5, due.dayOfMonth)
    }

    @Test
    fun before2359TheClampIs2359Sharp() {
        assertEquals(dt(2026, 11, 5, 23, 59), DefaultTime.timeOn(day(2026, 11, 5), dt(2026, 11, 5, 23, 58, 59)))
    }

    @Test
    fun anotherDayInTheLastMinuteTakesTheMorning() {
        assertEquals(dt(2026, 11, 6, 9), DefaultTime.timeOn(day(2026, 11, 6), dt(2026, 11, 5, 23, 59, 30)))
    }

    @Test
    fun lateAtNightAPickedFutureDateLandsAtNineWithItsReminder() {
        val late = dt(2026, 11, 5, 23, 30)
        val due = DefaultTime.timeOn(day(2026, 11, 15), late)
        assertEquals(dt(2026, 11, 15, 9), due)
        val remind = TaskDefaults.autoReminderFor(due, late)!!
        assertEquals(dt(2026, 11, 15, 8), remind)
        assertTrue(remind.isAfter(late))
    }

    @Test
    fun lateAtNightTodayIsStillClamped() {
        assertEquals(dt(2026, 11, 5, 23, 59), DefaultTime.timeOn(day(2026, 11, 5), dt(2026, 11, 5, 23, 30)))
    }

    @Test
    fun before23NothingChanges() {
        val evening = dt(2026, 11, 5, 21, 10)
        assertFalse(DefaultTime.pastMidnight(evening))
        assertEquals(dt(2026, 11, 15, 22, 10), DefaultTime.timeOn(day(2026, 11, 15), evening))
        assertEquals(dt(2026, 11, 5, 22, 10), DefaultTime.repeatStart(evening))
    }

    @Test
    fun aRepeatWithNoTimeStartsTomorrowAtNineLateAtNight() {
        val late = dt(2026, 11, 5, 23, 30)
        assertTrue(DefaultTime.pastMidnight(late))
        val start = DefaultTime.repeatStart(late)
        assertEquals(dt(2026, 11, 6, 9), start)
        assertEquals(dt(2026, 11, 6, 8), TaskDefaults.autoReminderFor(start, late))
    }

    @Test
    fun theLateClampIsRecognisedOnlyLateOnlyToday() {
        val late = dt(2026, 11, 5, 23, 30)
        assertTrue(DefaultTime.isLateTodayDefault(dt(2026, 11, 5, 23, 59), late))
        assertFalse(DefaultTime.isLateTodayDefault(dt(2026, 11, 6, 9), late))
        assertFalse(DefaultTime.isLateTodayDefault(dt(2026, 11, 5, 23, 59), dt(2026, 11, 5, 21)))
    }

    @Test
    fun anHourAheadExactMinutesKept() {
        assertEquals(dt(2026, 11, 5, 15, 37), DefaultTime.instant(dt(2026, 11, 5, 14, 37)))
    }

    @Test
    fun secondsRoundUpToTheNextWholeMinute() {
        assertEquals(dt(2026, 11, 5, 15, 38), DefaultTime.instant(dt(2026, 11, 5, 14, 37, 1)))
        assertEquals(dt(2026, 11, 6, 0, 0), DefaultTime.instant(dt(2026, 11, 5, 22, 59, 30)))
        assertEquals(dt(2026, 11, 5, 15, 38), DefaultTime.instant(LocalDateTime.of(2026, 11, 5, 14, 37, 0, 1)))
    }

    @Test
    fun todayAndAnotherDayTakeTheSameClockTime() {
        val now = dt(2026, 11, 5, 14, 37)
        assertEquals(dt(2026, 11, 5, 15, 37), DefaultTime.timeOn(day(2026, 11, 5), now))
        assertEquals(dt(2026, 11, 20, 15, 37), DefaultTime.timeOn(day(2026, 11, 20), now))
        assertEquals(dt(2026, 11, 20, 10, 11), DefaultTime.timeOn(day(2026, 11, 20), dt(2026, 11, 5, 9, 10, 5)))
    }

    @Test
    fun after23TodayIsClampedAndOtherDaysTakeTheMorning() {
        val late = dt(2026, 11, 5, 23, 30)
        assertEquals(dt(2026, 11, 6, 9), DefaultTime.timeOn(day(2026, 11, 6), late))
        assertEquals(dt(2026, 11, 15, 9), DefaultTime.timeOn(day(2026, 11, 15), late))
        assertEquals(dt(2026, 11, 5, 23, 59), DefaultTime.timeOn(day(2026, 11, 5), dt(2026, 11, 5, 22, 59)))
    }

    @Test
    fun constants() {
        assertEquals(java.time.Duration.ofHours(1), DefaultTime.LEAD)
        assertEquals(9, DefaultTime.LATE_DEFAULT_HOUR)
    }
}
