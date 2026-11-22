package com.ftapps.remft.domain.repeat

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class SeriesRuleTest {
    private fun utc(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Instant =
        LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC)

    private fun titled(title: String) = SeriesTemplate(mapOf(SeriesTemplate.Title to JsonPrimitive(title)))

    @Test
    fun `create stores the rule and adopts the task it starts from`() {
        val db = SeriesTestDb(utc(2030, 6, 4, 12))
        val at = utc(2030, 6, 5, 9)
        val taskId = db.createTask(title = "Daily report", dueAt = at)

        val id = db.start(
            RepeatRule(freq = RepeatFreq.Daily),
            anchorAt = at,
            timezone = "Asia/Kolkata",
            template = titled("Daily report"),
            fromTaskId = taskId,
        )

        val series = db.state.series.single()
        assertEquals(id, series.id)
        assertEquals("Asia/Kolkata", series.timezone)
        assertEquals(at, series.anchorAt)
        assertTrue(series.templateJson.contains("Daily report"))
        assertEquals(RepeatFreq.Daily, db.series(id)!!.rule.freq)
        assertEquals(id, db.row(taskId).seriesId)
    }

    @Test
    fun `the engine stores whatever timezone the caller passes`() {
        val device = ZoneId.systemDefault().id
        for (zone in listOf(device, "Europe/Berlin", "America/New_York")) {
            val db = SeriesTestDb(utc(2030, 6, 1, 12))
            db.start(
                RepeatRule(freq = RepeatFreq.Daily),
                anchorAt = utc(2030, 6, 5, 9),
                timezone = zone,
                template = titled("Daily report"),
            )
            val series = db.state.series.single()
            assertEquals(zone, series.timezone)
            assertTrue(series.timezone.isNotEmpty())
        }
    }

    @Test
    fun `an after-completion rule keeps its basis through an edit`() {
        val db = SeriesTestDb(utc(2030, 6, 1, 12))
        val after = RepeatRule(freq = RepeatFreq.Daily, interval = 90, basis = RepeatBasis.Completion)
        val id = db.start(after, anchorAt = utc(2030, 6, 5, 9), template = titled("Water filter"))
        assertTrue(db.series(id)!!.rule.isAfterCompletion)

        db.updateRule(id, after.copy(interval = 60))

        val rule = db.series(id)!!.rule
        assertTrue(rule.isAfterCompletion)
        assertEquals(60, rule.interval)
    }
}
