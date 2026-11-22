package com.ftapps.remft.domain.repeat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceTest {
    private fun roundTrip(rule: RepeatRule): RepeatRule? = RepeatRule.fromJsonString(rule.toJsonString())

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun expand(rule: RepeatRule, anchor: String, max: Int = 5) = Recurrence.expand(rule, anchor = anchor, max = max)

    @Test
    fun `plans only the anchor - there is no window to fill`() {
        val rule = RepeatRule(freq = RepeatFreq.Daily, basis = RepeatBasis.Completion)
        assertEquals(listOf("2026-10-17"), Recurrence.expand(rule, anchor = "2026-10-17", max = 5))
    }

    @Test
    fun `round-trips the basis, a calendar rule writes no basis key`() {
        val after = RepeatRule(freq = RepeatFreq.Monthly, interval = 3, basis = RepeatBasis.Completion, end = RepeatEnd.Count(4))
        assertEquals(JsonPrimitive("completion"), after.toJson()["basis"])
        assertEquals(after, roundTrip(after))
        val calendar = RepeatRule(freq = RepeatFreq.Daily)
        assertFalse(calendar.toJson().containsKey("basis"))
        assertEquals(calendar, RepeatRule.fromJson(json("""{"freq":"daily","basis":"calendar"}""")))
        assertNotEquals(after, after.copy(basis = RepeatBasis.Calendar))
    }

    @Test
    fun `round-trips through JSON without losing a field`() {
        val rule = RepeatRule(
            freq = RepeatFreq.Monthly,
            interval = 2,
            byWeekday = listOf(WeekdayPick("MO")),
            byMonthDay = listOf(23, 24, 25, 26, 27, 28, 29),
            end = RepeatEnd.Count(10),
        )
        assertEquals(rule, roundTrip(rule))
    }

    @Test
    fun `writes rule_json byte for byte in the stored shape`() {
        val rule = RepeatRule(
            freq = RepeatFreq.Monthly,
            byWeekday = listOf(WeekdayPick("MO"), WeekdayPick("TU", 2)),
            byMonthDay = listOf(-1),
            byMonth = listOf(2, 5),
            end = RepeatEnd.Until("2027-01-31"),
        )
        assertEquals(
            """{"freq":"monthly","interval":1,"byWeekday":[{"day":"MO","ordinal":null},{"day":"TU","ordinal":2}],"byMonthDay":[-1],"byMonth":[2,5],"end":{"type":"until","until":"2027-01-31"}}""",
            rule.toJsonString(),
        )
        assertEquals(
            """{"freq":"daily","interval":3,"end":{"type":"never"},"basis":"completion"}""",
            RepeatRule(freq = RepeatFreq.Daily, interval = 3, basis = RepeatBasis.Completion).toJsonString(),
        )
    }

    @Test
    fun `tolerates unknown fields and odd values`() {
        val rule = RepeatRule.fromJsonString(
            """{"freq":"weekly","interval":0,"byWeekday":[{"day":"XX"},{"day":"FR","ordinal":"2"},7],"byMonthDay":["3",4],"end":{"type":"count","count":"5"},"future":true}""",
        )
        assertEquals(
            RepeatRule(freq = RepeatFreq.Weekly, interval = 1, byWeekday = listOf(WeekdayPick("FR")), byMonthDay = listOf(4)),
            rule,
        )
        assertEquals(RepeatEnd.Never, RepeatRule.fromJsonString("""{"freq":"daily","end":{"type":"until"}}""")!!.end)
        assertNull(RepeatRule.fromJsonString("not json"))
    }

    @Test
    fun `reads a rule with weekday ordinals (ordinal nulls included)`() {
        val rule = RepeatRule.fromJson(
            json("""{"freq":"monthly","interval":1,"byWeekday":[{"day":"TU","ordinal":2}],"end":{"type":"never"}}"""),
        )
        assertEquals(2, rule!!.byWeekday.single().ordinal)
        assertTrue(rule.end.isNever)
    }

    @Test
    fun `rejects nonsense instead of guessing`() {
        assertNull(RepeatRule.fromJson(json("""{"freq":"hourly"}""")))
        assertNull(RepeatRule.fromJson(JsonPrimitive("every day")))
        assertNotNull(validateRepeatRule(RepeatRule(freq = RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("MO", 2)))))
    }

    @Test
    fun `validation messages are exact`() {
        assertEquals("interval must be an integer between 1 and 366", validateRepeatRule(RepeatRule(RepeatFreq.Daily, interval = 367)))
        assertEquals("byWeekday is not allowed with freq=daily; use freq=weekly", validateRepeatRule(RepeatRule(RepeatFreq.Daily, byWeekday = listOf(WeekdayPick("MO")))))
        assertEquals("byWeekday.ordinal must be 1..5 or -1 (last)", validateRepeatRule(RepeatRule(RepeatFreq.Monthly, byWeekday = listOf(WeekdayPick("MO", 6)))))
        assertEquals("byMonthDay is only allowed with freq=monthly or yearly", validateRepeatRule(RepeatRule(RepeatFreq.Weekly, byMonthDay = listOf(3))))
        assertEquals("byMonthDay entries must be 1..31 or -1 (last day)", validateRepeatRule(RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(0))))
        assertEquals("end.until must be a YYYY-MM-DD date", validateRepeatRule(RepeatRule(RepeatFreq.Daily, end = RepeatEnd.Until("2026-02-30"))))
        assertEquals("end.count must be an integer between 1 and 1000", validateRepeatRule(RepeatRule(RepeatFreq.Daily, end = RepeatEnd.Count(1001))))
        assertThrows(IllegalArgumentException::class.java) { Recurrence.expand(RepeatRule(RepeatFreq.Daily), anchor = "2026-13-01") }
    }

    @Test
    fun `returns exactly five days from today`() {
        assertEquals(
            listOf("2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04", "2026-01-05"),
            expand(RepeatRule(RepeatFreq.Daily), "2026-01-01"),
        )
    }

    @Test
    fun `shows the clamp, so the user sees the system did not break`() {
        assertEquals(
            listOf("2026-01-31", "2026-02-28", "2026-03-31"),
            expand(RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(31)), "2026-01-31", 3),
        )
    }

    @Test
    fun `day keys and month lengths`() {
        assertEquals("2026-02-05", RepeatDays.key(java.time.LocalDate.of(2026, 2, 5)))
        assertEquals(29, RepeatDays.lastDayOfMonth(2028, 2))
    }

    @Test
    fun `a quarterly rule round-trips as monthly every 3 months`() {
        val rule = RepeatRule(RepeatFreq.Monthly, interval = 3, byMonthDay = listOf(15), end = RepeatEnd.Count(8))
        assertEquals("""{"freq":"monthly","interval":3,"byMonthDay":[15],"end":{"type":"count","count":8}}""", rule.toJsonString())
        assertEquals(rule, roundTrip(rule))
    }

    @Test
    fun `the explicit quarterly form round-trips`() {
        val rule = RepeatRule(RepeatFreq.Monthly, byMonth = listOf(2, 5, 8, 11), byMonthDay = listOf(15))
        assertEquals(
            """{"freq":"monthly","interval":1,"byMonthDay":[15],"byMonth":[2,5,8,11],"end":{"type":"never"}}""",
            rule.toJsonString(),
        )
        assertEquals(rule, roundTrip(rule))
        assertNull(validateRepeatRule(rule))
    }

    @Test
    fun `byMonth stays refused on weekly rules and out-of-range months`() {
        assertNotNull(validateRepeatRule(RepeatRule(RepeatFreq.Weekly, byMonth = listOf(1))))
        assertNotNull(validateRepeatRule(RepeatRule(RepeatFreq.Monthly, byMonth = listOf(13))))
    }

    @Test
    fun `monthly + byMonth is a filter - only those months count`() {
        assertEquals(
            listOf("2026-02-15", "2026-05-15", "2026-08-15", "2026-11-15", "2027-02-15"),
            expand(RepeatRule(RepeatFreq.Monthly, byMonth = listOf(2, 5, 8, 11), byMonthDay = listOf(15)), "2026-01-10"),
        )
    }

    @Test
    fun `explicit quarterly on the 31st lands on each short month last day`() {
        val rule = RepeatRule(RepeatFreq.Monthly, byMonth = listOf(2, 5, 8, 11), byMonthDay = listOf(31))
        assertEquals(listOf("2026-02-28", "2026-05-31", "2026-08-31", "2026-11-30", "2027-02-28"), expand(rule, "2026-01-01"))
        assertEquals(listOf("2028-02-29"), expand(rule, "2028-01-01", 1))
    }

    @Test
    fun `explicit half-yearly with an end count`() {
        assertEquals(
            listOf("2026-10-31", "2027-04-30", "2027-10-31"),
            expand(RepeatRule(RepeatFreq.Monthly, byMonth = listOf(4, 10), byMonthDay = listOf(-1), end = RepeatEnd.Count(3)), "2026-05-01"),
        )
    }

    @Test
    fun `monthly + byMonth + an nth weekday`() {
        assertEquals(
            listOf("2026-03-02", "2026-09-07"),
            expand(RepeatRule(RepeatFreq.Monthly, byMonth = listOf(3, 9), byWeekday = listOf(WeekdayPick("MO", 1))), "2026-01-01", 2),
        )
    }

    @Test
    fun `a yearly month + day round-trips`() {
        val rule = RepeatRule(RepeatFreq.Yearly, byMonth = listOf(3), byMonthDay = listOf(15))
        assertEquals(rule, roundTrip(rule))
        assertNull(validateRepeatRule(rule))
    }

    @Test
    fun `quarterly counts from the anchor month (Feb, May, Aug, Nov)`() {
        assertEquals(
            listOf("2026-02-15", "2026-05-15", "2026-08-15", "2026-11-15", "2027-02-15"),
            expand(RepeatRule(RepeatFreq.Monthly, interval = 3, byMonthDay = listOf(15)), "2026-02-10"),
        )
    }

    @Test
    fun `quarterly - a day already past in the anchor month waits a quarter`() {
        assertEquals(
            listOf("2026-05-15", "2026-08-15", "2026-11-15"),
            expand(RepeatRule(RepeatFreq.Monthly, interval = 3, byMonthDay = listOf(15)), "2026-02-20", 3),
        )
    }

    @Test
    fun `quarterly on the 31st lands on each short month's last day`() {
        assertEquals(
            listOf("2026-11-30", "2027-02-28", "2027-05-31", "2027-08-31", "2027-11-30"),
            expand(RepeatRule(RepeatFreq.Monthly, interval = 3, byMonthDay = listOf(31)), "2026-11-30"),
        )
    }

    @Test
    fun `half-yearly on the last day`() {
        assertEquals(
            listOf("2026-03-31", "2026-09-30", "2027-03-31", "2027-09-30"),
            expand(RepeatRule(RepeatFreq.Monthly, interval = 6, byMonthDay = listOf(-1)), "2026-03-01", 4),
        )
    }

    @Test
    fun `half-yearly with an end count stops after N`() {
        assertEquals(
            listOf("2026-01-01", "2026-07-01"),
            expand(RepeatRule(RepeatFreq.Monthly, interval = 6, byMonthDay = listOf(1), end = RepeatEnd.Count(2)), "2026-01-01"),
        )
    }

    @Test
    fun `monthly on the 31st reads Feb 28, and Feb 29 in a leap year`() {
        val rule = RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(31))
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30"), expand(rule, "2026-01-31", 4))
        assertEquals(listOf("2028-01-31", "2028-02-29"), expand(rule, "2028-01-31", 2))
    }

    @Test
    fun `yearly on a picked month and day`() {
        assertEquals(
            listOf("2027-03-15", "2028-03-15"),
            expand(RepeatRule(RepeatFreq.Yearly, byMonth = listOf(3), byMonthDay = listOf(15)), "2026-11-05", 2),
        )
    }

    @Test
    fun `yearly on 29 February is the 28th in years without one`() {
        assertEquals(
            listOf("2026-02-28", "2027-02-28", "2028-02-29", "2029-02-28"),
            expand(RepeatRule(RepeatFreq.Yearly, byMonth = listOf(2), byMonthDay = listOf(29)), "2026-01-01", 4),
        )
    }

    @Test
    fun `yearly on 31 April is 30 April`() {
        assertEquals(
            listOf("2026-04-30"),
            expand(RepeatRule(RepeatFreq.Yearly, byMonth = listOf(4), byMonthDay = listOf(31)), "2026-01-01", 1),
        )
    }

    @Test
    fun `monthStep recognises both forms`() {
        val monthly = RepeatRule(RepeatFreq.Monthly)
        assertNull(Recurrence.monthStep(monthly))
        assertEquals(3, Recurrence.monthStep(monthly.copy(interval = 3)))
        assertEquals(6, Recurrence.monthStep(monthly.copy(interval = 6)))
        assertEquals(3, Recurrence.monthStep(monthly.copy(byMonth = listOf(12, 3, 6, 9))))
        assertEquals(6, Recurrence.monthStep(monthly.copy(byMonth = listOf(4, 10))))
        assertNull(Recurrence.monthStep(monthly.copy(byMonth = listOf(1, 4, 7))))
        assertNull(Recurrence.monthStep(monthly.copy(byMonth = listOf(1, 5))))
        assertNull(Recurrence.monthStep(monthly.copy(interval = 2, byMonth = listOf(4, 10))))
        assertNull(Recurrence.monthStep(RepeatRule(RepeatFreq.Yearly, byMonth = listOf(4, 10))))
        assertEquals(listOf(2, 5, 8, 11), Recurrence.evenMonths(11, 3))
        assertEquals(listOf(3, 9), Recurrence.evenMonths(9, 6))
    }

    @Test
    fun `the cycle months of a monthly rule`() {
        val quarterly = RepeatRule(RepeatFreq.Monthly, interval = 3)
        assertEquals(listOf(2, 5, 8, 11), Recurrence.cycleMonths(quarterly, 2))
        assertEquals(listOf(3, 6, 9, 12), Recurrence.cycleMonths(quarterly, 12))
        assertEquals(listOf(5, 11), Recurrence.cycleMonths(quarterly.copy(interval = 6), 11))
        assertNull(Recurrence.cycleMonths(quarterly.copy(interval = 1), 2))
        assertNull(Recurrence.cycleMonths(quarterly.copy(interval = 5), 2))
    }

    @Test
    fun `scenario C round-trips through its recogniser`() {
        val read = RepeatPatterns.afterDayOf(RepeatPatterns.afterDayRule(22, "MO"))
        assertEquals(AfterDay(22, "MO"), read)
        assertNull(RepeatPatterns.afterDayOf(RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(23, 25))))
    }

    @Test
    fun `after completion next instant keeps the time of day`() {
        val rule = RepeatRule(RepeatFreq.Monthly, basis = RepeatBasis.Completion)
        val completed = java.time.Instant.parse("2027-01-31T15:00:00Z")
        val timeFrom = java.time.Instant.parse("2027-01-20T03:30:00Z")
        assertEquals(
            java.time.Instant.parse("2027-02-28T03:30:00Z"),
            AfterCompletion.nextInstant(rule, completed, timeFrom, "Asia/Kolkata"),
        )
        assertNull(AfterCompletion.nextInstant(rule.copy(end = RepeatEnd.Count(1)), completed, timeFrom, "Asia/Kolkata"))
    }
}
