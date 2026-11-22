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
    fun keepsTheStoredOrderAndLeavesRemovedOptionsOut() {
        assertEquals(listOf("1_hour", "5_min"), SnoozePresets.parseOrder("1_hour,5_min"))
        assertEquals(listOf("tomorrow_morning"), SnoozePresets.parseOrder("tomorrow_morning"))
    }

    @Test
    fun ignoresJunkAndDuplicates() {
        assertEquals(listOf("5_min", "30_min"), SnoozePresets.parseOrder("nope,5_min,5_min,,30_min"))
        assertEquals(SnoozePresets.ids, SnoozePresets.parseOrder(null))
        assertEquals("5_min,30_min,1_hour,tomorrow_morning", SnoozePresets.defaultOrderCsv)
    }

    @Test
    fun emptyOrAllJunkMeansTheDefaultFour() {
        assertEquals(SnoozePresets.ids, SnoozePresets.parseOrder(""))
        assertEquals(SnoozePresets.ids, SnoozePresets.parseOrder("  "))
        assertEquals(SnoozePresets.ids, SnoozePresets.parseOrder("nope,m:0,m:abc,,"))
        assertEquals(SnoozePresets.defaultOrderCsv, SnoozePresets.encodeOrder(emptyList()))
    }

    @Test
    fun customDurationsParseAndTrim() {
        assertEquals(listOf("m:10", "1_hour", "m:1440", "m:90"), SnoozePresets.parseOrder(" m:10 , 1_hour,m:1440,m:90"))
        assertEquals("m:10,tomorrow_morning", SnoozePresets.encodeOrder(listOf("m:10", "tomorrow_morning", "m:10")))
    }

    @Test
    fun customDurationsMatchingABuiltInBecomeTheBuiltIn() {
        assertEquals(listOf("5_min", "30_min", "1_hour"), SnoozePresets.parseOrder("m:5,m:30,m:60"))
        assertEquals(listOf("5_min"), SnoozePresets.parseOrder("5_min,m:5"))
        assertEquals("1_hour", SnoozePresets.customId(60))
        assertEquals("m:61", SnoozePresets.customId(61))
    }

    @Test
    fun customDurationsOutsideOneMinuteToAYearAreDropped() {
        assertEquals("m:1", SnoozePresets.normalize("m:1"))
        assertEquals("m:525600", SnoozePresets.normalize("m:525600"))
        assertNull(SnoozePresets.normalize("m:525601"))
        assertNull(SnoozePresets.normalize("m:0"))
        assertNull(SnoozePresets.normalize("m:-5"))
        assertNull(SnoozePresets.normalize("m:+5"))
        assertNull(SnoozePresets.normalize("m:05"))
        assertNull(SnoozePresets.normalize("m:"))
        assertNull(SnoozePresets.normalize("m:99999999999999999999"))
        assertNull(SnoozePresets.normalize("M:10"))
        assertNull(SnoozePresets.customId(0))
        assertNull(SnoozePresets.customId(SnoozePresets.MAX_CUSTOM_MINUTES + 1))
    }

    @Test
    fun theListIsCappedAtEight() {
        val many = (1..12).map { "m:${it * 10}" }
        val parsed = SnoozePresets.parseOrder(many.joinToString(","))
        assertEquals(SnoozePresets.MAX_OPTIONS, parsed.size)
        assertEquals(many.take(8).map { SnoozePresets.normalize(it) }, parsed)
        assertFalse(SnoozePresets.canAdd(parsed))
        assertTrue(SnoozePresets.canAdd(SnoozePresets.ids))
    }

    @Test
    fun theLastOptionCannotBeRemoved() {
        assertEquals(listOf("5_min", "1_hour", "tomorrow_morning"), SnoozePresets.remove(SnoozePresets.ids, "30_min"))
        assertEquals(listOf("m:10"), SnoozePresets.remove(listOf("m:10"), "m:10"))
        assertFalse(SnoozePresets.canRemove(listOf("m:10")))
    }

    @Test
    fun addingChecksTheNumberTheUnitDuplicatesAndTheCap() {
        val order = SnoozePresets.ids
        assertEquals(SnoozeAddCheck.Ok("m:10", order + "m:10"), SnoozePresets.checkAdd(order, "10", SnoozeUnit.MINUTES))
        assertEquals(SnoozeAddCheck.Ok("m:120", order + "m:120"), SnoozePresets.checkAdd(order, " 2 ", SnoozeUnit.HOURS))
        assertEquals(SnoozeAddCheck.Ok("m:2880", order + "m:2880"), SnoozePresets.checkAdd(order, "2", SnoozeUnit.DAYS))
        assertEquals(SnoozeAddCheck.Ok("m:525600", order + "m:525600"), SnoozePresets.checkAdd(order, "365", SnoozeUnit.DAYS))
        assertEquals(SnoozeAddCheck.Empty, SnoozePresets.checkAdd(order, "", SnoozeUnit.MINUTES))
        assertEquals(SnoozeAddCheck.Empty, SnoozePresets.checkAdd(order, "1.5", SnoozeUnit.HOURS))
        assertEquals(SnoozeAddCheck.Empty, SnoozePresets.checkAdd(order, "-3", SnoozeUnit.MINUTES))
        assertEquals(SnoozeAddCheck.TooShort, SnoozePresets.checkAdd(order, "0", SnoozeUnit.DAYS))
        assertEquals(SnoozeAddCheck.TooLong, SnoozePresets.checkAdd(order, "366", SnoozeUnit.DAYS))
        assertEquals(SnoozeAddCheck.TooLong, SnoozePresets.checkAdd(order, "99999999999999999999", SnoozeUnit.MINUTES))
        assertEquals(SnoozeAddCheck.Duplicate("1_hour"), SnoozePresets.checkAdd(order, "60", SnoozeUnit.MINUTES))
        assertEquals(SnoozeAddCheck.Duplicate("m:10"), SnoozePresets.checkAdd(listOf("m:10"), "10", SnoozeUnit.MINUTES))
        val full = (1..8).map { "m:$it" }
        assertEquals(SnoozeAddCheck.Full, SnoozePresets.checkAdd(full, "20", SnoozeUnit.MINUTES))
    }

    @Test
    fun customDurationsSnoozeForThatLong() {
        val now = utc(2026, 7, 15, 12)
        assertEquals(now.plusSeconds(600), SnoozePresets.untilFor("m:10", now, kolkata))
        assertEquals(now.plusSeconds(90 * 60), SnoozePresets.untilForOrNull("m:90", now, kolkata))
        assertEquals(now.plusSeconds(365L * 86400), SnoozePresets.untilFor("m:525600", now, kolkata))
        assertEquals(now.plusSeconds(300), SnoozePresets.untilFor("m:5", now, kolkata))
        assertTrue(SnoozePresets.isKnown("m:10"))
        assertFalse(SnoozePresets.isKnown("m:0"))
        assertNull(SnoozePresets.untilForOrNull("m:0", now, kolkata))
        assertNull(SnoozePresets.untilForOrNull("m:999999", now, kolkata))
    }

    @Test
    fun durationsSplitIntoDaysHoursAndMinutes() {
        assertEquals(SnoozeParts(0, 0, 10), SnoozePresets.partsOf(10))
        assertEquals(SnoozeParts(0, 2, 0), SnoozePresets.partsOf(120))
        assertEquals(SnoozeParts(1, 6, 0), SnoozePresets.partsOf(1800))
        assertEquals(SnoozeParts(1, 0, 1), SnoozePresets.partsOf(1441))
        assertEquals(10L, SnoozePresets.minutesOf("m:10"))
        assertEquals(60L, SnoozePresets.minutesOf("1_hour"))
        assertNull(SnoozePresets.minutesOf("tomorrow_morning"))
        assertTrue(SnoozePresets.isCustom("m:10"))
        assertFalse(SnoozePresets.isCustom("m:30"))
        assertFalse(SnoozePresets.isCustom("tomorrow_morning"))
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
