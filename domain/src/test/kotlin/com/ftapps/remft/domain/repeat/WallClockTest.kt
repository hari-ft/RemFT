package com.ftapps.remft.domain.repeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class WallClockTest {
    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int): Instant = LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC)

    private fun resolve(zone: String, y: Int, m: Int, d: Int, h: Int, min: Int) = WallClock.toInstant(zone, y, m, d, h, min)

    private fun shown(instant: Instant, zone: String): String =
        WallClock.wallClock(instant, zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    @Test
    fun `New York - a normal winter time resolves exactly`() {
        assertEquals(utc(2026, 1, 15, 14, 0), resolve("America/New_York", 2026, 1, 15, 9, 0))
    }

    @Test
    fun `New York - a normal summer time resolves exactly`() {
        assertEquals(utc(2026, 7, 15, 13, 0), resolve("America/New_York", 2026, 7, 15, 9, 0))
    }

    @Test
    fun `New York - a time inside the spring-forward gap moves forward, not back`() {
        val at = resolve("America/New_York", 2026, 3, 8, 2, 30)
        assertEquals(utc(2026, 3, 8, 7, 30), at)
        assertEquals("2026-03-08 03:30", shown(at, "America/New_York"))
    }

    @Test
    fun `New York - the start of the gap becomes the first valid minute`() {
        assertEquals("2026-03-08 03:00", shown(resolve("America/New_York", 2026, 3, 8, 2, 0), "America/New_York"))
    }

    @Test
    fun `New York - the times either side of the gap are untouched`() {
        assertEquals("2026-03-08 01:59", shown(resolve("America/New_York", 2026, 3, 8, 1, 59), "America/New_York"))
        assertEquals("2026-03-08 03:00", shown(resolve("America/New_York", 2026, 3, 8, 3, 0), "America/New_York"))
    }

    @Test
    fun `New York - a time the fall-back night repeats resolves to its first ring`() {
        val at = resolve("America/New_York", 2026, 11, 1, 1, 30)
        assertEquals(utc(2026, 11, 1, 5, 30), at)
        assertEquals("2026-11-01 01:30", shown(at, "America/New_York"))
    }

    @Test
    fun `London - a time inside the spring-forward gap moves forward`() {
        val at = resolve("Europe/London", 2026, 3, 29, 1, 30)
        assertEquals(utc(2026, 3, 29, 1, 30), at)
        assertEquals("2026-03-29 02:30", shown(at, "Europe/London"))
    }

    @Test
    fun `London - a time the fall-back night repeats resolves to its first ring`() {
        val at = resolve("Europe/London", 2026, 10, 25, 1, 30)
        assertEquals(utc(2026, 10, 25, 0, 30), at)
        assertEquals("2026-10-25 01:30", shown(at, "Europe/London"))
    }

    @Test
    fun `London - a normal time resolves exactly`() {
        assertEquals(utc(2026, 6, 1, 6, 15), resolve("Europe/London", 2026, 6, 1, 7, 15))
    }

    @Test
    fun `Kolkata - every time resolves at the fixed +05 30 offset`() {
        assertEquals(utc(2026, 3, 7, 21, 0), resolve("Asia/Kolkata", 2026, 3, 8, 2, 30))
        assertEquals(utc(2026, 10, 31, 20, 0), resolve("Asia/Kolkata", 2026, 11, 1, 1, 30))
        assertEquals(utc(2026, 6, 1, 3, 30), resolve("Asia/Kolkata", 2026, 6, 1, 9, 0))
    }

    @Test
    fun `an unknown or local zone falls back to the device clock, never UTC`() {
        val at = utc(2026, 6, 1, 3, 30)
        assertEquals(WallClock.wallDay(at, java.time.ZoneId.systemDefault().id), WallClock.wallDay(at, WallClock.LocalZone))
        assertEquals(WallClock.wallDay(at, java.time.ZoneId.systemDefault().id), WallClock.wallDay(at, "Not/AZone"))
        assertFalse(WallClock.isKnownZone("Not/AZone"))
        assertFalse(WallClock.isKnownZone(WallClock.LocalZone))
        assertTrue(WallClock.isKnownZone("Asia/Kolkata"))
    }
}
