package com.ftapps.remft.data.backup

import com.ftapps.remft.data.alarm.AppLogLevel
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.domain.backup.BackupLog

object AndroidBackupLog : BackupLog {
    override fun event(level: BackupLog.Level, code: String, fields: Map<String, Any?>, error: Throwable?) {
        AppLogs.record(
            when (level) {
                BackupLog.Level.Info -> AppLogLevel.INFO
                BackupLog.Level.Warn -> AppLogLevel.WARN
                BackupLog.Level.Error -> AppLogLevel.ERROR
            },
            code,
            fields,
            error,
        )
    }
}
