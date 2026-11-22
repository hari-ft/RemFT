package com.ftapps.remft.domain.repeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class SeriesAdoptTest {
    private val now = utc(2026, 11, 6, 5)
    private val db = SeriesTestDb(now)

    private val janJul = RepeatRule(freq = RepeatFreq.Monthly, byMonthDay = listOf(1), byMonth = listOf(1, 7))

    private fun utc(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Instant =
        LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC)

    private fun adoptDateless(title: String, rule: RepeatRule): Pair<String, String> {
        val id = db.createTask(title = title, createdAt = now)
        val seriesId = db.start(
            rule,
            anchorAt = utc(2026, 11, 6, 6, 30),
            timezone = "UTC",
            template = null,
            fromTaskId = id,
            at = now,
        )
        return id to seriesId
    }

    @Test
    fun `gets its occurrence deadline at the series time of day`() {
        val (id, seriesId) = adoptDateless("Pay insurance", janJul)

        val adopted = db.row(id)
        assertEquals(seriesId, adopted.seriesId)
        assertEquals("2027-01-01", adopted.occurrenceDate)
        assertEquals(utc(2027, 1, 1, 6, 30), adopted.dueAt)

        val rows = db.rowsOf(seriesId)
        assertEquals(setOf("2027-01-01", "2027-07-01"), rows.map { it.occurrenceDate }.toSet())
        assertTrue(rows.all { it.dueAt != null })
    }

    private fun datedOnItsFirstDay(rule: RepeatRule, day: String) {
        val (id, seriesId) = adoptDateless("Renew", rule)
        val adopted = db.row(id)
        assertEquals(day, adopted.occurrenceDate)
        val p = day.split('-').map(String::toInt)
        assertEquals(utc(p[0], p[1], p[2], 6, 30), adopted.dueAt)
        assertTrue(db.rowsOf(seriesId).all { it.dueAt != null })
    }

    @Test
    fun `yearly on 15 March - dated on its first day`() = datedOnItsFirstDay(
        RepeatRule(freq = RepeatFreq.Yearly, byMonthDay = listOf(15), byMonth = listOf(3)),
        "2027-03-15",
    )

    @Test
    fun `quarterly on the 1st - dated on its first day`() = datedOnItsFirstDay(
        RepeatRule(freq = RepeatFreq.Monthly, byMonthDay = listOf(1), byMonth = listOf(1, 4, 7, 10)),
        "2027-01-01",
    )
}
