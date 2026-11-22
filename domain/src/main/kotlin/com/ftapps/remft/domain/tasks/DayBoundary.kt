package com.ftapps.remft.domain.tasks

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

object DayBoundary {
    val SLACK: Duration = Duration.ofSeconds(1)

    fun startOfDay(now: LocalDateTime): LocalDateTime = now.toLocalDate().atStartOfDay()

    fun startOfDayInstant(now: Instant, zone: ZoneId): Instant =
        now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    fun delayUntilNextDay(now: ZonedDateTime): Duration {
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        return Duration.between(now, nextMidnight).plus(SLACK)
    }
}
