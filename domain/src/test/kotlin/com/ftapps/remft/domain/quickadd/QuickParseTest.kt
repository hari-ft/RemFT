package com.ftapps.remft.domain.quickadd

import com.ftapps.remft.domain.settings.DateFormatId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class QuickParseTest {
    private val friday = LocalDateTime.of(2026, 10, 16, 10, 0)
    private val wallFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    private fun parse(text: String, now: LocalDateTime = friday, ignored: Set<String> = emptySet()) =
        QuickAdd.parse(text, QuickParseOptions(now = now, ignored = ignored))

    private fun wall(value: LocalDateTime?) = value!!.format(wallFormat)
    private fun day(value: LocalDateTime?) = value!!.toLocalDate().toString()

    @Test
    fun offsetsCoverTheExactSourceSubstrings() {
        val text = "Tomorrow  9am rent #home p1 every month on the 31st"
        val p = parse(text)
        for (t in p.tokens) assertEquals(t.text, text.substring(t.start, t.end))
        assertEquals(listOf("Tomorrow", "9am", "p1", "every month on the 31st"), p.tokens.map { it.text })
        assertEquals(p.tokens.map { it.start until it.end }, p.highlightRanges)
    }

    @Test
    fun keysAreKindPlusTheFoldedSource() {
        val p = parse("Tomorrow 9 o’clock rent !Urgent")
        assertEquals(listOf("due:tomorrow", "time:9 o'clock", "priority:!urgent"), p.tokens.map { it.key })
    }

    @Test
    fun aDeadlineAndItsTimeAreOneChip() {
        val p = parse("Tomorrow 9am rent p1")
        assertEquals(listOf(QuickTokenKind.Due, QuickTokenKind.Priority), p.chips.map { it.kind })
        assertEquals(listOf("due:tomorrow", "time:9am"), p.chips.first().keys)
        assertEquals("due:tomorrow|time:9am", p.chips.first().id)
    }

    @Test
    fun forBeforeADateGoesWithTheDate() {
        val weekend = parse("Sample idea for weekend")
        assertEquals("Sample idea", weekend.title)
        assertEquals("2026-10-17", day(weekend.dueAt))
        assertEquals(listOf("for weekend"), weekend.tokens.map { it.text })
        val friday = parse("Gift for Friday")
        assertEquals("Gift", friday.title)
        assertEquals("2026-10-16", day(friday.dueAt))
        val tomorrow = parse("Slides for tomorrow 9am")
        assertEquals("Slides", tomorrow.title)
        assertEquals("2026-10-17T09:00", wall(tomorrow.dueAt))
    }

    @Test
    fun forWithoutADateStaysInTheTitle() {
        val p = parse("Look for keys")
        assertEquals("Look for keys", p.title)
        assertNull(p.dueAt)
    }

    @Test
    fun keepingForADateAsTextKeepsTheWholePhrase() {
        val first = parse("Sample idea for weekend")
        val kept = parse("Sample idea for weekend", ignored = first.tokens.map { it.key }.toSet())
        assertEquals("Sample idea for weekend", kept.title)
        assertNull(kept.dueAt)
    }

    @Test
    fun aTimeAloneIsTheDeadlineChip() {
        val p = parse("9am breakfast")
        assertEquals(QuickTokenKind.Due, p.chips.single().kind)
        assertEquals(listOf("time:9am"), p.chips.single().keys)
    }

    @Test
    fun aTimeWithARepeatRidesOnTheRepeatChip() {
        val p = parse("every monday 9am gym")
        assertEquals(QuickTokenKind.Repeat, p.chips.single().kind)
        assertEquals(listOf("repeat:every monday", "time:9am"), p.chips.single().keys)
    }

    @Test
    fun startingMondayIsADeadline() {
        val p = parse("report starting monday")
        assertEquals("report starting", p.title)
        assertEquals(LocalDateTime.of(2026, 10, 19, 11, 0), p.dueAt)
        assertEquals(listOf(QuickTokenKind.Due), p.tokens.map { it.kind })
    }

    @Test
    fun nonEnglishWordsArePlainTitleText() {
        for (text in listOf("mañana a las nueve renta", "cada lunes gimnasio", "!dringend x")) {
            val p = parse(text)
            assertTrue(text, p.tokens.isEmpty())
            assertEquals(text, p.title)
        }
    }

    @Test
    fun requiredNegativesStayTitleText() {
        for (text in listOf("Tomorrowland", "may I call", "sat on the chair", "3 apples")) {
            val p = parse(text)
            assertTrue(text, p.isEmpty)
            assertFalse(text, p.hasSchedule)
            assertEquals(text, p.title)
        }
    }

    @Test
    fun slashDatesAreDayFirstByDefault() {
        assertEquals("2026-11-05", day(parse("5/11 x").dueAt))
        val us = QuickAdd.parse("5/11 x", QuickParseOptions(now = friday, monthFirstSlash = true))
        assertEquals("2027-05-11", day(us.dueAt))
    }

    @Test
    fun dateFormatPreferenceDrivesSlashOrder() {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = friday.atZone(zone).toInstant()
        assertEquals("2026-11-05", day(QuickAdd.parse("5/11 x", now, zone, DateFormatId.System).dueAt))
        assertEquals("2026-11-05", day(QuickAdd.parse("5/11 x", now, zone, DateFormatId.DmySlash).dueAt))
        assertEquals("2027-05-11", day(QuickAdd.parse("5/11 x", now, zone, DateFormatId.Mdy12h).dueAt))
        assertEquals("2026-10-17T11:00", wall(QuickAdd.parse("tomorrow x", now, zone, DateFormatId.Iso).dueAt))
    }

    @Test
    fun dismissingTheDeadlineChipGivesEveryWordBack() {
        val options = QuickParseOptions(now = friday)
        val first = QuickAdd.parse("Tomorrow 9am rent", options)
        val again = QuickAdd.ignoring("Tomorrow 9am rent", options, first.chips.first())
        assertEquals("Tomorrow 9am rent", again.title)
        assertNull(again.dueAt)
        assertTrue(again.chips.isEmpty())
    }

    @Test
    fun aDismissedPhraseLetsALaterOneFillTheSlot() {
        val p = parse("tomorrow rent friday", ignored = setOf("due:tomorrow"))
        assertEquals("tomorrow rent", p.title)
        assertEquals("2026-10-16", day(p.dueAt))
    }

    @Test
    fun dismissingPriorityKeepsTheRest() {
        val options = QuickParseOptions(now = friday)
        val first = QuickAdd.parse("tomorrow pay rent p1", options)
        val chip = first.chips.single { it.kind == QuickTokenKind.Priority }
        val again = QuickAdd.ignoring("tomorrow pay rent p1", options, chip)
        assertNull(again.priority)
        assertEquals("pay rent p1", again.title)
        assertEquals("2026-10-17", day(again.dueAt))
    }

    @Test
    fun hashAndPlusWordsStayInTheTitle() {
        val p = parse("#home +work x")
        assertTrue(p.tokens.isEmpty())
        assertEquals("#home +work x", p.title)
    }

    @Test
    fun nothingLeftKeepsTheWholeTextAsTitle() {
        val p = parse("tomorrow 9am")
        assertEquals("tomorrow 9am", p.title)
        assertEquals("2026-10-17T09:00", wall(p.dueAt))
    }

    @Test
    fun titleKeepsCasingAndDropsDanglingCommas() {
        assertEquals("Pay Rent", parse("Pay Rent, tomorrow").title)
    }

    @Test
    fun emptyTextParsesToNothing() {
        val p = parse("   ")
        assertTrue(p.tokens.isEmpty())
        assertFalse(p.hasSchedule)
    }

    @Test
    fun aWeekdayIncludesTodayUntilItsTimeHasPassed() {
        assertEquals("2026-10-16T11:00", wall(parse("friday 11am x").dueAt))
        assertEquals("2026-10-23T09:00", wall(parse("friday 9am x").dueAt))
        assertEquals("2026-10-16T11:00", wall(parse("friday x").dueAt))
    }

    @Test
    fun aDayWithNoTimeRoundsAnHourAheadUpToTheMinute() {
        val now = LocalDateTime.of(2026, 10, 16, 10, 0, 20)
        assertEquals("2026-10-17T11:01", wall(parse("tomorrow x", now = now).dueAt))
        assertEquals("2026-10-16T11:01", wall(parse("today x", now = now).dueAt))
    }

    @Test
    fun lateAtNightTodayClampsAndOtherDaysTakeTheMorning() {
        val late = LocalDateTime.of(2026, 10, 16, 23, 30)
        assertEquals("2026-10-16T23:59", wall(parse("today x", now = late).dueAt))
        assertEquals("2026-10-17T09:00", wall(parse("tomorrow x", now = late).dueAt))
        assertEquals("2026-10-17T09:00", wall(parse("every day x", now = late).dueAt))
        val lastMinute = LocalDateTime.of(2026, 10, 16, 23, 59, 30)
        assertEquals(LocalDateTime.of(2026, 10, 16, 23, 59, 59, 999_000_000), parse("today x", now = lastMinute).dueAt)
    }

    @Test
    fun aPassedDateWithoutAYearRollsToNextYear() {
        assertEquals("2027-10-10", day(parse("10 oct x").dueAt))
        assertEquals("2027-01-15", day(parse("15 jan x", now = LocalDateTime.of(2026, 12, 20, 0, 0)).dueAt))
    }

    @Test
    fun invalidDaysAreNotRecognized() {
        assertNull(parse("31.02 x").dueAt)
        assertNull(parse("29 february x").dueAt)
        assertEquals("2028-02-29", day(parse("29.02.2028 x").dueAt))
    }

    @Test
    fun monthArithmeticClampsToTheMonthEnd() {
        assertEquals("2026-02-28", day(parse("in 1 month x", now = LocalDateTime.of(2026, 1, 31, 9, 0)).dueAt))
    }

    @Test
    fun aRepeatIsNeverBornOverdue() {
        assertEquals("2026-10-26T09:00", wall(parse("every monday 9am", now = LocalDateTime.of(2026, 10, 19, 10, 0)).dueAt))
    }

    @Test
    fun weekStartMovesNextWeek() {
        val sunday = QuickAdd.parse("next week x", QuickParseOptions(now = friday, weekStart = DayOfWeek.SUNDAY))
        assertEquals("2026-10-18", day(sunday.dueAt))
        assertEquals("2026-10-19", day(parse("next week x").dueAt))
    }

    @Test
    fun repeatRulesSerialiseInTheStoredRuleShape() {
        val p = parse("every mon, thu and fri gym")
        assertEquals(
            "{\"freq\":\"weekly\",\"interval\":1,\"byWeekday\":[{\"day\":\"MO\",\"ordinal\":null},{\"day\":\"TH\",\"ordinal\":null},{\"day\":\"FR\",\"ordinal\":null}],\"end\":{\"type\":\"never\"}}",
            p.repeat!!.toJson().toString(),
        )
        assertEquals("gym", p.title)
        val quarterly = parse("quarterly review")
        assertEquals(QuickRepeatRule(QuickRepeatFreq.Monthly, interval = 3), quarterly.repeat)
        val last = parse("every last day of the month rent")
        assertEquals(listOf(-1), last.repeat!!.byMonthDay)
    }

    @Test
    fun anOutOfRangeIntervalIsNotARepeat() {
        val p = parse("every 400 days x")
        assertNull(p.repeat)
    }

    @Test
    fun repeatDaysExpandFromTheAnchor() {
        val rule = QuickRepeatRule(QuickRepeatFreq.Monthly, byMonthDay = listOf(31))
        assertEquals(
            listOf(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31)),
            QuickRepeatDays.firstDays(rule, LocalDate.of(2026, 10, 16), 3),
        )
    }

    @Test
    fun aCaptureKeepsDateWordsAsTextAndOnlyReadsThePriority() {
        val p = QuickAdd.parse("Sample idea for weekend every week 9am p1", QuickParseOptions(now = friday, kinds = QuickParseOptions.CaptureKinds))
        assertEquals("Sample idea for weekend every week 9am", p.title)
        assertNull(p.dueAt)
        assertNull(p.repeat)
        assertFalse(p.hasSchedule)
        assertEquals(listOf(QuickTokenKind.Priority), p.tokens.map { it.kind })
    }
}
