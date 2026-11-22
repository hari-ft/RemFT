package com.ftapps.remft.domain.backup.auto

import com.ftapps.remft.domain.backup.BackupNames
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class StoredBackup(val id: String, val name: String, val size: Long? = null)

object Retention {
    const val MIN_KEPT = 3
    const val WEEKS_KEPT = 4
    const val MONTHS_KEPT = 12
    val KEEP_DAY_CHOICES: List<Int> = listOf(3, 7, 14, 30, 90)
    const val DEFAULT_KEEP_DAYS = 7

    fun keepDays(raw: String?): Int {
        val days = raw?.toIntOrNull()
        return if (days != null && days in KEEP_DAY_CHOICES) days else DEFAULT_KEEP_DAYS
    }

    fun expired(
        all: Iterable<StoredBackup>,
        keepDays: Int,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<StoredBackup> {
        val dated = all.mapNotNull { b -> BackupNames.parseAutomatic(b.name, zone)?.let { b to it } }
            .sortedWith { a, b -> b.second.compareTo(a.second) }
        val cutoff = now.minus(Duration.ofDays(keepDays.toLong()))
        val oldestWeek = isoWeekStart(now).minusDays(7L * (WEEKS_KEPT - 1))
        val oldestMonth = monthIndex(now) - (MONTHS_KEPT - 1)
        val weeks = HashSet<LocalDate>()
        val months = HashSet<Int>()
        val expired = ArrayList<StoredBackup>()
        dated.forEachIndexed { i, (backup, at) ->
            val week = isoWeekStart(at)
            val month = monthIndex(at)
            val weekly = !week.isBefore(oldestWeek) && weeks.add(week)
            val monthly = month >= oldestMonth && months.add(month)
            val kept = i < MIN_KEPT || !at.isBefore(cutoff) || weekly || monthly
            if (!kept) expired.add(backup)
        }
        return expired
    }

    private fun isoWeekStart(at: Instant): LocalDate {
        val day = at.atOffset(ZoneOffset.UTC).toLocalDate()
        return day.minusDays((day.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    }

    private fun monthIndex(at: Instant): Int {
        val d = at.atOffset(ZoneOffset.UTC)
        return d.year * 12 + d.monthValue - 1
    }
}
