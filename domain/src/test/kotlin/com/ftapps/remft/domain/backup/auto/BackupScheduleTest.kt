package com.ftapps.remft.domain.backup.auto

import com.ftapps.remft.domain.backup.JsonText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class BackupScheduleTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(month: Int, day: Int, hour: Int = 0, minute: Int = 0, year: Int = 2026, z: ZoneId = zone): Instant =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, z).toInstant()

    @Test
    fun anInstallThatNeverChoseBacksUpDailyAround2am() {
        val s = BackupSchedule.parse(null)
        assertEquals(BackupSchedule.defaults, s)
        assertEquals(BackupFrequency.Daily, s.frequency)
        assertEquals(120, s.minutes)
    }

    @Test
    fun aLastSuccessFromTheOld24HourRuleIsDueAtTheFirst2amAfterIt() {
        val s = BackupSchedule.defaults
        val last = at(11, 7, 15)
        assertEquals(at(11, 8, 2), s.nextRunAfter(last, zone))
        assertFalse(s.isDue(at(11, 8, 1, 59), last, zone))
        assertTrue(s.isDue(at(11, 8, 2), last, zone))
    }

    @Test
    fun noBackupYetIsDueAtOnce() {
        assertTrue(BackupSchedule.defaults.isDue(at(11, 8, 12), null, zone))
    }

    @Test
    fun aBrokenOrUnknownStoredValueFallsBackFieldByField() {
        assertEquals(BackupSchedule.defaults, BackupSchedule.fromJson(JsonText.parse("\"nope\"")))
        assertEquals(BackupSchedule.defaults, BackupSchedule.parse("{torn"))
        val s = BackupSchedule.parse("""{"frequency":"hourly","minutes":9999,"weekday":3,"everyDays":1,"monthDay":40}""")
        assertEquals(BackupFrequency.Daily, s.frequency)
        assertEquals(BackupSchedule.DEFAULT_MINUTES, s.minutes)
        assertEquals(3, s.weekday)
        assertEquals(2, s.everyDays)
        assertEquals(1, s.monthDay)
        assertEquals(120, BackupSchedule.parse("""{"minutes":1.5}""").minutes)
    }

    @Test
    fun storedAndReadBackAsCompactJson() {
        val s = BackupSchedule(frequency = BackupFrequency.EveryNDays, minutes = 23 * 60 + 30, everyDays = 5)
        val raw = s.encode()
        assertEquals("""{"frequency":"everyNDays","minutes":1410,"secondMinutes":840,"weekday":7,"everyDays":5,"monthDay":1}""", raw)
        assertEquals(s, BackupSchedule.parse(raw))
    }

    @Test
    fun dailyTheTimeTodayIfStillAheadElseTomorrow() {
        val s = BackupSchedule(minutes = 22 * 60)
        assertEquals(at(11, 8, 22), s.nextRunAfter(at(11, 8, 9), zone))
        assertEquals(at(11, 9, 22), s.nextRunAfter(at(11, 8, 22), zone))
    }

    @Test
    fun twiceDailyWhicheverComesNextInEitherOrder() {
        val s = BackupSchedule(frequency = BackupFrequency.TwiceDaily, minutes = 20 * 60, secondMinutes = 8 * 60)
        assertEquals(listOf(480, 1200), s.times)
        assertEquals(at(11, 8, 8), s.nextRunAfter(at(11, 8, 7), zone))
        assertEquals(at(11, 8, 20), s.nextRunAfter(at(11, 8, 8), zone))
        assertEquals(at(11, 9, 8), s.nextRunAfter(at(11, 8, 21), zone))
        assertEquals(listOf(480), s.copy(minutes = 480).times)
    }

    @Test
    fun weeklyTheChosenWeekday() {
        val s = BackupSchedule(frequency = BackupFrequency.Weekly, weekday = 3, minutes = 3 * 60)
        assertEquals(at(11, 11, 3), s.nextRunAfter(at(11, 8, 12), zone))
        assertEquals(at(11, 18, 3), s.nextRunAfter(at(11, 11, 3), zone))
    }

    @Test
    fun everyNDaysCountedFromTheDayOfTheLastBackup() {
        val s = BackupSchedule(frequency = BackupFrequency.EveryNDays, everyDays = 3)
        assertEquals(at(11, 11, 2), s.nextRunAfter(at(11, 8, 2), zone))
        assertEquals(at(11, 14, 2), s.nextRunAfter(at(11, 11, 17), zone))
    }

    @Test
    fun monthlyTheChosenDayAndAShortMonthUsesItsLastDay() {
        val s = BackupSchedule(frequency = BackupFrequency.Monthly, monthDay = 31, minutes = 60)
        assertEquals(at(2, 28, 1), s.nextRunAfter(at(1, 31, 2), zone))
        assertEquals(at(3, 31, 1), s.nextRunAfter(at(2, 28, 1), zone))
        assertEquals(at(4, 30, 1), s.nextRunAfter(at(4, 1), zone))
        val fifteenth = BackupSchedule(frequency = BackupFrequency.Monthly, monthDay = 15)
        assertEquals(at(12, 15, 2), fifteenth.nextRunAfter(at(11, 15, 3), zone))
        val leap = BackupSchedule(frequency = BackupFrequency.Monthly, monthDay = 30)
        assertEquals(at(2, 29, 2, year = 2028), leap.nextRunAfter(at(1, 31, 3, year = 2028), zone))
    }

    @Test
    fun aUtcLastSuccessIsReadInLocalTime() {
        val last = at(11, 7, 15)
        assertEquals(at(11, 8, 2), BackupSchedule.defaults.nextRunAfter(last, zone))
        val utc = ZoneId.of("UTC")
        assertEquals(at(11, 8, 2, z = utc), BackupSchedule.defaults.nextRunAfter(last, utc))
    }

    @Test
    fun calendarDaysAcrossADaylightSavingChangeKeepTheTimeOfDay() {
        val london = ZoneId.of("Europe/London")
        val s = BackupSchedule(frequency = BackupFrequency.EveryNDays, everyDays = 2, minutes = 3 * 60)
        assertEquals(at(3, 30, 3, z = london), s.nextRunAfter(at(3, 28, 10, z = london), london))
        assertEquals(at(3, 29, 2, z = london), BackupSchedule.defaults.nextRunAfter(at(3, 28, 10, z = london), london))
    }

    @Test
    fun aMissedSlotRunsAtTheNextChanceAndOnlyOnce() {
        val s = BackupSchedule.defaults
        val last = at(11, 1, 2)
        val now = at(11, 8, 10)
        assertTrue(s.isDue(now, last, zone))
        assertFalse(s.isDue(now.plusSeconds(60), now, zone))
        assertEquals(at(11, 9, 2), s.nextRunAfter(now, zone))
    }

    @Test
    fun aLastSuccessInTheFutureIsDue() {
        assertTrue(BackupSchedule.defaults.isDue(at(11, 8, 10), at(12, 8, 10), zone))
        assertFalse(BackupSchedule.defaults.isDue(at(11, 8, 10), at(11, 8, 10, 30), zone))
    }

    @Test
    fun overdueMeansTwoPlannedRunsMissed() {
        val s = BackupSchedule.defaults
        val last = at(11, 6, 2)
        assertFalse(s.isOverdue(at(11, 7, 9), last, zone))
        assertTrue(s.isOverdue(at(11, 8, 2), last, zone))
        val weekly = BackupSchedule(frequency = BackupFrequency.Weekly, weekday = 7)
        assertFalse(weekly.isOverdue(at(11, 8, 9), at(11, 1, 2), zone))
        assertTrue(weekly.isOverdue(at(11, 15, 3), at(11, 1, 2), zone))
        assertFalse(s.isOverdue(at(11, 8, 2), at(12, 8, 2), zone))
    }

    @Test
    fun upcomingSlotsInOrderAsIfEachRunHappensOnTime() {
        val s = BackupSchedule(frequency = BackupFrequency.TwiceDaily, minutes = 120, secondMinutes = 840)
        assertEquals(listOf(at(11, 8, 14), at(11, 9, 2), at(11, 9, 14)), s.upcoming(at(11, 8, 10), at(11, 8, 2), 3, zone))
    }

    @Test
    fun aDueBackupCountsOnFromNow() {
        val s = BackupSchedule(frequency = BackupFrequency.EveryNDays, everyDays = 2)
        assertEquals(listOf(at(11, 10, 2), at(11, 12, 2)), s.upcoming(at(11, 8, 10), at(11, 1, 2), 2, zone))
        assertEquals(listOf(at(11, 10, 2)), s.upcoming(at(11, 8, 10), null, 1, zone))
    }
}
