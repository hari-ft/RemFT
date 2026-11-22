package com.ftapps.remft.domain.alarm

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class SnoozeUnit(val minutes: Long) { MINUTES(1), HOURS(60), DAYS(1440) }

data class SnoozeParts(val days: Long, val hours: Long, val minutes: Long)

sealed interface SnoozeAddCheck {
    data class Ok(val id: String, val order: List<String>) : SnoozeAddCheck

    data object Empty : SnoozeAddCheck

    data object TooShort : SnoozeAddCheck

    data object TooLong : SnoozeAddCheck

    data class Duplicate(val id: String) : SnoozeAddCheck

    data object Full : SnoozeAddCheck
}

object SnoozePresets {
    const val FIVE_MIN = "5_min"
    const val THIRTY_MIN = "30_min"
    const val ONE_HOUR = "1_hour"
    const val TOMORROW_MORNING = "tomorrow_morning"
    const val CUSTOM_PREFIX = "m:"

    const val DEFAULT = FIVE_MIN
    const val TOMORROW_MORNING_CUTOFF_HOUR = 4
    const val TOMORROW_MORNING_HOUR = 9

    const val MIN_CUSTOM_MINUTES = 1L
    const val MAX_CUSTOM_MINUTES = 365L * 24 * 60
    const val MAX_OPTIONS = 8
    private const val MAX_DIGITS = 7

    val ids: List<String> = listOf(FIVE_MIN, THIRTY_MIN, ONE_HOUR, TOMORROW_MORNING)

    val defaultOrderCsv: String = ids.joinToString(",")

    val CustomSnoozeAnchor: Duration = Duration.ofMinutes(30)
    const val CUSTOM_SNOOZE_MAX_DAYS = 365L

    private val builtInMinutes: Map<String, Long> = mapOf(FIVE_MIN to 5L, THIRTY_MIN to 30L, ONE_HOUR to 60L)

    fun customId(minutes: Long): String? {
        if (minutes < MIN_CUSTOM_MINUTES || minutes > MAX_CUSTOM_MINUTES) return null
        return builtInMinutes.entries.firstOrNull { it.value == minutes }?.key ?: "$CUSTOM_PREFIX$minutes"
    }

    fun normalize(raw: String): String? {
        val id = raw.trim()
        if (id in ids) return id
        if (!id.startsWith(CUSTOM_PREFIX)) return null
        val digits = id.substring(CUSTOM_PREFIX.length)
        if (digits.isEmpty() || digits.length > MAX_DIGITS || digits.startsWith("0") || !digits.all { it in '0'..'9' }) return null
        return customId(digits.toLong())
    }

    fun minutesOf(id: String): Long? = normalize(id)?.let { builtInMinutes[it] ?: it.removePrefix(CUSTOM_PREFIX).toLongOrNull() }

    fun isCustom(id: String): Boolean = normalize(id)?.startsWith(CUSTOM_PREFIX) == true

    fun partsOf(minutes: Long): SnoozeParts = SnoozeParts(minutes / 1440, minutes % 1440 / 60, minutes % 60)

    fun parseOrder(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return ids
        val parsed = ArrayList<String>()
        for (part in raw.split(",")) {
            val id = normalize(part) ?: continue
            if (id !in parsed && parsed.size < MAX_OPTIONS) parsed.add(id)
        }
        return parsed.ifEmpty { ids }
    }

    fun encodeOrder(order: List<String>): String = parseOrder(order.joinToString(",")).joinToString(",")

    fun canAdd(order: List<String>): Boolean = order.size < MAX_OPTIONS

    fun canRemove(order: List<String>): Boolean = order.size > 1

    fun remove(order: List<String>, id: String): List<String> = if (canRemove(order)) order - id else order

    fun checkAdd(order: List<String>, amount: String, unit: SnoozeUnit): SnoozeAddCheck {
        if (!canAdd(order)) return SnoozeAddCheck.Full
        val text = amount.trim()
        if (text.isEmpty() || !text.all { it in '0'..'9' }) return SnoozeAddCheck.Empty
        if (text.trimStart('0').length > MAX_DIGITS) return SnoozeAddCheck.TooLong
        val minutes = text.toLong() * unit.minutes
        if (minutes < MIN_CUSTOM_MINUTES) return SnoozeAddCheck.TooShort
        val id = customId(minutes) ?: return SnoozeAddCheck.TooLong
        if (id in order) return SnoozeAddCheck.Duplicate(id)
        return SnoozeAddCheck.Ok(id, order + id)
    }

    fun isKnown(preset: String): Boolean = normalize(preset) != null

    fun untilFor(preset: String, now: Instant, zone: ZoneId): Instant {
        val id = normalize(preset) ?: throw IllegalArgumentException("unknown snooze preset: $preset")
        if (id == TOMORROW_MORNING) {
            val local = now.atZone(zone)
            val days = if (local.hour < TOMORROW_MORNING_CUTOFF_HOUR) 0L else 1L
            return local.toLocalDate().plusDays(days).atTime(LocalTime.of(TOMORROW_MORNING_HOUR, 0)).atZone(zone).toInstant()
        }
        val minutes = minutesOf(id) ?: throw IllegalArgumentException("unknown snooze preset: $preset")
        return now.plus(Duration.ofMinutes(minutes))
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
