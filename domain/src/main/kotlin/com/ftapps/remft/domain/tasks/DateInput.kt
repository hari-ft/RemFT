package com.ftapps.remft.domain.tasks

import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.time.DateFormats
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class PickerPattern(val compact: String, val dayFirst: Boolean)

object DateInput {
    fun atTimeOf(day: LocalDate, time: LocalTime): LocalDateTime = day.atTime(time.hour, time.minute)

    fun defaultReminderOn(day: LocalDate, deadline: LocalDateTime, now: LocalDateTime): LocalDateTime {
        if (day != deadline.toLocalDate()) return DefaultTime.timeOn(day, now)
        val dayStart = day.atStartOfDay()
        var before = deadline.minusHours(1)
        if (before.isBefore(dayStart)) before = dayStart
        val ahead = DefaultTime.instant(now)
        return if (ahead.isAfter(before) && ahead.isBefore(deadline)) ahead else before
    }

    fun withDate(current: LocalDateTime?, date: LocalDate, deadline: LocalDateTime?, now: LocalDateTime): LocalDateTime {
        if (current != null) return atTimeOf(date, current.toLocalTime())
        if (deadline != null) return defaultReminderOn(date, deadline, now)
        return DefaultTime.timeOn(date, now)
    }

    fun withTime(current: LocalDateTime?, time: LocalTime, anchor: LocalDateTime?, now: LocalDateTime): LocalDateTime {
        val day = current ?: anchor
        if (day != null) return atTimeOf(day.toLocalDate(), time)
        val today = atTimeOf(now.toLocalDate(), time)
        return if (today.isAfter(now)) today else atTimeOf(now.toLocalDate().plusDays(1), time)
    }

    fun initialPickerDate(current: LocalDateTime?, anchor: LocalDateTime?, now: LocalDateTime): LocalDate =
        (current ?: anchor)?.toLocalDate() ?: now.toLocalDate()

    fun pickerFirstDate(now: LocalDateTime): LocalDate = now.minusDays(365).toLocalDate()

    fun pickerLastDate(now: LocalDateTime): LocalDate = now.plusDays(365L * 5).toLocalDate()

    fun pickerPattern(format: DateFormatId): PickerPattern {
        val date = DateFormats.spec(format).date
        val dayFirst = !date.startsWith("M")
        val compact = if (date.contains("MMM")) {
            if (dayFirst) "dd/MM/yyyy" else "MM/dd/yyyy"
        } else {
            date
        }
        return PickerPattern(compact, dayFirst)
    }
}
