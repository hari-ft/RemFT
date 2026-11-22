package com.ftapps.remft.domain.backup

fun interface BackupLog {
    fun event(level: Level, code: String, fields: Map<String, Any?>, error: Throwable?)

    fun info(code: String, fields: Map<String, Any?> = emptyMap()) = event(Level.Info, code, fields, null)

    fun warn(code: String, error: Throwable? = null, fields: Map<String, Any?> = emptyMap()) =
        event(Level.Warn, code, fields, error)

    fun error(code: String, error: Throwable? = null, fields: Map<String, Any?> = emptyMap()) =
        event(Level.Error, code, fields, error)

    enum class Level { Info, Warn, Error }

    companion object {
        val None: BackupLog = BackupLog { _, _, _, _ -> }
    }
}
