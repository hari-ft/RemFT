package com.ftapps.remft.domain.repeat

import com.ftapps.remft.domain.settings.DateFormatId
import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatSentenceTest {
    private val sentence = RepeatSentence(EnglishRepeatStrings)

    private fun say(rule: RepeatRule, anchor: String? = null, format: DateFormatId = DateFormatId.DmyDot) =
        sentence.sentence(rule, format, anchor)

    @Test
    fun `the last day of the month`() {
        assertEquals("Every month on the last day · no end", say(RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(-1))))
    }

    @Test
    fun `every month on the 15th`() {
        assertEquals("Every month on the 15th · no end", say(RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(15))))
    }

    @Test
    fun `scenario C reads as itself, not as a seven-day window`() {
        assertEquals(
            "Every month on the first Monday after day 22 · no end",
            say(RepeatPatterns.afterDayRule(22, "MO")),
        )
    }

    @Test
    fun `the nth weekday and the interval get their own forms`() {
        assertEquals(
            "Every 2 months on the second Tuesday · 10 times",
            say(RepeatRule(RepeatFreq.Monthly, interval = 2, byWeekday = listOf(WeekdayPick("TU", 2)), end = RepeatEnd.Count(10))),
        )
    }

    @Test
    fun `five weekdays say weekdays instead of listing themselves`() {
        assertEquals(
            "Every week on weekdays · no end",
            say(RepeatRule(RepeatFreq.Weekly, byWeekday = listOf("MO", "TU", "WE", "TH", "FR").map { WeekdayPick(it) })),
        )
        assertEquals(
            "Every 2 weeks on Monday, Wednesday · no end",
            say(RepeatRule(RepeatFreq.Weekly, interval = 2, byWeekday = listOf(WeekdayPick("WE"), WeekdayPick("MO")))),
        )
    }

    @Test
    fun `monthly every 3 months reads Quarterly, with its months`() {
        val rule = RepeatRule(RepeatFreq.Monthly, interval = 3, byMonthDay = listOf(15))
        assertEquals("Quarterly on the 15th (Feb, May, Aug, Nov) · no end", say(rule, "2026-02-10"))
        assertEquals("Quarterly on the 15th · no end", say(rule))
    }

    @Test
    fun `the explicit form reads the same, with its own months`() {
        val rule = RepeatRule(RepeatFreq.Monthly, byMonth = listOf(2, 5, 8, 11), byMonthDay = listOf(15))
        assertEquals("Quarterly on the 15th (Feb, May, Aug, Nov) · no end", say(rule, "2026-01-10"))
        assertEquals("Quarterly on the 15th (Feb, May, Aug, Nov) · no end", say(rule))
        assertEquals(
            "Half-yearly on the 1st (Mar, Sep) · no end",
            say(RepeatRule(RepeatFreq.Monthly, byMonth = listOf(3, 9), byMonthDay = listOf(1))),
        )
        assertEquals(
            "Every month on the 1st (Jan, Mar) · no end",
            say(RepeatRule(RepeatFreq.Monthly, byMonth = listOf(1, 3), byMonthDay = listOf(1))),
        )
    }

    @Test
    fun `monthly every 6 months reads Half-yearly`() {
        assertEquals(
            "Half-yearly on the last day (Mar, Sep) · 4 times",
            say(RepeatRule(RepeatFreq.Monthly, interval = 6, byMonthDay = listOf(-1), end = RepeatEnd.Count(4)), "2026-09-01"),
        )
    }

    @Test
    fun `other intervals keep their count form`() {
        val rule = RepeatRule(RepeatFreq.Monthly, interval = 4, byMonthDay = listOf(1))
        assertEquals("Every 4 months on the 1st (Jan, May, Sep) · no end", say(rule, "2026-01-01"))
        assertEquals("Every 5 months on the 1st · no end", say(rule.copy(interval = 5), "2026-01-01"))
    }

    @Test
    fun `yearly names its day and month`() {
        assertEquals("Every year on 15 March · no end", say(RepeatRule(RepeatFreq.Yearly, byMonth = listOf(3), byMonthDay = listOf(15))))
        assertEquals(
            "Every year on the last day of February · no end",
            say(RepeatRule(RepeatFreq.Yearly, byMonth = listOf(2), byMonthDay = listOf(-1))),
        )
        assertEquals(
            "Every year on the second Tuesday of March · no end",
            say(RepeatRule(RepeatFreq.Yearly, byMonth = listOf(3), byWeekday = listOf(WeekdayPick("TU", 2)))),
        )
        assertEquals("Every year on 4 July · no end", say(RepeatRule(RepeatFreq.Yearly), "2026-07-04"))
        assertEquals(
            "Every 2 years in March, September on the 1st · no end",
            say(RepeatRule(RepeatFreq.Yearly, interval = 2, byMonth = listOf(9, 3), byMonthDay = listOf(1))),
        )
    }

    @Test
    fun `a rule with no day picked says the anchor day, or nothing`() {
        val monthly = RepeatRule(RepeatFreq.Monthly)
        assertEquals("Every month on the 16th · no end", say(monthly, "2026-10-16"))
        assertEquals("Every month · no end", say(monthly))
        assertEquals("Every week on Friday · no end", say(RepeatRule(RepeatFreq.Weekly), "2026-10-16"))
        assertEquals("Quarterly · no end", say(RepeatRule(RepeatFreq.Monthly, interval = 3)))
        assertEquals("Every 3 days · no end", say(RepeatRule(RepeatFreq.Daily, interval = 3)))
    }

    @Test
    fun `English ordinals`() {
        assertEquals(
            listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31).map(sentence::ordinalDay),
        )
    }

    @Test
    fun `every unit and the N forms after completion`() {
        fun after(freq: RepeatFreq, n: Int) = say(RepeatRule(freq, interval = n, basis = RepeatBasis.Completion))
        assertEquals("1 day after completion · no end", after(RepeatFreq.Daily, 1))
        assertEquals("90 days after completion · no end", after(RepeatFreq.Daily, 90))
        assertEquals("3 days after completion · no end", after(RepeatFreq.Daily, 3))
        assertEquals("1 week after completion · no end", after(RepeatFreq.Weekly, 1))
        assertEquals("2 weeks after completion · no end", after(RepeatFreq.Weekly, 2))
        assertEquals("1 month after completion · no end", after(RepeatFreq.Monthly, 1))
        assertEquals("6 months after completion · no end", after(RepeatFreq.Monthly, 6))
        assertEquals(
            "1 week after completion · 4 times",
            say(RepeatRule(RepeatFreq.Weekly, basis = RepeatBasis.Completion, end = RepeatEnd.Count(4))),
        )
    }

    @Test
    fun `an end date follows the chosen date format`() {
        val rule = RepeatRule(RepeatFreq.Daily, end = RepeatEnd.Until("2027-03-05"))
        assertEquals("Every day · until 05.03.2027", say(rule))
        assertEquals("Every day · until 2027-03-05", say(rule, format = DateFormatId.Iso))
        assertEquals("Every day · until 5 March 2027", say(rule, format = DateFormatId.DmyLong))
    }
}
