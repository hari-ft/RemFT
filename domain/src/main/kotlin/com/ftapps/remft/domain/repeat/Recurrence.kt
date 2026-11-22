package com.ftapps.remft.domain.repeat

import java.time.LocalDate

object Recurrence {
    private const val IterationGuard = 20000

    fun expand(
        rule: RepeatRule,
        anchor: String,
        from: String? = null,
        to: String? = null,
        max: Int = MaxOccurrences,
    ): List<String> {
        validateRepeatRule(rule)?.let { throw IllegalArgumentException(it) }
        val anchorDay = RepeatDays.parse(anchor) ?: throw IllegalArgumentException("anchor must be a YYYY-MM-DD date")
        val fromDay = if (from == null) anchorDay else RepeatDays.parse(from)
            ?: throw IllegalArgumentException("from must be a YYYY-MM-DD date")
        val toDay = if (to == null) null else RepeatDays.parse(to)
            ?: throw IllegalArgumentException("to must be a YYYY-MM-DD date")
        val end = rule.end
        val untilDay = if (end is RepeatEnd.Until) RepeatDays.parse(end.until) else null
        val limit = if (end is RepeatEnd.Count) end.count else null

        if (rule.isAfterCompletion) {
            if (untilDay != null && anchorDay > untilDay) return emptyList()
            if (anchorDay < fromDay) return emptyList()
            if (toDay != null && anchorDay > toDay) return emptyList()
            return if (max > 0) listOf(RepeatDays.key(anchorDay)) else emptyList()
        }

        val out = mutableListOf<String>()
        var emitted = 0
        for (step in 0 until IterationGuard) {
            if (toDay != null && periodStart(rule, anchorDay, step) > toDay) return out
            val candidates = candidates(rule, anchorDay, step).sorted()
            for (candidate in candidates) {
                if (candidate < anchorDay) continue
                if (untilDay != null && candidate > untilDay) return out
                if (limit != null && emitted >= limit) return out
                emitted += 1
                if (toDay != null && candidate > toDay) continue
                if (candidate < fromDay) continue
                out.add(RepeatDays.key(candidate))
                if (out.size >= max) return out
            }
        }
        return out
    }

    fun nextAfterCompletion(rule: RepeatRule, completedDay: String, emitted: Int = 1): String? {
        validateRepeatRule(rule)?.let { throw IllegalArgumentException(it) }
        require(rule.isAfterCompletion) { "nextAfterCompletion needs basis=completion" }
        val from = RepeatDays.parse(completedDay) ?: throw IllegalArgumentException("completedDay must be a YYYY-MM-DD date")
        val end = rule.end
        if (end is RepeatEnd.Count && emitted >= end.count) return null
        val next = when (rule.freq) {
            RepeatFreq.Daily -> from.plusDays(rule.interval.toLong())
            RepeatFreq.Weekly -> from.plusDays(rule.interval * 7L)
            RepeatFreq.Monthly, RepeatFreq.Yearly -> monthShift(from, rule.interval)
        }
        val until = if (end is RepeatEnd.Until) RepeatDays.parse(end.until) else null
        if (until != null && next > until) return null
        return RepeatDays.key(next)
    }

    fun evenMonths(start: Int, step: Int): List<Int> =
        (0 until 12 / step).map { k -> (start - 1 + k * step) % 12 + 1 }.sorted()

    fun monthStep(rule: RepeatRule): Int? {
        if (rule.freq != RepeatFreq.Monthly || rule.isAfterCompletion) return null
        if (rule.byMonth.isEmpty()) return if (rule.interval == 3 || rule.interval == 6) rule.interval else null
        if (rule.interval != 1) return null
        val months = rule.byMonth.toSortedSet().toList()
        for (step in listOf(3, 6)) {
            if (months.size == 12 / step && evenMonths(months.first(), step) == months) return step
        }
        return null
    }

    fun cycleMonths(rule: RepeatRule, anchorMonth: Int?): List<Int>? {
        if (rule.freq != RepeatFreq.Monthly || rule.isAfterCompletion) return null
        if (rule.byMonth.isNotEmpty()) return rule.byMonth.toSortedSet().toList()
        val n = rule.interval
        if (anchorMonth == null || n <= 1 || n >= 12 || 12 % n != 0) return null
        return evenMonths(anchorMonth, n)
    }

