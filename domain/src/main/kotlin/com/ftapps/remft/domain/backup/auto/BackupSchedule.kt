package com.ftapps.remft.domain.backup.auto

import com.ftapps.remft.domain.backup.JsonText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

enum class BackupFrequency(val wire: String) {
    Daily("daily"),
    TwiceDaily("twiceDaily"),
    Weekly("weekly"),
    EveryNDays("everyNDays"),
    Monthly("monthly"),
}

data class BackupSchedule(
    val frequency: BackupFrequency = BackupFrequency.Daily,
    val minutes: Int = DEFAULT_MINUTES,
    val secondMinutes: Int = DEFAULT_SECOND_MINUTES,
    val weekday: Int = 7,
    val everyDays: Int = 2,
    val monthDay: Int = 1,
) {
    val times: List<Int>
        get() = if (frequency != BackupFrequency.TwiceDaily || secondMinutes == minutes) {
            listOf(minutes)
        } else {
            listOf(minOf(minutes, secondMinutes), maxOf(minutes, secondMinutes))
        }

    fun toJson(): JsonObject = buildJsonObject {
        put("frequency", frequency.wire)
        put("minutes", minutes)
        put("secondMinutes", secondMinutes)
        put("weekday", weekday)
        put("everyDays", everyDays)
        put("monthDay", monthDay)
    }

    fun encode(): String = JsonText.encode(toJson())

    fun nextRunAfter(after: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant {
        val day = after.atZone(zone).toLocalDate()
        if (frequency == BackupFrequency.EveryNDays) return slot(day.plusDays(everyDays.toLong()), minutes, zone)
        for (i in 0..64) {
            val d = day.plusDays(i.toLong())
            if (!isSlotDay(d)) continue
            for (m in times) {
                val s = slot(d, m, zone)
                if (s.isAfter(after)) return s
            }
        }
        throw IllegalStateException("No slot found")
    }

    fun isDue(now: Instant, lastSuccess: Instant?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (lastSuccess == null) return true
        if (lastSuccess.isAfter(now.plus(CLOCK_SLACK))) return true
        return !now.isBefore(nextRunAfter(lastSuccess, zone))
    }

    fun isOverdue(now: Instant, lastSuccess: Instant, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (lastSuccess.isAfter(now.plus(CLOCK_SLACK))) return false
        val first = nextRunAfter(lastSuccess, zone)
        return !now.isBefore(nextRunAfter(first, zone))
    }

    fun upcoming(now: Instant, lastSuccess: Instant?, count: Int, zone: ZoneId = ZoneId.systemDefault()): List<Instant> {
        val out = ArrayList<Instant>()
        var from = if (lastSuccess == null || lastSuccess.isAfter(now)) now else lastSuccess
        while (out.size < count) {
            var next = nextRunAfter(from, zone)
            if (!next.isAfter(now)) next = nextRunAfter(now, zone)
            out.add(next)
            from = next
        }
        return out
    }

    private fun isSlotDay(d: LocalDate): Boolean = when (frequency) {
        BackupFrequency.Daily, BackupFrequency.TwiceDaily, BackupFrequency.EveryNDays -> true
        BackupFrequency.Weekly -> d.dayOfWeek.value == weekday
        BackupFrequency.Monthly -> d.dayOfMonth == minOf(monthDay, YearMonth.of(d.year, d.month).lengthOfMonth())
    }

    private fun slot(d: LocalDate, m: Int, zone: ZoneId): Instant =
        ZonedDateTime.of(d, LocalTime.of(m / 60, m % 60), zone).toInstant()

    companion object {
        const val DEFAULT_MINUTES = 2 * 60
        const val DEFAULT_SECOND_MINUTES = 14 * 60
        const val EVERY_DAYS_MIN = 2
        const val EVERY_DAYS_MAX = 30
        val CLOCK_SLACK: Duration = Duration.ofHours(1)
        val defaults = BackupSchedule()

        fun fromJson(json: JsonElement?): BackupSchedule {
            if (json !is JsonObject) return defaults
            fun pick(key: String, fallback: Int, min: Int, max: Int): Int {
                val v = JsonText.intOf(json[key]) ?: return fallback
                return if (v >= min && v <= max) v.toInt() else fallback
            }
            val wire = JsonText.stringOf(json["frequency"])
            val frequency = BackupFrequency.entries.firstOrNull { it.wire == wire }
            return BackupSchedule(
                frequency = frequency ?: defaults.frequency,
                minutes = pick("minutes", DEFAULT_MINUTES, 0, 24 * 60 - 1),
                secondMinutes = pick("secondMinutes", DEFAULT_SECOND_MINUTES, 0, 1439),
                weekday = pick("weekday", defaults.weekday, 1, 7),
                everyDays = pick("everyDays", defaults.everyDays, EVERY_DAYS_MIN, EVERY_DAYS_MAX),
                monthDay = pick("monthDay", defaults.monthDay, 1, 31),
            )
        }

        fun parse(raw: String?): BackupSchedule {
            if (raw == null) return defaults
            val json = JsonText.parseOrNull(raw) ?: return defaults
            return fromJson(json)
        }
    }
}
