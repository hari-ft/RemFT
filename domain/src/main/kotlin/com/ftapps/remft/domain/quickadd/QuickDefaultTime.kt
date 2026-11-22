package com.ftapps.remft.domain.quickadd

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal object QuickDefaultTime {
    private const val LATE_DEFAULT_HOUR = 9

    fun instant(now: LocalDateTime, zone: ZoneId?): LocalDateTime {
        val ahead = if (zone == null) now.plusHours(1) else now.atZone(zone).plusHours(1).toLocalDateTime()
        val floor = ahead.truncatedTo(ChronoUnit.MINUTES)
        return if (floor == ahead) floor else floor.plusMinutes(1)
    }

    fun pastMidnight(now: LocalDateTime, zone: ZoneId?): Boolean =
        instant(now, zone).toLocalDate() != now.toLocalDate()

    fun on(day: LocalDate, now: LocalDateTime, zone: ZoneId?): LocalDateTime {
        val at = instant(now, zone)
        if (pastMidnight(now, zone)) {
            if (day != now.toLocalDate()) return day.atTime(LATE_DEFAULT_HOUR, 0)
            val clamp = day.atTime(23, 59)
            return if (clamp.isAfter(now)) clamp else day.atTime(23, 59, 59, 999_000_000)
        }
        return day.atTime(at.hour, at.minute)
    }
}
