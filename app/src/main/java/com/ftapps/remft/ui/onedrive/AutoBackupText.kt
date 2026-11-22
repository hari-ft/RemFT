package com.ftapps.remft.ui.onedrive

import android.content.res.Resources
import androidx.annotation.StringRes
import com.ftapps.remft.R
import com.ftapps.remft.data.mirror.MirrorDownloadPhase
import com.ftapps.remft.data.mirror.MirrorDownloadState
import com.ftapps.remft.data.onedrive.AutoBackupOutcome
import com.ftapps.remft.data.onedrive.AutoBackupSnapshot
import com.ftapps.remft.data.onedrive.OneDriveErrors
import com.ftapps.remft.domain.backup.auto.BackupFrequency
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.backup.formatBackupDate
import java.time.Instant
import java.time.LocalDateTime

object AutoBackupText {
    @StringRes
    fun error(code: String?): Int = when (code) {
        OneDriveErrors.BUILD -> R.string.autoBackup_error_BUILD
        OneDriveErrors.UPLOAD -> R.string.autoBackup_error_UPLOAD
        OneDriveErrors.MIRROR_CHECK -> R.string.autoBackup_error_MIRROR_CHECK
        OneDriveErrors.MIRROR_MISSING -> R.string.autoBackup_error_MIRROR_MISSING
        OneDriveErrors.OTHER_ACCOUNT -> R.string.autoBackup_error_ONEDRIVE_OTHER_ACCOUNT
        OneDriveErrors.MIRROR_DAMAGED -> R.string.autoBackup_error_MIRROR_DAMAGED
        OneDriveErrors.MIRROR_NEEDS_ONEDRIVE -> R.string.autoBackup_error_MIRROR_NEEDS_ONEDRIVE
        OneDriveErrors.SIGNED_OUT -> R.string.autoBackup_error_ONEDRIVE_SIGNED_OUT
        OneDriveErrors.RECONNECT -> R.string.autoBackup_error_ONEDRIVE_RECONNECT
        OneDriveErrors.CANCELLED -> R.string.autoBackup_error_ONEDRIVE_CANCELLED
        OneDriveErrors.SIGN_IN_FAILED -> R.string.autoBackup_error_ONEDRIVE_SIGN_IN_FAILED
        OneDriveErrors.NO_BROWSER -> R.string.autoBackup_error_ONEDRIVE_NO_BROWSER
        OneDriveErrors.DENIED -> R.string.autoBackup_error_ONEDRIVE_DENIED
        OneDriveErrors.FULL -> R.string.autoBackup_error_ONEDRIVE_FULL
        OneDriveErrors.ERROR -> R.string.autoBackup_error_ONEDRIVE_ERROR
        OneDriveErrors.OFFLINE -> R.string.autoBackup_error_OFFLINE
        else -> R.string.autoBackup_error_UNKNOWN
    }

    @StringRes
    fun outcome(outcome: AutoBackupOutcome): Int = when (outcome) {
        AutoBackupOutcome.Done -> R.string.autoBackup_done
        AutoBackupOutcome.Busy -> R.string.autoBackup_busy
        AutoBackupOutcome.Paused -> R.string.autoBackup_pausedShort
        AutoBackupOutcome.Incomplete -> R.string.autoBackup_incomplete
        else -> R.string.autoBackup_failed
    }

    @StringRes
    fun frequency(f: BackupFrequency): Int = when (f) {
        BackupFrequency.Daily -> R.string.autoBackup_schedule_freq_daily
        BackupFrequency.TwiceDaily -> R.string.autoBackup_schedule_freq_twiceDaily
        BackupFrequency.Weekly -> R.string.autoBackup_schedule_freq_weekly
        BackupFrequency.EveryNDays -> R.string.autoBackup_schedule_freq_everyNDays
        BackupFrequency.Monthly -> R.string.autoBackup_schedule_freq_monthly
    }

    @StringRes
    fun weekday(day: Int): Int = when (day) {
        1 -> R.string.autoBackup_schedule_weekdays_1
        2 -> R.string.autoBackup_schedule_weekdays_2
        3 -> R.string.autoBackup_schedule_weekdays_3
        4 -> R.string.autoBackup_schedule_weekdays_4
        5 -> R.string.autoBackup_schedule_weekdays_5
        6 -> R.string.autoBackup_schedule_weekdays_6
        else -> R.string.autoBackup_schedule_weekdays_7
    }

    fun time(minutes: Int, format: DateFormatId): String =
        DateFormats.time(LocalDateTime.of(2000, 1, 1, minutes / 60, minutes % 60), format)

    fun summary(resources: Resources, s: AutoBackupSnapshot?, format: DateFormatId): String {
        if (s == null || !s.on) return resources.getString(R.string.autoBackup_off)
        if (s.pause != null) return resources.getString(R.string.autoBackup_pausedSummary)
        val last = s.lastOk ?: return resources.getString(R.string.autoBackup_onNoneYet)
        val date = formatBackupDate(last, format)
        return if (s.unavailable > 0) {
            resources.getString(R.string.autoBackup_lastOkIncomplete).fill("date" to date, "n" to s.unavailable)
        } else {
            resources.getString(R.string.autoBackup_lastOk).fill("date" to date)
        }
    }

    fun next(resources: Resources, s: AutoBackupSnapshot, format: DateFormatId, now: Instant = Instant.now()): String? {
        if (!s.on || s.pause != null) return null
        val last = s.lastOk
        if (last == null || s.schedule.isDue(now, last)) return resources.getString(R.string.autoBackup_schedule_nextDue)
        return resources.getString(R.string.autoBackup_schedule_next).fill("date" to formatBackupDate(s.schedule.nextRunAfter(last), format))
    }

    fun downloadLine(resources: Resources, state: MirrorDownloadState): String? = when (state.phase) {
        MirrorDownloadPhase.Idle -> null
        MirrorDownloadPhase.Downloading ->
            resources.getString(R.string.mirror_downloading).fill("done" to state.done, "total" to state.total)
        MirrorDownloadPhase.NeedsOneDrive -> resources.getString(R.string.mirror_needsOneDrive).fill("n" to state.remaining)
        MirrorDownloadPhase.Waiting -> resources.getString(R.string.mirror_waiting)
            .fill("n" to state.remaining, "error" to resources.getString(error(state.error)))
    }
}
