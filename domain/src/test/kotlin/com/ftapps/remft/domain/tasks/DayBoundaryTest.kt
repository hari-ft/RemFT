package com.ftapps.remft.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DayBoundaryTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    @Test
    fun stripsTheTimeKeepsTheLocalDay() {
        assertEquals(LocalDateTime.of(2026, 7, 28, 0, 0), DayBoundary.startOfDay(LocalDateTime.of(2026, 7, 28, 23, 59, 59)))
        assertEquals(LocalDateTime.of(2026, 7, 29, 0, 0), DayBoundary.startOfDay(LocalDateTime.of(2026, 7, 29, 0, 0, 1)))
    }

    @Test
    fun startOfDayInstantIsLocalMidnight() {
        val now = Instant.parse("2026-07-28T20:00:00Z")
        assertEquals(Instant.parse("2026-07-28T18:30:00Z"), DayBoundary.startOfDayInstant(now, zone))
        assertEquals(Instant.parse("2026-07-28T00:00:00Z"), DayBoundary.startOfDayInstant(now, ZoneId.of("UTC")))
    }

    @Test
    fun delayRunsToTheNextMidnightPlusASecond() {
        val now = ZonedDateTime.of(2026, 7, 28, 23, 0, 0, 0, zone)
        assertEquals(Duration.ofHours(1).plusSeconds(1), DayBoundary.delayUntilNextDay(now))
        val justAfter = ZonedDateTime.of(2026, 7, 29, 0, 0, 0, 0, zone)
        assertEquals(Duration.ofDays(1).plusSeconds(1), DayBoundary.delayUntilNextDay(justAfter))
    }

    @Test
    fun delayFollowsADaylightSavingNight() {
        val berlin = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, berlin)
        assertEquals(Duration.ofHours(12).plusSeconds(1), DayBoundary.delayUntilNextDay(now))
        val springDay = ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, berlin)
        assertEquals(Duration.ofHours(23).plusSeconds(1), DayBoundary.delayUntilNextDay(springDay))
    }
}
