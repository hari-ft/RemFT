package com.ftapps.remft.domain.time

import com.ftapps.remft.domain.settings.DateFormatId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DateFormatsTest {
    private val sample = DateFormats.sample

    @Test
    fun formatsTheSampleInEveryFormat() {
        assertEquals("31/12/2026 11:59 PM", DateFormats.dateTime(sample, DateFormatId.System))
        assertEquals("31.12.2026 23:59", DateFormats.dateTime(sample, DateFormatId.DmyDot))
        assertEquals("31/12/2026 23:59", DateFormats.dateTime(sample, DateFormatId.DmySlash))
        assertEquals("12/31/2026 11:59 PM", DateFormats.dateTime(sample, DateFormatId.Mdy12h))
        assertEquals("2026-12-31 23:59", DateFormats.dateTime(sample, DateFormatId.Iso))
        assertEquals("31 December 2026 23:59", DateFormats.dateTime(sample, DateFormatId.DmyLong))
    }

    @Test
    fun shortRowTextAndDayHeading() {
        val value = LocalDateTime.of(2026, 7, 16, 9, 5)
        assertEquals("16 Jul, 9:05 AM", DateFormats.short(value, DateFormatId.System))
        assertEquals("Jul 16, 9:05 AM", DateFormats.short(value, DateFormatId.Mdy12h))
        assertEquals("16-07, 09:05", DateFormats.short(value, DateFormatId.Iso))
        assertEquals("Thursday · 16 Jul", DateFormats.dayHeading(value, DateFormatId.System))
        assertEquals("Thursday · 16 July", DateFormats.dayHeading(value, DateFormatId.DmyLong))
    }

    @Test
    fun clockKindFollowsThePattern() {
        assertFalse(DateFormats.spec(DateFormatId.System).uses24HourClock)
        assertTrue(DateFormats.spec(DateFormatId.Iso).uses24HourClock)
        assertTrue(DateFormats.spec(DateFormatId.Mdy12h).monthFirst)
        assertFalse(DateFormats.spec(DateFormatId.DmySlash).monthFirst)
    }
}
