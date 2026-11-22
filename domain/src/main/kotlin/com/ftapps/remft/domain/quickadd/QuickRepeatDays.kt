package com.ftapps.remft.domain.quickadd

import java.time.LocalDate
import java.time.YearMonth

internal object QuickRepeatDays {
    private const val ITERATION_GUARD = 20000

    fun isValid(rule: QuickRepeatRule): Boolean {
        if (rule.interval !in 1..366) return false
        if (rule.isAfterCompletion) {
            if (rule.freq == QuickRepeatFreq.Yearly) return false
            if (rule.byWeekday.isNotEmpty() || rule.byMonthDay.isNotEmpty() || rule.byMonth.isNotEmpty()) return false
        }
        val monthlyOrYearly = rule.freq == QuickRepeatFreq.Monthly || rule.freq == QuickRepeatFreq.Yearly
        if (rule.byWeekday.isNotEmpty()) {
            if (rule.freq == QuickRepeatFreq.Daily) return false
            for (pick in rule.byWeekday) {
                if (pick.day !in QuickRepeatRule.weekdayCodes) return false
                val ordinal = pick.ordinal ?: continue
                if (!monthlyOrYearly) return false
                if (ordinal == 0 || ordinal > 5 || ordinal < -1) return false
            }
        }
        if (rule.byMonthDay.isNotEmpty()) {
            if (!monthlyOrYearly) return false
            if (rule.byMonthDay.any { it == 0 || it > 31 || it < -1 }) return false
        }
        if (rule.byMonth.isNotEmpty()) {
            if (!monthlyOrYearly) return false
            if (rule.byMonth.any { it !in 1..12 }) return false
        }
        when (val end = rule.end) {
            is QuickRepeatEnd.Until -> if (runCatching { LocalDate.parse(end.until) }.isFailure) return false
            is QuickRepeatEnd.Count -> if (end.count !in 1..1000) return false
            QuickRepeatEnd.Never -> Unit
        }
        return true
    }

    fun firstDays(rule: QuickRepeatRule, anchor: LocalDate, max: Int): List<LocalDate> {
        require(isValid(rule))
        val until = (rule.end as? QuickRepeatEnd.Until)?.let { LocalDate.parse(it.until) }
        val limit = (rule.end as? QuickRepeatEnd.Count)?.count
        if (rule.isAfterCompletion) {
            if (until != null && anchor.isAfter(until)) return emptyList()
            return if (max > 0) listOf(anchor) else emptyList()
        }
        val out = mutableListOf<LocalDate>()
        var emitted = 0
        for (step in 0 until ITERATION_GUARD) {
            for (candidate in candidates(rule, anchor, step).sorted()) {
                if (candidate.isBefore(anchor)) continue
                if (until != null && candidate.isAfter(until)) return out
                if (limit != null && emitted >= limit) return out
                emitted += 1
                out.add(candidate)
                if (out.size >= max) return out
            }
        }
        return out
    }

    private fun weekdayIndex(day: LocalDate): Int = day.dayOfWeek.value - 1

    private fun candidates(rule: QuickRepeatRule, anchor: LocalDate, step: Int): List<LocalDate> =
        when (rule.freq) {
            QuickRepeatFreq.Daily -> listOf(anchor.plusDays(step.toLong() * rule.interval))
            QuickRepeatFreq.Weekly -> {
                val weekStart = anchor.minusDays(weekdayIndex(anchor).toLong())
                    .plusDays(step.toLong() * rule.interval * 7)
                val targets = if (rule.byWeekday.isNotEmpty()) {
                    rule.byWeekday.map { QuickRepeatRule.weekdayCodes.indexOf(it.day) }.toSortedSet()
                } else {
                    sortedSetOf(weekdayIndex(anchor))
                }
                targets.map { weekStart.plusDays(it.toLong()) }
            }
            QuickRepeatFreq.Monthly -> {
                val month = YearMonth.from(anchor).plusMonths(step.toLong() * rule.interval)
                if (rule.byMonth.isNotEmpty() && month.monthValue !in rule.byMonth) {
                    emptyList()
                } else {
                    daysInMonth(rule, month, anchor).map { month.atDay(it) }
                }
            }
            QuickRepeatFreq.Yearly -> {
                val year = anchor.year + step * rule.interval
                val months = if (rule.byMonth.isNotEmpty()) rule.byMonth.toSortedSet().toList() else listOf(anchor.monthValue)
                months.flatMap { m ->
                    val month = YearMonth.of(year, m)
                    daysInMonth(rule, month, anchor).map { month.atDay(it) }
                }
            }
        }

    private fun daysInMonth(rule: QuickRepeatRule, month: YearMonth, anchor: LocalDate): List<Int> {
        val last = month.lengthOfMonth()
        if (rule.byMonthDay.isEmpty() && rule.byWeekday.isEmpty()) return listOf(minOf(anchor.dayOfMonth, last))
        val monthDays = if (rule.byMonthDay.isEmpty()) null else rule.byMonthDay
            .map { if (it < 0) last + 1 + it else minOf(it, last) }
            .filter { it in 1..last }
            .toSet()
        val weekdayDays: Set<Int>? = if (rule.byWeekday.isEmpty()) null else buildSet<Int> {
            for (pick in rule.byWeekday) {
                val target = QuickRepeatRule.weekdayCodes.indexOf(pick.day)
                val matches = (1..last).filter { weekdayIndex(month.atDay(it)) == target }
                val ordinal = pick.ordinal
                when {
                    ordinal == null -> addAll(matches)
                    ordinal == -1 -> matches.lastOrNull()?.let { add(it) }
                    matches.size >= ordinal -> add(matches[ordinal - 1])
                }
            }
        }
        val result = if (monthDays != null && weekdayDays != null) {
            monthDays.filter { it in weekdayDays }
        } else {
            (monthDays ?: weekdayDays!!).toList()
        }
        return result.sorted()
    }
}
