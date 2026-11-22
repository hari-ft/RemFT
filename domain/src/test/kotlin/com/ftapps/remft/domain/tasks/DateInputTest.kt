package com.ftapps.remft.domain.tasks

import com.ftapps.remft.domain.settings.DateFormatId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class DateInputTest {
    private val now = LocalDateTime.of(2026, 11, 5, 14, 37)

    private fun dt(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = LocalDateTime.of(y, m, d, h, min)
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    @Test
    fun atTimeOfKeepsTheDayAndTakesHourAndMinute() {
        assertEquals(dt(2026, 12, 1, 14, 37), DateInput.atTimeOf(day(2026, 12, 1), now.toLocalTime()))
    }

    @Test
    fun atTimeOfDropsSecondsAndMillis() {
        val at = DateInput.atTimeOf(day(2026, 12, 1), LocalTime.of(8, 5, 42, 300_000_000))
        assertEquals(dt(2026, 12, 1, 8, 5), at)
    }

    @Test
    fun withDateKeepsTheExistingTime() {
        assertEquals(dt(2026, 11, 20, 9, 15), DateInput.withDate(dt(2026, 11, 5, 9, 15), day(2026, 11, 20), null, now))
    }

    @Test
    fun withDateOnAnEmptyValueLandsAnHourFromNow() {
        assertEquals(dt(2026, 11, 20, 15, 37), DateInput.withDate(null, day(2026, 11, 20), null, now))
        assertEquals(dt(2026, 11, 5, 15, 37), DateInput.withDate(null, day(2026, 11, 5), null, now))
    }

    @Test
    fun reminderOnTheDeadlinesDayIsAnHourBefore() {
        assertEquals(dt(2026, 11, 20, 8), DateInput.withDate(null, day(2026, 11, 20), dt(2026, 11, 20, 9), now))
    }

    @Test
    fun reminderIsAnHourFromNowWhenLaterButStillBeforeTheDeadline() {
        assertEquals(dt(2026, 11, 5, 15, 37), DateInput.defaultReminderOn(day(2026, 11, 5), dt(2026, 11, 5, 16), now))
    }

    @Test
    fun reminderNeverAfterTheDeadline() {
        assertEquals(dt(2026, 11, 5, 14), DateInput.defaultReminderOn(day(2026, 11, 5), dt(2026, 11, 5, 15), now))
        assertEquals(dt(2026, 11, 20, 0, 0), DateInput.defaultReminderOn(day(2026, 11, 20), dt(2026, 11, 20, 0, 30), now))
    }

    @Test
    fun reminderOnAnotherDayTakesTheUsualDefault() {
        assertEquals(dt(2026, 11, 18, 15, 37), DateInput.withDate(null, day(2026, 11, 18), dt(2026, 11, 20, 9), now))
    }

    @Test
    fun anExistingReminderKeepsItsTime() {
        assertEquals(dt(2026, 11, 20, 7, 45), DateInput.withDate(dt(2026, 11, 19, 7, 45), day(2026, 11, 20), dt(2026, 11, 20, 9), now))
    }

    @Test
    fun withTimeKeepsTheExistingDay() {
        assertEquals(dt(2026, 11, 20, 9, 5), DateInput.withTime(dt(2026, 11, 20, 18), LocalTime.of(9, 5), null, now))
    }

    @Test
    fun withTimeOnAnEmptyValueWithAnAnchorLandsOnTheAnchorsDay() {
        assertEquals(dt(2026, 11, 30, 9, 5), DateInput.withTime(null, LocalTime.of(9, 5), dt(2026, 11, 30, 23), now))
    }

    @Test
    fun withTimeOnAnEmptyValueLandsOnTheNextTimeTheClockShowsIt() {
        assertEquals(dt(2026, 11, 6, 9, 5), DateInput.withTime(null, LocalTime.of(9, 5), null, now))
        assertEquals(dt(2026, 11, 5, 18), DateInput.withTime(null, LocalTime.of(18, 0), null, now))
        assertEquals(dt(2026, 11, 6, 14, 37), DateInput.withTime(null, LocalTime.of(14, 37), null, now))
    }

    @Test
    fun initialPickerDatePrefersCurrentThenAnchorThenToday() {
        assertEquals(day(2026, 12, 1), DateInput.initialPickerDate(dt(2026, 12, 1, 9), dt(2026, 12, 9, 9), now))
        assertEquals(day(2026, 12, 9), DateInput.initialPickerDate(null, dt(2026, 12, 9, 9), now))
        assertEquals(day(2026, 11, 5), DateInput.initialPickerDate(null, null, now))
    }

    @Test
    fun pickerRange() {
        assertEquals(day(2025, 11, 5), DateInput.pickerFirstDate(now))
        assertEquals(day(2031, 11, 4), DateInput.pickerLastDate(now))
    }

    @Test
    fun defaultFormatIsDayFirstSlashes() {
        val pattern = DateInput.pickerPattern(DateFormatId.System)
        assertEquals(PickerPattern("dd/MM/yyyy", true), pattern)
        assertEquals("dd/mm/yyyy", pattern.compact.lowercase())
        val formatter = DateTimeFormatter.ofPattern(pattern.compact, Locale.ENGLISH)
        assertEquals(day(2026, 12, 28), LocalDate.parse("28/12/2026", formatter))
        assertEquals(day(2026, 11, 5), LocalDate.parse("05/11/2026", formatter))
        assertEquals("05/11/2026", formatter.format(day(2026, 11, 5)))
    }

    @Test
    fun everyFormatParsesItsOwnOrder() {
        val typed = mapOf(
            DateFormatId.System to "28/12/2026",
            DateFormatId.DmyDot to "28.12.2026",
            DateFormatId.DmySlash to "28/12/2026",
            DateFormatId.Mdy12h to "12/28/2026",
            DateFormatId.Iso to "2026-12-28",
            DateFormatId.DmyLong to "28/12/2026",
        )
        assertEquals(DateFormatId.entries.toSet(), typed.keys)
        for ((format, text) in typed) {
            val formatter = DateTimeFormatter.ofPattern(DateInput.pickerPattern(format).compact, Locale.ENGLISH)
            assertEquals(format.id, day(2026, 12, 28), LocalDate.parse(text, formatter))
            assertEquals(format.id, text, formatter.format(day(2026, 12, 28)))
        }
    }

    @Test
    fun onlyTheMonthFirstFormatReadsMonthFirst() {
        val mdy = DateInput.pickerPattern(DateFormatId.Mdy12h)
        assertEquals("mm/dd/yyyy", mdy.compact.lowercase())
        assertFalse(mdy.dayFirst)
        assertEquals(day(2026, 5, 11), LocalDate.parse("05/11/2026", DateTimeFormatter.ofPattern(mdy.compact)))
        assertEquals("yyyy-mm-dd", DateInput.pickerPattern(DateFormatId.Iso).compact.lowercase())
        assertEquals("dd.mm.yyyy", DateInput.pickerPattern(DateFormatId.DmyDot).compact.lowercase())
        assertEquals("dd/mm/yyyy", DateInput.pickerPattern(DateFormatId.DmyLong).compact.lowercase())
        assertTrue(DateInput.pickerPattern(DateFormatId.Iso).dayFirst)
        assertTrue(DateInput.pickerPattern(DateFormatId.DmyLong).dayFirst)
    }

    @Test
    fun anUnknownFormatFallsBackToTheDefault() {
        assertEquals("dd/mm/yyyy", DateInput.pickerPattern(DateFormatId.parse("junk")).compact.lowercase())
    }
}
