package com.ftapps.remft.domain.repeat

import java.time.LocalDate
import java.time.YearMonth

object RepeatDays {
    private val pattern = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    fun parse(value: String?): LocalDate? {
        if (value == null) return null
        val match = pattern.matchEntire(value) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        if (month < 1 || month > 12) return null
        if (day < 1 || day > lastDayOfMonth(year, month)) return null
        return LocalDate.of(year, month, day)
    }

    fun key(date: LocalDate): String =
        "${date.year.toString().padStart(4, '0')}-${date.monthValue.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"

    fun key(year: Int, month: Int, day: Int): String = key(LocalDate.of(year, month, day))

    fun lastDayOfMonth(year: Int, month: Int): Int = YearMonth.of(year, month).lengthOfMonth()

    fun weekdayIndex(date: LocalDate): Int = date.dayOfWeek.value - 1

    fun weekdayCode(date: LocalDate): String = RepeatWeekdays[weekdayIndex(date)]

    fun addDays(day: String, delta: Int): String = key(parseKey(day).plusDays(delta.toLong()))

    fun addMonths(day: String, delta: Int): String {
        val date = parseKey(day)
        val index = date.year * 12 + (date.monthValue - 1) + delta
        val year = Math.floorDiv(index, 12)
        val month = Math.floorMod(index, 12) + 1
        val last = lastDayOfMonth(year, month)
        return key(year, month, minOf(date.dayOfMonth, last))
    }

    fun later(a: String, b: String): String = if (a > b) a else b

    internal fun parseKey(day: String): LocalDate {
        val parts = day.split('-').map { it.toInt() }
        return LocalDate.of(parts[0], 1, 1).plusMonths((parts[1] - 1).toLong()).plusDays((parts[2] - 1).toLong())
    }
}
