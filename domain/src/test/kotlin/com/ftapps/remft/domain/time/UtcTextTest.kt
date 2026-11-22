package com.ftapps.remft.domain.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class UtcTextTest {
    @Test
    fun writesMillisecondPrecisionUtc() {
        assertEquals("2026-10-16T04:30:00.000Z", UtcText.format(Instant.parse("2026-10-16T04:30:00Z")))
        assertEquals("2026-10-16T04:30:00.123Z", UtcText.formatMillis(Instant.parse("2026-10-16T04:30:00.123456Z").toEpochMilli()))
    }

    @Test
    fun readsThreeAndSixFractionalDigits() {
        assertEquals(Instant.parse("2026-10-16T04:30:00.123Z"), UtcText.parse("2026-10-16T04:30:00.123Z"))
        assertEquals(Instant.parse("2026-10-16T04:30:00.123456Z"), UtcText.parse("2026-10-16T04:30:00.123456Z"))
    }

    @Test
    fun honoursAnIsoOffset() {
        assertEquals(Instant.parse("2026-11-05T05:15:00Z"), UtcText.parse("2026-11-05T10:45:00.000+05:30"))
        assertEquals(Instant.parse("2026-11-05T12:45:00Z"), UtcText.parse("2026-11-05T10:45:00-02:00"))
    }

    @Test
    fun refusesValuesThatAreNotIsoInstants() {
        assertNull(UtcText.parse("2026-11-05T10:45:00.000"))
        assertNull(UtcText.parse("2026-11-05 10:45:00Z"))
        assertNull(UtcText.parse("1762339500000"))
        assertNull(UtcText.parse("not a date"))
        assertNull(UtcText.parse(null))
    }

    @Test
    fun readsDayOnlyValues() {
        assertEquals("2026-02-28", UtcText.parseDay("2026-02-28").toString())
        assertNull(UtcText.parseDay("2026-02-30"))
    }
}