    fun plans(rule: RepeatRule, anchor: String, day: String): Boolean =
        day >= anchor && expand(rule.copy(end = RepeatEnd.Never), anchor = anchor, from = day, to = day, max = 1).isNotEmpty()

    private fun monthShift(from: LocalDate, months: Int): LocalDate {
        val index = from.year * 12 + (from.monthValue - 1) + months
        val year = index / 12
        val month = index % 12 + 1
        val last = RepeatDays.lastDayOfMonth(year, month)
        return LocalDate.of(year, month, minOf(from.dayOfMonth, last))
    }

    private fun startOfWeek(day: LocalDate): LocalDate = day.minusDays(RepeatDays.weekdayIndex(day).toLong())

    private fun daysInMonthFor(rule: RepeatRule, year: Int, month: Int, anchor: LocalDate): List<Int> {
        val last = RepeatDays.lastDayOfMonth(year, month)
        val hasMonthDays = rule.byMonthDay.isNotEmpty()
        val hasWeekdays = rule.byWeekday.isNotEmpty()
        if (!hasMonthDays && !hasWeekdays) return listOf(minOf(anchor.dayOfMonth, last))

        val monthDays: Set<Int>? = if (hasMonthDays) {
            val set = linkedSetOf<Int>()
            for (value in rule.byMonthDay) {
                val day = if (value < 0) last + 1 + value else minOf(value, last)
                if (day in 1..last) set.add(day)
            }
            set
        } else {
            null
        }

        val weekdayDays: Set<Int>? = if (hasWeekdays) {
            val set = linkedSetOf<Int>()
            for (pick in rule.byWeekday) {
                val target = RepeatWeekdays.indexOf(pick.day)
                val matches = (1..last).filter { RepeatDays.weekdayIndex(LocalDate.of(year, month, it)) == target }
                when (val ordinal = pick.ordinal) {
                    null -> set.addAll(matches)
                    -1 -> matches.lastOrNull()?.let(set::add)
                    else -> if (matches.size >= ordinal) set.add(matches[ordinal - 1])
                }
            }
            set
        } else {
            null
        }

        val result = if (monthDays != null && weekdayDays != null) {
            monthDays.filter { it in weekdayDays }
        } else {
            (monthDays ?: weekdayDays!!).toList()
        }
        return result.sorted()
    }

    private fun candidates(rule: RepeatRule, anchor: LocalDate, step: Int): List<LocalDate> = when (rule.freq) {
        RepeatFreq.Daily -> listOf(anchor.plusDays(step.toLong() * rule.interval))
        RepeatFreq.Weekly -> {
            val weekStart = startOfWeek(anchor).plusDays(step.toLong() * rule.interval * 7)
            val targets = if (rule.byWeekday.isNotEmpty()) {
                rule.byWeekday.map { RepeatWeekdays.indexOf(it.day) }.toSet()
            } else {
                setOf(RepeatDays.weekdayIndex(anchor))
            }
            targets.sorted().map { weekStart.plusDays(it.toLong()) }
        }
        RepeatFreq.Monthly -> {
            val index = anchor.year * 12 + (anchor.monthValue - 1) + step * rule.interval
            val year = index / 12
            val month = index % 12 + 1
            if (rule.byMonth.isNotEmpty() && month !in rule.byMonth) {
                emptyList()
            } else {
                daysInMonthFor(rule, year, month, anchor).map { LocalDate.of(year, month, it) }
            }
        }
        RepeatFreq.Yearly -> {
            val year = anchor.year + step * rule.interval
            val months = if (rule.byMonth.isNotEmpty()) rule.byMonth.toSortedSet().toList() else listOf(anchor.monthValue)
            months.flatMap { month -> daysInMonthFor(rule, year, month, anchor).map { LocalDate.of(year, month, it) } }
        }
    }

    private fun periodStart(rule: RepeatRule, anchor: LocalDate, step: Int): LocalDate = when (rule.freq) {
        RepeatFreq.Daily -> anchor.plusDays(step.toLong() * rule.interval)
        RepeatFreq.Weekly -> startOfWeek(anchor).plusDays(step.toLong() * rule.interval * 7)
        RepeatFreq.Monthly -> {
            val index = anchor.year * 12 + (anchor.monthValue - 1) + step * rule.interval
            LocalDate.of(index / 12, index % 12 + 1, 1)
        }
        RepeatFreq.Yearly -> LocalDate.of(anchor.year + step * rule.interval, 1, 1)
    }
}
