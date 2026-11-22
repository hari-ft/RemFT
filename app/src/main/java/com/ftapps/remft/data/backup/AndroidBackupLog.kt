package com.ftapps.remft.data.backup

import android.util.Log
import com.ftapps.remft.domain.backup.BackupLog

object AndroidBackupLog : BackupLog {
    private const val TAG = "RemFT.backup"

    override fun event(level: BackupLog.Level, code: String, fields: Map<String, Any?>, error: Throwable?) {
        val line = if (fields.isEmpty()) code else "$code $fields"
        when (level) {
            BackupLog.Level.Info -> Log.i(TAG, line, error)
            BackupLog.Level.Warn -> Log.w(TAG, line, error)
            BackupLog.Level.Error -> Log.e(TAG, line, error)
        }
    }
}
