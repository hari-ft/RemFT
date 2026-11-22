package com.ftapps.remft.domain.tasks

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

object DefaultTime {
    val LEAD: Duration = Duration.ofHours(1)

    const val LATE_DEFAULT_HOUR = 9

    fun instant(now: LocalDateTime): LocalDateTime {
        val ahead = now.plus(LEAD)
        val floor = ahead.truncatedTo(ChronoUnit.MINUTES)
        return if (floor == ahead) floor else floor.plusMinutes(1)
    }

    fun pastMidnight(now: LocalDateTime): Boolean = instant(now).toLocalDate() != now.toLocalDate()

    fun timeOn(day: LocalDate, now: LocalDateTime): LocalDateTime {
        val at = instant(now)
        if (pastMidnight(now)) {
            if (day != now.toLocalDate()) return day.atTime(LATE_DEFAULT_HOUR, 0)
            val clamp = day.atTime(23, 59)
            return if (clamp.isAfter(now)) clamp else day.atTime(23, 59, 59, 999_000_000)
        }
        return day.atTime(at.hour, at.minute)
    }

    fun repeatStart(now: LocalDateTime): LocalDateTime {
        if (!pastMidnight(now)) return timeOn(now.toLocalDate(), now)
        return now.toLocalDate().plusDays(1).atTime(LATE_DEFAULT_HOUR, 0)
    }

    fun isLateTodayDefault(value: LocalDateTime, now: LocalDateTime): Boolean =
        pastMidnight(now) && value.toLocalDate() == now.toLocalDate()
}
