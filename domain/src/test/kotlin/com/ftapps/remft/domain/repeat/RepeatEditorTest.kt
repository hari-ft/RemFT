package com.ftapps.remft.domain.repeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class RepeatEditorTest {
    private val sentence = RepeatSentence(EnglishRepeatStrings)

    private fun say(editor: RepeatEditor) = sentence.sentence(
        editor.rule,
        com.ftapps.remft.domain.settings.DateFormatId.DmyDot,
        if (editor.isAfter) null else RepeatDays.key(editor.anchor),
    )

    private val janJul = RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(1), byMonth = listOf(1, 7))
    private val made = LocalDateTime.of(2026, 11, 6, 10, 30)

    @Test
    fun `first due - a day the rule skips moves to its first planned day`() {
        assertEquals(LocalDateTime.of(2027, 1, 1, 10, 30), RepeatEditor.firstRepeatDue(janJul, made))
    }

    @Test
    fun `first due - every kind of repeat whose first day is not today`() {
        val cases = listOf(
            RepeatRule(RepeatFreq.Yearly, byMonthDay = listOf(15), byMonth = listOf(3)) to LocalDateTime.of(2027, 3, 15, 10, 30),
            RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(1), byMonth = listOf(1, 4, 7, 10)) to LocalDateTime.of(2027, 1, 1, 10, 30),
            RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(20), byMonth = listOf(2, 5, 8, 11)) to LocalDateTime.of(2026, 11, 20, 10, 30),
            RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(-1), byMonth = listOf(6, 12)) to LocalDateTime.of(2026, 12, 31, 10, 30),
            RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(3)) to LocalDateTime.of(2026, 12, 3, 10, 30),
            RepeatRule(RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("MO"))) to LocalDateTime.of(2026, 11, 9, 10, 30),
            RepeatRule(RepeatFreq.Yearly, byWeekday = listOf(WeekdayPick("MO", 1)), byMonth = listOf(2)) to LocalDateTime.of(2027, 2, 1, 10, 30),
        )
        for ((rule, expected) in cases) assertEquals(rule.toString(), expected, RepeatEditor.firstRepeatDue(rule, made))
    }

    @Test
    fun `first due - a planned day stays`() {
        assertEquals(LocalDateTime.of(2027, 7, 1, 9, 0), RepeatEditor.firstRepeatDue(janJul, LocalDateTime.of(2027, 7, 1, 9, 0)))
    }

    @Test
    fun `first due - an after-completion rule starts where it is`() {
        val after = RepeatRule(RepeatFreq.Daily, interval = 3, basis = RepeatBasis.Completion)
        assertEquals(made, RepeatEditor.firstRepeatDue(after, made))
    }

    @Test
    fun `opens on presets and previews the next five days`() {
        val editor = RepeatEditor.open(LocalDate.of(2026, 1, 5)).applyPreset(RepeatPreset.Daily)
        assertEquals(RepeatPreset.Daily, editor.activePreset)
        assertEquals(listOf("2026-01-05", "2026-01-06", "2026-01-07", "2026-01-08", "2026-01-09"), editor.preview())
    }

    @Test
    fun `the default is every month on the anchor day`() {
        val editor = RepeatEditor.open(LocalDate.of(2026, 1, 5))
        assertEquals(RepeatPreset.Monthly, editor.activePreset)
        assertEquals(RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(5)), editor.rule)
        assertFalse(editor.advanced)
    }

    @Test
    fun `the preview shows the clamp - February reads 28`() {
        val editor = RepeatEditor.open(LocalDate.of(2026, 1, 31)).applyPreset(RepeatPreset.Monthly)
        assertEquals("2026-02-28", editor.preview()[1])
    }

    @Test
    fun `the weekly and weekdays presets`() {
        val anchor = LocalDate.of(2026, 10, 16)
        assertEquals(
            RepeatRule(RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("FR"))),
            RepeatEditor.open(anchor).applyPreset(RepeatPreset.Weekly).rule,
        )
        val weekdays = RepeatEditor.open(anchor).applyPreset(RepeatPreset.Weekdays)
        assertEquals(RepeatPreset.Weekdays, weekdays.activePreset)
        assertEquals(listOf("MO", "TU", "WE", "TH", "FR"), weekdays.rule.byWeekday.map { it.day })
        assertEquals(RepeatRule(RepeatFreq.Yearly, byMonthDay = listOf(16), byMonth = listOf(10)), RepeatEditor.open(anchor).applyPreset(RepeatPreset.Yearly).rule)
    }

    @Test
    fun `Quarterly + day 15 saves the 15th of four named months`() {
        val editor = RepeatEditor.open(LocalDate.of(2026, 2, 10)).applyPreset(RepeatPreset.Quarterly).withMonthDayChoice(15)
        assertEquals(RepeatPreset.Quarterly, editor.activePreset)
        assertTrue(editor.preview().containsAll(listOf("2026-02-15", "2026-05-15", "2026-08-15")))
        assertEquals("Quarterly on the 15th (Feb, May, Aug, Nov) · no end", say(editor))
        assertEquals(RepeatRule(RepeatFreq.Monthly, byMonth = listOf(2, 5, 8, 11), byMonthDay = listOf(15)), editor.rule)
    }

    @Test
    fun `Quarterly lets the user pick the months - the 31st clamps`() {
        var editor = RepeatEditor.open(LocalDate.of(2026, 2, 10)).applyPreset(RepeatPreset.Quarterly)
        assertEquals(listOf(listOf(1, 4, 7, 10), listOf(2, 5, 8, 11), listOf(3, 6, 9, 12)), editor.cycleStartChoices)
        assertEquals(2, editor.cycleStartChoice)
        editor = editor.copy(month = 1).withMonthDayChoice(31)
        assertEquals(listOf("2026-04-30", "2026-07-31", "2026-10-31", "2027-01-31", "2027-04-30"), editor.preview())
        assertEquals(RepeatRule(RepeatFreq.Monthly, byMonth = listOf(1, 4, 7, 10), byMonthDay = listOf(31)), editor.rule)
        assertEquals(ClampHint.ShortMonths, editor.clampHint)
    }

    @Test
    fun `Half-yearly offers six month pairs`() {
        var editor = RepeatEditor.open(LocalDate.of(2026, 2, 10)).applyPreset(RepeatPreset.HalfYearly)
        assertEquals(6, editor.cycleStartChoices.size)
        assertEquals(listOf(2, 8), editor.cycleStartChoices[editor.cycleStartChoice - 1])
        editor = editor.copy(month = 6)
        assertEquals(listOf(6, 12), editor.rule.byMonth)
        assertEquals(1, editor.rule.interval)
        assertEquals(listOf(10), editor.rule.byMonthDay)
    }

    @Test
    fun `the day survives switching Monthly to Half-yearly`() {
        val editor = RepeatEditor.open(LocalDate.of(2026, 3, 4))
            .applyPreset(RepeatPreset.Monthly)
            .withMonthDayChoice(-1)
            .applyPreset(RepeatPreset.HalfYearly)
        assertTrue("2026-09-30" in editor.preview())
        assertEquals(RepeatRule(RepeatFreq.Monthly, byMonth = listOf(3, 9), byMonthDay = listOf(-1)), editor.rule)
        assertNull(editor.clampHint)
    }

    @Test
    fun `monthly on the 31st previews February on the 28th`() {
        val editor = RepeatEditor.open(LocalDate.of(2026, 1, 5)).applyPreset(RepeatPreset.Monthly).withMonthDayChoice(31)
        assertTrue("2026-02-28" in editor.preview())
        assertEquals(ClampHint.ShortMonths, editor.clampHint)
    }

    @Test
    fun `Yearly saves month + day`() {
        val editor = RepeatEditor.open(LocalDate.of(2026, 11, 5)).applyPreset(RepeatPreset.Quarterly)
            .applyPreset(RepeatPreset.Yearly).copy(month = 3).withMonthDayChoice(15)
        assertEquals("Every year on 15 March · no end", say(editor))
        assertTrue("2027-03-15" in editor.preview())
        assertEquals(RepeatRule(RepeatFreq.Yearly, byMonth = listOf(3), byMonthDay = listOf(15)), editor.rule)
    }

    @Test
    fun `Yearly on 29 February says what happens in other years`() {
        val editor = RepeatEditor.open(LocalDate.of(2026, 1, 1)).applyPreset(RepeatPreset.Yearly).copy(month = 2).withMonthDayChoice(29)
        assertEquals(ClampHint.LeapDay, editor.clampHint)
        assertTrue(editor.preview().containsAll(listOf("2026-02-28", "2028-02-29")))
        assertEquals(ClampHint.ShortMonthYearly, editor.copy(month = 4).withMonthDayChoice(31).clampHint)
    }

    @Test
    fun `a quarterly rule reopens as Quarterly, unchanged`() {
        val initial = RepeatRule(RepeatFreq.Monthly, byMonth = listOf(3, 6, 9, 12), byMonthDay = listOf(15), end = RepeatEnd.Count(6))
        val editor = RepeatEditor.open(LocalDate.of(2026, 2, 10), initial)
        assertEquals(RepeatPreset.Quarterly, editor.activePreset)
        assertFalse(editor.advanced)
        assertEquals(initial, editor.rule)
    }

    @Test
    fun `an interval-form quarterly rule reopens as Quarterly and saves the explicit form`() {
        val editor = RepeatEditor.open(LocalDate.of(2026, 2, 10), RepeatRule(RepeatFreq.Monthly, interval = 3, byMonthDay = listOf(15)))
        assertEquals(RepeatPreset.Quarterly, editor.activePreset)
        assertEquals(RepeatRule(RepeatFreq.Monthly, byMonth = listOf(2, 5, 8, 11), byMonthDay = listOf(15)), editor.rule)
    }

    @Test
    fun `half-yearly and yearly rules reopen on their months`() {
        val half = RepeatEditor.open(LocalDate.of(2026, 2, 10), RepeatRule(RepeatFreq.Monthly, interval = 6, byMonthDay = listOf(1)))
        assertEquals(RepeatPreset.HalfYearly, half.activePreset)
        assertEquals(listOf(2, 8), half.rule.byMonth)
        val explicit = RepeatRule(RepeatFreq.Monthly, byMonth = listOf(4, 10), byMonthDay = listOf(-1))
        assertEquals(explicit, RepeatEditor.open(LocalDate.of(2026, 2, 10), explicit).rule)
        val yearly = RepeatRule(RepeatFreq.Yearly, byMonth = listOf(3), byMonthDay = listOf(15))
        val reopened = RepeatEditor.open(LocalDate.of(2026, 2, 10), yearly)
        assertEquals(RepeatPreset.Yearly, reopened.activePreset)
        assertEquals(yearly, reopened.rule)
    }

    @Test
    fun `an existing advanced rule comes back unchanged with Advanced open`() {
        val afterDay = RepeatPatterns.afterDayRule(22, "MO", RepeatEnd.Until("2027-06-30"))
        val editor = RepeatEditor.open(LocalDate.of(2026, 1, 10), afterDay)
        assertEquals(MonthMode.AfterDay, editor.monthMode)
        assertTrue(editor.advanced)
        assertEquals(afterDay, editor.rule)
        val nth = RepeatRule(RepeatFreq.Monthly, interval = 2, byWeekday = listOf(WeekdayPick("TU", 2)), end = RepeatEnd.Count(10))
        val reopened = RepeatEditor.open(LocalDate.of(2026, 1, 10), nth)
        assertEquals(MonthMode.NthWeekday, reopened.monthMode)
        assertEquals(nth, reopened.rule)
        val weekly = RepeatRule(RepeatFreq.Weekly, interval = 2, byWeekday = listOf(WeekdayPick("MO"), WeekdayPick("WE")))
        assertEquals(weekly, RepeatEditor.open(LocalDate.of(2026, 1, 10), weekly).rule)
    }

    @Test
    fun `editing a series previews from this occurrence on`() {
        val editor = RepeatEditor.open(LocalDate.of(2026, 1, 15), RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(15)))
        assertEquals(listOf("2026-04-15", "2026-05-15"), editor.preview(previewFrom = LocalDate.of(2026, 4, 1), max = 2))
        assertEquals(listOf("2026-01-15"), editor.preview(previewFrom = LocalDate.of(2025, 4, 1), max = 1))
    }

    @Test
    fun `after completion - interval and unit drive the one date it previews and saves`() {
        var editor = RepeatEditor.open(LocalDate.of(2026, 10, 17)).copy(basis = RepeatBasis.Completion)
        val today = LocalDate.of(2026, 10, 17)
        assertEquals("2026-10-18", editor.afterPreview(today))
        editor = editor.withAfterInterval(2).copy(afterFreq = RepeatFreq.Weekly)
        assertEquals("2026-10-31", editor.afterPreview(today))
        assertEquals(RepeatRule(RepeatFreq.Weekly, interval = 2, basis = RepeatBasis.Completion), editor.rule)
        assertEquals("2 weeks after completion · no end", say(editor))
        assertTrue(editor.isValid())
        assertEquals("2027-02-28", editor.copy(afterFreq = RepeatFreq.Monthly, afterInterval = 1).afterPreview(LocalDate.of(2027, 1, 31)))
    }

    @Test
    fun `an after-completion rule reopens in its own mode, unchanged`() {
        val rule = RepeatRule(RepeatFreq.Monthly, interval = 3, basis = RepeatBasis.Completion, end = RepeatEnd.Count(4))
        val editor = RepeatEditor.open(LocalDate.of(2026, 10, 17), rule)
        assertTrue(editor.isAfter)
        assertTrue(editor.advanced)
        assertEquals(rule, editor.rule)
        assertEquals(RepeatRule(RepeatFreq.Monthly, byMonthDay = listOf(17), end = RepeatEnd.Count(4)), editor.copy(basis = RepeatBasis.Calendar).rule)
    }

    @Test
    fun `out of range intervals are ignored and an empty preview is invalid`() {
        val editor = RepeatEditor.open(LocalDate.of(2026, 1, 5))
        assertEquals(1, editor.withInterval(0).interval)
        assertEquals(1, editor.withInterval(367).interval)
        val ended = editor.copy(endType = EndType.Until, until = LocalDate.of(2026, 1, 4))
        assertTrue(ended.preview().isEmpty())
        assertFalse(ended.isValid())
    }

    @Test
    fun `the scope question preselects this for dates and future otherwise`() {
        assertEquals(EditScope.This, EditScope.preselected(isDateEdit = true))
        assertEquals(EditScope.Future, EditScope.preselected(isDateEdit = false))
        assertEquals(EditScope.All, EditScope.parse("all"))
    }
}
