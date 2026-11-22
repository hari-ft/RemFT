package com.ftapps.remft.domain.repeat

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

object WallClock {
    const val LocalZone: String = "local"

    fun zone(name: String): ZoneId {
        if (name == LocalZone || name.isEmpty()) return ZoneId.systemDefault()
        return runCatching { ZoneId.of(name) }.getOrElse { ZoneId.systemDefault() }
    }

    fun isKnownZone(name: String): Boolean =
        name != LocalZone && name.isNotEmpty() && runCatching { ZoneId.of(name) }.isSuccess

    fun wallClock(instant: Instant, zone: String): LocalDateTime =
        LocalDateTime.ofInstant(instant, zone(zone)).withNano(0)

    fun wallDay(instant: Instant, zone: String): String = RepeatDays.key(wallClock(instant, zone).toLocalDate())

    fun wallTime(instant: Instant, zone: String): LocalTime = wallClock(instant, zone).toLocalTime()

    fun toInstant(zone: String, year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant {
        val target = LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC)
        var ts = target
        repeat(2) {
            ts = ts.plus(Duration.between(wallAsUtc(ts, zone), target))
        }
        val shortfall = Duration.between(wallAsUtc(ts, zone), target)
        if (shortfall > Duration.ZERO) return ts.plus(shortfall)
        val before = ts.minus(Duration.ofHours(6))
        val earlier = target.minus(Duration.between(before, wallAsUtc(before, zone)))
        if (earlier < ts && wallAsUtc(earlier, zone) == target) return earlier
        return ts
    }

    fun toInstant(zone: String, day: String, time: LocalTime): Instant {
        val date = RepeatDays.parseKey(day)
        return toInstant(zone, date.year, date.monthValue, date.dayOfMonth, time.hour, time.minute)
    }

    fun toInstant(zone: String, date: LocalDate, time: LocalTime): Instant =
        toInstant(zone, date.year, date.monthValue, date.dayOfMonth, time.hour, time.minute)

    private fun wallAsUtc(instant: Instant, zone: String): Instant =
        wallClock(instant, zone).toInstant(ZoneOffset.UTC)
}
