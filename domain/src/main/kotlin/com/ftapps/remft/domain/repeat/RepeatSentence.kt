package com.ftapps.remft.domain.repeat

import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

data class AfterDay(val day: Int, val weekday: String)

object RepeatPatterns {
    fun afterDayOf(rule: RepeatRule): AfterDay? {
        if (rule.byWeekday.size != 1) return null
        if (rule.byWeekday.single().ordinal != null) return null
        if (rule.byMonthDay.size != 7) return null
        val days = rule.byMonthDay.sorted()
        if (days.first() < 2) return null
        for (i in 1 until days.size) {
            if (days[i] != days[i - 1] + 1) return null
        }
        return AfterDay(days.first() - 1, rule.byWeekday.single().day)
    }

    fun afterDayRule(day: Int, weekday: String, end: RepeatEnd = RepeatEnd.Never, interval: Int = 1): RepeatRule =
        RepeatRule(
            freq = RepeatFreq.Monthly,
            interval = interval,
            byWeekday = listOf(WeekdayPick(weekday)),
            byMonthDay = (1..7).map { day + it },
            end = end,
        )
}

class RepeatSentence(private val strings: RepeatStrings) {
    private val locale = Locale.ENGLISH

    fun sentence(rule: RepeatRule, dateFormat: DateFormatId, anchor: String? = null): String {
        val head = if (rule.isAfterCompletion) {
            afterCompletion(rule)
        } else {
            calendar(rule, anchor?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
        }
        val end = end(rule, dateFormat)
        return if (end.isEmpty()) head else "$head · $end"
    }

    fun ordinalDay(day: Int): String {
        val key = when (day % 100) {
            11, 12, 13 -> RepeatString.DayOrdinalTh
            else -> when (day % 10) {
                1 -> RepeatString.DayOrdinalSt
                2 -> RepeatString.DayOrdinalNd
                3 -> RepeatString.DayOrdinalRd
                else -> RepeatString.DayOrdinalTh
            }
        }
        return t(key, "n" to "$day")
    }

    fun frequency(rule: RepeatRule): String {
        val n = rule.interval
        val many = n > 1
        val arg = "n" to "$n"
        return when (rule.freq) {
            RepeatFreq.Daily -> if (many) t(RepeatString.EveryDailyN, arg) else t(RepeatString.EveryDaily)
            RepeatFreq.Weekly -> if (many) t(RepeatString.EveryWeeklyN, arg) else t(RepeatString.EveryWeekly)
            RepeatFreq.Monthly -> when (Recurrence.monthStep(rule)) {
                3 -> t(RepeatString.EveryQuarterly)
                6 -> t(RepeatString.EveryHalfYearly)
                else -> if (many) t(RepeatString.EveryMonthlyN, arg) else t(RepeatString.EveryMonthly)
            }
            RepeatFreq.Yearly -> if (many) t(RepeatString.EveryYearlyN, arg) else t(RepeatString.EveryYearly)
        }
    }

    fun weekdayName(code: String): String = t(RepeatString.weekday(code))

    private fun t(string: RepeatString, vararg args: Pair<String, String>): String =
        strings.template(string).fill(*args)

    private fun on(rule: String, what: String): String =
        if (what.isEmpty()) rule else t(RepeatString.PatternOn, "rule" to rule, "what" to what)

    private fun inMonths(rule: String, what: String): String =
        if (what.isEmpty()) rule else t(RepeatString.PatternIn, "rule" to rule, "what" to what)

    private fun monthName(month: Int): String = Month.of(month).getDisplayName(TextStyle.FULL, locale)

    private fun monthShort(month: Int): String = Month.of(month).getDisplayName(TextStyle.SHORT, locale)

    private fun calendar(rule: RepeatRule, anchor: LocalDate?): String {
        val rhythm = frequency(rule)
        return when (rule.freq) {
            RepeatFreq.Daily -> rhythm
            RepeatFreq.Weekly -> {
                val picks = rule.byWeekday.ifEmpty {
                    listOfNotNull(anchor?.let { WeekdayPick(RepeatDays.weekdayCode(it)) })
                }
                on(rhythm, if (picks.isEmpty()) "" else weekdayList(picks))
            }
            RepeatFreq.Monthly -> {
                val sentence = on(rhythm, monthSelector(rule, anchor))
                val cycle = Recurrence.cycleMonths(rule, anchor?.monthValue) ?: return sentence
                t(RepeatString.PatternCycle, "rule" to sentence, "months" to cycle.joinToString(", ") { monthShort(it) })
            }
            RepeatFreq.Yearly -> yearly(rule, rhythm, anchor)
        }
    }

    private fun yearly(rule: RepeatRule, rhythm: String, anchor: LocalDate?): String {
        val months = if (rule.byMonth.isNotEmpty()) {
            rule.byMonth.toSortedSet().toList()
        } else {
            listOfNotNull(anchor?.monthValue)
        }
        val selector = monthSelector(rule, anchor)
        if (months.size == 1) {
            val month = monthName(months.single())
            if (rule.byWeekday.isEmpty() && rule.byMonthDay.size <= 1) {
                val day = if (rule.byMonthDay.isEmpty()) anchor?.dayOfMonth else rule.byMonthDay.single()
                if (day == -1) return on(rhythm, t(RepeatString.OnLastDayOf, "month" to month))
                if (day != null) return on(rhythm, t(RepeatString.OnDayMonth, "day" to "$day", "month" to month))
            }
            if (selector.isNotEmpty()) return on(rhythm, t(RepeatString.OnOfMonth, "what" to selector, "month" to month))
            return inMonths(rhythm, month)
        }
        val named = inMonths(rhythm, months.joinToString(", ") { monthName(it) })
        return on(named, selector)
    }

    private fun afterCompletion(rule: RepeatRule): String {
        val n = rule.interval
        val many = n > 1
        val arg = "n" to "$n"
        return when (rule.freq) {
            RepeatFreq.Daily -> if (many) t(RepeatString.AfterDailyN, arg) else t(RepeatString.AfterDaily)
            RepeatFreq.Weekly -> if (many) t(RepeatString.AfterWeeklyN, arg) else t(RepeatString.AfterWeekly)
            RepeatFreq.Monthly, RepeatFreq.Yearly ->
                if (many) t(RepeatString.AfterMonthlyN, arg) else t(RepeatString.AfterMonthly)
        }
    }

    private fun monthSelector(rule: RepeatRule, anchor: LocalDate?): String {
        if (rule.byMonthDay.isEmpty() && rule.byWeekday.isEmpty()) {
            return if (anchor == null) "" else t(RepeatString.OnMonthDay, "day" to ordinalDay(anchor.dayOfMonth))
        }
        RepeatPatterns.afterDayOf(rule)?.let {
            return t(RepeatString.OnAfterDay, "day" to "${it.day}", "weekday" to weekdayName(it.weekday))
        }
        val phrases = mutableListOf<String>()
        for (day in rule.byMonthDay) {
            phrases.add(
                if (day == -1) t(RepeatString.OnLastDay) else t(RepeatString.OnMonthDay, "day" to ordinalDay(day)),
            )
        }
        for (pick in rule.byWeekday) {
            val name = weekdayName(pick.day)
            phrases.add(
                when (val ordinal = pick.ordinal) {
                    null -> name
                    -1 -> t(RepeatString.OnLastWeekday, "weekday" to name)
                    else -> t(
                        RepeatString.OnNthWeekday,
                        "ordinal" to (RepeatString.ordinal(ordinal)?.let { t(it) } ?: "$ordinal"),
                        "weekday" to name,
                    )
                },
            )
        }
        return phrases.joinToString(", ")
    }

    private fun weekdayList(picks: List<WeekdayPick>): String {
        val days = picks.map { it.day }.toSet()
        if (days.size == 5 && days.containsAll(listOf("MO", "TU", "WE", "TH", "FR"))) return t(RepeatString.OnWeekdays)
        return RepeatWeekdays.filter { it in days }.joinToString(", ") { weekdayName(it) }
    }

    private fun end(rule: RepeatRule, dateFormat: DateFormatId): String = when (val end = rule.end) {
        is RepeatEnd.Until -> {
            val parsed = runCatching { LocalDate.parse(end.until) }.getOrNull()
            if (parsed == null) "" else t(RepeatString.EndUntilSentence, "date" to DateFormats.date(parsed.atStartOfDay(), dateFormat))
        }
        is RepeatEnd.Count -> t(RepeatString.EndCountSentence, "count" to "${end.count}")
        RepeatEnd.Never -> t(RepeatString.EndNeverSentence)
    }
}
