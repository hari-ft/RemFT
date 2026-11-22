package com.ftapps.remft.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class TaskDefaultsTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 11, 5, 14, 37)

    private fun dt(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = LocalDateTime.of(y, m, d, h, min)
    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Instant = dt(y, m, d, h, min).atZone(zone).toInstant()
    private fun utc(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Instant = dt(y, m, d, h, min).toInstant(ZoneOffset.UTC)

    @Test
    fun anHourBeforeADeadlineFurtherAway() {
        assertEquals(dt(2026, 11, 5, 15), TaskDefaults.autoReminderFor(dt(2026, 11, 5, 16), now))
    }

    @Test
    fun noneWithoutADeadline() {
        assertNull(TaskDefaults.autoReminderFor(null as LocalDateTime?, now))
        assertNull(TaskDefaults.autoReminderFor(null as Instant?, Instant.EPOCH))
    }

    @Test
    fun noneWhenItWouldNotBeAfterNow() {
        assertNull(TaskDefaults.autoReminderFor(dt(2026, 11, 5, 15, 37), now))
        assertNull(TaskDefaults.autoReminderFor(dt(2026, 11, 5, 15), now))
        assertNull(TaskDefaults.autoReminderFor(dt(2026, 11, 5, 9), now))
        assertEquals(dt(2026, 11, 5, 14, 38), TaskDefaults.autoReminderFor(dt(2026, 11, 5, 15, 38), now))
    }

    @Test
    fun instantFormAgrees() {
        val nowInstant = at(2026, 11, 5, 14, 37)
        assertEquals(at(2026, 11, 5, 15), TaskDefaults.autoReminderFor(at(2026, 11, 5, 16), nowInstant))
        assertNull(TaskDefaults.autoReminderFor(at(2026, 11, 5, 15, 37), nowInstant))
        assertEquals(java.time.Duration.ofHours(1), TaskDefaults.AUTO_REMINDER_GAP)
    }

    @Test
    fun aHandSetReminderStaysWhenOnlyTheTimeOfDayChanges() {
        assertFalse(TaskDefaults.reminderFollowsDue(at(2027, 3, 25, 22, 40), at(2027, 3, 25, 22, 45), at(2027, 3, 25, 22, 35), zone))
    }

    @Test
    fun theDerivedReminderFollows() {
        assertTrue(TaskDefaults.reminderFollowsDue(at(2027, 3, 25, 18), at(2027, 3, 25, 20), at(2027, 3, 25, 17), zone))
    }

    @Test
    fun aDeadlineMovedBeforeItsReminderTakesItAlong() {
        assertTrue(TaskDefaults.reminderFollowsDue(at(2027, 3, 25, 22, 40), at(2027, 3, 25, 22, 30), at(2027, 3, 25, 22, 35), zone))
    }

    @Test
    fun aMoveToAnotherDayTakesAnyReminder() {
        assertTrue(TaskDefaults.reminderFollowsDue(at(2027, 3, 25, 12), at(2027, 3, 27, 12), at(2027, 3, 25, 9), zone))
    }

    @Test
    fun anUnmovedDeadlineNeverDrags() {
        assertFalse(TaskDefaults.reminderFollowsDue(at(2027, 3, 25, 18), at(2027, 3, 25, 18), at(2027, 3, 25, 17), zone))
    }

    @Test
    fun theDayIsReadInTheGivenZone() {
        val oldDue = utc(2027, 3, 25, 17)
        val newDue = utc(2027, 3, 25, 19)
        val remind = utc(2027, 3, 25, 15)
        assertFalse(TaskDefaults.reminderFollowsDue(oldDue, newDue, remind, ZoneOffset.UTC))
        assertTrue(TaskDefaults.reminderFollowsDue(oldDue, newDue, remind, zone))
    }
}
