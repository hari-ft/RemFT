package com.ftapps.remft.domain.repeat

import java.time.LocalDate
import java.time.LocalDateTime

enum class RepeatPreset(val key: String) {
    Daily("daily"),
    Weekly("weekly"),
    Weekdays("weekdays"),
    Monthly("monthly"),
    Quarterly("quarterly"),
    HalfYearly("halfYearly"),
    Yearly("yearly"),
}

enum class MonthMode { MonthDay, NthWeekday, AfterDay }

enum class EndType(val wire: String) { Never("never"), Until("until"), Count("count") }

enum class ClampHint { ShortMonths, ShortMonthYearly, LeapDay }

val AfterCompletionUnits: List<RepeatFreq> = listOf(RepeatFreq.Daily, RepeatFreq.Weekly, RepeatFreq.Monthly)

private val WorkWeek = setOf("MO", "TU", "WE", "TH", "FR")

data class RepeatEditor(
    val anchor: LocalDate,
    val basis: RepeatBasis = RepeatBasis.Calendar,
    val freq: RepeatFreq = RepeatFreq.Monthly,
    val interval: Int = 1,
    val weekdays: Set<String> = setOf(RepeatDays.weekdayCode(anchor)),
    val monthMode: MonthMode = MonthMode.MonthDay,
    val monthDay: Int = anchor.dayOfMonth,
    val lastDay: Boolean = false,
    val month: Int = anchor.monthValue,
    val step: Int = 1,
    val ordinal: Int = 1,
    val ordinalWeekday: String = RepeatDays.weekdayCode(anchor),
    val afterDay: Int = if (anchor.dayOfMonth > 1) anchor.dayOfMonth - 1 else 1,
    val afterWeekday: String = RepeatDays.weekdayCode(anchor),
    val endType: EndType = EndType.Never,
    val until: LocalDate? = null,
    val count: Int = 10,
    val advanced: Boolean = false,
    val afterFreq: RepeatFreq = RepeatFreq.Daily,
    val afterInterval: Int = 1,
) {
    val isAfter: Boolean get() = basis == RepeatBasis.Completion

    val end: RepeatEnd
        get() = when {
            endType == EndType.Until && until != null -> RepeatEnd.Until(RepeatDays.key(until))
            endType == EndType.Count -> RepeatEnd.Count(count)
            else -> RepeatEnd.Never
        }

    val afterRule: RepeatRule
        get() = RepeatRule(freq = afterFreq, interval = afterInterval, end = end, basis = RepeatBasis.Completion)

    private val cycled: Boolean get() = freq == RepeatFreq.Monthly && interval == 1 && step > 1

    val calendarRule: RepeatRule
        get() = when (freq) {
            RepeatFreq.Daily -> RepeatRule(freq = RepeatFreq.Daily, interval = interval, end = end)
            RepeatFreq.Weekly -> RepeatRule(
                freq = RepeatFreq.Weekly,
                interval = interval,
                byWeekday = RepeatWeekdays.filter { it in weekdays }.map { WeekdayPick(it) },
                end = end,
            )
            RepeatFreq.Monthly, RepeatFreq.Yearly -> {
                val byMonth = when {
                    freq == RepeatFreq.Yearly -> listOf(month)
                    cycled -> Recurrence.evenMonths(month, step)
                    else -> emptyList()
                }
                when (monthMode) {
                    MonthMode.MonthDay -> RepeatRule(
                        freq = freq,
                        interval = interval,
                        byMonthDay = listOf(if (lastDay) -1 else monthDay),
                        byMonth = byMonth,
                        end = end,
                    )
                    MonthMode.NthWeekday -> RepeatRule(
                        freq = freq,
                        interval = interval,
                        byWeekday = listOf(WeekdayPick(ordinalWeekday, ordinal)),
                        byMonth = byMonth,
                        end = end,
                    )
                    MonthMode.AfterDay -> RepeatPatterns.afterDayRule(afterDay, afterWeekday, end, interval)
                        .copy(freq = freq, byMonth = byMonth)
                }
            }
        }

    val rule: RepeatRule get() = if (isAfter) afterRule else calendarRule

    val activePreset: RepeatPreset?
        get() {
            if (freq == RepeatFreq.Monthly && interval == 1) {
                return when (step) {
                    3 -> RepeatPreset.Quarterly
                    6 -> RepeatPreset.HalfYearly
                    else -> RepeatPreset.Monthly
                }
            }
            if (interval != 1) return null
            return when (freq) {
                RepeatFreq.Daily -> RepeatPreset.Daily
                RepeatFreq.Weekly -> if (weekdays.size == 5 && weekdays.containsAll(WorkWeek)) RepeatPreset.Weekdays else RepeatPreset.Weekly
                RepeatFreq.Monthly -> RepeatPreset.Monthly
                RepeatFreq.Yearly -> RepeatPreset.Yearly
            }
        }

    fun applyPreset(preset: RepeatPreset): RepeatEditor {
        val base = copy(
            advanced = false,
            interval = 1,
            step = when (preset) {
                RepeatPreset.Quarterly -> 3
                RepeatPreset.HalfYearly -> 6
                else -> 1
            },
        )
        return when (preset) {
            RepeatPreset.Daily -> base.copy(freq = RepeatFreq.Daily)
            RepeatPreset.Weekly -> base.copy(freq = RepeatFreq.Weekly, weekdays = setOf(RepeatDays.weekdayCode(anchor)))
            RepeatPreset.Weekdays -> base.copy(freq = RepeatFreq.Weekly, weekdays = WorkWeek)
            RepeatPreset.Monthly, RepeatPreset.Quarterly, RepeatPreset.HalfYearly -> base.copy(freq = RepeatFreq.Monthly)
            RepeatPreset.Yearly -> base.copy(freq = RepeatFreq.Yearly)
        }
    }

    fun withInterval(value: Int): RepeatEditor = if (value in 1..366) copy(interval = value) else this

    fun withAfterInterval(value: Int): RepeatEditor = if (value in 1..366) copy(afterInterval = value) else this

    fun withMonthDayChoice(value: Int): RepeatEditor =
        if (value == -1) copy(lastDay = true) else copy(lastDay = false, monthDay = value)

    val monthDayChoice: Int get() = if (lastDay) -1 else monthDay

    val cycleStartChoices: List<List<Int>> get() = (1..step).map { Recurrence.evenMonths(it, step) }

    val cycleStartChoice: Int get() = (month - 1) % step + 1

    val clampHint: ClampHint?
        get() {
            if (freq != RepeatFreq.Monthly && freq != RepeatFreq.Yearly) return null
            val byDay = monthMode == MonthMode.MonthDay
            if (!byDay || lastDay) return null
            return if (freq == RepeatFreq.Yearly) {
                when {
                    month == 2 && monthDay == 29 -> ClampHint.LeapDay
                    monthDay > RepeatDays.lastDayOfMonth(2024, month) -> ClampHint.ShortMonthYearly
                    else -> null
                }
            } else {
                if (monthDay > 28) ClampHint.ShortMonths else null
            }
        }

    fun preview(previewFrom: LocalDate? = null, max: Int = 5): List<String> = try {
        Recurrence.expand(
            rule,
            anchor = RepeatDays.key(anchor),
            from = if (previewFrom == null || previewFrom < anchor) null else RepeatDays.key(previewFrom),
            max = max,
        )
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    fun afterPreview(today: LocalDate): String? = try {
        Recurrence.nextAfterCompletion(afterRule, RepeatDays.key(today))
    } catch (_: IllegalArgumentException) {
        null
    }

    fun isValid(previewFrom: LocalDate? = null): Boolean =
        validateRepeatRule(rule) == null && (isAfter || preview(previewFrom).isNotEmpty())

    companion object {
        fun open(anchor: LocalDate, initial: RepeatRule? = null): RepeatEditor {
            var editor = RepeatEditor(anchor = anchor)
            val rule = initial ?: return editor
            editor = editor.copy(
                freq = rule.freq,
                interval = rule.interval,
                weekdays = rule.byWeekday.map { it.day }.toSet(),
                month = if (rule.byMonth.isNotEmpty()) rule.byMonth.first() else anchor.monthValue,
            )
            val after = RepeatPatterns.afterDayOf(rule)
            editor = when {
                after != null -> editor.copy(monthMode = MonthMode.AfterDay, afterDay = after.day, afterWeekday = after.weekday)
                rule.byMonthDay.isNotEmpty() -> {
                    val last = rule.byMonthDay.first() == -1
                    editor.copy(
                        monthMode = MonthMode.MonthDay,
                        lastDay = last,
                        monthDay = if (last) editor.monthDay else rule.byMonthDay.first(),
                    )
                }
                rule.byWeekday.isNotEmpty() && rule.byWeekday.first().ordinal != null && rule.freq != RepeatFreq.Weekly ->
                    editor.copy(
                        monthMode = MonthMode.NthWeekday,
                        ordinal = rule.byWeekday.first().ordinal!!,
                        ordinalWeekday = rule.byWeekday.first().day,
                    )
                else -> editor
            }
            Recurrence.monthStep(rule)?.let { step ->
                editor = editor.copy(
                    step = step,
                    interval = 1,
                    month = if (rule.byMonth.isEmpty()) anchor.monthValue else rule.byMonth.sorted().first(),
                )
            }
            editor = editor.copy(advanced = editor.monthMode != MonthMode.MonthDay || editor.activePreset == null)
            val end = rule.end
            editor = editor.copy(
                endType = when (end) {
                    is RepeatEnd.Until -> EndType.Until
                    is RepeatEnd.Count -> EndType.Count
                    RepeatEnd.Never -> EndType.Never
                },
                until = (end as? RepeatEnd.Until)?.let { runCatching { LocalDate.parse(it.until) }.getOrNull() },
                count = (end as? RepeatEnd.Count)?.count ?: 10,
            )
            if (rule.isAfterCompletion) {
                editor = editor.copy(
                    basis = RepeatBasis.Completion,
                    afterFreq = if (rule.freq in AfterCompletionUnits) rule.freq else RepeatFreq.Daily,
                    afterInterval = rule.interval,
                    freq = RepeatFreq.Monthly,
                    interval = 1,
                    advanced = !rule.end.isNever,
                )
            }
            return editor
        }

        fun firstRepeatDue(rule: RepeatRule, from: LocalDateTime): LocalDateTime? {
            if (rule.isAfterCompletion) return from
            return try {
                val days = Recurrence.expand(rule, anchor = RepeatDays.key(from.toLocalDate()), max = 1)
                if (days.isEmpty()) {
                    null
                } else {
                    LocalDate.parse(days.first()).atTime(from.hour, from.minute)
                }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
