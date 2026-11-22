package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SnoozePresetsTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test
    fun keepsTheStoredOrderAndAppendsTheRest() {
        assertEquals(listOf("1_hour", "5_min", "30_min", "tomorrow_morning"), SnoozePresets.parseOrder("1_hour,5_min"))
    }

    @Test
    fun ignoresJunkAndDuplicates() {
        assertEquals(listOf("5_min", "30_min", "1_hour", "tomorrow_morning"), SnoozePresets.parseOrder("nope,5_min,5_min,,30_min"))
        assertEquals(SnoozePresets.ids, SnoozePresets.parseOrder(null))
        assertEquals("5_min,30_min,1_hour,tomorrow_morning", SnoozePresets.defaultOrderCsv)
    }

    @Test
    fun fixedPresetsAddToNow() {
        val now = utc(2026, 7, 15, 12)
        assertEquals(now.plusSeconds(300), SnoozePresets.untilFor("5_min", now, kolkata))
        assertEquals(now.plusSeconds(1800), SnoozePresets.untilFor("30_min", now, kolkata))
        assertEquals(now.plusSeconds(3600), SnoozePresets.untilFor("1_hour", now, kolkata))
        assertNull(SnoozePresets.untilForOrNull("2_days", now, kolkata))
    }

    @Test
    fun tomorrowMorningBeforeFourIsThisMorning() {
        val night = ZonedDateTime.of(2026, 7, 15, 0, 30, 0, 0, kolkata).toInstant()
        assertEquals(ZonedDateTime.of(2026, 7, 15, 9, 0, 0, 0, kolkata).toInstant(), SnoozePresets.untilFor("tomorrow_morning", night, kolkata))
        val evening = ZonedDateTime.of(2026, 7, 15, 4, 0, 0, 0, kolkata).toInstant()
        assertEquals(ZonedDateTime.of(2026, 7, 16, 9, 0, 0, 0, kolkata).toInstant(), SnoozePresets.untilFor("tomorrow_morning", evening, kolkata))
    }

    @Test
    fun customSnoozeIsAnchoredHalfAnHourAheadAndRefusesThePast() {
        val now = ZonedDateTime.of(2026, 7, 15, 23, 45, 0, 0, kolkata).toInstant()
        assertEquals(now.plusSeconds(1800), SnoozePresets.customSuggestion(now))
        assertEquals(LocalDate.of(2026, 7, 15), SnoozePresets.customFirstDate(now, kolkata))
        assertEquals(LocalDate.of(2027, 7, 15), SnoozePresets.customLastDate(now, kolkata))
        val past = SnoozePresets.customUntil(LocalDate.of(2026, 7, 15), LocalTime.of(23, 0), kolkata)
        assertFalse(SnoozePresets.isCustomSnoozeAccepted(past, now))
        val later = SnoozePresets.customUntil(LocalDate.of(2026, 7, 16), LocalTime.of(0, 15), kolkata)
        assertTrue(SnoozePresets.isCustomSnoozeAccepted(later, now))
    }
}
