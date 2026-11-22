package com.ftapps.remft.domain.alarm

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object SnoozePresets {
    const val FIVE_MIN = "5_min"
    const val THIRTY_MIN = "30_min"
    const val ONE_HOUR = "1_hour"
    const val TOMORROW_MORNING = "tomorrow_morning"

    const val DEFAULT = FIVE_MIN
    const val TOMORROW_MORNING_CUTOFF_HOUR = 4
    const val TOMORROW_MORNING_HOUR = 9

    val ids: List<String> = listOf(FIVE_MIN, THIRTY_MIN, ONE_HOUR, TOMORROW_MORNING)

    val defaultOrderCsv: String = ids.joinToString(",")

    val CustomSnoozeAnchor: Duration = Duration.ofMinutes(30)
    const val CUSTOM_SNOOZE_MAX_DAYS = 365L

    fun parseOrder(raw: String?): List<String> {
        val parsed = ArrayList<String>()
        for (part in (raw ?: "").split(",")) {
            val id = part.trim()
            if (id in ids && id !in parsed) parsed.add(id)
        }
        for (id in ids) {
            if (id !in parsed) parsed.add(id)
        }
        return parsed
    }

    fun encodeOrder(order: List<String>): String = parseOrder(order.joinToString(",")).joinToString(",")

    fun isKnown(preset: String): Boolean = preset in ids

    fun untilFor(preset: String, now: Instant, zone: ZoneId): Instant = when (preset) {
        FIVE_MIN -> now.plus(Duration.ofMinutes(5))
        THIRTY_MIN -> now.plus(Duration.ofMinutes(30))
        ONE_HOUR -> now.plus(Duration.ofHours(1))
        TOMORROW_MORNING -> {
            val local = now.atZone(zone)
            val days = if (local.hour < TOMORROW_MORNING_CUTOFF_HOUR) 0L else 1L
            local.toLocalDate().plusDays(days).atTime(LocalTime.of(TOMORROW_MORNING_HOUR, 0)).atZone(zone).toInstant()
        }
        else -> throw IllegalArgumentException("unknown snooze preset: $preset")
    }

    fun customSuggestion(now: Instant): Instant = now.plus(CustomSnoozeAnchor)

    fun customFirstDate(now: Instant, zone: ZoneId): LocalDate = now.atZone(zone).toLocalDate()

    fun customLastDate(now: Instant, zone: ZoneId): LocalDate = now.plus(Duration.ofDays(CUSTOM_SNOOZE_MAX_DAYS)).atZone(zone).toLocalDate()

    fun customUntil(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        date.atTime(time.hour, time.minute).atZone(zone).toInstant()

    fun isCustomSnoozeAccepted(until: Instant, now: Instant): Boolean = until.isAfter(now)

    fun untilForOrNull(preset: String, now: Instant, zone: ZoneId): Instant? =
        if (isKnown(preset)) untilFor(preset, now, zone) else null
}
