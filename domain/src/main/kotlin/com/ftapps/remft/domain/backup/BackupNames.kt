package com.ftapps.remft.domain.backup

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

object BackupNames {
    val autoNamePattern = Regex("^remft-auto-(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})(\\d{2})(\\d{2})(Z?)\\.zip$")
    val manualNamePattern = Regex("^remft-backup-.*\\.zip$")
    val autoLeftoverPattern = Regex("^remft-auto-.*\\.zip$")

    fun manual(localDate: LocalDate): String =
        "remft-backup-${localDate.year}-${two(localDate.monthValue)}-${two(localDate.dayOfMonth)}.zip"

    fun automatic(at: Instant): String {
        val u = at.atOffset(ZoneOffset.UTC)
        return "remft-auto-${u.year}-${two(u.monthValue)}-${two(u.dayOfMonth)}-" +
            "${two(u.hour)}${two(u.minute)}${two(u.second)}Z.zip"
    }

    fun parseAutomatic(name: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val match = autoNamePattern.matchEntire(name) ?: return null
        val v = (1..6).map { match.groupValues[it].toInt() }
        val wall = LocalDateTime.of(v[0], 1, 1, 0, 0, 0)
            .plusMonths((v[1] - 1).toLong())
            .plusDays((v[2] - 1).toLong())
            .plusHours(v[3].toLong())
            .plusMinutes(v[4].toLong())
            .plusSeconds(v[5].toLong())
        return if (match.groupValues[7] == "Z") wall.toInstant(ZoneOffset.UTC) else wall.atZone(zone).toInstant()
    }

    fun isAutomatic(name: String): Boolean = autoNamePattern.matches(name)

    private fun two(value: Int): String = value.toString().padStart(2, '0')
}
