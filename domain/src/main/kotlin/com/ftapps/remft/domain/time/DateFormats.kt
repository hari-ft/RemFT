package com.ftapps.remft.domain.time

import com.ftapps.remft.domain.settings.DateFormatId
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DateFormatSpec(val id: DateFormatId, val date: String, val time: String, val short: String) {
    val uses24HourClock: Boolean get() = time.contains('H')
    val monthFirst: Boolean get() = id == DateFormatId.Mdy12h
}

object DateFormats {
    private val locale: Locale = Locale.ENGLISH

    val specs: List<DateFormatSpec> = listOf(
        DateFormatSpec(DateFormatId.System, "dd/MM/yyyy", "h:mm a", "d MMM"),
        DateFormatSpec(DateFormatId.DmyDot, "dd.MM.yyyy", "HH:mm", "d MMM"),
        DateFormatSpec(DateFormatId.DmySlash, "dd/MM/yyyy", "HH:mm", "d MMM"),
        DateFormatSpec(DateFormatId.Mdy12h, "MM/dd/yyyy", "h:mm a", "MMM d"),
        DateFormatSpec(DateFormatId.Iso, "yyyy-MM-dd", "HH:mm", "dd-MM"),
        DateFormatSpec(DateFormatId.DmyLong, "d MMMM yyyy", "HH:mm", "d MMMM"),
    )

    val sample: LocalDateTime = LocalDateTime.of(2026, 12, 31, 23, 59)

    fun spec(id: DateFormatId): DateFormatSpec = specs.first { it.id == id }

    private fun pattern(text: String) = DateTimeFormatter.ofPattern(text, locale)

    fun date(value: LocalDateTime, id: DateFormatId): String = pattern(spec(id).date).format(value)

    fun time(value: LocalDateTime, id: DateFormatId): String = pattern(spec(id).time).format(value)

    fun dateTime(value: LocalDateTime, id: DateFormatId): String = "${date(value, id)} ${time(value, id)}"

    fun short(value: LocalDateTime, id: DateFormatId, withTime: Boolean = true): String {
        val day = pattern(spec(id).short).format(value)
        return if (withTime) "$day, ${time(value, id)}" else day
    }

    fun dayHeading(value: LocalDateTime, id: DateFormatId): String =
        "${pattern("EEEE").format(value)} · ${short(value, id, withTime = false)}"
}
