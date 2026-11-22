package com.ftapps.remft.domain.backup

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import com.ftapps.remft.domain.time.UtcText

object BackupReminder {
    val AFTER: Duration = Duration.ofDays(30)

    fun month(at: ZonedDateTime): String =
        at.year.toString().padStart(4, '0') + "-" + at.monthValue.toString().padStart(2, '0')

    fun isDue(now: ZonedDateTime, hasData: Boolean, lastBackup: Instant? = null, dismissedMonth: String? = null): Boolean {
        if (!hasData) return false
        if (lastBackup != null && Duration.between(lastBackup, now.toInstant()) < AFTER) return false
        return dismissedMonth != month(now)
    }

    fun lastBackupValue(at: Instant): String = UtcText.format(at)
}
